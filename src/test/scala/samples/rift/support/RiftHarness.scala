package samples.rift.support

import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, ServerSocket, Socket}
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

  /** Pick a fresh imposter port in `[lo, hi]` for a scenario. Default: scan for a host-free port —
    * external/embedded imposters bind the port directly on the host, so host-freeness is exactly
    * the right check. Container mode overrides this: its imposters bind *inside* the container and
    * the matching host ports are held by Docker's 1:1 forward, so a host-freeness scan would always
    * find them busy; it hands out from its published pool instead.
    */
  def freshImposterPort(lo: Int, hi: Int): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      (lo to hi)
        .find(p =>
          try { new ServerSocket(p).close(); true }
          catch { case _: Throwable => false }
        )
        .getOrElse(throw new RuntimeException(s"no free probe port in $lo..$hi"))
    }

  /** The intercept / TLS-MITM listener's `(host, port)`, if this mode started one — for driving
    * HTTPS `CONNECT` requests through it (feature 12, rift 0.10.0+). `None` when no intercept
    * listener is running (the default; external mode starts one and overrides this). The intercept
    * rules + CA/truststore admin is served on [[adminUrl]] whenever a listener is running.
    */
  def interceptEndpoint: UIO[Option[(String, Int)]] = ZIO.succeed(None)

  /** Add one intercept rule (feature 12). Default: `POST {adminUrl}/intercept/rules` over HTTP
    * (external/container, where intercept shares the HTTP admin plane). Embedded overrides this
    * with the FFI (`interceptAddRules`), because its admin plane drives intercept over the C-ABI,
    * not HTTP.
    */
  def interceptAddRule(ruleJson: String): Task[Unit] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/intercept/rules"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(ruleJson))
        .build()
      val code = RiftHarness.client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      if code != 201 then
        throw new RuntimeException(s"POST /intercept/rules -> $code (expected 201)")
    }

  /** The intercept truststore (PKCS#12) bytes, for a SUT to trust the MITM leaf certs (feature 12).
    * Default: HTTP `GET {adminUrl}/intercept/truststore.p12`. Embedded overrides via the FFI
    * (`interceptExportTruststore` to a temp file, read back) — no HTTP truststore route there.
    */
  def interceptTruststore(password: String): Task[Array[Byte]] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/intercept/truststore.p12?password=$password"))
        .GET()
        .build()
      val resp = RiftHarness.client.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if resp.statusCode != 200 then
        throw new RuntimeException(s"GET /intercept/truststore.p12 -> ${resp.statusCode}")
      resp.body()
    }

  /** The PEM of the caller-provided intercept CA this mode seeded at start (rift 0.11.3
    * `--intercept-ca-cert`, feature 12 / #55), or `None` when the mode lets rift mint an ephemeral
    * CA. External seeds the committed test CA and overrides this; feature 12 asserts the exported
    * `/intercept/ca.pem` equals it (proving the caller CA was loaded, not a fresh one).
    */
  def interceptCaPem: UIO[Option[String]] = ZIO.succeed(None)

  /** Start a dedicated plain engine with NO intercept listener and return its admin URL, for
    * exercising the runtime intercept start/stop lifecycle (rift 0.13.0 `POST`/`GET`/`DELETE
    * /intercept`, feature 12). The shared harness engine always runs an `--intercept-port`
    * listener, so the off→on→off lifecycle needs a second engine that starts with intercept OFF.
    * External mode overrides this (raw-spawns a child on fresh ports, remembered and reaped by
    * [[stopRuntimeInterceptEngine]] and the layer scope). Other modes cannot spawn a second
    * no-intercept engine, so they fail — the scenario is tagged `@intercept-runtime` and runs
    * external-only.
    */
  def startRuntimeInterceptEngine: Task[String] =
    ZIO.fail(new UnsupportedOperationException("runtime intercept lifecycle is external-mode only"))

  /** The admin URL of the [[startRuntimeInterceptEngine]] engine; fails if none is running. */
  def runtimeInterceptAdminUrl: Task[String] =
    ZIO.fail(new UnsupportedOperationException("runtime intercept lifecycle is external-mode only"))

  /** Reap the [[startRuntimeInterceptEngine]] engine if one is running; a no-op otherwise, so it is
    * safe to call unconditionally from a cleanup hook.
    */
  def stopRuntimeInterceptEngine: Task[Unit] = ZIO.unit

  /** Start a dedicated plain engine with `--upstream-ca-file` pointing at the committed test CA
    * (`rift-fixtures/intercept/ca-cert.pem`) and return its admin URL — feature 09
    * `@upstream-trust` (rift#974): its proxy stubs trust an HTTPS upstream serving a leaf that CA
    * signed, where the shared engine (no flag) refuses it. External mode only; the other modes
    * cannot spawn a second engine, so they fail and the scenario is excluded there by tag.
    */
  def startUpstreamTrustEngine: Task[String] =
    ZIO.fail(new UnsupportedOperationException("the upstream-trust engine is external-mode only"))

  /** Reap the [[startUpstreamTrustEngine]] engine if one is running; a no-op otherwise, so it is
    * safe to call unconditionally from a cleanup hook.
    */
  def stopUpstreamTrustEngine: Task[Unit] = ZIO.unit

  /** Start a dedicated plain engine and return its admin URL — feature 21 `@admin-isolated`
    * (rift-conformance#200): `DELETE /imposters` wipes every imposter on the engine it hits, so it
    * cannot run against the shared one. External mode only; the other modes fail and the scenarios
    * are excluded there by tag.
    */
  def startIsolatedAdminEngine: Task[String] =
    ZIO.fail(new UnsupportedOperationException("the isolated admin engine is external-mode only"))

  /** Reap the [[startIsolatedAdminEngine]] engine if one is running; a no-op otherwise. */
  def stopIsolatedAdminEngine: Task[Unit] = ZIO.unit

  /** Swap in a `--configfile`-backed engine for the life of the returned `Scope`, reverting to a
    * plain engine (same admin/metrics ports) when the scope closes.
    */
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit]

  /** Start a `--configfile`-backed engine (rift#316, feature 11) and remember it as pending —
    * paired with `revertEngine`. Unlike `withConfigFile`, this does not span a `Scope`: it is meant
    * to be driven from one BDD step and reverted from another (typically via `afterScenario`).
    */
  def startReloadConfig(json: String): Task[Unit]

  /** [[startReloadConfig]] with the engine also started with `--front-door` on a fresh port, which
    * is returned (rift#1160, feature 11 `@front-door`). Paired with `revertEngine` the same way.
    * External mode only: the other modes cannot pass the engine a CLI flag, so they fail and the
    * scenarios are excluded there by tag.
    */
  def startReloadConfigWithFrontDoor(json: String): Task[Int] =
    ZIO.fail(new UnsupportedOperationException("the front door is external-mode only"))

  /** Boot a throwaway engine with `--front-door` and `json` as its `--configfile`, on fresh ports,
    * and wait for it to exit — for asserting that an invalid `routes` table fails the boot (feature
    * 16). The shared engine is untouched. An engine still running after the readiness budget is
    * killed and reported with `exitCode = None` (it booted). External mode only, like
    * [[startReloadConfigWithFrontDoor]].
    */
  def bootFrontDoorEngine(json: String): Task[EngineBoot] =
    ZIO.fail(new UnsupportedOperationException("the front door is external-mode only"))

  /** Overwrite the config file of the pending [[startReloadConfig]] engine, so a following `POST
    * /admin/reload` picks up the edit. Fails if no reload engine is pending. External mode only,
    * like [[startReloadConfigWithFrontDoor]].
    */
  def rewriteReloadConfig(json: String): Task[Unit] =
    ZIO.fail(new UnsupportedOperationException("rewriting the reload config is external-mode only"))

  /** Revert to a plain engine (same ports) if `startReloadConfig` left one pending; a no-op
    * otherwise, so it is safe to call unconditionally from a cleanup hook.
    */
  def revertEngine: Task[Unit]

/** How a [[RiftHarness.bootFrontDoorEngine]] boot ended: the exit code (`None` = it was still
  * running, i.e. it booted, and was killed) and everything it wrote to stdout/stderr.
  */
final case class EngineBoot(exitCode: Option[Int], output: String)

final case class RiftHarnessConfig(
    mode: RiftMode,
    buildDir: Path,
    corpusRoot: Path,
    preferredAdminPort: Int
)

object RiftHarnessConfig:
  val DefaultAdminPort = 2525
  val DefaultCorpusRoot = "corpus"

  /** The `lintFeatures` step-matching gate runs suites under `--dry-run` and sets this system
    * property so [[RiftHarness.layer]] skips the engine entirely (see [[RiftHarnessDryRun]]). Read
    * as a property, not an env var, so bare `sbt lintFeatures` works without a wrapper script.
    */
  val DryRunProp = "rift.dryRun"
  def isDryRun(prop: Option[String]): Boolean = prop.contains("true")
  def dryRun: Boolean = isDryRun(sys.props.get(DryRunProp))

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
    interceptPort: Int,
    cfg: RiftHarnessConfig,
    engineRef: Ref[Process],
    pendingConfigRef: Ref[Option[Path]],
    runtimeInterceptRef: Ref[Option[(Process, String)]],
    upstreamTrustRef: Ref[Option[Process]],
    isolatedAdminRef: Ref[Option[Process]],
    val tools: RiftTools,
    info: RiftBuildInfo
) extends RiftHarness:
  def hostFor(imposterPort: Int): String = "localhost" // identity in external mode
  def buildInfo: UIO[RiftBuildInfo] = ZIO.succeed(info)
  override def interceptEndpoint: UIO[Option[(String, Int)]] =
    ZIO.succeed(Some(("localhost", interceptPort)))
  override def interceptCaPem: UIO[Option[String]] =
    ZIO.succeed(RiftHarness.interceptCa.map(_._3))

  /** Raw-spawn a plain engine started WITHOUT `--intercept-port` (so its `/intercept` admin routes
    * report "not running" until a `POST /intercept` starts the listener). Remembered in
    * `runtimeInterceptRef` so later steps read its admin URL and `stopRuntimeInterceptEngine` (or
    * the layer finalizer) reaps it. Adopts the process into the ref BEFORE the readiness wait — the
    * same "never orphan a spawned child" discipline as the reload path — and force-kills it on a
    * readiness failure so a never-ready engine doesn't hold its ports for the rest of the run.
    */
  override def startRuntimeInterceptEngine: Task[String] =
    for
      _ <- stopRuntimeInterceptEngine // never leak a prior sidecar if a scenario forgot to stop it
      port <- RiftHarness.freePort
      metricsPort <- RiftHarness.freePort
      proc <- RiftHarness.rawSpawn(cfg, port, metricsPort, None, None)
      url = s"http://localhost:$port"
      _ <- runtimeInterceptRef.set(Some((proc, url)))
      _ <- RiftHarness
        .awaitReady(s"$url/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield url

  override def runtimeInterceptAdminUrl: Task[String] =
    runtimeInterceptRef.get.flatMap {
      case Some((_, url)) => ZIO.succeed(url)
      case None => ZIO.fail(new IllegalStateException("no runtime-intercept engine started"))
    }

  /** Same spawn-adopt-await discipline as [[startRuntimeInterceptEngine]]. */
  override def startUpstreamTrustEngine: Task[String] =
    for
      _ <- stopUpstreamTrustEngine
      ca <- ZIO
        .fromOption(RiftHarness.interceptCa)
        .orElseFail(new IllegalStateException("test CA fixture rift-fixtures/intercept missing"))
      port <- RiftHarness.freePort
      metricsPort <- RiftHarness.freePort
      proc <- RiftHarness.rawSpawn(cfg, port, metricsPort, None, None, upstreamCaFile = Some(ca._1))
      url = s"http://localhost:$port"
      _ <- upstreamTrustRef.set(Some(proc))
      _ <- RiftHarness
        .awaitReady(s"$url/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield url

  override def stopUpstreamTrustEngine: Task[Unit] =
    upstreamTrustRef.getAndSet(None).flatMap(_.fold(ZIO.unit)(RiftHarness.killProcess))

  /** Same spawn-adopt-await discipline as [[startRuntimeInterceptEngine]]. */
  override def startIsolatedAdminEngine: Task[String] =
    for
      _ <- stopIsolatedAdminEngine
      port <- RiftHarness.freePort
      metricsPort <- RiftHarness.freePort
      proc <- RiftHarness.rawSpawn(cfg, port, metricsPort, None, None)
      url = s"http://localhost:$port"
      _ <- isolatedAdminRef.set(Some(proc))
      _ <- RiftHarness
        .awaitReady(s"$url/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield url

  override def stopIsolatedAdminEngine: Task[Unit] =
    isolatedAdminRef.getAndSet(None).flatMap(_.fold(ZIO.unit)(RiftHarness.killProcess))

  override def stopRuntimeInterceptEngine: Task[Unit] =
    runtimeInterceptRef.getAndSet(None).flatMap {
      case Some((proc, _)) => RiftHarness.killProcess(proc)
      case None => ZIO.unit
    }

  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] =
    ZIO
      .acquireRelease(startConfigFileEngine(json, None))(path => revertToPlainEngine(path).orDie)
      .unit

  def startReloadConfig(json: String): Task[Unit] =
    startConfigFileEngine(json, None).flatMap(path => pendingConfigRef.set(Some(path)))

  override def startReloadConfigWithFrontDoor(json: String): Task[Int] =
    for
      frontDoor <- RiftHarness.freePort
      path <- startConfigFileEngine(json, Some(frontDoor))
      _ <- pendingConfigRef.set(Some(path))
      proc <- engineRef.get
      _ <- RiftHarness.awaitTcpReady("localhost", frontDoor, proc)
    yield frontDoor

  override def bootFrontDoorEngine(json: String): Task[EngineBoot] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val conf = Files.createTempFile("rift-boot-", ".json")
        val log = Files.createTempFile("rift-boot-", ".log")
        Files.write(conf, json.getBytes(StandardCharsets.UTF_8))
        (conf, log)
      }
    )((conf, log) =>
      ZIO.attemptBlocking { Files.deleteIfExists(conf); Files.deleteIfExists(log) }.ignore
    ) { (conf, log) =>
      for
        port <- RiftHarness.freePort
        metricsPort <- RiftHarness.freePort
        frontDoor <- RiftHarness.freePort
        exit <- ZIO.acquireReleaseWith(
          RiftHarness
            .rawSpawn(cfg, port, metricsPort, Some(conf), None, Some(frontDoor), None, Some(log))
        )(proc => RiftHarness.killProcess(proc).orDie)(proc =>
          ZIO.attemptBlockingInterrupt(
            Option.when(proc.waitFor(RiftHarness.ReadinessBudgetMs, TimeUnit.MILLISECONDS))(
              proc.exitValue()
            )
          )
        )
        output <- ZIO.attemptBlocking(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
      yield EngineBoot(exit, output)
    }

  override def rewriteReloadConfig(json: String): Task[Unit] =
    pendingConfigRef.get.flatMap {
      case Some(path) =>
        ZIO.attemptBlocking(Files.write(path, json.getBytes(StandardCharsets.UTF_8))).unit
      case None => ZIO.fail(new IllegalStateException("no reload config engine is pending"))
    }

  def revertEngine: Task[Unit] =
    pendingConfigRef.get.flatMap {
      case Some(path) => revertToPlainEngine(path) *> pendingConfigRef.set(None)
      case None => ZIO.unit // nothing pending — safe to call unconditionally from cleanup
    }

  /** Kill whatever engine is current, spawn a fresh one bound to `<tmp>` (the given config, written
    * to a temp file), await its admin API, and make it current. Returns the temp path so the caller
    * can hand it back to `revertToPlainEngine` later.
    */
  private def startConfigFileEngine(json: String, frontDoor: Option[Int]): Task[Path] =
    for
      old <- engineRef.get
      _ <- RiftHarness.killProcess(old)
      tmp <- ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-reload-", ".json")
        p.toFile.deleteOnExit()
        Files.write(p, json.getBytes(StandardCharsets.UTF_8))
        p
      }
      proc <- RiftHarness.rawSpawn(
        cfg,
        adminPort,
        metricsPort,
        Some(tmp),
        Some(interceptPort),
        frontDoor
      )
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
      proc <- RiftHarness.rawSpawn(cfg, adminPort, metricsPort, None, Some(interceptPort))
      // Same discipline as startConfigFileEngine: adopt the process into engineRef before the
      // readiness wait so a never-ready plain engine is still reaped, and force-kill on failure.
      _ <- engineRef.set(proc)
      _ <- RiftHarness
        .awaitReady(s"$adminUrl/imposters", proc)
        .onError(_ => RiftHarness.killProcess(proc).ignore)
    yield ()

object RiftHarness:
  private[support] val ReadinessBudgetMs = 15000L
  private val ReadinessSpacingMs = 100L
  private val httpTimeout = JDuration.ofSeconds(2)
  private[support] val client = HttpClient.newHttpClient()

  // The committed test intercept CA (#55): a throwaway CA (CN=rift-conformance-test-intercept-ca)
  // under test resources. Extract cert + key from the classpath to temp files once (rift's
  // `--intercept-ca-cert/--key` need filesystem paths), and keep the cert PEM so feature 12 can
  // assert the exported `/intercept/ca.pem` equals exactly the CA we provided (proving rift loaded
  // the caller CA rather than minting an ephemeral one). `None` if the fixture is absent.
  private[support] lazy val interceptCa: Option[(Path, Path, String)] =
    (
      readResource("/rift-fixtures/intercept/ca-cert.pem"),
      readResource("/rift-fixtures/intercept/ca-key.pem")
    ) match
      case (Some(certPem), Some(keyPem)) =>
        Some(
          (
            writeTemp("rift-intercept-ca-cert-", certPem),
            writeTemp("rift-intercept-ca-key-", keyPem),
            certPem
          )
        )
      case _ => None

  private def readResource(path: String): Option[String] =
    Option(getClass.getResourceAsStream(path)).map { is =>
      try new String(is.readAllBytes(), StandardCharsets.UTF_8)
      finally is.close()
    }
  private def writeTemp(prefix: String, content: String): Path =
    val p = Files.createTempFile(prefix, ".pem")
    p.toFile.deleteOnExit()
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  /** Scoped so the engine process is torn down when the scope closes; shared across suites so the
    * engine starts once per sbt run.
    *
    * The engine is RESTARTABLE in place (`RiftHarnessLive.engineRef`) so `withConfigFile` /
    * `startReloadConfig` can swap it for a `--configfile`-backed one and revert. Exactly ONE
    * finalizer is registered here — `engineRef.get.flatMap(killProcess)` — so whatever engine is
    * CURRENT when the scope closes is the one reaped, even if it was swapped/reverted long after
    * this layer was built.
    */
  val layer: ZLayer[Any, Throwable, RiftHarness] = layerFor(RiftHarnessConfig.dryRun)

  /** The environment layer, split out from [[layer]] so the dry-run wiring is unit-testable without
    * fighting the memoization of the `object`-level `val layer` (see `DryRunLayer142Spec`). Under a
    * dry run it yields the engine-free [[RiftHarnessDryRun]]; otherwise it reads the env and
    * spawns.
    */
  def layerFor(dryRun: Boolean): ZLayer[Any, Throwable, RiftHarness] =
    if dryRun then RiftHarnessDryRun.layer
    else
      ZLayer.scoped {
        RiftHarnessConfig.fromEnv.flatMap { cfg =>
          cfg.mode match
            case RiftMode.Embedded => RiftHarnessEmbedded.acquire(cfg)
            case RiftMode.Container => RiftHarnessContainer.acquire(cfg)
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
      (proc0, port, metricsPort, interceptPort) = spawned
      info <- RiftBuildInfo.fromBuildDir(cfg.buildDir, engineVersion = None)
      engineRef <- Ref.make(proc0)
      pendingConfigRef <- Ref.make(Option.empty[Path])
      // The runtime-intercept sidecar engine (feature 12, @intercept-runtime): started/stopped
      // per-scenario, but reaped here too so an aborted scenario never leaks it past layer-scope.
      runtimeInterceptRef <- Ref.make(Option.empty[(Process, String)])
      // The feature-09 @upstream-trust engine, reaped here too for the same reason.
      upstreamTrustRef <- Ref.make(Option.empty[Process])
      // The feature-21 @admin-isolated engine, likewise.
      isolatedAdminRef <- Ref.make(Option.empty[Process])
      // A closure over `engineRef`, NOT `proc0` directly — this is what lets a later swap (and
      // revert) still be reaped at layer-scope close.
      _ <- ZIO.addFinalizer(engineRef.get.flatMap(killProcess).orDie)
      _ <- ZIO.addFinalizer(runtimeInterceptRef.get.flatMap {
        case Some((proc, _)) => killProcess(proc).orDie
        case None => ZIO.unit
      })
      _ <- ZIO.addFinalizer(upstreamTrustRef.get.flatMap(_.fold(ZIO.unit)(killProcess(_).orDie)))
      _ <- ZIO.addFinalizer(isolatedAdminRef.get.flatMap(_.fold(ZIO.unit)(killProcess(_).orDie)))
    yield RiftHarnessLive(
      s"http://localhost:$port",
      s"http://localhost:$metricsPort",
      port,
      metricsPort,
      interceptPort,
      cfg,
      engineRef,
      pendingConfigRef,
      runtimeInterceptRef,
      upstreamTrustRef,
      isolatedAdminRef,
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
  ): IO[Throwable, (Process, Int, Int, Int)] =
    for
      port <- if useFreshAdminPort then freePort else choosePort(cfg.preferredAdminPort)
      metricsPort <- freePort
      interceptPort <- freePort
      proc <- rawSpawn(cfg, port, metricsPort, None, Some(interceptPort))
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
          awaitReady(s"$metricsUrl/metrics", proc) *>
          // The intercept listener binds a plain TCP port (it speaks CONNECT, not GET-able HTTP), so
          // probe it with a raw connect. Gating on it means a rift that silently no-ops
          // `--intercept-port` fails the harness loudly rather than letting feature 12 race a
          // not-yet-bound (or absent) listener.
          awaitTcpReady("localhost", interceptPort, proc))
          .onError(_ => ZIO.attemptBlocking(proc.destroyForcibly()).ignore)
    yield (proc, port, metricsPort, interceptPort)

  /** Prefer 2525, but fall back to a free port if it is *busy* so a stray engine never wedges the
    * run; `adminUrl` then reflects the port actually chosen. Only a bind failure (IOException)
    * triggers the fallback — anything else (e.g. SecurityException) is a real fault and propagates.
    */
  private[rift] def choosePort(preferred: Int): UIO[Int] =
    probeFree(preferred).catchSome { case _: java.io.IOException =>
      ZIO.logInfo(s"admin port $preferred busy; falling back to a free port") *> freePort
    }.orDie

  /** Busy means a live listener OR an unbindable port — delegates to [[TestPorts.isFree]], which
    * implements exactly this connect-then-bind check (the connect check has to come first: the bind
    * probe alone cannot see a listener, since `ServerSocket` sets SO_REUSEADDR and binds the
    * wildcard, and on macOS that bind succeeds beside a process listening on IPv4 `0.0.0.0`).
    * zio-bdd builds the environment layer more than once per run, so a second build finding "2525
    * free" while the first build's engine holds it spawns a doomed engine. Since rift 0.18.0 binds
    * the admin listener LAST (after metrics and intercept), that doomed engine passes every
    * readiness probe — admin answered by the OTHER engine, metrics/intercept by its own short-lived
    * listeners — then exits with `Address already in use`, leaving the harness pointed at dead
    * ports.
    */
  private def probeFree(port: Int): IO[Throwable, Int] =
    TestPorts.isFree(port).flatMap {
      case true => ZIO.succeed(port)
      case false => ZIO.fail(new java.net.BindException(s"port $port has a live listener"))
    }

  /** A fresh port for a spawned engine's metrics/intercept/front-door listener, or its admin port
    * on the fresh-port fallback leg of [[choosePort]]. `TestPorts.next` (#184), not a bare
    * `ServerSocket(0)`: the latter hands back a port from the OS ephemeral range, which the JVM's
    * own outgoing connections (and other processes) draw from too — a TOCTOU window between this
    * probe-and-release and the spawned rift's own bind. See [[TestPorts]]'s scaladoc for the full
    * rationale.
    */
  private[support] def freePort: IO[Throwable, Int] = TestPorts.next

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
      configFile: Option[Path],
      interceptPort: Option[Int],
      frontDoorPort: Option[Int] = None,
      upstreamCaFile: Option[Path] = None,
      logTo: Option[Path] = None
  ): Task[Process] =
    ZIO.attemptBlocking {
      val binary = cfg.buildDir.resolve("rift").toString
      // --nologfile: don't drop an mb.log into the corpus repo (cwd); capture output to a temp file
      // (or to `logTo`, when the caller wants to read it back).
      val logFile = logTo.map(_.toFile).getOrElse {
        val f = Files.createTempFile(s"rift-engine-$port-", ".log").toFile
        f.deleteOnExit()
        f
      }
      val baseArgs = List(
        binary,
        "--port",
        port.toString,
        "--allow-injection",
        "--nologfile",
        "--metrics-port",
        metricsPort.toString
      )
      // --intercept-port starts the opt-in TLS-MITM listener (rift 0.10.0+); its rules + CA share the
      // admin API. Harmless when no intercept rules are posted, so external mode always starts one.
      // When a listener runs, seed a committed caller-provided intercept CA (rift 0.11.3, #55) so the
      // exported CA is deterministic — feature 12 asserts `/intercept/ca.pem` equals that fixture.
      val caArgs =
        if interceptPort.isDefined then
          interceptCa.toList.flatMap((cert, key, _) =>
            List("--intercept-ca-cert", cert.toString, "--intercept-ca-key", key.toString)
          )
        else Nil
      val args = baseArgs ++
        configFile.toList.flatMap(p => List("--configfile", p.toString)) ++
        interceptPort.toList.flatMap(p => List("--intercept-port", p.toString)) ++
        frontDoorPort.toList.flatMap(p => List("--front-door", p.toString)) ++
        upstreamCaFile.toList.flatMap(p => List("--upstream-ca-file", p.toString)) ++
        caArgs
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

  /** Bounded, blocking readiness probe that a plain TCP listener is accepting on `host:port` (the
    * intercept listener, which speaks `CONNECT` rather than answering a GET, or the front door,
    * which 404s an unrouted probe). Watches the child: if the engine exits early it fails fast with
    * the exit code. Same budget/spacing as [[awaitReady]].
    */
  private[support] def awaitTcpReady(host: String, port: Int, proc: Process): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime + ReadinessBudgetMs * 1000000L
      var ready = false
      var lastErr: Throwable = null
      while !ready && java.lang.System.nanoTime < deadline do
        if !proc.isAlive then
          throw new RuntimeException(
            s"rift exited early (code ${proc.exitValue()}) before listener :$port came up"
          )
        try
          val s = new Socket()
          try s.connect(new InetSocketAddress(host, port), 1000)
          finally s.close()
          ready = true
        catch
          case e: Throwable =>
            lastErr = e
            Thread.sleep(ReadinessSpacingMs)
      if !ready then
        throw new RuntimeException(
          s"listener not ready on $host:$port within ${ReadinessBudgetMs}ms",
          lastErr
        )
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
