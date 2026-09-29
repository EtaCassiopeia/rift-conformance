package samples.rift.support

import zio.*
import zio.bdd.mock.rift.embedded.EmbeddedRift
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.nio.file.Path

/** The raw C-ABI calls the Plane A embedded harness needs, JSON in and JSON out. Blocking; each
  * throws on an engine error. Implemented by `io.github.achirdlabs.rift.embedded.ConformanceFfi`
  * (src/test/scala-jdk22), which is compiled only on a JDK 22+ build, the FFM floor of
  * `rift-java-embedded`. This trait is what lets the rest of the harness compile on JDK 21.
  */
trait EmbeddedFfi:
  def serveAdmin(optionsJson: String): String
  def buildInfo(): String
  def startIntercept(optionsJson: String): String
  def interceptAddRules(rulesJson: String): Unit
  def interceptExportTruststore(format: String, password: String, outPath: String): Unit
  // The v2 admin long-tail (rift#491, #75). A `null` flowId selects the imposter's default flow.
  def listImposters(optionsJson: String): String
  def getImposter(port: Int, optionsJson: String): String
  def addStub(port: Int, stubJson: String, index: Int): Unit
  def getStub(port: Int, refJson: String): String
  def updateStub(port: Int, refJson: String, stubJson: String): Unit
  def deleteStub(port: Int, refJson: String): Unit
  def clearRecorded(port: Int): Unit
  def clearProxyRecordings(port: Int): Unit
  def setImposterEnabled(port: Int, enabled: Boolean): Unit
  def scenarios(port: Int, flowId: String): String
  def setScenarioState(port: Int, name: String, stateJson: String): Unit
  def resetScenarios(port: Int, flowId: String): Unit
  def stubWarnings(port: Int): String
  def verify(port: Int, bodyJson: String): String
  // The C-ABI long tail (#205): per-space plane, flow state, whole-set reconcile, ABI identity,
  // and the rest of the intercept control plane.
  def spaceAddStub(port: Int, flowId: String, stubJson: String): Unit
  def spaceListStubs(port: Int, flowId: String): String
  def spaceDelete(port: Int, flowId: String): Unit
  def spaceRecorded(port: Int, flowId: String): String
  def flowStateGet(port: Int, flowId: String, key: String): String
  def flowStatePut(port: Int, flowId: String, key: String, valueJson: String): Unit
  def flowStateDelete(port: Int, flowId: String, key: String): Unit
  def applyConfig(json: String): String
  def abiVersion(): Int
  def interceptListRules(): String
  def stopIntercept(): Unit

  /** `rift_last_error` read on the calling thread right after a failed call, optionally with a
    * successful call in between; `null` when no error is set.
    */
  def lastErrorAfterFailure(thenSucceed: Boolean): String
  def stop(): Unit

/** Starts the in-process engine with its real admin API for the Plane A `RiftHarness` embedded mode
  * (rift-conformance #18).
  *
  * Why not zio-bdd's `EmbeddedRift` or rift-java's public `Rift.embedded`: neither can pass
  * `serve_admin` the `allowInjection` and `configFile` options (rift-java's `EmbeddedOptions` sends
  * only host/port/apiKey/upstream trust), and neither takes raw intercept rule JSON. The harness
  * needs all three to serve the same corpus as external mode, so it binds the C-ABI directly
  * through rift-java's package-private `RiftFfi` — the coupling is confined to [[EmbeddedFfi]]'s
  * one implementation.
  */
object EmbeddedAdmin:

  private val ImplClass = "io.github.achirdlabs.rift.embedded.ConformanceFfi"

  /** True iff the FFM binding and a `librift_ffi` for this host resolve — false on JDK < 22 or an
    * unsupported platform. The harness gates on this.
    */
  def available: Boolean =
    EmbeddedRift.available && scala.util.Try(Class.forName(ImplClass)).isSuccess

  final case class Admin(
      adminUrl: String,
      adminPort: Int,
      commit: Option[String],
      version: String,
      handle: Handle
  )

  /** The intercept C-ABI ops. `serve_admin` does not expose `/intercept` over HTTP, so feature 12's
    * rule/truststore admin goes through here for embedded (#53). Valid only inside the scope opened
    * by [[serve]].
    */
  final class Handle private[EmbeddedAdmin] (ffi: EmbeddedFfi):
    /** Start the intercept/TLS-MITM listener on loopback; yields its `(host, port)`. */
    def startIntercept: Task[(String, Int)] =
      for
        out <- ZIO.attemptBlocking(ffi.startIntercept("""{"host":"127.0.0.1","port":0}"""))
        port <- field(out, "interceptPort").flatMap(asInt(out, "interceptPort"))
      yield ("127.0.0.1", port)

    def interceptAddRules(rulesJson: String): Task[Unit] =
      ZIO.attemptBlocking(ffi.interceptAddRules(rulesJson))

    /** Export the intercept truststore (PKCS#12) to `outPath`. */
    def interceptExportTruststore(password: String, outPath: String): Task[Unit] =
      ZIO.attemptBlocking(ffi.interceptExportTruststore("pkcs12", password, outPath))

    // ── v2 admin long-tail (rift#491, #75): the C-ABI twins of the imposter admin routes ──

    /** `rift_list_imposters` — the summary list, or the `replayable` projection. */
    def listImposters(replayable: Boolean = false, removeProxies: Boolean = false): Task[Json] =
      ZIO
        .attemptBlocking(ffi.listImposters(viewOptions(replayable, removeProxies)))
        .flatMap(parseJson)

    /** `rift_get_imposter` — the detail object, or the `replayable` projection. */
    def getImposter(
        port: Int,
        replayable: Boolean = false,
        removeProxies: Boolean = false
    ): Task[Json] =
      ZIO
        .attemptBlocking(ffi.getImposter(port, viewOptions(replayable, removeProxies)))
        .flatMap(parseJson)

    /** `rift_add_stub` — insert at `index`, or append when `None` (the C-ABI treats any negative
      * index as append).
      */
    def addStub(port: Int, stub: Json, index: Option[Int] = None): Task[Unit] =
      ZIO.attemptBlocking(ffi.addStub(port, stub.toJson, index.getOrElse(-1)))

    def getStub(port: Int, ref: StubRef): Task[Json] =
      ZIO.attemptBlocking(ffi.getStub(port, ref.toJson)).flatMap(parseJson)

    def updateStub(port: Int, ref: StubRef, stub: Json): Task[Unit] =
      ZIO.attemptBlocking(ffi.updateStub(port, ref.toJson, stub.toJson))

    def deleteStub(port: Int, ref: StubRef): Task[Unit] =
      ZIO.attemptBlocking(ffi.deleteStub(port, ref.toJson))

    def clearRecorded(port: Int): Task[Unit] =
      ZIO.attemptBlocking(ffi.clearRecorded(port))

    def clearProxyRecordings(port: Int): Task[Unit] =
      ZIO.attemptBlocking(ffi.clearProxyRecordings(port))

    def setImposterEnabled(port: Int, enabled: Boolean): Task[Unit] =
      ZIO.attemptBlocking(ffi.setImposterEnabled(port, enabled))

    /** `rift_scenarios` — `{"flowId","scenarios":[{"name","state"}]}`; `None` = default flow. */
    def scenarios(port: Int, flowId: Option[String] = None): Task[Json] =
      ZIO.attemptBlocking(ffi.scenarios(port, flowId.orNull)).flatMap(parseJson)

    def setScenarioState(
        port: Int,
        name: String,
        state: String,
        flowId: Option[String] = None
    ): Task[Unit] =
      val body = Json.Obj(
        Chunk("state" -> Json.Str(state)) ++ flowId.toList.map(f => "flowId" -> Json.Str(f))
      )
      ZIO.attemptBlocking(ffi.setScenarioState(port, name, body.toJson))

    def resetScenarios(port: Int, flowId: Option[String] = None): Task[Unit] =
      ZIO.attemptBlocking(ffi.resetScenarios(port, flowId.orNull))

    /** `rift_stub_warnings` (rift#423) — the cached stub-overlap analysis, a JSON array of
      * `{"warningType","message","stubIndex"?,"stubId"?,…}` (empty for a clean imposter).
      */
    def stubWarnings(port: Int): Task[Json] =
      ZIO.attemptBlocking(ffi.stubWarnings(port)).flatMap(parseJson)

    /** `rift_verify` (rift#494) — `body` is the `POST /imposters/{port}/verify` body
      * `{"predicates","flowId"?,"includeRequests"?,"includeClosest"?}`; yields the
      * `{"matched","total","requests"?,"closest"?}` envelope.
      */
    def verify(port: Int, body: Json): Task[Json] =
      ZIO.attemptBlocking(ffi.verify(port, body.toJson)).flatMap(parseJson)

    // ── C-ABI long tail (#205) ──

    /** `rift_serve_admin` with raw options — fails with `rift_last_error` on a refusal. */
    def serveAdmin(options: Json): Task[Json] =
      ZIO.attemptBlocking(ffi.serveAdmin(options.toJson)).flatMap(parseJson)

    def buildInfo: Task[Json] = ZIO.attemptBlocking(ffi.buildInfo()).flatMap(parseJson)

    def abiVersion: Task[Int] = ZIO.attemptBlocking(ffi.abiVersion())

    def lastErrorAfterFailure(thenSucceed: Boolean): Task[Option[String]] =
      ZIO.attemptBlocking(Option(ffi.lastErrorAfterFailure(thenSucceed)))

    def spaceAddStub(port: Int, space: String, stub: Json): Task[Unit] =
      ZIO.attemptBlocking(ffi.spaceAddStub(port, space, stub.toJson))

    /** `rift_space_add_stub` with a raw body, for feeding it something that is not a stub. */
    def spaceAddStubRaw(port: Int, space: String, body: String): Task[Unit] =
      ZIO.attemptBlocking(ffi.spaceAddStub(port, space, body))

    def spaceListStubs(port: Int, space: String): Task[Json] =
      ZIO.attemptBlocking(ffi.spaceListStubs(port, space)).flatMap(parseJson)

    def spaceDelete(port: Int, space: String): Task[Unit] =
      ZIO.attemptBlocking(ffi.spaceDelete(port, space))

    def spaceRecorded(port: Int, space: String): Task[Json] =
      ZIO.attemptBlocking(ffi.spaceRecorded(port, space)).flatMap(parseJson)

    def flowStateGet(port: Int, flowId: String, key: String): Task[Json] =
      ZIO.attemptBlocking(ffi.flowStateGet(port, flowId, key)).flatMap(parseJson)

    def flowStatePut(port: Int, flowId: String, key: String, value: Json): Task[Unit] =
      ZIO.attemptBlocking(ffi.flowStatePut(port, flowId, key, value.toJson))

    def flowStateDelete(port: Int, flowId: String, key: String): Task[Unit] =
      ZIO.attemptBlocking(ffi.flowStateDelete(port, flowId, key))

    /** `rift_apply_config` — reconciles the whole imposter set; yields the apply report. */
    def applyConfig(config: Json): Task[Json] =
      ZIO.attemptBlocking(ffi.applyConfig(config.toJson)).flatMap(parseJson)

    /** `rift_start_intercept` with raw options; yields the whole response object. */
    def startInterceptWith(options: Json): Task[Json] =
      ZIO.attemptBlocking(ffi.startIntercept(options.toJson)).flatMap(parseJson)

    def interceptListRules: Task[Json] =
      ZIO.attemptBlocking(ffi.interceptListRules()).flatMap(parseJson)

    def stopIntercept: Task[Unit] = ZIO.attemptBlocking(ffi.stopIntercept())

  /** How `rift_get_stub`/`rift_update_stub`/`rift_delete_stub` address a stub. */
  enum StubRef:
    case ByIndex(index: Int)
    case ById(id: String)

    def toJson: String = this match
      case ByIndex(i) => Json.Obj("index" -> Json.Num(i)).toJson
      case ById(id) => Json.Obj("id" -> Json.Str(id)).toJson

  private def viewOptions(replayable: Boolean, removeProxies: Boolean): String =
    Json
      .Obj("replayable" -> Json.Bool(replayable), "removeProxies" -> Json.Bool(removeProxies))
      .toJson

  private def parseJson(out: String): Task[Json] =
    ZIO
      .fromEither(out.fromJson[Json])
      .mapError(e => new RuntimeException(s"embedded engine answered non-JSON ($e): $out"))

  /** Start the in-process engine and its real admin API on loopback:`port` (0 → OS-assigned),
    * seeding `configFile` so `POST /admin/reload` reconciles toward it (feature 11). Scoped: the
    * engine is stopped when the scope closes. A `serve_admin` rejection is a real error and fails
    * loudly.
    *
    * `allowInjection` (default `true`) mirrors the `--allowInjection` flag external/container mode
    * spawn the binary with: `serve_admin` defaults it to `false` (rift#492/#511), which would
    * reject every inject/scripting imposter the corpus serves. `AllowInjectionGate82Spec` (#82)
    * asserts the gate itself with both values. Direct FFI ops stay ungated by design.
    */
  def serve(
      port: Int,
      configFile: Path,
      allowInjection: Boolean = true
  ): ZIO[Scope, Throwable, Admin] =
    for
      ffi <- ZIO.acquireRelease(open)(f => ZIO.attemptBlocking(f.stop()).orDie)
      opts = Json.Obj(
        "host" -> Json.Str("127.0.0.1"),
        "port" -> Json.Num(port),
        "allowInjection" -> Json.Bool(allowInjection),
        "configFile" -> Json.Str(configFile.toAbsolutePath.toString)
      )
      served <- ZIO.attemptBlocking(ffi.serveAdmin(opts.toJson))
      adminUrl <- field(served, "adminUrl").flatMap(asString(served, "adminUrl"))
      build <- ZIO.attemptBlocking(ffi.buildInfo())
      version <- field(build, "version").flatMap(asString(build, "version"))
      // An absent or empty commit means the natives carry no git identity.
      commit = parse(build).toOption
        .flatMap(_.get("commit"))
        .collect { case Json.Str(c) if c.nonEmpty => c }
    yield Admin(adminUrl, URI.create(adminUrl).getPort, commit, version, new Handle(ffi))

  /** A fresh engine handle with no admin plane served yet, stopped when the scope closes — for
    * driving `rift_serve_admin` options directly (#205).
    */
  def bare: ZIO[Scope, Throwable, Handle] =
    ZIO.acquireRelease(open)(f => ZIO.attemptBlocking(f.stop()).orDie).map(new Handle(_))

  private def open: Task[EmbeddedFfi] =
    ZIO.attemptBlocking(
      Class.forName(ImplClass).getDeclaredConstructor().newInstance().asInstanceOf[EmbeddedFfi]
    )

  private def parse(json: String): Either[String, Json.Obj] =
    json.fromJson[Json.Obj]

  private def field(json: String, name: String): Task[Json] =
    ZIO
      .fromEither(parse(json).flatMap(_.get(name).toRight(s"no '$name' field")))
      .mapError(e => new RuntimeException(s"embedded engine answered unexpectedly ($e): $json"))

  private def asString(json: String, name: String)(v: Json): Task[String] = v match
    case Json.Str(s) => ZIO.succeed(s)
    case other => ZIO.fail(new RuntimeException(s"'$name' is not a string ($other): $json"))

  private def asInt(json: String, name: String)(v: Json): Task[Int] = v match
    case Json.Num(n) => ZIO.succeed(n.intValue)
    case other => ZIO.fail(new RuntimeException(s"'$name' is not a number ($other): $json"))
