package samples.rift.support

import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration as JDuration

/** Embedded mode (#18): the engine runs IN-PROCESS over the FFM bridge — zero process spawn, no
  * Docker. [[EmbeddedAdmin.serve]] starts the real admin API in-process (functionally the same
  * surface as the external engine's HTTP admin API) and yields its loopback URL; every Plane A step
  * then drives that URL over HTTP exactly as external mode does. There is nothing to reap on scope
  * close beyond the FFI runtime (owned by the scoped `EmbeddedAdmin.serve`), so this holds no
  * process handle and registers no shutdown hook.
  *
  * @param reloadConfigFile
  *   the config file `serve_admin` was seeded with; feature 11 overwrites it and calls `POST
  *   /admin/reload`, then reverts it to an empty imposter set. `adminUrl` never changes across a
  *   reload (the admin server is not restarted — only its config source is re-read).
  */
private final class RiftHarnessEmbeddedLive(
    val adminUrl: String,
    corpusRoot: Path,
    reloadConfigFile: Path,
    interceptHost: String,
    interceptPort: Int,
    handle: EmbeddedAdmin.Handle,
    info: RiftBuildInfo,
    val tools: RiftTools,
    pendingRef: Ref[Boolean]
) extends RiftHarness:

  // No separate metrics listener in embedded mode — feature 09 (gateway/https/metrics) is an
  // external/container-only surface (DESIGN §3.1) and is not run here, so pointing metricsUrl at the
  // admin URL only satisfies the type; the metrics steps never execute against it.
  val metricsUrl: String = adminUrl

  def hostFor(imposterPort: Int): String = "127.0.0.1" // identity — imposters bind real host ports

  def buildInfo: UIO[RiftBuildInfo] = ZIO.succeed(info)

  // Intercept (feature 12, #53): embedded drives it over the FFI, not HTTP — startIntercept gave us
  // the listener endpoint, and rules/truststore go through the FFI handle. The CA-pem + list/clear
  // scenarios (HTTP-only, tagged @intercept-http) are excluded for embedded (no FFI binding).
  override def interceptEndpoint: UIO[Option[(String, Int)]] =
    ZIO.succeed(Some((interceptHost, interceptPort)))

  override def interceptAddRule(ruleJson: String): Task[Unit] = handle.interceptAddRules(ruleJson)

  override def interceptTruststore(password: String): Task[Array[Byte]] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempFile("rift-embedded-ts-", ".p12"))
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore) { p =>
      handle.interceptExportTruststore(password, p.toAbsolutePath.toString) *>
        ZIO.attemptBlocking(Files.readAllBytes(p))
    }

  private val corpusAbs = corpusRoot.toAbsolutePath.toString

  // Absolutize engine-cwd-relative data-source paths (e.g. a lookup's "data/products.csv"): the
  // in-process engine shares the JVM's cwd (the project root), so a bare "data/..." would not
  // resolve. Predicate paths are "/..."; only data-source values start with "data/", so keying on
  // the leading quote + "data/" is unambiguous.
  override def rewriteFixture(json: String): String =
    json.replace("\"data/", s"\"$corpusAbs/data/")

  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] =
    ZIO.acquireRelease(startReloadConfig(json))(_ => revertEngine.orDie).unit

  def startReloadConfig(json: String): Task[Unit] =
    writeConfig(json) *> reload *> pendingRef.set(true)

  def revertEngine: Task[Unit] =
    pendingRef.get.flatMap {
      case true =>
        writeConfig(RiftHarnessEmbedded.EmptyImposters) *> reload *> pendingRef.set(false)
      case false => ZIO.unit // nothing pending — safe to call unconditionally from a cleanup hook
    }

  private def writeConfig(json: String): Task[Unit] =
    ZIO.attemptBlocking(Files.write(reloadConfigFile, json.getBytes(StandardCharsets.UTF_8))).unit

  /** `POST {adminUrl}/admin/reload` — the real in-process admin API reconciles the running
    * imposters toward `reloadConfigFile` (identical byte semantics to external mode's
    * `--configfile`).
    */
  private def reload: Task[Unit] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/admin/reload"))
        .timeout(JDuration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      val code =
        RiftHarnessEmbedded.client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      if code != 200 then
        throw new RuntimeException(s"POST $adminUrl/admin/reload -> $code (expected 200)")
    }

object RiftHarnessEmbedded:
  private[support] val EmptyImposters = """{"imposters":[]}"""
  private[support] val client = HttpClient.newHttpClient()

  /** Acquire the in-process engine + admin API (scoped). Requires JDK 22+ with a resolvable
    * `librift_ffi`; fails with clear guidance otherwise (the runner script SKIPs on JDK < 22 before
    * reaching here, so this is the belt-and-braces guard).
    */
  def acquire(cfg: RiftHarnessConfig): ZIO[Scope, Throwable, RiftHarness] =
    for
      _ <- ZIO.unless(EmbeddedAdmin.available)(
        ZIO.fail(
          new RuntimeException(
            "RIFT_MODE=embedded requires a JDK 22+ JVM with a resolvable librift_ffi " +
              "(rift-java-embedded + a rift-java-natives classifier jar on the test classpath). " +
              "On JDK < 22 use RIFT_MODE=external."
          )
        )
      )
      // Seed the reload config file with an empty imposter set so serve_admin has a source and
      // POST /admin/reload is meaningful; feature 11 overwrites it then reverts it to empty. Inert
      // for every other suite (nothing else reloads).
      reloadConfigFile <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val p = Files.createTempFile("rift-embedded-reload-", ".json")
          Files.write(p, EmptyImposters.getBytes(StandardCharsets.UTF_8))
          p
        }
      )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore)
      admin <- EmbeddedAdmin.serve(0, reloadConfigFile)
      // Start the intercept/TLS-MITM listener (FFI) so feature 12 can drive it; the endpoint feeds
      // `interceptEndpoint`. Rules/truststore go through the same handle.
      intercept <- admin.handle.startIntercept
      (iHost, iPort) = intercept
      info <- RiftBuildInfo.fromBuildDir(cfg.buildDir, engineVersion = admin.commit)
      pendingRef <- Ref.make(false)
    yield RiftHarnessEmbeddedLive(
      admin.adminUrl,
      cfg.corpusRoot,
      reloadConfigFile,
      iHost,
      iPort,
      admin.handle,
      info,
      RiftTools.fromBuildDir(cfg.buildDir),
      pendingRef
    )
