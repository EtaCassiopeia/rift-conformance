package samples.rift

import samples.rift.support.EmbeddedAdmin
import samples.rift.support.EmbeddedAdmin.{Admin, StubRef}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** FFI ↔ admin-HTTP parity for the 12 v2 admin C-ABI symbols (rift#491/#513, #75).
  *
  * One in-process engine (`EmbeddedAdmin.serve`) serves both its real admin HTTP API and the FFI
  * handle, so every case drives the SAME engine through both doors. A read symbol is compared with
  * its HTTP route's body; a mutating symbol is applied over FFI to one imposter and over HTTP to an
  * identical twin, then both imposters are read back over HTTP and compared. JSON is compared as
  * parsed values with object keys order-insensitive, and every case also pins a literal expectation
  * so parity between two equally wrong answers cannot pass.
  *
  * Differences that are asserted explicitly rather than masked:
  *   - the admin HTTP API decorates imposters and stubs with hypermedia `_links` (absolute admin
  *     URLs); the C-ABI has no admin URL and omits them, so `_links` is stripped before comparing;
  *   - the HTTP list summary adds `stubCount`/`recordRequests` to the FFI's documented keys;
  *   - the default scenario flow is keyed by the imposter's own port, so twins differ in `flowId`;
  *   - `GET /imposters/{port}?replayable=true` ignores `replayable` (rift#1219), so the FFI
  *     replayable view is compared with the list route's entry and the single route is pinned.
  *
  * Self-gating: a no-op pass unless `RIFT_MODE=embedded` AND a `librift_ffi` resolves for this JVM
  * (JDK 22+), mirroring [[AllowInjectionGate82Spec]]. Cases run sequentially on the shared engine;
  * each uses its own fresh ports, so list-level comparisons see a stable engine.
  */
object FfiAdminParity75Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private final case class Resp(status: Int, body: String)

  private def http(method: String, url: String, body: Option[String] = None): Task[Resp] =
    ZIO.attemptBlocking {
      val publisher =
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .method(method, publisher)
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      Resp(resp.statusCode, resp.body)
    }

  /** An HTTP call that must answer `expected`; its body parsed as JSON (an empty body is `null`).
    */
  private def httpJson(
      method: String,
      url: String,
      body: Option[String] = None,
      expected: Int = 200
  ): Task[Json] =
    http(method, url, body).flatMap { r =>
      if r.status != expected then
        ZIO.fail(
          new RuntimeException(s"$method $url -> ${r.status} (expected $expected): ${r.body}")
        )
      else if r.body.isBlank then ZIO.succeed(Json.Null)
      else
        ZIO
          .fromEither(r.body.fromJson[Json])
          .mapError(e => new RuntimeException(s"$method $url answered non-JSON ($e): ${r.body}"))
    }

  /** Order-insensitive object keys, `_links` (HTTP-only hypermedia) removed; arrays keep order. */
  private def norm(j: Json): Json = j match
    case Json.Obj(fields) =>
      Json.Obj(fields.filterNot(_._1 == "_links").map((k, v) => k -> norm(v)).sortBy(_._1))
    case Json.Arr(elems) => Json.Arr(elems.map(norm))
    case other => other

  private def field(j: Json, name: String): Json = j match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, v) => v }.getOrElse(Json.Null)
    case _ => Json.Null

  private def arr(j: Json): Chunk[Json] = j match
    case Json.Arr(elems) => elems
    case _ => Chunk.empty

  private def str(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _ => None

  private def firstResponseKind(stub: Json): Option[String] =
    arr(field(stub, "responses")).headOption.flatMap(r => fieldNames(r).find(_ != "_behaviors"))

  private def fieldNames(j: Json): Set[String] = j match
    case Json.Obj(fields) => fields.map(_._1).toSet
    case _ => Set.empty

  /** A `{"imposters":[…]}` listing keyed by port, normalised. */
  private def byPort(list: Json): Map[Int, Json] =
    arr(field(norm(list), "imposters")).flatMap { i =>
      field(i, "port") match
        case Json.Num(p) => Some(p.intValue -> i)
        case _ => None
    }.toMap

  private def stubIds(stubs: Json): Chunk[Option[String]] = arr(stubs).map(s => str(field(s, "id")))

  private def freePort: Task[Int] =
    ZIO.attemptBlocking {
      val s = new ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  // ── fixtures ──

  private def stub(id: String, path: String, body: String): Json =
    Json.Obj(
      "id" -> Json.Str(id),
      "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str(path)))),
      "responses" -> Json.Arr(
        Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> Json.Str(body)))
      )
    )

  private val TwoStubs = Chunk(stub("a", "/a", "A"), stub("b", "/b", "B"))

  /** Scenario `s` on `/go`: `Started` answers 201 and moves to `done`; `done` answers 200. */
  private val FsmStubs: Chunk[Json] =
    def fsmStub(required: String, next: Option[String], status: Int): Json =
      Json.Obj(
        Chunk(
          "scenarioName" -> Json.Str("s"),
          "requiredScenarioState" -> Json.Str(required)
        ) ++ next.toList.map(n => "newScenarioState" -> Json.Str(n)) ++ Chunk(
          "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str("/go")))),
          "responses" -> Json.Arr(Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(status))))
        )
      )
    Chunk(fsmStub("Started", Some("done"), 201), fsmStub("done", None, 200))

  private def imposterJson(port: Int, stubs: Chunk[Json], extra: (String, Json)*): String =
    Json
      .Obj(
        Chunk(
          "protocol" -> Json.Str("http"),
          "port" -> Json.Num(port),
          "recordRequests" -> Json.Bool(true),
          "stubs" -> Json.Arr(stubs)
        ) ++ extra
      )
      .toJson

  private val InMemoryFlowState: (String, Json) =
    "_rift" -> Json.Obj("flowState" -> Json.Obj("backend" -> Json.Str("inmemory")))

  /** Create an imposter over HTTP on a fresh port; yields the port. */
  private def create(admin: Admin, stubs: Chunk[Json], extra: (String, Json)*): Task[Int] =
    for
      port <- freePort
      _ <- httpJson(
        "POST",
        s"${admin.adminUrl}/imposters",
        Some(imposterJson(port, stubs, extra*)),
        201
      )
    yield port

  /** Two identical imposters: the first is mutated over FFI, the second over HTTP. */
  private def twins(admin: Admin, stubs: Chunk[Json], extra: (String, Json)*): Task[(Int, Int)] =
    create(admin, stubs, extra*).zip(create(admin, stubs, extra*))

  private def hit(port: Int, path: String): Task[Int] =
    http("GET", s"http://127.0.0.1:$port$path").map(_.status)

  /** The imposter's live stubs as `GET /imposters/{port}` reports them. */
  private def httpStubs(admin: Admin, port: Int): Task[Json] =
    httpImposter(admin, port).map(j => norm(field(j, "stubs")))

  private def httpImposter(admin: Admin, port: Int): Task[Json] =
    httpJson("GET", s"${admin.adminUrl}/imposters/$port")

  private def httpScenarios(admin: Admin, port: Int, flowId: Option[String] = None): Task[Json] =
    httpJson(
      "GET",
      s"${admin.adminUrl}/imposters/$port/scenarios${flowId.fold("")(f => s"?flowId=$f")}"
    )
      .map(norm)

  private def stateOf(scenarios: Json, name: String): Option[String] =
    arr(field(scenarios, "scenarios"))
      .find(s => str(field(s, "name")).contains(name))
      .flatMap(s => str(field(s, "state")))

  // ── engine: one per spec, shared by every case ──

  private val embedded: Boolean =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private val emptyConfig: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-ffi-parity-75-", ".json")
        Files.write(p, """{"imposters":[]}""".getBytes(StandardCharsets.UTF_8))
        p
      }
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)

  private val engine: ZLayer[Any, Throwable, Option[Admin]] =
    ZLayer.scoped {
      if !embedded || !EmbeddedAdmin.available then ZIO.none
      else emptyConfig.flatMap(cfg => EmbeddedAdmin.serve(0, cfg)).asSome
    }

  private def withEngine(
      f: Admin => Task[TestResult]
  ): ZIO[Option[Admin], Throwable, TestResult] =
    ZIO.serviceWithZIO[Option[Admin]](_.fold(ZIO.succeed(assertCompletes))(f))

  def spec = suite("FFI ↔ admin HTTP parity: v2 admin C-ABI (embedded, #75)")(
    test("rift_list_imposters matches GET /imposters (summary and replayable)") {
      withEngine { admin =>
        val ffi = admin.handle
        // Documented shape difference: rift_list_imposters' summary carries exactly
        // {protocol, port, name?, numberOfRequests, enabled} (rift_ffi.h); the HTTP summary adds
        // `stubCount` and `recordRequests`. The shared keys must agree; the extras are pinned below.
        val ffiSummaryKeys = Set("protocol", "port", "name", "numberOfRequests", "enabled")
        def keep(keys: Set[String])(j: Json): Json = j match
          case Json.Obj(fields) => Json.Obj(fields.filter((k, _) => keys(k)))
          case other => other
        for
          port <- create(admin, TwoStubs)
          _ <- hit(port, "/a")
          ffiSummary <- ffi.listImposters()
          httpSummary <- httpJson("GET", s"${admin.adminUrl}/imposters")
          ffiReplay <- ffi.listImposters(replayable = true)
          httpReplay <- httpJson("GET", s"${admin.adminUrl}/imposters?replayable=true")
          mine = byPort(ffiSummary).get(port)
          httpMine = byPort(httpSummary).get(port)
        yield assertTrue(
          byPort(ffiSummary) == byPort(httpSummary).view.mapValues(keep(ffiSummaryKeys)).toMap,
          byPort(ffiReplay) == byPort(httpReplay),
          byPort(ffiReplay)
            .get(port)
            .map(i => stubIds(field(i, "stubs")))
            .contains(Chunk(Some("a"), Some("b"))),
          byPort(ffiReplay).get(port).exists(i => !fieldNames(i).contains("requests")),
          mine.map(field(_, "numberOfRequests")).contains(Json.Num(1)),
          mine.map(field(_, "enabled")).contains(Json.Bool(true)),
          httpMine
            .map(fieldNames(_) -- ffiSummaryKeys)
            .contains(Set("stubCount", "recordRequests")),
          httpMine.map(field(_, "stubCount")).contains(Json.Num(2))
        )
      }
    },
    test("rift_get_imposter matches GET /imposters/{port} (detail and replayable)") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          port <- create(admin, TwoStubs)
          _ <- hit(port, "/b")
          ffiDetail <- ffi.getImposter(port)
          httpDetail <- httpImposter(admin, port)
          ffiReplay <- ffi.getImposter(port, replayable = true)
          httpListReplay <- httpJson("GET", s"${admin.adminUrl}/imposters?replayable=true")
          httpSingleReplay <- httpJson("GET", s"${admin.adminUrl}/imposters/$port?replayable=true")
        yield assertTrue(
          norm(ffiDetail) == norm(httpDetail),
          field(ffiDetail, "numberOfRequests") == Json.Num(1),
          // The replayable oracle is the list route's entry: `GET /imposters/{port}?replayable=true`
          // ignores `replayable` and answers the detail view (rift#1219, pinned below).
          byPort(httpListReplay).get(port).contains(norm(ffiReplay)),
          stubIds(field(ffiReplay, "stubs")) == Chunk(Some("a"), Some("b")),
          !fieldNames(ffiReplay).contains("requests"),
          // @gap rift#1219: flip to `== norm(ffiReplay)` once the single route honours replayable.
          fieldNames(httpSingleReplay).contains("requests")
        )
      }
    },
    test("rift_add_stub (at index / append) matches POST /imposters/{port}/stubs") {
      withEngine { admin =>
        val ffi = admin.handle
        val (c, d) = (stub("c", "/c", "C"), stub("d", "/d", "D"))
        for
          ports <- twins(admin, TwoStubs)
          (viaFfi, viaHttp) = ports
          _ <- ffi.addStub(viaFfi, c, index = Some(0))
          _ <- ffi.addStub(viaFfi, d)
          url = s"${admin.adminUrl}/imposters/$viaHttp/stubs"
          _ <- httpJson("POST", url, Some(Json.Obj("index" -> Json.Num(0), "stub" -> c).toJson))
          _ <- httpJson("POST", url, Some(Json.Obj("stub" -> d).toJson))
          ffiSide <- httpStubs(admin, viaFfi)
          httpSide <- httpStubs(admin, viaHttp)
        yield assertTrue(
          ffiSide == httpSide,
          stubIds(ffiSide) == Chunk(Some("c"), Some("a"), Some("b"), Some("d"))
        )
      }
    },
    test("rift_get_stub (by index / by id) matches GET /imposters/{port}/stubs/{index|by-id}") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          port <- create(admin, TwoStubs)
          ffiByIndex <- ffi.getStub(port, StubRef.ByIndex(1))
          httpByIndex <- httpJson("GET", s"${admin.adminUrl}/imposters/$port/stubs/1")
          ffiById <- ffi.getStub(port, StubRef.ById("a"))
          httpById <- httpJson("GET", s"${admin.adminUrl}/imposters/$port/stubs/by-id/a")
        yield assertTrue(
          norm(ffiByIndex) == norm(httpByIndex),
          norm(ffiById) == norm(httpById),
          str(field(ffiByIndex, "id")).contains("b"),
          str(field(ffiById, "id")).contains("a")
        )
      }
    },
    test("rift_update_stub (by id / by index) matches PUT /imposters/{port}/stubs/{by-id|index}") {
      withEngine { admin =>
        val ffi = admin.handle
        val (a2, b2) = (stub("a", "/a", "A2"), stub("b", "/b", "B2"))
        for
          ports <- twins(admin, TwoStubs)
          (viaFfi, viaHttp) = ports
          _ <- ffi.updateStub(viaFfi, StubRef.ById("a"), a2)
          _ <- ffi.updateStub(viaFfi, StubRef.ByIndex(1), b2)
          base = s"${admin.adminUrl}/imposters/$viaHttp/stubs"
          _ <- httpJson("PUT", s"$base/by-id/a", Some(a2.toJson))
          _ <- httpJson("PUT", s"$base/1", Some(b2.toJson))
          ffiSide <- httpStubs(admin, viaFfi)
          httpSide <- httpStubs(admin, viaHttp)
          servedA <- http("GET", s"http://127.0.0.1:$viaFfi/a").map(_.body)
          servedB <- http("GET", s"http://127.0.0.1:$viaFfi/b").map(_.body)
        yield assertTrue(
          ffiSide == httpSide,
          stubIds(ffiSide) == Chunk(Some("a"), Some("b")),
          servedA == "A2",
          servedB == "B2"
        )
      }
    },
    test(
      "rift_delete_stub (by index / by id) matches DELETE /imposters/{port}/stubs/{index|by-id}"
    ) {
      withEngine { admin =>
        val ffi = admin.handle
        for
          ports <- twins(admin, TwoStubs)
          (viaFfi, viaHttp) = ports
          _ <- ffi.deleteStub(viaFfi, StubRef.ByIndex(0))
          _ <- httpJson("DELETE", s"${admin.adminUrl}/imposters/$viaHttp/stubs/0")
          ffiAfterIndex <- httpStubs(admin, viaFfi)
          httpAfterIndex <- httpStubs(admin, viaHttp)
          _ <- ffi.deleteStub(viaFfi, StubRef.ById("b"))
          _ <- httpJson("DELETE", s"${admin.adminUrl}/imposters/$viaHttp/stubs/by-id/b")
          ffiAfterId <- httpStubs(admin, viaFfi)
          httpAfterId <- httpStubs(admin, viaHttp)
        yield assertTrue(
          ffiAfterIndex == httpAfterIndex,
          stubIds(ffiAfterIndex) == Chunk(Some("b")),
          ffiAfterId == httpAfterId,
          arr(ffiAfterId).isEmpty
        )
      }
    },
    test("rift_clear_recorded matches DELETE /imposters/{port}/savedRequests") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          ports <- twins(admin, TwoStubs)
          (viaFfi, viaHttp) = ports
          _ <- ZIO.foreachDiscard(List(viaFfi, viaHttp))(p => hit(p, "/a") *> hit(p, "/b"))
          before <- httpImposter(admin, viaFfi)
          _ <- ffi.clearRecorded(viaFfi)
          _ <- httpJson("DELETE", s"${admin.adminUrl}/imposters/$viaHttp/savedRequests")
          ffiSide <- httpImposter(admin, viaFfi)
          httpSide <- httpImposter(admin, viaHttp)
        yield assertTrue(
          field(before, "numberOfRequests") == Json.Num(2),
          field(ffiSide, "numberOfRequests") == field(httpSide, "numberOfRequests"),
          field(ffiSide, "requests") == field(httpSide, "requests"),
          field(ffiSide, "numberOfRequests") == Json.Num(0),
          arr(field(ffiSide, "requests")).isEmpty
        )
      }
    },
    test("rift_clear_proxy_recordings matches DELETE /imposters/{port}/savedProxyResponses") {
      withEngine { admin =>
        val ffi = admin.handle
        // A proxyOnce stub without predicateGenerators records into the proxy store (not as a
        // stub) and replays from it, so the upstream sees only the first request. Clearing the
        // store is observable as the upstream being reached again. Each twin gets its own upstream.
        def proxyTo(upstream: Int): Json = Json.Obj(
          "responses" -> Json.Arr(
            Json.Obj(
              "proxy" -> Json.Obj(
                "to" -> Json.Str(s"http://127.0.0.1:$upstream"),
                "mode" -> Json.Str("proxyOnce")
              )
            )
          )
        )
        def upstreamHits(upstream: Int) =
          httpImposter(admin, upstream).map(field(_, "numberOfRequests"))
        for
          upFfi <- create(admin, Chunk(stub("up", "/x", "upstream")))
          upHttp <- create(admin, Chunk(stub("up", "/x", "upstream")))
          viaFfi <- create(admin, Chunk(proxyTo(upFfi)))
          viaHttp <- create(admin, Chunk(proxyTo(upHttp)))
          _ <- ZIO.foreachDiscard(List(viaFfi, viaHttp))(p => hit(p, "/x").repeatN(1))
          replayedFfi <- upstreamHits(upFfi)
          ffiNoProxies <- ffi.getImposter(viaFfi, replayable = true, removeProxies = true)
          httpNoProxies <- httpJson(
            "GET",
            s"${admin.adminUrl}/imposters?replayable=true&removeProxies=true"
          )
          _ <- ffi.clearProxyRecordings(viaFfi)
          _ <- httpJson("DELETE", s"${admin.adminUrl}/imposters/$viaHttp/savedProxyResponses")
          _ <- ZIO.foreachDiscard(List(viaFfi, viaHttp))(p => hit(p, "/x"))
          ffiSide <- upstreamHits(upFfi)
          httpSide <- upstreamHits(upHttp)
          ffiStubList <- httpStubs(admin, viaFfi)
          httpStubList <- httpStubs(admin, viaHttp)
        yield assertTrue(
          replayedFfi == Json.Num(1),
          ffiSide == httpSide,
          ffiSide == Json.Num(2),
          byPort(httpNoProxies).get(viaFfi).contains(norm(ffiNoProxies)),
          !arr(field(ffiNoProxies, "stubs")).flatMap(firstResponseKind).contains("proxy"),
          arr(ffiStubList).map(firstResponseKind) == Chunk(Some("proxy")),
          arr(ffiStubList).map(firstResponseKind) == arr(httpStubList).map(firstResponseKind)
        )
      }
    },
    test("rift_set_imposter_enabled matches POST /imposters/{port}/disable|enable") {
      withEngine { admin =>
        val ffi = admin.handle
        def enabled(port: Int) = httpImposter(admin, port).map(field(_, "enabled"))
        for
          ports <- twins(admin, TwoStubs)
          (viaFfi, viaHttp) = ports
          _ <- ffi.setImposterEnabled(viaFfi, enabled = false)
          _ <- httpJson("POST", s"${admin.adminUrl}/imposters/$viaHttp/disable")
          ffiOff <- enabled(viaFfi)
          httpOff <- enabled(viaHttp)
          _ <- ffi.setImposterEnabled(viaFfi, enabled = true)
          _ <- httpJson("POST", s"${admin.adminUrl}/imposters/$viaHttp/enable")
          ffiOn <- enabled(viaFfi)
          httpOn <- enabled(viaHttp)
        yield assertTrue(
          ffiOff == httpOff,
          ffiOff == Json.Bool(false),
          ffiOn == httpOn,
          ffiOn == Json.Bool(true)
        )
      }
    },
    test("rift_scenarios (default and named flow) matches GET /imposters/{port}/scenarios") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          port <- create(admin, FsmStubs, InMemoryFlowState)
          _ <- hit(port, "/go")
          ffiDefault <- ffi.scenarios(port)
          httpDefault <- httpScenarios(admin, port)
          _ <- ffi.setScenarioState(port, "s", "named", Some("f1"))
          ffiNamed <- ffi.scenarios(port, Some("f1"))
          httpNamed <- httpScenarios(admin, port, Some("f1"))
        yield assertTrue(
          norm(ffiDefault) == httpDefault,
          str(field(ffiDefault, "flowId")).contains(port.toString),
          stateOf(ffiDefault, "s").contains("done"),
          norm(ffiNamed) == httpNamed,
          stateOf(ffiNamed, "s").contains("named"),
          str(field(ffiNamed, "flowId")).contains("f1")
        )
      }
    },
    test(
      "rift_set_scenario_state (default and named flow) matches PUT .../scenarios/{name}/state"
    ) {
      withEngine { admin =>
        val ffi = admin.handle
        for
          ports <- twins(admin, FsmStubs, InMemoryFlowState)
          (viaFfi, viaHttp) = ports
          _ <- ffi.setScenarioState(viaFfi, "s", "done")
          _ <- ffi.setScenarioState(viaFfi, "s", "other", Some("f2"))
          url = s"${admin.adminUrl}/imposters/$viaHttp/scenarios/s/state"
          _ <- httpJson("PUT", url, Some("""{"state":"done"}"""))
          _ <- httpJson("PUT", url, Some("""{"state":"other","flowId":"f2"}"""))
          ffiDefault <- httpScenarios(admin, viaFfi)
          httpDefault <- httpScenarios(admin, viaHttp)
          ffiNamed <- httpScenarios(admin, viaFfi, Some("f2"))
          httpNamed <- httpScenarios(admin, viaHttp, Some("f2"))
          served <- hit(viaFfi, "/go")
        yield assertTrue(
          // The default flow is keyed by the imposter's own port, so twins differ in `flowId` only.
          field(ffiDefault, "scenarios") == field(httpDefault, "scenarios"),
          str(field(ffiDefault, "flowId")).contains(viaFfi.toString),
          stateOf(ffiDefault, "s").contains("done"),
          ffiNamed == httpNamed,
          stateOf(ffiNamed, "s").contains("other"),
          served == 200
        )
      }
    },
    test("rift_reset_scenarios (default and named flow) matches POST .../scenarios/reset") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          ports <- twins(admin, FsmStubs, InMemoryFlowState)
          (viaFfi, viaHttp) = ports
          _ <- ZIO.foreachDiscard(List(viaFfi, viaHttp)) { p =>
            ffi.setScenarioState(p, "s", "done") *> ffi.setScenarioState(p, "s", "done", Some("f3"))
          }
          _ <- ffi.resetScenarios(viaFfi)
          _ <- httpJson("POST", s"${admin.adminUrl}/imposters/$viaHttp/scenarios/reset", Some("{}"))
          ffiDefault <- httpScenarios(admin, viaFfi)
          httpDefault <- httpScenarios(admin, viaHttp)
          namedUntouched <- httpScenarios(admin, viaFfi, Some("f3"))
          _ <- ffi.resetScenarios(viaFfi, Some("f3"))
          _ <- httpJson(
            "POST",
            s"${admin.adminUrl}/imposters/$viaHttp/scenarios/reset",
            Some("""{"flowId":"f3"}""")
          )
          ffiNamed <- httpScenarios(admin, viaFfi, Some("f3"))
          httpNamed <- httpScenarios(admin, viaHttp, Some("f3"))
        yield assertTrue(
          field(ffiDefault, "scenarios") == field(httpDefault, "scenarios"),
          stateOf(ffiDefault, "s").contains("Started"),
          stateOf(namedUntouched, "s").contains("done"),
          ffiNamed == httpNamed,
          stateOf(ffiNamed, "s").contains("Started")
        )
      }
    },
    test("an unknown port or stub is an FFI error where the HTTP route answers 404") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          port <- create(admin, TwoStubs)
          unknown <- freePort
          httpImp <- http("GET", s"${admin.adminUrl}/imposters/$unknown").map(_.status)
          httpStub <- http("GET", s"${admin.adminUrl}/imposters/$port/stubs/by-id/nope")
            .map(_.status)
          failures <- ZIO.foreach(
            List(
              ffi.getImposter(unknown),
              ffi.clearRecorded(unknown),
              ffi.setImposterEnabled(unknown, enabled = false),
              ffi.getStub(port, StubRef.ById("nope")),
              ffi.deleteStub(port, StubRef.ByIndex(99))
            )
          )(_.either.map(_.left.toOption.map(_.getMessage)))
        yield assertTrue(
          httpImp == 404,
          httpStub == 404,
          // Each call fails with the engine's own `rift_last_error`, not the no-message fallback.
          failures.forall(_.exists(_.startsWith("rift engine call failed: ")))
        )
      }
    }
  ).provideShared(engine) @@ TestAspect.sequential
