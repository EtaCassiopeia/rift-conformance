package samples.rift

import samples.rift.support.{
  LastResponse,
  RawHttpConnection,
  RawRead,
  RawResponse,
  RiftConformanceSupport,
  RiftHarness,
  RiftState,
  TestPorts,
  WsEchoOrigin
}
import zio.*
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.bdd.core.Assertions
import zio.bdd.core.Suite
import zio.bdd.core.step.{Stage, ZIOSteps}

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, ProxySelector, URI}
import javax.net.ssl.SSLContext
import scala.jdk.CollectionConverters.*

/** Feature 12 — the intercept / TLS-MITM forward-proxy (rift 0.10.0 admin API + 0.11.0 FFI). The
  * harness starts rift with an intercept listener ([[RiftHarness.interceptEndpoint]]); these steps
  * drive its `/intercept` admin API (rules + CA/truststore export) and then issue a real HTTPS
  * request **through the intercept proxy** with a JDK `HttpClient` — `CONNECT`-tunnelled and
  * TLS-terminated by rift with a leaf minted from the exported CA — asserting the decrypted request
  * was matched (served inline, or forwarded to an imposter).
  *
  * External mode only (it starts an `--intercept-port` listener); the embedded/container aliases do
  * not list this suite. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/12-intercept.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Intercept12Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer

  private val http = HttpClient.newHttpClient()
  private val TrustPassword = "changeit"

  /** Raw tunnels opened by @intercept-raw steps, closed after every scenario. */
  private val openTunnels: Ref[List[RawHttpConnection]] =
    Unsafe.unsafe(u => Ref.unsafe.make(List.empty[RawHttpConnection])(using u))

  /** WebSocket echo origins started by @intercept-ws steps, closed after every scenario. */
  private val wsOrigins: Ref[List[WsEchoOrigin]] =
    Unsafe.unsafe(u => Ref.unsafe.make(List.empty[WsEchoOrigin])(using u))

  // Rules are host-scoped and accumulate on the shared listener. For external/container this HTTP
  // DELETE actually clears them after each scenario (keeping the list/clear count assertions honest).
  // Embedded has NO clear capability — the FFI binds start/add-rules/export-truststore but not
  // list/clear, and its serveAdmin plane serves no `/intercept` HTTP (so the DELETE 404s, a no-op);
  // embedded rules therefore persist for the engine's lifetime. That's safe only because every
  // scenario here uses a UNIQUE host, so accumulated rules never shadow each other — a required
  // convention for any embedded-capable scenario added later (there is no per-scenario reset there).
  afterScenario {
    // Close any raw tunnel an @intercept-raw scenario left open (a no-op otherwise), and any
    // @intercept-ws echo origin.
    openTunnels.getAndSet(Nil).flatMap(cs => ZIO.foreachDiscard(cs)(c => ZIO.succeed(c.close()))) *>
      wsOrigins.getAndSet(Nil).flatMap(os => ZIO.foreachDiscard(os)(o => ZIO.succeed(o.close()))) *>
      ZIO
        .serviceWithZIO[RiftHarness](h =>
          // Clear accumulated rules on the shared listener AND reap the @intercept-runtime sidecar
          // engine if a scenario started one (a no-op otherwise). The rule-clear is logged-then-ignored
          // (a non-404 failure is a real admin fault worth surfacing, per deleteRules) but must never
          // block the sidecar reap that follows.
          deleteRules(h.adminUrl)
            .tapError(e => ZIO.logWarning(s"intercept rules cleanup failed: ${e.getMessage}"))
            .ignore *> h.stopRuntimeInterceptEngine
            .ensuring(h.stopInterceptAuthEngine.ignoreLogged)
            .ensuring(h.stopUpstreamTrustEngine.ignoreLogged)
        )
        .tapError(e => ZIO.logWarning(s"intercept sidecar cleanup failed: ${e.getMessage}"))
        .ignore
  }

  // ── HTTP-only admin (list/clear/ca.pem) — external/container; embedded lacks these FFI bindings,
  //    so the scenarios using them are tagged @intercept-http and excluded for embedded. Rule
  //    creation + truststore export go through the RiftHarness seam (HTTP or FFI per mode) instead.

  private def deleteRules(adminUrl: String): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/intercept/rules")).DELETE().build()
      val code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      // 404 = nothing to clear (fine); any other non-2xx is a real admin failure worth surfacing —
      // a silently-ignored DELETE would leak rules into the next scenario (and mask a broken API).
      if code >= 300 && code != 404 then
        throw new RuntimeException(s"DELETE /intercept/rules -> $code")
    }

  private def rulesCount(adminUrl: String): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/intercept/rules")).GET().build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      // Assert 200 first: a non-200 must fail loudly, never default to a vacuous "0 rules".
      if resp.statusCode != 200 then
        throw new RuntimeException(
          s"GET /intercept/rules -> ${resp.statusCode}: ${resp.body.take(80)}"
        )
      val body = resp.body.trim
      // The list endpoint returns a JSON array of `{host, ...}` rule objects; count them by the `host`
      // key rather than pulling in a parser for this one assertion. Safe here because the only rule
      // bodies posted in this feature contain no literal "host" text.
      if body == "[]" || body.isEmpty then 0 else body.split("\"host\"", -1).length - 1
    }

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** `method path` against the admin API at `admin`, with an optional JSON body. */
  private def adminSend(
      admin: String,
      method: String,
      path: String,
      body: Option[String] = None
  ): IO[Throwable, LastResponse] =
    ZIO.attemptBlocking {
      val publisher =
        body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
      val req = HttpRequest
        .newBuilder(URI.create(s"$admin$path"))
        .header("Content-Type", "application/json")
        .method(method, publisher)
        .build()
      val r = http.send(req, HttpResponse.BodyHandlers.ofString())
      val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
      LastResponse(r.statusCode, r.body, hs)
    }

  /** Send `method path` to the @intercept-runtime sidecar engine's admin API (the one started with
    * NO `--intercept-port`), so the shared `the response status is N` / `the response body contains
    * X` steps can assert on it. POST defaults to an empty JSON object body — rift's `POST
    * /intercept` takes optional options and defaults them.
    */
  private def runtimeInterceptSend(
      method: String,
      path: String,
      body: String = "{}"
  ): ZIO[RiftHarness, Throwable, LastResponse] =
    ZIO
      .serviceWithZIO[RiftHarness](_.runtimeInterceptAdminUrl)
      .flatMap(adminSend(_, method, path, Option.when(method == "POST")(body)))

  /** The listener a `POST /intercept` started: its port, and the CA PEM the 201 handed back, if any
    * (`returnCaKey`).
    */
  private final case class RuntimeListener(port: Int, returnedCa: Option[String])

  /** `POST /intercept` with `body` on the runtime engine; the response becomes the last response,
    * and a 201's listener port (and returned CA) is kept for the tunnel steps.
    */
  private def startRuntimeListener(body: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      r <- runtimeInterceptSend("POST", "/intercept", body)
      _ <- Stage.put(r)
      _ <- ZIO.when(r.status == 201) {
        for
          json <- ZIO
            .fromEither(r.body.fromJson[Json])
            .mapError(e => new RuntimeException(s"POST /intercept 201 is not JSON ($e): ${r.body}"))
          port <- ZIO
            .fromOption(json.get(JsonCursor.field("interceptPort")).toOption.collect {
              case Json.Num(n) => n.intValue
            })
            .orElseFail(
              new RuntimeException(s"POST /intercept 201 has no interceptPort: ${r.body}")
            )
          ca = json.get(JsonCursor.field("caCertPem")).toOption.collect { case Json.Str(s) => s }
          _ <- Stage.put(RuntimeListener(port, ca))
        yield ()
      }
    yield ()

  /** A tunnel through the runtime listener trusting `caPem`, then `GET path` on it. */
  private def runtimeListenerExchange(
      host: String,
      path: String,
      caPem: String
  ): IO[Throwable, Unit] =
    for
      listener <- Stage
        .get[RuntimeListener]
        .mapError(e => new IllegalStateException(s"no runtime listener was started ($e)"))
      ssl <- ZIO.attemptBlocking(RawHttpConnection.sslTrustingPem(caPem))
      conn <- ZIO.attemptBlocking(RawHttpConnection.tunnel("127.0.0.1", listener.port, host, ssl))
      _ <- openTunnels.update(conn :: _)
      _ <- rawRead(conn, RawHttpConnection.request("GET", host, path), host)
    yield ()

  private def runtimeRulesPost(body: String): ZIO[RiftHarness, Throwable, Unit] =
    runtimeInterceptSend("POST", "/intercept/rules", body).flatMap(Stage.put(_))

  /** A serve rule for any path on `host` answering 200 with `body`. */
  private def serveRule(host: String, body: String): Json =
    Json.Obj(
      "host" -> Json.Str(host),
      "action" -> Json.Obj(
        "serve" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> Json.Str(body))
      )
    )

  private def fixture(path: String): String =
    val is = getClass.getResourceAsStream(path)
    if is == null then throw new IllegalStateException(s"missing test resource $path")
    try new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally is.close()

  /** The committed test intercept CA (#55). */
  private lazy val TestCaCert = fixture("/rift-fixtures/intercept/ca-cert.pem")
  private lazy val TestCaKey = fixture("/rift-fixtures/intercept/ca-key.pem")

  // ── @intercept-auth ──

  /** The scenario's `--intercept-auth` engine. */
  private final case class AuthEngine(adminUrl: String, interceptPort: Int)

  private def authEngine: IO[Throwable, AuthEngine] =
    Stage
      .get[AuthEngine]
      .mapError(e => new IllegalStateException(s"no intercept-auth engine was started ($e)"))

  private def proxyAuthorization(credentials: String): (String, String) =
    "Proxy-Authorization" ->
      s"Basic ${java.util.Base64.getEncoder.encodeToString(credentials.getBytes("UTF-8"))}"

  /** A bare `CONNECT host:443` on a plaintext connection to the auth listener; the answer becomes
    * the last response, and the connection is kept to observe the close.
    */
  private def connectToAuthListener(
      host: String,
      headers: List[(String, String)]
  ): IO[Throwable, Unit] =
    for
      engine <- authEngine
      conn <- ZIO.attemptBlocking(RawHttpConnection.plain("localhost", engine.interceptPort))
      _ <- openTunnels.update(conn :: _)
      _ <- Stage.put(ListenerConnection(conn))
      _ <- rawRead(conn, RawHttpConnection.connectRequest(host, headers), host)
    yield ()

  /** Build an SSLContext that trusts the intercept CA from the exported PKCS#12 truststore bytes —
    * exactly what a real SUT would do to trust the MITM leaf certs. The bytes come from the harness
    * seam (`interceptTruststore`), which is HTTP for external/container and FFI for embedded.
    */
  private def sslFromTruststore(bytes: Array[Byte]): IO[Throwable, SSLContext] =
    ZIO.attemptBlocking(RawHttpConnection.sslTrusting(bytes, TrustPassword))

  /** Issue an HTTPS GET to `host``path` THROUGH the intercept listener (as an HTTP proxy: the JDK
    * client sends `CONNECT host:443`, which rift intercepts and TLS-MITMs). No DNS for `host` is
    * needed — the proxy tunnels by name. `version` is the client's preference; the protocol ALPN
    * actually settled on comes back with the response.
    */
  private def httpsViaIntercept(
      host: String,
      path: String,
      version: HttpClient.Version = HttpClient.Version.HTTP_2
  ): ZIO[RiftHarness, Throwable, (LastResponse, HttpClient.Version)] =
    for
      listener <- interceptListener
      (iHost, iPort) = listener
      bytes <- ZIO.serviceWithZIO[RiftHarness](_.interceptTruststore(TrustPassword))
      ssl <- sslFromTruststore(bytes)
      resp <- ZIO.attemptBlocking {
        val client = HttpClient
          .newBuilder()
          .version(version)
          .sslContext(ssl)
          .proxy(ProxySelector.of(new InetSocketAddress(iHost, iPort)))
          .build()
        val req = HttpRequest.newBuilder(URI.create(s"https://$host$path")).GET().build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
        (LastResponse(r.statusCode, r.body, hs), r.version)
      }
    yield resp

  /** The HTTP version the last `httpsViaIntercept` exchange was served over. */
  private final case class NegotiatedVersion(version: HttpClient.Version)

  // ── @intercept-raw: the raw tunnel client (framing + connection contracts HttpClient hides) ──

  /** The scenario's open raw tunnel: `CONNECT host:443` + TLS, reused by every raw step to the same
    * host so a second exchange rides the same connection. Kept in the Stage for the steps and in
    * [[openTunnels]] so `afterScenario` closes it however the scenario ends.
    */
  private final case class RawTunnel(host: String, conn: RawHttpConnection)

  /** A plaintext connection to the intercept listener itself, before any tunnel (#151). */
  private final case class ListenerConnection(conn: RawHttpConnection)

  private def interceptListener: ZIO[RiftHarness, Throwable, (String, Int)] =
    ZIO
      .serviceWithZIO[RiftHarness](_.interceptEndpoint)
      .someOrFail(new RuntimeException("no intercept listener in this RIFT_MODE"))

  /** The scenario's already-open tunnel to `host`; never opens one, so a step using it proves
    * reuse.
    */
  private def openTunnelTo(host: String): IO[Throwable, RawHttpConnection] =
    Stage.get[RawTunnel].option.flatMap {
      case Some(RawTunnel(`host`, conn)) => ZIO.succeed(conn)
      case other =>
        ZIO.fail(new IllegalStateException(s"no open raw tunnel to $host (have $other)"))
    }

  /** The open tunnel to `host`, or a fresh one through the intercept listener trusting its CA. */
  private def tunnelTo(host: String): ZIO[RiftHarness, Throwable, RawHttpConnection] =
    Stage.get[RawTunnel].option.flatMap {
      case Some(RawTunnel(`host`, conn)) => ZIO.succeed(conn)
      case _ =>
        for
          listener <- interceptListener
          (iHost, iPort) = listener
          bytes <- ZIO.serviceWithZIO[RiftHarness](_.interceptTruststore(TrustPassword))
          ssl <- sslFromTruststore(bytes)
          conn <- ZIO.attemptBlocking(RawHttpConnection.tunnel(iHost, iPort, host, ssl))
          _ <- openTunnels.update(conn :: _)
          _ <- Stage.put(RawTunnel(host, conn))
        yield conn
    }

  /** Send `request` on the tunnel to `host` and make the answer the scenario's last response. A
    * close before any answer fails the step — the raw steps that allow a close say so themselves.
    */
  private def rawExchange(host: String, request: Array[Byte]): ZIO[RiftHarness, Throwable, Unit] =
    tunnelTo(host).flatMap(rawRead(_, request, host))

  private def rawRead(
      conn: RawHttpConnection,
      request: Array[Byte],
      host: String
  ): IO[Throwable, Unit] =
    ZIO.attemptBlocking(conn.exchange(request)).flatMap {
      case RawRead.Response(r) => Stage.put(r.toLastResponse) *> Stage.put(r)
      case RawRead.Closed =>
        ZIO.fail(
          new RuntimeException(s"the intercept connection for $host closed without answering")
        )
    }

  private def lastRawResponse: IO[Throwable, RawResponse] =
    Stage
      .get[RawResponse]
      .mapError(e => new IllegalStateException(s"no raw tunnel response yet ($e)"))

  /** The `serve.body` of the listed intercept rule for `host`, from `GET /intercept/rules`. */
  private def listedServeBody(adminUrl: String, host: String): IO[Throwable, Option[Json]] =
    listedServe(adminUrl, host).map(_.flatMap(_.get(JsonCursor.field("body")).toOption))

  /** The `action.serve` object of the listed intercept rule for `host`. */
  private def listedServe(adminUrl: String, host: String): IO[Throwable, Option[Json]] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/intercept/rules")).GET().build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode != 200 then
        throw new RuntimeException(s"GET /intercept/rules -> ${resp.statusCode}: ${resp.body}")
      resp.body.fromJson[Json] match
        case Left(e) => throw new RuntimeException(s"GET /intercept/rules is not JSON ($e)")
        case Right(json) =>
          json.asArray.toList.flatten
            .find(_.get(JsonCursor.field("host")).toOption.contains(Json.Str(host)))
            .flatMap(
              _.get(JsonCursor.field("action").isObject.field("serve")).toOption
            )
    }

  /** A serve rule for `host``path` whose body is the one-field object `{key: value}` — as a JSON
    * object, or as that object already rendered to an escaped JSON string (the pre-rift#933 form).
    */
  private def objectBodyRule(
      host: String,
      path: String,
      key: String,
      value: String,
      asString: Boolean
  ) =
    val obj = Json.Obj(key -> Json.Str(value))
    val body = if asString then Json.Str(obj.toJson) else obj
    Json
      .Obj(
        "host" -> Json.Str(host),
        "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str(path)))),
        "action" -> Json.Obj("serve" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> body))
      )
      .toJson

  // ── steps ─────────────────────────────────────────────────────────────────────────────────

  Then("the exported intercept CA is the caller-provided test CA") {
    for
      seeded <- ZIO.serviceWithZIO[RiftHarness](_.interceptCaPem)
      expected <- ZIO
        .fromOption(seeded)
        .orElseFail(new RuntimeException("this mode seeded no caller CA (interceptCaPem is None)"))
      exported <- ZIO.serviceWithZIO[RiftHarness](h =>
        ZIO.attemptBlocking {
          val req =
            HttpRequest.newBuilder(URI.create(s"${h.adminUrl}/intercept/ca.pem")).GET().build()
          http.send(req, HttpResponse.BodyHandlers.ofString())
        }
      )
      _ <- Assertions.assertTrue(
        exported.statusCode == 200 && exported.body.trim == expected.trim,
        s"exported /intercept/ca.pem (${exported.statusCode}) should byte-equal the provided test CA — " +
          s"proving rift loaded --intercept-ca-cert rather than minting an ephemeral CA"
      )
    yield ()
  }

  Then("the intercept CA PEM is exported") {
    ZIO.serviceWithZIO[RiftHarness] { h =>
      ZIO
        .attemptBlocking {
          val req =
            HttpRequest.newBuilder(URI.create(s"${h.adminUrl}/intercept/ca.pem")).GET().build()
          http.send(req, HttpResponse.BodyHandlers.ofString())
        }
        .flatMap(r =>
          Assertions.assertTrue(
            r.statusCode == 200 && r.body.contains("BEGIN CERTIFICATE"),
            s"GET /intercept/ca.pem -> ${r.statusCode}, body head='${r.body.take(40)}'"
          )
        )
    }
  }

  Then("the intercept truststore is exported with password " / string) { (password: String) =>
    ZIO.serviceWithZIO[RiftHarness] { h =>
      ZIO
        .attemptBlocking {
          val req = HttpRequest
            .newBuilder(URI.create(s"${h.adminUrl}/intercept/truststore.p12?password=$password"))
            .GET()
            .build()
          http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        }
        .flatMap { r =>
          val echoed = r.headers.firstValue("x-truststore-password").orElse("")
          Assertions.assertTrue(
            r.statusCode == 200 && r.body.nonEmpty && echoed == password,
            s"truststore.p12 -> ${r.statusCode}, ${r.body.length} bytes, x-truststore-password='$echoed' (expected '$password')"
          )
        }
    }
  }

  Given(
    "an intercept rule serves " / int / " " / string / " for host " / string / " path " / string
  ) { (status: Int, body: String, host: String, path: String) =>
    val rule =
      s"""{"host":"${jsonEscape(host)}","predicates":[{"equals":{"path":"${jsonEscape(
          path
        )}"}}],"action":{"serve":{"statusCode":$status,"headers":{"content-type":"text/plain"},"body":"${jsonEscape(
          body
        )}"}}}"""
    ZIO.serviceWithZIO[RiftHarness](_.interceptAddRule(rule))
  }

  Given("an intercept rule forwards host " / string / " to the current imposter") {
    (host: String) =>
      for
        port <- currentPort
        rule = s"""{"host":"${jsonEscape(host)}","action":{"forward":{"port":$port}}}"""
        _ <- ZIO.serviceWithZIO[RiftHarness](_.interceptAddRule(rule))
      yield ()
  }

  Given(
    "an intercept rule serves " / int / " " / string / " for host " / string / " when the body equals " / string
  ) { (status: Int, body: String, host: String, expected: String) =>
    val rule =
      s"""{"host":"${jsonEscape(host)}","predicates":[{"equals":{"body":"${jsonEscape(
          expected
        )}"}}],"action":{"serve":{"statusCode":$status,"body":"${jsonEscape(body)}"}}}"""
    ZIO.serviceWithZIO[RiftHarness](_.interceptAddRule(rule))
  }

  When(
    "a raw HTTPS POST of " / int / " body bytes to " / string / " " / string / " through the intercept tunnel"
  ) { (n: Int, host: String, path: String) =>
    rawExchange(
      host,
      RawHttpConnection.request("POST", host, path, body = Array.fill(n)('a'.toByte))
    )
  }

  When(
    "a raw HTTPS POST of the chunked body " / string / " to " / string / " " / string / " through the intercept tunnel"
  ) { (body: String, host: String, path: String) =>
    // Several chunks, so the body only reads right if the engine reassembles them.
    rawExchange(host, RawHttpConnection.chunkedRequest("POST", host, path, body.grouped(5).toList))
  }

  When("a raw HTTPS GET to " / string / " " / string / " through the intercept tunnel") {
    (host: String, path: String) => rawExchange(host, RawHttpConnection.request("GET", host, path))
  }

  Then("the intercept tunnel answered " / int / " requests on one connection") { (n: Int) =>
    Stage.get[RawTunnel].option.flatMap {
      case Some(RawTunnel(host, conn)) =>
        assertRepro(
          conn.responsesRead == n,
          s"expected $n responses on the one tunnel to $host, got ${conn.responsesRead}"
        )
      case None => ZIO.fail(new IllegalStateException("no raw tunnel was opened"))
    }
  }

  // The body is back-to-back copies of a complete `GET <smuggled>` request. If the engine ever framed
  // the unread tail of the refused body as requests, the next response would be the smuggled one.
  When(
    "a raw HTTPS POST of " / int / " body bytes of pipelined " / string / " requests to " / string / " " / string / " through the intercept tunnel"
  ) { (n: Int, smuggled: String, host: String, path: String) =>
    val unit = RawHttpConnection.request("GET", host, smuggled)
    val body = Array.tabulate(n)(i => unit(i % unit.length))
    rawExchange(host, RawHttpConnection.request("POST", host, path, body = body))
  }

  // rift#993 allows either outcome after a 413: the connection is reused (and the tail discarded),
  // or it is closed. What it must never do is answer from the leftover bytes.
  Then(
    "the next raw HTTPS GET to " / string / " " / string / " on the same tunnel is answered " / string / " or the connection is closed"
  ) { (host: String, path: String, expected: String) =>
    for
      conn <- openTunnelTo(host)
      read <- ZIO.attemptBlocking(conn.exchange(RawHttpConnection.request("GET", host, path)))
      _ <- read match
        case RawRead.Closed => ZIO.unit
        case RawRead.Response(r) =>
          assertRepro(
            r.status == 200 && r.bodyText == expected,
            s"after the 413, GET $path on the same tunnel should be answered 200 '$expected' " +
              s"(or the connection closed), got ${r.status} '${r.bodyText.take(80)}'"
          )
    yield ()
  }

  When("a plain GET for " / string / " is sent to the intercept listener") { (url: String) =>
    for
      listener <- interceptListener
      conn <- ZIO.attemptBlocking(RawHttpConnection.plain(listener._1, listener._2))
      _ <- openTunnels.update(conn :: _)
      _ <- Stage.put(ListenerConnection(conn))
      host = java.net.URI.create(url).getHost
      read <- ZIO.attemptBlocking(conn.exchange(RawHttpConnection.request("GET", host, url)))
      _ <- read match
        case RawRead.Response(r) => Stage.put(r.toLastResponse)
        case RawRead.Closed =>
          ZIO.fail(new RuntimeException("the intercept listener closed without answering"))
    yield ()
  }

  Then("the intercept listener then closes the connection") {
    for
      listener <- Stage
        .get[ListenerConnection]
        .mapError(e => new IllegalStateException(s"no listener connection was opened ($e)"))
      read <- ZIO.attemptBlocking(listener.conn.readNext())
      _ <- assertRepro(
        read == RawRead.Closed,
        s"expected the listener to close after the refusal, but it sent another response: $read"
      )
    yield ()
  }

  Given(
    "an intercept rule for host " / string / " path " / string / " serves the object body with " / string / " set to " / string
  ) { (host: String, path: String, key: String, value: String) =>
    ZIO.serviceWithZIO[RiftHarness](
      _.interceptAddRule(objectBodyRule(host, path, key, value, asString = false))
    )
  }

  Given(
    "an intercept rule for host " / string / " path " / string / " serves the escaped-string body with " / string / " set to " / string
  ) { (host: String, path: String, key: String, value: String) =>
    ZIO.serviceWithZIO[RiftHarness](
      _.interceptAddRule(objectBodyRule(host, path, key, value, asString = true))
    )
  }

  Then("the response body is the compact JSON object with " / string / " set to " / string) {
    (key: String, value: String) =>
      val expected = Json.Obj(key -> Json.Str(value)).toJson // zio-json renders compact
      lastRawResponse.flatMap(r =>
        assertRepro(r.bodyText == expected, s"expected body '$expected', got '${r.bodyText}'")
      )
  }

  Then("the response content-length matches its body") {
    lastRawResponse.flatMap { r =>
      val declared = r.headers.collect { case ("content-length", v) => v }
      assertRepro(
        declared == List(r.body.length.toString),
        s"content-length $declared does not frame the ${r.body.length}-byte body"
      )
    }
  }

  Then(
    "the response body is byte-identical to a raw HTTPS GET to " / string / " " / string / " through the intercept tunnel"
  ) { (host: String, path: String) =>
    for
      first <- lastRawResponse
      _ <- rawExchange(host, RawHttpConnection.request("GET", host, path))
      second <- lastRawResponse
      same = java.util.Arrays.equals(first.body, second.body)
      _ <- assertRepro(
        same && second.status == first.status,
        s"expected identical bodies, got ${first.status} '${first.bodyText}' vs " +
          s"${second.status} '${second.bodyText}'"
      )
    yield ()
  }

  Then(
    "the intercept rule for host " / string / " lists its serve body as a JSON object with " / string / " set to " / string
  ) { (host: String, key: String, value: String) =>
    ZIO.serviceWithZIO[RiftHarness](h =>
      listedServeBody(h.adminUrl, host).flatMap(body =>
        assertRepro(
          body.contains(Json.Obj(key -> Json.Str(value))),
          s"expected GET /intercept/rules to list the $host serve body as the object " +
            s"{$key: $value}, got $body"
        )
      )
    )
  }

  Then("the current imposter has recorded " / int / " requests") { (n: Int) =>
    currentPort
      .flatMap(numberOfRequests)
      .flatMap(c =>
        assertRepro(c == n, s"expected the imposter to have recorded $n request(s), got $c")
      )
  }

  When("an HTTPS GET to " / string / " " / string / " through the intercept proxy") {
    (host: String, path: String) => httpsViaIntercept(host, path).flatMap((r, _) => Stage.put(r))
  }

  When("an HTTP/2 HTTPS GET to " / string / " " / string / " through the intercept proxy") {
    (host: String, path: String) =>
      httpsViaIntercept(host, path, HttpClient.Version.HTTP_2).flatMap((r, v) =>
        Stage.put(r) *> Stage.put(NegotiatedVersion(v))
      )
  }

  Then("the response was served over HTTP/2") {
    Stage
      .get[NegotiatedVersion]
      .mapError(e => new IllegalStateException(s"no HTTP/2 exchange was made ($e)"))
      .flatMap(n =>
        assertRepro(
          n.version == HttpClient.Version.HTTP_2,
          s"expected ALPN to negotiate HTTP/2 through the tunnel, got ${n.version}"
        )
      )
  }

  Given(
    "an intercept rule serves " / int / " " / string / " for host " / string / " when header " / string / " equals " / string
  ) { (status: Int, body: String, host: String, header: String, value: String) =>
    val rule =
      s"""{"host":"${jsonEscape(host)}","predicates":[{"equals":{"headers":{"${jsonEscape(
          header
        )}":"${jsonEscape(value)}"}}}],"action":{"serve":{"statusCode":$status,"body":"${jsonEscape(
          body
        )}"}}}"""
    ZIO.serviceWithZIO[RiftHarness](_.interceptAddRule(rule))
  }

  // Two separate header lines with one name — a JDK HttpClient may fold them, the raw client can't.
  When(
    "a raw HTTPS GET to " / string / " " / string / " with header " / string / " sent as " / string / " then " / string / " through the intercept tunnel"
  ) { (host: String, path: String, name: String, first: String, second: String) =>
    rawExchange(
      host,
      RawHttpConnection.request("GET", host, path, headers = List(name -> first, name -> second))
    )
  }

  Then(
    "the current imposter recorded header " / string / " as " / string / " then " / string
  ) { (name: String, first: String, second: String) =>
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      body <- ZIO.attemptBlocking {
        val req = HttpRequest.newBuilder(URI.create(s"$admin/imposters/$port")).GET().build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).body
      }
      json <- ZIO.fromEither(body.fromJson[Json]).mapError(e => new RuntimeException(s"$e: $body"))
      // The one recorded request's values for `name` (header names are case-insensitive); a
      // single value records as a string, a repeated header as an array.
      values = json match
        case Json.Obj(fields) =>
          fields
            .collectFirst { case ("requests", Json.Arr(reqs)) => reqs.toList }
            .toList
            .flatten
            .collect { case Json.Obj(r) => r }
            .flatMap(_.collectFirst { case ("headers", Json.Obj(hs)) => hs.toList }.toList.flatten)
            .collect { case (k, v) if k.equalsIgnoreCase(name) => v }
            .flatMap {
              case Json.Str(v) => List(v)
              case Json.Arr(vs) => vs.toList.collect { case Json.Str(v) => v }
              case _ => Nil
            }
        case _ => Nil
      _ <- assertRepro(
        values == List(first, second),
        s"expected the forwarded request to carry $name as [$first, $second] in order, got $values: " +
          body.take(400)
      )
    yield ()
  }

  Then("the intercept rules count is " / int) { (n: Int) =>
    ZIO.serviceWithZIO[RiftHarness](h =>
      rulesCount(h.adminUrl).flatMap(c =>
        Assertions.assertTrue(c == n, s"expected $n intercept rule(s), got $c")
      )
    )
  }

  When("the intercept rules are cleared") {
    ZIO.serviceWithZIO[RiftHarness](h => deleteRules(h.adminUrl))
  }

  // ── @intercept-runtime: start/stop the TLS-MITM listener at runtime (rift 0.13.0, rift#493) ──
  //    Driven against a dedicated engine the external harness raw-spawns WITHOUT `--intercept-port`,
  //    so its `/intercept` admin routes begin in the "not running" state.

  Given("a rift server started without an intercept listener") {
    ZIO.serviceWithZIO[RiftHarness](_.startRuntimeInterceptEngine()).unit
  }

  When("the intercept listener is queried over the admin API") {
    runtimeInterceptSend("GET", "/intercept").flatMap(Stage.put(_))
  }

  When("the intercept listener is started over the admin API") {
    runtimeInterceptSend("POST", "/intercept").flatMap(Stage.put(_))
  }

  When("the intercept listener is stopped over the admin API") {
    runtimeInterceptSend("DELETE", "/intercept").flatMap(Stage.put(_))
  }

  // ── #190: POST /intercept start options + rule-store limits (the @intercept-runtime engine) ──

  Given("a rift server started without an intercept listener or --allow-injection") {
    ZIO.serviceWithZIO[RiftHarness](_.startRuntimeInterceptEngine(allowInjection = false)).unit
  }

  When(
    "the intercept listener is started over the admin API seeding a serve rule " / string / " for host " / string
  ) { (body: String, host: String) =>
    startRuntimeListener(
      Json.Obj("rules" -> Json.Arr(serveRule(host, body))).toJson
    )
  }

  When("the intercept listener is started over the admin API with returnCaKey") {
    startRuntimeListener("""{"returnCaKey":true}""")
  }

  When("the intercept listener is started over the admin API with the test CA as inline PEM") {
    startRuntimeListener(
      Json.Obj("caCertPem" -> Json.Str(TestCaCert), "caKeyPem" -> Json.Str(TestCaKey)).toJson
    )
  }

  When("the intercept listener is started over the admin API with the " / string / " options") {
    (options: String) =>
      val body = options match
        case "returnCaKey with a supplied CA" =>
          Json
            .Obj(
              "returnCaKey" -> Json.Bool(true),
              "caCertPem" -> Json.Str(TestCaCert),
              "caKeyPem" -> Json.Str(TestCaKey)
            )
            .toJson
        case "half PEM pair" => Json.Obj("caCertPem" -> Json.Str(TestCaCert)).toJson
        case "DNS host" => """{"host":"localhost"}"""
        case "unknown field" => """{"bogus":1}"""
        case other => throw new IllegalArgumentException(s"unknown start-options case '$other'")
      startRuntimeListener(body)
  }

  Then("the runtime intercept rules count is " / int) { (n: Int) =>
    ZIO
      .serviceWithZIO[RiftHarness](_.runtimeInterceptAdminUrl)
      .flatMap(rulesCount)
      .flatMap(c => assertRepro(c == n, s"expected $n runtime intercept rule(s), got $c"))
  }

  Then("the runtime intercept CA PEM is the test CA") {
    for
      admin <- ZIO.serviceWithZIO[RiftHarness](_.runtimeInterceptAdminUrl)
      r <- adminSend(admin, "GET", "/intercept/ca.pem")
      _ <- assertRepro(
        r.status == 200 && r.body.trim == TestCaCert.trim,
        s"GET /intercept/ca.pem (${r.status}) should equal the inline test CA, got '${r.body.take(80)}'"
      )
    yield ()
  }

  When("a raw HTTPS GET to " / string / " " / string / " through the runtime intercept listener") {
    (host: String, path: String) =>
      for
        admin <- ZIO.serviceWithZIO[RiftHarness](_.runtimeInterceptAdminUrl)
        ca <- adminSend(admin, "GET", "/intercept/ca.pem")
        _ <- runtimeListenerExchange(host, path, ca.body)
      yield ()
  }

  When(
    "a raw HTTPS GET to " / string / " " / string / " through the runtime intercept listener trusting the returned CA"
  ) { (host: String, path: String) =>
    Stage
      .get[RuntimeListener]
      .mapError(e => new IllegalStateException(s"no runtime listener was started ($e)"))
      .flatMap(l =>
        ZIO
          .fromOption(l.returnedCa)
          .orElseFail(new IllegalStateException("the start response carried no caCertPem"))
      )
      .flatMap(runtimeListenerExchange(host, path, _))
  }

  When(
    "a clean serve rule and a rule with an inject predicate nested under not are posted as one runtime intercept batch"
  ) {
    val poisoned = Json.Obj(
      "host" -> Json.Str("poisoned.test"),
      "predicates" -> Json.Arr(
        Json.Obj("not" -> Json.Obj("inject" -> Json.Str("function (r) { return true; }")))
      ),
      "action" -> Json.Obj("serve" -> Json.Obj("statusCode" -> Json.Num(200)))
    )
    runtimeRulesPost(Json.Arr(serveRule("clean.test", "clean"), poisoned).toJson)
  }

  When("a batch of " / int / " serve rules is posted to the runtime intercept rules") { (n: Int) =>
    runtimeRulesPost(Json.Arr(Chunk.tabulate(n)(i => serveRule(s"cap-$i.test", "x"))).toJson)
  }

  // ── @intercept-auth: the --intercept-auth listener (rift#878) ──

  Given("a rift server whose intercept listener requires proxy auth") {
    ZIO.serviceWithZIO[RiftHarness](_.startInterceptAuthEngine).flatMap { (admin, port) =>
      Stage.put(AuthEngine(admin, port))
    }
  }

  Given(
    "an intercept rule on the authenticated engine serves " / string / " for host " / string
  ) { (body: String, host: String) =>
    for
      engine <- authEngine
      r <- adminSend(
        engine.adminUrl,
        "POST",
        "/intercept/rules",
        Some(serveRule(host, body).toJson)
      )
      _ <- assertRepro(r.status == 201, s"POST /intercept/rules -> ${r.status}: ${r.body}")
    yield ()
  }

  When(
    "a CONNECT to " / string / " is sent to the authenticated intercept listener without credentials"
  )((host: String) => connectToAuthListener(host, Nil))

  When(
    "a CONNECT to " / string / " is sent to the authenticated intercept listener with credentials " / string
  ) { (host: String, credentials: String) =>
    connectToAuthListener(host, List(proxyAuthorization(credentials)))
  }

  Then("the response carries a Basic proxy-authenticate challenge") {
    lastRawResponse.flatMap { r =>
      val challenge = r.headers.collect { case ("proxy-authenticate", v) => v }
      assertRepro(
        challenge.exists(_.startsWith("Basic")),
        s"expected a `Proxy-Authenticate: Basic …` challenge, got $challenge"
      )
    }
  }

  Then("the authenticated intercept listener then closes the connection") {
    for
      listener <- Stage
        .get[ListenerConnection]
        .mapError(e => new IllegalStateException(s"no listener connection was opened ($e)"))
      read <- ZIO.attemptBlocking(listener.conn.readNext())
      _ <- assertRepro(
        read == RawRead.Closed,
        s"expected the listener to close after the 407, but it sent another response: $read"
      )
    yield ()
  }

  When(
    "a raw HTTPS GET to " / string / " " / string / " through the authenticated intercept listener with credentials " / string
  ) { (host: String, path: String, credentials: String) =>
    for
      engine <- authEngine
      ssl <- ZIO.attemptBlocking(RawHttpConnection.sslTrustingPem(TestCaCert))
      conn <- ZIO.attemptBlocking(
        RawHttpConnection.tunnel(
          "localhost",
          engine.interceptPort,
          host,
          ssl,
          List(proxyAuthorization(credentials))
        )
      )
      _ <- openTunnels.update(conn :: _)
      _ <- rawRead(conn, RawHttpConnection.request("GET", host, path), host)
    yield ()
  }

  // ── #191: remaining tunnel behaviour on the shared listener ──

  private def widenedRule(
      host: String,
      status: String,
      multi: String,
      a: String,
      b: String,
      scalar: String,
      n: Int
  ) =
    Json
      .Obj(
        "host" -> Json.Str(host),
        "action" -> Json.Obj(
          "serve" -> Json.Obj(
            "statusCode" -> Json.Str(status),
            "headers" -> Json.Obj(
              multi -> Json.Arr(Json.Str(a), Json.Str(b)),
              scalar -> Json.Num(n)
            ),
            "body" -> Json.Str("widened")
          )
        )
      )
      .toJson

  Given(
    "an intercept rule for host " / string / " serves status " / string / " with " / string / " as " / string / " and " / string / " and " / string / " as the number " / int
  ) { (host: String, status: String, multi: String, a: String, b: String, scalar: String, n: Int) =>
    ZIO.serviceWithZIO[RiftHarness](
      _.interceptAddRule(widenedRule(host, status, multi, a, b, scalar, n))
    )
  }

  Then("the raw response carries " / string / " lines " / string / " then " / string) {
    (name: String, first: String, second: String) =>
      lastRawResponse.flatMap { r =>
        val lines = r.headers.collect { case (`name`, v) => v }
        assertRepro(
          lines == List(first, second),
          s"expected two $name lines [$first, $second] in order, got $lines"
        )
      }
  }

  Then(
    "the intercept rule for host " / string / " lists statusCode " / int / " as a number and header " / string / " as the string " / string
  ) { (host: String, status: Int, header: String, value: String) =>
    ZIO.serviceWithZIO[RiftHarness](h =>
      listedServe(h.adminUrl, host).flatMap { serve =>
        val listedStatus = serve.flatMap(_.get(JsonCursor.field("statusCode")).toOption)
        val listedHeader = serve.flatMap(
          _.get(JsonCursor.field("headers").isObject.field(header)).toOption
        )
        assertRepro(
          listedStatus.contains(Json.Num(status)) && listedHeader.contains(Json.Str(value)),
          s"expected $host listed with statusCode $status (number) and $header \"$value\" " +
            s"(string), got statusCode=$listedStatus $header=$listedHeader"
        )
      }
    )
  }

  Given("an intercept rule for host " / string / " serves 200 with no body") { (host: String) =>
    ZIO.serviceWithZIO[RiftHarness](
      _.interceptAddRule(
        Json
          .Obj(
            "host" -> Json.Str(host),
            "action" -> Json.Obj("serve" -> Json.Obj("statusCode" -> Json.Num(200)))
          )
          .toJson
      )
    )
  }

  Then("the raw response has content-length 0 and an empty body") {
    lastRawResponse.flatMap { r =>
      val declared = r.headers.collect { case ("content-length", v) => v }
      assertRepro(
        declared == List("0") && r.body.isEmpty,
        s"expected content-length [0] and an empty body, got $declared and ${r.body.length} bytes"
      )
    }
  }

  When(
    "a raw HTTPS GET to " / string / " " / string / " with SNI and Host " / string / " through the intercept tunnel"
  ) { (connectHost: String, path: String, name: String) =>
    for
      listener <- interceptListener
      bytes <- ZIO.serviceWithZIO[RiftHarness](_.interceptTruststore(TrustPassword))
      ssl <- sslFromTruststore(bytes)
      conn <- ZIO.attemptBlocking(
        RawHttpConnection.tunnel(listener._1, listener._2, connectHost, ssl, sni = Some(name))
      )
      _ <- openTunnels.update(conn :: _)
      _ <- rawRead(conn, RawHttpConnection.request("GET", name, path), connectHost)
    yield ()
  }

  When(
    "a raw HTTPS POST with the chunk-size line " / string / " to " / string / " " / string / " through the intercept tunnel"
  ) { (sizeLine: String, host: String, path: String) =>
    val request =
      s"POST $path HTTP/1.1\r\nHost: $host\r\nTransfer-Encoding: chunked\r\n\r\n" +
        s"$sizeLine\r\nhello\r\n0\r\n\r\n"
    rawExchange(host, request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
  }

  Given("an intercept rule forwards host " / string / " to a closed port") { (host: String) =>
    // A port nothing listens on: TestPorts hands out ports it found free.
    TestPorts.next.flatMap(port =>
      ZIO.serviceWithZIO[RiftHarness](
        _.interceptAddRule(s"""{"host":"${jsonEscape(
            host
          )}","action":{"forward":{"port":$port}}}""")
      )
    )
  }

  Then(
    "the intercept JKS truststore with password " / string / " echoes the password and holds the intercept CA"
  ) { (password: String) =>
    ZIO.serviceWithZIO[RiftHarness] { h =>
      for
        jks <- ZIO.attemptBlocking {
          val req = HttpRequest
            .newBuilder(URI.create(s"${h.adminUrl}/intercept/truststore.jks?password=$password"))
            .GET()
            .build()
          http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        }
        caPem <- adminSend(h.adminUrl, "GET", "/intercept/ca.pem")
        holdsCa <- ZIO.attemptBlocking {
          val ca = java.security.cert.CertificateFactory
            .getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(caPem.body.getBytes("UTF-8")))
          val ks = java.security.KeyStore.getInstance("JKS")
          ks.load(new java.io.ByteArrayInputStream(jks.body), password.toCharArray)
          ks.getCertificateAlias(ca) != null
        }
        echoed = jks.headers.firstValue("x-truststore-password").orElse("")
        // The JDK's "JKS" loader also accepts PKCS#12, so check the JKS magic number too.
        magic = jks.body.take(4).map(b => f"${b & 0xff}%02X").mkString
        _ <- assertRepro(
          jks.statusCode == 200 && echoed == password && holdsCa && magic == "FEEDFEED",
          s"truststore.jks -> ${jks.statusCode}, x-truststore-password='$echoed', holds CA=$holdsCa, " +
            s"magic=$magic (JKS is FEEDFEED)"
        )
      yield ()
    }
  }

  Then("the intercept CA PEM is served as " / string) { (contentType: String) =>
    ZIO.serviceWithZIO[RiftHarness](h =>
      adminSend(h.adminUrl, "GET", "/intercept/ca.pem").flatMap { r =>
        val got = r.headers.getOrElse("content-type", Nil)
        assertRepro(
          r.status == 200 && got == List(contentType),
          s"GET /intercept/ca.pem -> ${r.status}, content-type $got"
        )
      }
    )
  }

  // ── @intercept-ws: WebSocket passthrough (rift#997) ──

  /** The scenario's TLS echo origin, and the engine whose intercept listener relays to it. */
  private final case class WsOrigin(origin: WsEchoOrigin)
  private final case class WsEngine(adminUrl: String, interceptPort: Int)

  Given("a TLS WebSocket echo origin on localhost") {
    for
      origin <- ZIO.attemptBlocking(
        WsEchoOrigin.start(
          fixture("/rift-fixtures/tls/server-cert.pem"),
          fixture("/rift-fixtures/tls/server-key.pem")
        )
      )
      _ <- wsOrigins.update(origin :: _)
      _ <- Stage.put(WsOrigin(origin))
    yield ()
  }

  Given("an intercept engine that trusts the test CA upstream") {
    for
      interceptPort <- TestPorts.next
      admin <- ZIO.serviceWithZIO[RiftHarness](_.startUpstreamTrustEngine(Some(interceptPort)))
      _ <- Stage.put(WsEngine(admin, interceptPort))
    yield ()
  }

  private def wsEngine: IO[Throwable, WsEngine] =
    Stage
      .get[WsEngine]
      .mapError(e => new IllegalStateException(s"no intercept engine for the origin ($e)"))

  private def wsOrigin: IO[Throwable, WsEchoOrigin] =
    Stage
      .get[WsOrigin]
      .map(_.origin)
      .mapError(e => new IllegalStateException(s"no WebSocket echo origin ($e)"))

  Given("an intercept rule on that engine serves " / string / " for host " / string) {
    (body: String, host: String) =>
      for
        engine <- wsEngine
        r <- adminSend(
          engine.adminUrl,
          "POST",
          "/intercept/rules",
          Some(serveRule(host, body).toJson)
        )
        _ <- assertRepro(r.status == 201, s"POST /intercept/rules -> ${r.status}: ${r.body}")
      yield ()
  }

  When("a WebSocket upgrade to the echo origin is sent through that engine's intercept listener") {
    for
      engine <- wsEngine
      origin <- wsOrigin
      ssl <- ZIO.attemptBlocking(RawHttpConnection.sslTrustingPem(TestCaCert))
      conn <- ZIO.attemptBlocking(
        RawHttpConnection.tunnel(
          "localhost",
          engine.interceptPort,
          "localhost",
          ssl,
          targetPort = origin.port
        )
      )
      _ <- openTunnels.update(conn :: _)
      _ <- Stage.put(RawTunnel("localhost", conn))
      request = RawHttpConnection.request(
        "GET",
        s"localhost:${origin.port}",
        "/echo",
        headers = List(
          "Upgrade" -> "websocket",
          "Connection" -> "Upgrade",
          "Sec-WebSocket-Key" -> "dGhlIHNhbXBsZSBub25jZQ==",
          "Sec-WebSocket-Version" -> "13"
        )
      )
      read <- ZIO.attemptBlocking(conn.upgrade(request))
      _ <- read match
        case RawRead.Response(r) => Stage.put(r.toLastResponse) *> Stage.put(r)
        case RawRead.Closed =>
          ZIO.fail(
            new RuntimeException(
              "the intercept tunnel closed without answering the upgrade " +
                s"(origin error: ${origin.lastError.getOrElse("none")})"
            )
          )
    yield ()
  }

  // A blind TCP tunnel would reach the origin too, and the client trusts the CA that signed the
  // origin's leaf. Only a leaf that is not the origin's shows rift terminated TLS and relayed.
  Then("the tunnel's TLS certificate was minted by the intercept listener, not the origin's") {
    for
      conn <- openTunnelTo("localhost")
      origin <- ZIO.attemptBlocking(
        java.security.cert.CertificateFactory
          .getInstance("X.509")
          .generateCertificate(
            new java.io.ByteArrayInputStream(
              fixture("/rift-fixtures/tls/server-cert.pem").getBytes("UTF-8")
            )
          )
      )
      peer = conn.peerCertificate
      _ <- assertRepro(
        peer.isDefined && !peer.contains(origin),
        s"expected an intercept-minted leaf on the tunnel, got ${peer.map(_.toString.take(200))}"
      )
    yield ()
  }

  Then("a text frame " / string / " is echoed back as " / string) {
    (sent: String, expected: String) =>
      for
        conn <- openTunnelTo("localhost")
        origin <- wsOrigin
        frame <- ZIO
          .attemptBlocking {
            conn.upgradedOut.write(WsEchoOrigin.clientTextFrame(sent))
            conn.upgradedOut.flush()
            WsEchoOrigin.readFrame(conn.upgradedIn)
          }
          .mapError(e =>
            new RuntimeException(
              s"no echo frame (${e.getMessage}; origin error: ${origin.lastError.getOrElse("none")})",
              e
            )
          )
        (opcode, payload) = frame
        text = new String(payload, java.nio.charset.StandardCharsets.UTF_8)
        _ <- assertRepro(
          opcode == 1 && text == expected,
          s"expected a text frame '$expected' back, got opcode $opcode '$text'"
        )
      yield ()
  }

  // The fall-through body is one text line: the message plus a trailing newline.
  Then("the response body is the line " / string) { (line: String) =>
    lastRawResponse.flatMap(r =>
      assertRepro(r.bodyText == line + "\n", s"expected body '$line\\n', got '${r.bodyText}'")
    )
  }
