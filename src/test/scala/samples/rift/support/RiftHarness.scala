package samples.rift.support

import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Duration as JDuration
import java.util.concurrent.TimeUnit

/** Owns the engine lifecycle — the one shared seam both attribution planes drive through. External
  * mode (this issue) spawns a real `rift` child.
  */
trait RiftHarness:
  def adminUrl: String
  def metricsUrl: String
  def hostFor(imposterPort: Int): String
  def tools: RiftTools
  def buildInfo: UIO[RiftBuildInfo]

  /** Rewrite a fixture's engine-cwd-relative data-source paths (e.g. a `lookup`'s
    * `data/products.csv`) so the engine resolves them regardless of its cwd. External mode returns
    * the JSON unchanged — the spawned engine's cwd is `$RIFT_CORPUS_ROOT`, so `data/...` already
    * resolves. Embedded mode absolutizes under the corpus root, because the in-process engine
    * shares the JVM's cwd (the project root), where `data/...` would not resolve.
    */
  def rewriteFixture(json: String): String = json

  /** Swap in a `--configfile`-backed engine for the life of the returned `Scope`, reverting to a
    * plain engine (same admin/metrics ports) when the scope closes.
    */
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit]

  /** Start a `--configfile`-backed engine (rift#316, feature 11) and remember it as pending —
    * paired with `revertEngine`. Unlike `withConfigFile`, this does not span a `Scope`: it is meant
    * to be driven from one BDD step and reverted from another (typically via `afterScenario`).
    */
  def startReloadConfig(json: String): Task[Unit]

  /** Revert to a plain engine (same ports) if `startReloadConfig` left one pending; a no-op
    * otherwise, so it is safe to call unconditionally from a cleanup hook.
    */
  def revertEngine: Task[Unit]

final case class RiftHarnessConfig(
    mode: RiftMode,
    buildDir: Path,
    corpusRoot: Path,
    preferredAdminPort: Int
)

object RiftHarnessConfig:
  val DefaultAdminPort = 2525
  val DefaultCorpusRoot = "corpus"

  /** Read config from the environment. `RIFT_BUILD_DIR` is required — it names the released build
    * produced by scripts/build-rift.sh; there is no safe default for it.
    */
  val fromEnv: IO[Throwable, RiftHarnessConfig] =
    for
      buildDir <- envRequired("RIFT_BUILD_DIR").map(Paths.get(_))
      modeStr <- envOr("RIFT_MODE", "external")
      mode <- ZIO.fromEither(RiftMode.fromString(modeStr)).mapError(new RuntimeException(_))
      corpus <- envOr("RIFT_CORPUS_ROOT", DefaultCorpusRoot).map(Paths.get(_))
    yield RiftHarnessConfig(mode, buildDir, corpus, DefaultAdminPort)

  private def envRequired(name: String): IO[Throwable, String] =
    envOpt(name).flatMap {
      case Some(v) => ZIO.succeed(v)
      case None =>
        ZIO.fail(
          new RuntimeException(s"$name must be set (run scripts/build-rift.sh and export it)")
        )
    }
  private def envOr(name: String, default: String): UIO[String] =
    envOpt(name).map(_.getOrElse(default)).orDie
  private def envOpt(name: String): IO[Throwable, Option[String]] =
    ZIO.attempt(Option(java.lang.System.getenv(name)).map(_.trim).filter(_.nonEmpty))

/** @param adminPort
  *   the port the CURRENT engine's admin API is bound to — fixed for the life of this harness
  *   instance; every respawn (reload / revert) reuses it so `adminUrl` never changes.
  * @param engineRef
  *   the currently-running engine `Process` — swapped in place by `startConfigFileEngine` /
  *   `revertToPlainEngine`. Exactly one finalizer (registered in `layer`) reads this ref at scope
  *   close, so whichever process is current at that point is the one reaped.
  * @param pendingConfigRef
  *   the temp configfile path of a `startReloadConfig`-started engine awaiting `revertEngine`;
  *   `None` when the current engine is plain (or driven purely through `withConfigFile`'s `Scope`,
  *   which threads its own path through the acquire/release closure instead of this ref).
  */
private final class RiftHarnessLive(
    val adminUrl: String,
    val metricsUrl: String,
    adminPort: Int,
    metricsPort: Int,
    cfg: RiftHarnessConfig,
    engineRef: Ref[Process],
    pendingConfigRef: Ref[Option[Path]],
    val tools: RiftTools,
    info: RiftBuildInfo
) extends RiftHarness:
  def hostFor(imposterPort: Int): String = "localhost" // identity in external mode
  def buildInfo: UIO[RiftBuildInfo] = ZIO.succeed(info)

  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] =
    ZIO.acquireRelease(startConfigFileEngine(json))(path => revertToPlainEngine(path).orDie).unit

  def startReloadConfig(json: String): Task[Unit] =
    startConfigFileEngine(json).flatMap(path => pendingConfigRef.set(Some(path)))

  def revertEngine: Task[Unit] =
    pendingConfigRef.get.flatMap {
      case Some(path) => revertToPlainEngine(path) *> pendingConfigRef.set(None)
      case None => ZIO.unit // nothing pending — safe to call unconditionally from cleanup
    }

  /** Kill whatever engine is current, spawn a fresh one bound to `<tmp>` (the given config, written
    * to a temp file), await its admin API, and make it current. Returns the temp path so the caller
    * can hand it back to `revertToPlainEngine` later.
    */
  private def startConfigFileEngine(json: String): Task[Path] =
    for
      old <- engineRef.get
      _ <- RiftHarness.killProcess(old)
      tmp <- ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-reload-", ".json")
        p.toFile.deleteOnExit()
        Files.write(p, json.getBytes(StandardCharsets.UTF_8))
        p
      }
      proc <- RiftHarness.rawSpawn(cfg, adminPort, metricsPort, Some(tmp))
      // Store BEFORE awaiting readiness so the layer finalizer reaps this process no matter what —
      // an engine that spawns but never becomes ready must not be orphaned. On a readiness failure
      // also force-kill it now so it doesn't hold the shared admin port for the rest of the run.
      _ <- engineRef.set(proc)
      _ <- RiftHarness
        .awaitReady(s"$adminUrl/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield tmp

  /** Kill the configfile engine, delete its temp config, and spawn a plain engine back on the SAME
    * ports — the invariant `withConfigFile`/`startReloadConfig` callers rely on: `adminUrl` serves
    * a plain engine again once this completes, exactly as before the swap.
    */
  private def revertToPlainEngine(confPath: Path): Task[Unit] =
    for
      old <- engineRef.get
      _ <- RiftHarness.killProcess(old)
      _ <- ZIO.attemptBlocking(Files.deleteIfExists(confPath)).ignore
      proc <- RiftHarness.rawSpawn(cfg, adminPort, metricsPort, None)
      // Same discipline as startConfigFileEngine: adopt the process into engineRef before the
      // readiness wait so a never-ready plain engine is still reaped, and force-kill on failure.
      _ <- engineRef.set(proc)
      _ <- RiftHarness
        .awaitReady(s"$adminUrl/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield ()

object RiftHarness:
  private val ReadinessBudgetMs = 15000L
  private val ReadinessSpacingMs = 100L
  private val httpTimeout = JDuration.ofSeconds(2)
  private val client = HttpClient.newHttpClient()

  /** Scoped so the engine process is torn down when the scope closes; shared across suites so the
    * engine starts once per sbt run.
    *
    * The engine is RESTARTABLE in place (`RiftHarnessLive.engineRef`) so `withConfigFile` /
    * `startReloadConfig` can swap it for a `--configfile`-backed one and revert. Exactly ONE
    * finalizer is registered here — `engineRef.get.flatMap(killProcess)` — so whatever engine is
    * CURRENT when the scope closes is the one reaped, even if it was swapped/reverted long after
    * this layer was built.
    */
  val layer: ZLayer[Any, Throwable, RiftHarness] =
    ZLayer.scoped {
      RiftHarnessConfig.fromEnv.flatMap { cfg =>
        cfg.mode match
          case RiftMode.Embedded => RiftHarnessEmbedded.acquire(cfg)
          case RiftMode.External => acquireExternal(cfg)
      }
    }

  /** External mode: spawn a real `rift` child, await readiness, adopt it into a restartable ref. */
  private def acquireExternal(cfg: RiftHarnessConfig): ZIO[Scope, Throwable, RiftHarness] =
    for
      // Bounded retry, always on a *fresh ephemeral* admin port after the first attempt. The
      // preferred port (2525) can be genuinely unbindable even though our own JVM-side probe
      // (`choosePort`) reports it free: `ServerSocket` sets SO_REUSEADDR so the probe's transient
      // bind-and-release succeeds regardless, but a lingering TCP TIME_WAIT on that local port
      // (left behind by earlier short-lived connections to it — admin API calls, readiness
      // polling, prior engine instances during rapid repeated test runs) can still make the
      // engine's own bind fail outright. Retrying with the SAME preferred port would just hit the
      // same wall every time; only a genuinely different (ephemeral) port sidesteps it. A failed
      // attempt never lingers: `acquireReadyEngine` force-kills its process on any readiness
      // failure (the bind-failure mode exits on its own; the timeout-while-alive mode is killed
      // explicitly there), so a retry starts with the ports actually freed.
      spawned <- acquireReadyEngine(cfg, useFreshAdminPort = false)
        .catchAll(_ => acquireReadyEngine(cfg, useFreshAdminPort = true).retry(Schedule.recurs(4)))
      (proc0, port, metricsPort) = spawned
      info <- RiftBuildInfo.fromBuildDir(cfg.buildDir, engineVersion = None)
      engineRef <- Ref.make(proc0)
      pendingConfigRef <- Ref.make(Option.empty[Path])
      // A closure over `engineRef`, NOT `proc0` directly — this is what lets a later swap (and
      // revert) still be reaped at layer-scope close.
      _ <- ZIO.addFinalizer(engineRef.get.flatMap(killProcess).orDie)
    yield RiftHarnessLive(
      s"http://localhost:$port",
      s"http://localhost:$metricsPort",
      port,
      metricsPort,
      cfg,
      engineRef,
      pendingConfigRef,
      RiftTools.fromBuildDir(cfg.buildDir),
      info
    )

  /** Choose ports, RAW-spawn a plain engine, and block until both its admin and metrics listeners
    * answer — no `Scope`/`acquireRelease` involved (a retry never leaves an orphaned finalizer
    * behind; any failure here force-kills the process it just spawned before propagating).
    */
  private def acquireReadyEngine(
      cfg: RiftHarnessConfig,
      useFreshAdminPort: Boolean
  ): IO[Throwable, (Process, Int, Int)] =
    for
      port <- if useFreshAdminPort then freePort else choosePort(cfg.preferredAdminPort)
      metricsPort <- freePort
      proc <- rawSpawn(cfg, port, metricsPort, None)
      url = s"http://localhost:$port"
      metricsUrl = s"http://localhost:$metricsPort"
      // Readiness can fail with the engine still ALIVE (a probe times out while the process runs —
      // e.g. the metrics listener never binds). Force-kill it *now* on any readiness failure so a
      // retry starts clean instead of leaking this attempt's ports for the rest of the run.
      _ <-
        (awaitReady(s"$url/imposters", proc) *>
          // The metrics HTTP listener can bind measurably later than the admin listener (it binds a
          // freshly-probed-then-released ephemeral port, which the OS can briefly delay rebinding) —
          // so "ready" must wait for both, or an immediate metrics probe can race a listener still
          // coming up.
          awaitReady(s"$metricsUrl/metrics", proc))
          .onError(_ => ZIO.attemptBlocking(proc.destroyForcibly()).ignore)
    yield (proc, port, metricsPort)

  /** Prefer 2525, but fall back to a free port if it is *busy* so a stray engine never wedges the
    * run; `adminUrl` then reflects the port actually chosen. Only a bind failure (IOException)
    * triggers the fallback — anything else (e.g. SecurityException) is a real fault and propagates.
    */
  private[rift] def choosePort(preferred: Int): UIO[Int] =
    probeFree(preferred).catchSome { case _: java.io.IOException =>
      ZIO.logInfo(s"admin port $preferred busy; falling back to a free port") *> freePort
    }.orDie

  private def probeFree(port: Int): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val s = new ServerSocket(port)
      s.close()
      port
    }
  private def freePort: IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val s = new ServerSocket(0)
      val p = s.getLocalPort
      s.close()
      p
    }

  /** RAW spawn — starts `rift` with the given ports (and, when present, a `--configfile`) and
    * registers the same belt-and-braces JVM shutdown hook `layer` always has, but does NOT wrap the
    * process in `ZIO.acquireRelease`: there is no per-spawn finalizer, so respawning (reload /
    * revert) never accumulates one. Callers are responsible for reaping the process they replace
    * via `killProcess` — `layer`'s single scope finalizer reaps whatever is current at scope-close.
    */
  private[support] def rawSpawn(
      cfg: RiftHarnessConfig,
      port: Int,
      metricsPort: Int,
      configFile: Option[Path]
  ): Task[Process] =
    ZIO.attemptBlocking {
      val binary = cfg.buildDir.resolve("rift").toString
      // --nologfile: don't drop an mb.log into the corpus repo (cwd); capture output to a temp file.
      val logFile = Files.createTempFile(s"rift-engine-$port-", ".log").toFile
      logFile.deleteOnExit()
      val baseArgs = List(
        binary,
        "--port",
        port.toString,
        "--allow-injection",
        "--nologfile",
        "--metrics-port",
        metricsPort.toString
      )
      val args = baseArgs ++ configFile.toList.flatMap(p => List("--configfile", p.toString))
      val pb = new ProcessBuilder(args*)
      pb.directory(cfg.corpusRoot.toFile)
      pb.redirectErrorStream(true)
      pb.redirectOutput(logFile)
      val proc = pb.start()
      // Belt-and-braces: an aborted sbt run (SIGINT) skips ZIO finalizers, so a JVM shutdown hook
      // guarantees the child is not leaked — mirrors the old shell suite's pkill.
      val hook = new Thread(() => if proc.isAlive then { proc.destroyForcibly(); () })
      try java.lang.Runtime.getRuntime.addShutdownHook(hook)
      catch
        case e: Throwable => // never leak the child if the hook can't be registered
          proc.destroyForcibly()
          throw e
      proc
    }

  /** destroy → waitFor(5s) → destroyForcibly → waitFor(5s); throws if the process survives even
    * `SIGKILL` — the whole point of the harness is "no orphan", so a teardown that doesn't actually
    * take says so loudly rather than reporting success.
    */
  private[support] def killProcess(proc: Process): Task[Unit] =
    ZIO.attemptBlocking {
      proc.destroy()
      if !proc.waitFor(5, TimeUnit.SECONDS) then proc.destroyForcibly()
      if !proc.waitFor(5, TimeUnit.SECONDS) then
        throw new RuntimeException(s"rift (pid ${proc.pid()}) survived teardown")
    }

  /** Bounded, blocking readiness probe against `probeUrl` (a full URL — the admin API's
    * `.../imposters` or the metrics server's `.../metrics`). Deliberately independent of the ZIO
    * `Clock` (so a shared layer never hangs under a spec's `TestClock`) and watches the child: if
    * the engine exits early it fails fast with the exit code instead of retrying connection-refused
    * for the full budget.
    */
  private[support] def awaitReady(probeUrl: String, proc: Process): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime + ReadinessBudgetMs * 1000000L
      var ready = false
      var lastErr: Throwable = null
      while !ready && java.lang.System.nanoTime < deadline do
        if !proc.isAlive then
          throw new RuntimeException(
            s"rift exited early (code ${proc.exitValue()}) before $probeUrl came up"
          )
        try
          if probeStatus(probeUrl) == 200 then ready = true
          else Thread.sleep(ReadinessSpacingMs)
        catch
          case e: Throwable =>
            lastErr = e
            Thread.sleep(ReadinessSpacingMs)
      if !ready then
        throw new RuntimeException(
          s"engine not ready on $probeUrl within ${ReadinessBudgetMs}ms",
          lastErr
        )
    }

  private def probeStatus(url: String): Int =
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(httpTimeout).GET().build()
    client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
