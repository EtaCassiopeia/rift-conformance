package zio.bdd.mock.rift.embedded

import zio.*

import java.nio.file.Path

/** Test-only bridge that re-exposes zio-bdd's package-private embedded FFM admin surface
  * (`EmbeddedRift.startEngine` + `EmbeddedEngine.serveAdmin`/`buildInfo`) as a PUBLIC API for the
  * Plane A `RiftHarness` embedded mode (rift-conformance #18).
  *
  * Why it lives here: `EmbeddedEngine`, `RiftFfi`, and `EmbeddedEngine.AdminInfo` are all
  * `private[embedded]` in the released `zio-bdd-rift-embedded` artifact, so the harness (in
  * `samples.rift.support`) cannot reach them directly. A file declared in this package can — and
  * the embedded engine's admin plane is functionally equivalent to the external engine's HTTP admin
  * API (`serveAdmin` starts the real admin API in-process, over the same engine, on a loopback
  * ephemeral port). So the harness drives everything over HTTP against [[Admin.adminUrl]] exactly
  * as external mode does, and this bridge stays minimal: start the engine + admin API, hand back
  * the URL and build identity, nothing else.
  *
  * This is a deliberate coupling to a `private[embedded]` surface; it is confined to this one
  * test-tree shim so an upstream refactor breaks here and nowhere else.
  */
object EmbeddedAdmin:

  /** True iff a `librift_ffi` resolves for this host + JDK — false on JDK < 22 (no FFM binding
    * loads) or an unsupported platform / missing natives jar. The harness gates on this.
    */
  def available: Boolean = EmbeddedRift.available

  /** The in-process admin API's coordinates + build identity, plus a [[Handle]] for the FFI-only
    * operations (intercept) the HTTP admin plane doesn't expose in embedded mode.
    */
  final case class Admin(
      adminUrl: String,
      adminPort: Int,
      commit: Option[String],
      version: String,
      handle: Handle
  )

  /** Wraps the (package-private) `EmbeddedEngine` so the harness can drive the intercept C-ABI
    * (`start_intercept`/`add_rules`/`export_truststore`) without naming the private type. The
    * embedded intercept surface is FFI-only — `serveAdmin` does not expose `/intercept` over HTTP —
    * so feature 12's rule/truststore admin routes through here for embedded (#53). The engine lives
    * in the scope opened by [[serve]]; this handle must only be used within that scope.
    */
  final class Handle private[EmbeddedAdmin] (engine: EmbeddedEngine):
    /** Start the intercept/TLS-MITM listener on loopback; yields its `(host, port)`. */
    def startIntercept: IO[Throwable, (String, Int)] =
      engine
        .startIntercept("""{"host":"127.0.0.1","port":0}""")
        .mapError(mockErr("startIntercept"))
        .map(i => ("127.0.0.1", i.interceptPort))

    def interceptAddRules(rulesJson: String): IO[Throwable, Unit] =
      engine.interceptAddRules(rulesJson).mapError(mockErr("interceptAddRules"))

    /** Export the intercept truststore (PKCS#12) to `outPath`. */
    def interceptExportTruststore(password: String, outPath: String): IO[Throwable, Unit] =
      engine
        .interceptExportTruststore("pkcs12", password, outPath)
        .mapError(mockErr("interceptExportTruststore"))

  /** Start the in-process engine and its real admin API on loopback:`port` (0 → OS-assigned),
    * seeding `configFile` so the admin API's `POST /admin/reload` reconciles toward it (feature
    * 11). Scoped: the engine is stopped when the surrounding scope closes (no process, so nothing
    * to reap beyond the FFI runtime). Fails loudly if the engine can't start or `serve_admin`
    * rejects the options — embedded mode requires a `librift_ffi` whose `serve_admin` honors
    * `configFile` (≥ the version this repo pins), so a rejection is a real error, not something to
    * paper over.
    */
  def serve(port: Int, configFile: Path): ZIO[Scope, Throwable, Admin] =
    for
      engine <- EmbeddedRift.startEngine.mapError(mockErr("engine start"))
      opts =
        s"""{"host":"127.0.0.1","port":$port,"configFile":"${jsonEscape(
            configFile.toAbsolutePath.toString
          )}"}"""
      info <- engine.serveAdmin(opts).mapError(mockErr("serveAdmin"))
      build <- engine.buildInfo.mapError(mockErr("buildInfo"))
    yield Admin(info.adminUrl, info.adminPort, build.commit, build.version, new Handle(engine))

  private def mockErr(op: String): zio.bdd.mock.MockError => Throwable =
    e => new RuntimeException(s"embedded $op failed: $e")

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
