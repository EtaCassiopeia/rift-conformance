package samples.rift

import samples.rift.support.{
  LastResponse,
  RawHttpConnection,
  RawRead,
  RiftConformanceSupport,
  RiftHarness,
  RiftState
}
import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.Suite
import zio.bdd.core.step.{Stage, ZIOSteps}

import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{SSLContext, TrustManagerFactory}
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
  featureDirs = Array("src/test/resources/features/rift/12-intercept.feature"),
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

  // Rules are host-scoped and accumulate on the shared listener. For external/container this HTTP
  // DELETE actually clears them after each scenario (keeping the list/clear count assertions honest).
  // Embedded has NO clear capability — the FFI binds start/add-rules/export-truststore but not
  // list/clear, and its serveAdmin plane serves no `/intercept` HTTP (so the DELETE 404s, a no-op);
  // embedded rules therefore persist for the engine's lifetime. That's safe only because every
  // scenario here uses a UNIQUE host, so accumulated rules never shadow each other — a required
  // convention for any embedded-capable scenario added later (there is no per-scenario reset there).
  afterScenario {
    // Close any raw tunnel an @intercept-raw scenario left open (a no-op otherwise).
    openTunnels.getAndSet(Nil).flatMap(cs => ZIO.foreachDiscard(cs)(c => ZIO.succeed(c.close()))) *>
      ZIO
        .serviceWithZIO[RiftHarness](h =>
          // Clear accumulated rules on the shared listener AND reap the @intercept-runtime sidecar
          // engine if a scenario started one (a no-op otherwise). The rule-clear is logged-then-ignored
          // (a non-404 failure is a real admin fault worth surfacing, per deleteRules) but must never
          // block the sidecar reap that follows.
          deleteRules(h.adminUrl)
            .tapError(e => ZIO.logWarning(s"intercept rules cleanup failed: ${e.getMessage}"))
            .ignore *> h.stopRuntimeInterceptEngine
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

  /** Send `method path` to the @intercept-runtime sidecar engine's admin API (the one started with
    * NO `--intercept-port`) and capture it as the scenario's last response, so the shared `the
    * response status is N` / `the response body contains X` steps assert on it. POST carries an
    * empty JSON object body — rift's `POST /intercept` takes optional options and defaults them.
    */
  private def runtimeInterceptSend(
      method: String,
      path: String
  ): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      admin <- ZIO.serviceWithZIO[RiftHarness](_.runtimeInterceptAdminUrl)
      resp <- ZIO.attemptBlocking {
        val builder = HttpRequest.newBuilder(URI.create(s"$admin$path"))
        val req = method match
          case "POST" =>
            builder
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build()
          case "DELETE" => builder.DELETE().build()
          case _ => builder.GET().build()
        val r = http.send(req, HttpResponse.BodyHandlers.ofString())
        val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
        LastResponse(r.statusCode, r.body, hs)
      }
    yield resp

  /** Build an SSLContext that trusts the intercept CA from the exported PKCS#12 truststore bytes —
    * exactly what a real SUT would do to trust the MITM leaf certs. The bytes come from the harness
    * seam (`interceptTruststore`), which is HTTP for external/container and FFI for embedded.
    */
  private def sslFromTruststore(bytes: Array[Byte]): IO[Throwable, SSLContext] =
    ZIO.attemptBlocking {
      val ks = KeyStore.getInstance("PKCS12")
      ks.load(new ByteArrayInputStream(bytes), TrustPassword.toCharArray)
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, tmf.getTrustManagers, new SecureRandom())
      ctx
    }

  /** Issue an HTTPS GET to `host``path` THROUGH the intercept listener (as an HTTP proxy: the JDK
    * client sends `CONNECT host:443`, which rift intercepts and TLS-MITMs). No DNS for `host` is
    * needed — the proxy tunnels by name.
    */
  private def httpsViaIntercept(
      host: String,
      path: String
  ): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      ep <- ZIO.serviceWithZIO[RiftHarness](_.interceptEndpoint)
      hostPort <- ZIO
        .fromOption(ep)
        .orElseFail(
          new RuntimeException("no intercept listener — RIFT_MODE does not support intercept")
        )
      (iHost, iPort) = hostPort
      bytes <- ZIO.serviceWithZIO[RiftHarness](_.interceptTruststore(TrustPassword))
      ssl <- sslFromTruststore(bytes)
      resp <- ZIO.attemptBlocking {
        val client = HttpClient
          .newBuilder()
          .sslContext(ssl)
          .proxy(ProxySelector.of(new InetSocketAddress(iHost, iPort)))
          .build()
        val req = HttpRequest.newBuilder(URI.create(s"https://$host$path")).GET().build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
        LastResponse(r.statusCode, r.body, hs)
      }
    yield resp

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
    for
      conn <- tunnelTo(host)
      read <- ZIO.attemptBlocking(conn.exchange(request))
      _ <- read match
        case RawRead.Response(r) => Stage.put(r.toLastResponse)
        case RawRead.Closed =>
          ZIO.fail(new RuntimeException(s"the intercept tunnel to $host closed without answering"))
    yield ()

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

  Then("the current imposter has recorded " / int / " requests") { (n: Int) =>
    currentPort
      .flatMap(numberOfRequests)
      .flatMap(c =>
        assertRepro(c == n, s"expected the imposter to have recorded $n request(s), got $c")
      )
  }

  When("an HTTPS GET to " / string / " " / string / " through the intercept proxy") {
    (host: String, path: String) => httpsViaIntercept(host, path).flatMap(Stage.put(_))
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
    ZIO.serviceWithZIO[RiftHarness](_.startRuntimeInterceptEngine).unit
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
