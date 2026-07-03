package samples.rift.support

import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.ServerSocket
import java.nio.file.{Files, Path, Paths}
import java.time.Duration as JDuration
import java.util.concurrent.TimeUnit

/** Owns the engine lifecycle — the one shared seam both attribution planes drive through. External
  * mode (this issue) spawns a real `rift-http-proxy` child.
  */
trait RiftHarness:
  def adminUrl: String
  def hostFor(imposterPort: Int): String
  def tools: RiftTools
  def buildInfo: UIO[RiftBuildInfo]
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit]

final case class RiftHarnessConfig(
    mode: RiftMode,
    buildDir: Path,
    corpusRoot: Path,
    preferredAdminPort: Int
)

object RiftHarnessConfig:
  val DefaultAdminPort = 2525
  val DefaultCorpusRoot = "../rift-imposter-samples"

  /** Read config from the environment. `RIFT_BUILD_DIR` is required — it names the clean-master
    * build produced by scripts/build-rift.sh; there is no safe default for it.
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

private final class RiftHarnessLive(
    val adminUrl: String,
    val tools: RiftTools,
    info: RiftBuildInfo
) extends RiftHarness:
  def hostFor(imposterPort: Int): String = "localhost" // identity in external mode
  def buildInfo: UIO[RiftBuildInfo] = ZIO.succeed(info)
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] =
    ZIO.fail(new UnsupportedOperationException("withConfigFile is implemented in #10"))

object RiftHarness:
  private val ReadinessBudgetMs = 15000L
  private val ReadinessSpacingMs = 100L
  private val httpTimeout = JDuration.ofSeconds(2)
  private val client = HttpClient.newHttpClient()

  /** Scoped so the engine process is torn down when the scope closes; shared across suites so the
    * engine starts once per sbt run.
    */
  val layer: ZLayer[Any, Throwable, RiftHarness] =
    ZLayer.scoped {
      for
        cfg <- RiftHarnessConfig.fromEnv
        port <- choosePort(cfg.preferredAdminPort)
        proc <- spawnEngine(cfg, port)
        url = s"http://localhost:$port"
        _ <- awaitReady(url, proc)
        info <- RiftBuildInfo.fromBuildDir(cfg.buildDir, engineVersion = None)
      yield RiftHarnessLive(url, RiftTools(cfg.buildDir), info)
    }

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

  private def spawnEngine(cfg: RiftHarnessConfig, port: Int): ZIO[Scope, Throwable, Process] =
    val binary = cfg.buildDir.resolve("rift-http-proxy").toString
    ZIO
      .acquireRelease(
        ZIO.attemptBlocking {
          // --nologfile: don't drop an mb.log into the corpus repo (cwd); capture output to a temp file.
          val logFile = Files.createTempFile(s"rift-engine-$port-", ".log").toFile
          logFile.deleteOnExit()
          val pb =
            new ProcessBuilder(binary, "--port", port.toString, "--allow-injection", "--nologfile")
          pb.directory(cfg.corpusRoot.toFile)
          pb.redirectErrorStream(true)
          pb.redirectOutput(logFile)
          val proc = pb.start()
          // Belt-and-braces: an aborted sbt run (SIGINT) skips ZIO finalizers, so a JVM shutdown
          // hook guarantees the child is not leaked — mirrors the old shell suite's pkill.
          val hook = new Thread(() => if proc.isAlive then { proc.destroyForcibly(); () })
          try java.lang.Runtime.getRuntime.addShutdownHook(hook)
          catch
            case e: Throwable => // never leak the child if the hook can't be registered
              proc.destroyForcibly()
              throw e
          (proc, hook, logFile)
        }
      ) { (proc, hook, logFile) =>
        ZIO.attemptBlocking {
          try java.lang.Runtime.getRuntime.removeShutdownHook(hook)
          catch case _: IllegalStateException => () // JVM already shutting down; the hook will run
          proc.destroy()
          if !proc.waitFor(5, TimeUnit.SECONDS) then proc.destroyForcibly()
          // The whole point of the harness is "no orphan" — if even SIGKILL didn't take, say so
          // loudly rather than reporting a clean teardown.
          if !proc.waitFor(5, TimeUnit.SECONDS) then
            throw new RuntimeException(s"rift-http-proxy (pid ${proc.pid()}) survived teardown")
          logFile.delete()
          ()
        }.orDie
      }
      .map(_._1)

  /** Bounded, blocking readiness probe. Deliberately independent of the ZIO `Clock` (so a shared
    * layer never hangs under a spec's `TestClock`) and watches the child: if the engine exits early
    * it fails fast with the exit code instead of retrying connection-refused for the full budget.
    */
  private def awaitReady(url: String, proc: Process): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime + ReadinessBudgetMs * 1000000L
      var ready = false
      var lastErr: Throwable = null
      while !ready && java.lang.System.nanoTime < deadline do
        if !proc.isAlive then
          throw new RuntimeException(
            s"rift-http-proxy exited early (code ${proc.exitValue()}) before the admin API on $url came up"
          )
        try
          if probeStatus(s"$url/imposters") == 200 then ready = true
          else Thread.sleep(ReadinessSpacingMs)
        catch
          case e: Throwable =>
            lastErr = e
            Thread.sleep(ReadinessSpacingMs)
      if !ready then
        throw new RuntimeException(
          s"engine not ready on $url within ${ReadinessBudgetMs}ms",
          lastErr
        )
    }

  private def probeStatus(url: String): Int =
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(httpTimeout).GET().build()
    client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
