package samples.rift

import samples.rift.support.{
  LastResponse,
  RawHttpConnection,
  RiftConformanceSupport,
  RiftHarness,
  RiftState
}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.bdd.core.Suite
import zio.bdd.core.step.{Stage, ZIOSteps}

import java.io.{ByteArrayOutputStream, DataInputStream}
import java.net.{InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Feature 20 — TLS termination options and HTTP/2 negotiation (#197; rift docs/features/tls.md,
  * rift#977/#1029): inline cert/key (PKCS#8, PKCS#1, a leaf-first chain), the 400s for a bad key
  * pair and the mutual-TLS combinations rift refuses, the clientAuth EKU, ALPN (h2 unless a TCP
  * fault or `_rift.script` forces http/1.1), h2c prior knowledge, and — on dedicated engines,
  * `@tls-flags` — `--default-tls-*`, `--no-self-signed-tls`, `RIFT_DISABLE_HTTP2` and
  * `--upstream-tls-skip-verify`.
  *
  * Every HTTPS client here trusts only the committed test CA, so a completed handshake proves the
  * imposter served the fixture chain. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/20-tls-and-http2.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object TlsTermination20Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer

  private val admin = HttpClient.newHttpClient()

  /** Imposters this suite posted, as (admin URL, port), deleted after every scenario. */
  private val created: Ref[List[(String, Int)]] =
    Unsafe.unsafe(u => Ref.unsafe.make(List.empty[(String, Int)])(using u))

  /** Temp files handed to a flag engine, deleted after every scenario. */
  private val tempFiles: Ref[List[Path]] =
    Unsafe.unsafe(u => Ref.unsafe.make(List.empty[Path])(using u))

  // The @tls-flags engine is reaped by RiftConformanceSupport's own afterScenario; an imposter on it
  // goes with it, so a failed DELETE there is expected and only logged.
  afterScenario {
    created.getAndSet(Nil).flatMap { imposters =>
      ZIO.foreachDiscard(imposters)((url, port) =>
        send(url, "DELETE", s"/imposters/$port", None)
          .tapError(e => ZIO.logWarning(s"DELETE imposter $port failed: ${e.getMessage}"))
          .ignore
      )
    } *> tempFiles
      .getAndSet(Nil)
      .flatMap(fs =>
        ZIO.foreachDiscard(fs)(f => ZIO.attemptBlocking(Files.deleteIfExists(f)).ignore)
      )
  }

  // ── fixtures and plumbing ─────────────────────────────────────────────────────────────────

  private def resource(path: String): IO[Throwable, String] =
    ZIO.attemptBlocking {
      val is = getClass.getResourceAsStream(path)
      if is == null then throw new IllegalStateException(s"missing test resource $path")
      try new String(is.readAllBytes(), UTF_8)
      finally is.close()
    }

  private def tls(name: String): IO[Throwable, String] = resource(s"/rift-fixtures/tls/$name")
  private val testCa: IO[Throwable, String] = resource("/rift-fixtures/intercept/ca-cert.pem")

  private def tempFile(content: String): IO[Throwable, Path] =
    ZIO
      .attemptBlocking(Files.writeString(Files.createTempFile("rift-tls-", ".pem"), content))
      .tap(p => tempFiles.update(p :: _))

  private def send(
      url: String,
      method: String,
      path: String,
      body: Option[String]
  ): IO[Throwable, LastResponse] =
    ZIO.attemptBlocking {
      val publisher =
        body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
      val req = HttpRequest
        .newBuilder(URI.create(s"$url$path"))
        .header("Content-Type", "application/json")
        .timeout(java.time.Duration.ofSeconds(10))
        .method(method, publisher)
        .build()
      val r = admin.send(req, HttpResponse.BodyHandlers.ofString())
      val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
      LastResponse(r.statusCode, r.body, hs)
    }

  /** The dedicated @tls-flags engine's admin URL, once a step started one. */
  private final case class FlagEngine(adminUrl: String)

  /** The imposter the HTTPS/h2c steps talk to. */
  private final case class Target(port: Int)

  private def sharedAdmin: URIO[RiftHarness, String] = ZIO.serviceWith[RiftHarness](_.adminUrl)

  private def flagAdmin: IO[Throwable, String] =
    Stage
      .get[FlagEngine]
      .map(_.adminUrl)
      .mapError(e => new IllegalStateException(s"no TLS-flag engine was started ($e)"))

  private def target: IO[Throwable, Int] =
    Stage.get[Target].map(_.port).mapError(e => new IllegalStateException(s"no imposter ($e)"))

  private val PlainStub =
    Json.Obj(
      "responses" -> Json.Arr(
        Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> Json.Str("tls-ok")))
      )
    )

  /** A stub on `path` only, so the plain stub still answers `/`. */
  private def onPath(path: String, response: Json): Json =
    Json.Obj(
      "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str(path)))),
      "responses" -> Json.Arr(response)
    )

  private def stubsFor(kind: String): IO[Throwable, List[Json]] =
    kind match
      case "a plain stub" => ZIO.succeed(List(PlainStub))
      case "a _rift.fault.tcp stub" =>
        val fault = Json.Obj(
          "is" -> Json.Obj("statusCode" -> Json.Num(200)),
          "_rift" -> Json.Obj("fault" -> Json.Obj("tcp" -> Json.Str("reset")))
        )
        ZIO.succeed(List(onPath("/fault", fault), PlainStub))
      case "a _rift.script stub" =>
        val script = Json.Obj(
          "_rift" -> Json.Obj(
            "script" -> Json.Obj(
              "engine" -> Json.Str("rhai"),
              "code" -> Json.Str("fn respond(ctx) { http(200, #{}) }")
            )
          )
        )
        ZIO.succeed(List(onPath("/script", script), PlainStub))
      case other => ZIO.fail(new IllegalArgumentException(s"unknown stub kind '$other'"))

  private def imposter(protocol: String, port: Int, fields: (String, Json)*)(
      stubs: List[Json]
  ): String =
    Json
      .Obj(
        (List(
          "port" -> Json.Num(port),
          "protocol" -> Json.Str(protocol),
          "stubs" -> Json.Arr(Chunk.fromIterable(stubs))
        ) ++ fields)*
      )
      .toJson

  /** POST an imposter built for a fresh port to `url`, keep the answer as the last response, and
    * target it when it was created.
    */
  private def post(url: String, build: Int => String): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      port <- trackedProbePort
      r <- send(url, "POST", "/imposters", Some(build(port)))
      _ <- Stage.put(r)
      _ <- ZIO.when(r.status == 201)(created.update((url, port) :: _) *> Stage.put(Target(port)))
    yield r

  /** POST and require a 201: for the setup steps, where a refusal is a broken scenario. */
  private def create(url: String, build: Int => String): ZIO[RiftHarness, Throwable, Unit] =
    post(url, build).flatMap(r =>
      assertRepro(r.status == 201, s"POST /imposters -> ${r.status}: ${r.body.take(300)}")
    )

  private def certKey(cert: String, key: String): IO[Throwable, List[(String, Json)]] =
    for
      c <- tls(cert)
      k <- tls(key)
    yield List("cert" -> Json.Str(c), "key" -> Json.Str(k))

  // ── HTTPS client ──────────────────────────────────────────────────────────────────────────

  private final case class PeerChain(certs: List[X509Certificate])
  private final case class Negotiated(version: HttpClient.Version)

  /** One HTTP/2-preferring client trusting only the test CA, shared by every HTTPS step (a client
    * per request would leave a selector thread per call until GC).
    */
  private lazy val caClient: HttpClient =
    val is = getClass.getResourceAsStream("/rift-fixtures/intercept/ca-cert.pem")
    if is == null then throw new IllegalStateException("missing the test CA fixture")
    val ca =
      try new String(is.readAllBytes(), UTF_8)
      finally is.close()
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .sslContext(RawHttpConnection.sslTrustingPem(ca))
      .build()

  /** GET `/` on the target imposter over HTTPS, preferring HTTP/2, trusting only the test CA. */
  private val httpsGet: ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- target
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      out <- ZIO.attemptBlocking {
        val req = HttpRequest
          .newBuilder(URI.create(s"https://$host:$port/"))
          .timeout(java.time.Duration.ofSeconds(10))
          .GET()
          .build()
        val r = caClient.send(req, HttpResponse.BodyHandlers.ofString())
        val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
        val chain = r.sslSession.toScala.toList
          .flatMap(_.getPeerCertificates.toList)
          .collect { case x: X509Certificate => x }
        (LastResponse(r.statusCode, r.body, hs), chain, r.version)
      }
      (resp, chain, version) = out
      _ <- Stage.put(resp) *> Stage.put(PeerChain(chain)) *> Stage.put(Negotiated(version))
    yield ()

  private def subject(c: X509Certificate): String = c.getSubjectX500Principal.getName

  // ── admin-API scenarios (shared engine) ───────────────────────────────────────────────────

  Given("an HTTPS imposter serving " / string / " with key " / string) {
    (cert: String, key: String) =>
      for
        url <- sharedAdmin
        fields <- certKey(cert, key)
        _ <- create(url, p => imposter("https", p, fields*)(List(PlainStub)))
      yield ()
  }

  Given(
    "an HTTPS imposter serving the chain " / string / " then " / string / " with key " / string
  ) { (leaf: String, intermediate: String, key: String) =>
    for
      url <- sharedAdmin
      l <- tls(leaf)
      i <- tls(intermediate)
      k <- tls(key)
      fields = List("cert" -> Json.Str(l + i), "key" -> Json.Str(k))
      _ <- create(url, p => imposter("https", p, fields*)(List(PlainStub)))
    yield ()
  }

  Given("an HTTPS imposter serving " / string / " with key " / string / " and " / string) {
    (cert: String, key: String, stubs: String) =>
      for
        url <- sharedAdmin
        fields <- certKey(cert, key)
        ss <- stubsFor(stubs)
        _ <- create(url, p => imposter("https", p, fields*)(ss))
      yield ()
  }

  When("an HTTPS GET trusting only the test CA is sent to the imposter")(httpsGet)

  When("an HTTP/2-preferring HTTPS GET trusting only the test CA is sent to the imposter")(httpsGet)

  Then("the peer certificate subject is " / string) { (expected: String) =>
    Stage
      .get[PeerChain]
      .mapError(e => new IllegalStateException(s"no HTTPS exchange ($e)"))
      .flatMap { p =>
        val got = p.certs.headOption.map(subject)
        assertRepro(got.contains(expected), s"expected peer subject '$expected', got $got")
      }
  }

  Then(
    "the peer certificate chain has " / int / " certificates, the first with subject " / string
  ) { (n: Int, expected: String) =>
    Stage
      .get[PeerChain]
      .mapError(e => new IllegalStateException(s"no HTTPS exchange ($e)"))
      .flatMap { p =>
        val subjects = p.certs.map(subject)
        assertRepro(
          subjects.size == n && subjects.headOption.contains(expected),
          s"expected $n certificates led by '$expected', got $subjects"
        )
      }
  }

  Then("the response was served over " / string) { (expected: String) =>
    Stage
      .get[Negotiated]
      .mapError(e => new IllegalStateException(s"no HTTPS exchange ($e)"))
      .flatMap(n =>
        assertRepro(n.version.toString == expected, s"expected $expected, got ${n.version}")
      )
  }

  When("an imposter with " / string / " is posted") { (kind: String) =>
    for
      url <- sharedAdmin
      ca <- testCa
      leaf <- certKey("server-leaf.pem", "server-leaf.key")
      mismatched <- certKey("server-leaf.pem", "chain-leaf.key")
      caStr = Json.Str(ca)
      yes = Json.Bool(true)
      spec <- kind match
        case "a key that does not match the cert" => ZIO.succeed(("https", mismatched))
        case "a cert without a key" => ZIO.succeed(("https", leaf.take(1)))
        case "rejectUnauthorized without mutualAuth" =>
          ZIO.succeed(("https", List("rejectUnauthorized" -> yes, "ca" -> caStr)))
        case "ca without rejectUnauthorized" =>
          ZIO.succeed(("https", List("mutualAuth" -> yes, "ca" -> caStr)))
        case "rejectUnauthorized without ca" =>
          ZIO.succeed(("https", List("mutualAuth" -> yes, "rejectUnauthorized" -> yes)))
        case "an empty ca list" =>
          ZIO.succeed(
            ("https", List("mutualAuth" -> yes, "rejectUnauthorized" -> yes, "ca" -> Json.Arr()))
          )
        case "a ca with no certificate" =>
          ZIO.succeed(
            (
              "https",
              List("mutualAuth" -> yes, "rejectUnauthorized" -> yes, "ca" -> Json.Str("no cert"))
            )
          )
        case "mutualAuth on an http imposter" => ZIO.succeed(("http", List("mutualAuth" -> yes)))
        case "mutualAuth false on an http imposter" =>
          ZIO.succeed(("http", List("mutualAuth" -> Json.Bool(false))))
        case other => ZIO.fail(new IllegalArgumentException(s"unknown imposter case '$other'"))
      _ <- post(url, p => imposter(spec._1, p, spec._2*)(List(PlainStub)))
    yield ()
  }

  // ── h2c prior knowledge ───────────────────────────────────────────────────────────────────

  Given("a plain-HTTP imposter answering 200 " / string) { (body: String) =>
    val stub = Json.Obj(
      "responses" -> Json.Arr(
        Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> Json.Str(body)))
      )
    )
    sharedAdmin.flatMap(url => create(url, p => imposter("http", p)(List(stub))))
  }

  private final case class H2cResponse(status: Option[Int], body: String)

  /** The static-table `:status` values HPACK can send as one indexed byte (RFC 7541 appendix A). */
  private val IndexedStatus =
    Map(8 -> 200, 9 -> 204, 10 -> 206, 11 -> 304, 12 -> 400, 13 -> 404, 14 -> 500)

  /** An HTTP/2 GET `/` with prior knowledge — the preface, an empty SETTINGS frame and one HEADERS
    * frame hand-encoded from HPACK's static table — reading frames until stream 1 ends. The JDK
    * client has no cleartext prior-knowledge mode (it tries an HTTP/1.1 `Upgrade: h2c`).
    */
  private def h2cGet(host: String, port: Int): IO[Throwable, H2cResponse] =
    ZIO.attemptBlocking {
      val socket = new Socket()
      try
        socket.connect(new InetSocketAddress(host, port), RawHttpConnection.TimeoutMillis)
        socket.setSoTimeout(RawHttpConnection.TimeoutMillis)
        def frame(tpe: Int, flags: Int, stream: Int, payload: Array[Byte]): Array[Byte] =
          val n = payload.length
          Array[Byte]((n >> 16).toByte, (n >> 8).toByte, n.toByte, tpe.toByte, flags.toByte) ++
            java.nio.ByteBuffer.allocate(4).putInt(stream).array() ++ payload
        val authority = host.getBytes(ISO_8859_1)
        // :method GET (2), :scheme http (6), :path / (4), :authority literal with indexed name (1)
        val headers =
          Array[Byte](0x82.toByte, 0x86.toByte, 0x84.toByte, 0x41, authority.length.toByte) ++
            authority
        val out = socket.getOutputStream
        out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(ISO_8859_1))
        out.write(frame(4, 0, 0, Array.emptyByteArray))
        out.write(frame(1, 0x5, 1, headers)) // END_STREAM | END_HEADERS
        out.flush()
        val in = new DataInputStream(socket.getInputStream)
        val body = new ByteArrayOutputStream()
        var status = Option.empty[Int]
        var ended = false
        while !ended do
          val len =
            (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte()
          val tpe = in.readUnsignedByte()
          val flags = in.readUnsignedByte()
          val stream = in.readInt() & 0x7fffffff
          val payload = in.readNBytes(len)
          if stream == 1 && tpe == 9 then
            throw new IllegalStateException("CONTINUATION frames are not handled by this probe")
          if stream == 1 && tpe == 1 && (flags & 0x28) != 0 then
            throw new IllegalStateException(s"PADDED/PRIORITY HEADERS (flags $flags) not handled")
          if stream == 1 then
            // Only an indexed field (high bit set) names the whole `:status`; anything else leaves
            // the status unknown, which fails the assertion rather than guessing.
            if tpe == 1 then
              status = payload.headOption
                .filter(b => (b & 0x80) != 0)
                .map(_ & 0x7f)
                .flatMap(IndexedStatus.get)
            if tpe == 0 then body.write(payload)
            if (tpe == 0 || tpe == 1) && (flags & 0x1) != 0 then ended = true
          if tpe == 7 then ended = true // GOAWAY
        H2cResponse(status, body.toString(UTF_8))
      finally socket.close()
    }

  When("an h2c prior-knowledge GET is sent to the imposter") {
    for
      port <- target
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      r <- h2cGet(host, port)
      _ <- Stage.put(r)
    yield ()
  }

  Then("the h2c response has status " / int / " and body " / string) {
    (status: Int, body: String) =>
      Stage
        .get[H2cResponse]
        .mapError(e => new IllegalStateException(s"no h2c exchange ($e)"))
        .flatMap(r =>
          assertRepro(
            r.status.contains(status) && r.body == body,
            s"expected an HTTP/2 $status '$body', got status ${r.status} body '${r.body}'"
          )
        )
  }

  // ── @tls-flags: dedicated engines ─────────────────────────────────────────────────────────

  private def flagEngine(
      args: List[String],
      env: Map[String, String]
  ): ZIO[RiftHarness, Throwable, Unit] =
    ZIO
      .serviceWithZIO[RiftHarness](_.startTlsFlagEngine(args, env))
      .flatMap(url => Stage.put(FlagEngine(url)))

  Given("an engine started with the default TLS cert " / string / " and key " / string) {
    (cert: String, key: String) =>
      for
        c <- tls(cert).flatMap(tempFile)
        k <- tls(key).flatMap(tempFile)
        _ <- flagEngine(
          List("--default-tls-cert", c.toString, "--default-tls-key", k.toString),
          Map.empty
        )
      yield ()
  }

  Given("an engine started with " / string)((flag: String) => flagEngine(List(flag), Map.empty))

  Given("an engine started with the environment " / string / " set to " / string) {
    (name: String, value: String) => flagEngine(Nil, Map(name -> value))
  }

  Given("an HTTPS imposter with no cert on that engine") {
    flagAdmin.flatMap(url => create(url, p => imposter("https", p)(List(PlainStub))))
  }

  Given("an HTTPS imposter serving " / string / " with key " / string / " on that engine") {
    (cert: String, key: String) =>
      for
        url <- flagAdmin
        fields <- certKey(cert, key)
        _ <- create(url, p => imposter("https", p, fields*)(List(PlainStub)))
      yield ()
  }

  When("an HTTPS imposter with no cert is posted to that engine") {
    flagAdmin.flatMap(url => post(url, p => imposter("https", p)(List(PlainStub)))).unit
  }

  Then("an engine started with a default TLS cert file that does not exist exits before serving") {
    for
      key <- tls("server-leaf.key").flatMap(tempFile)
      missing <- tempFile("").tap(p => ZIO.attemptBlocking(Files.delete(p)))
      log <- tempFile("")
      result <- ZIO
        .serviceWithZIO[RiftHarness](
          _.startTlsFlagEngine(
            List("--default-tls-cert", missing.toString, "--default-tls-key", key.toString),
            Map.empty,
            Some(log)
          )
        )
        .either
      output <- ZIO.attemptBlocking(Files.readString(log))
      _ <- assertRepro(
        result.left.exists(e => e.getMessage.contains("rift exited early (code 1)")) &&
          output.contains("No such file or directory"),
        s"expected the engine to exit with code 1 over the missing file before serving, got " +
          s"$result, output tail: ${output.takeRight(300)}"
      )
    yield ()
  }
