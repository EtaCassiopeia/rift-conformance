package samples.rift.support

import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.step.{Stage, ZIOSteps}
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManager, X509TrustManager}
import scala.jdk.CollectionConverters.*

/** Sending a body the server may reject mid-upload (issue #131).
  *
  * The JDK `HttpClient` writes the whole request body before it looks at the response, so a server
  * that answers early and closes — exactly what the engine's over-size door does once it has read
  * past its cap — surfaces as an `IOException` out of `send()` rather than as the response it
  * actually sent. Two things that do NOT fix it, both measured against a live 0.15.0 engine:
  * `Expect: 100-continue` (the engine answers `100 Continue` unconditionally instead of
  * pre-rejecting on `Content-Length`, so the body still streams), and polling `available()` between
  * writes (an RST discards bytes already sitting in the receive buffer, so checking afterwards is
  * too late).
  *
  * What works is consuming the response the moment it lands — start the reader before writing the
  * body. That is what curl's select loop does, and why the door looked solid when probed by hand.
  */
object RiftConformanceSupport:

  /** Bodies at or above this size route through [[sendOversize]]. Comfortably above every ordinary
    * staged body in the suite, so normal sends keep the plain `HttpClient` path.
    */
  val OversizeBodyBytes: Int = 1024 * 1024

  /** Whole-send budget. Mirrors the 10s request timeout on the `HttpClient` path: a wedged engine
    * must fail this scenario crisply rather than hang the CI run, and a thread blocked in a socket
    * write does not answer `Thread.interrupt`, so nothing else would bound it.
    */
  private val SendDeadlineMillis: Long = 30000

  /** POST a large body over a raw socket, reading the response as soon as the server sends one.
    * Also the one send that can set `Host` (`hostHeader`): the JDK `HttpClient` refuses it as a
    * restricted header, and the front door (feature 11) routes on it.
    */
  def sendOversize(
      url: String,
      method: String,
      headers: Map[String, String],
      body: String,
      hostHeader: Option[String] = None
  ): LastResponse =
    val uri = URI.create(url)
    // Plaintext only: this path speaks raw HTTP/1.1 on a bare socket, so an https URL would
    // silently send cleartext rather than fail.
    if Option(uri.getScheme).exists(_.equalsIgnoreCase("https")) then
      throw new IllegalArgumentException(s"sendOversize is plaintext-only, got $url")
    val port = if uri.getPort > 0 then uri.getPort else 80
    val sock = new Socket()
    val startedAt = java.lang.System.currentTimeMillis()
    try
      sock.connect(new InetSocketAddress(uri.getHost, port), 10000)
      sock.setSoTimeout(10000)
      val path = if uri.getRawPath.isEmpty then "/" else uri.getRawPath
      val query = Option(uri.getRawQuery).fold("")("?" + _)
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      val reserved = Set("host", "content-length", "connection")
      val head = new StringBuilder()
        .append(s"${method.toUpperCase} $path$query HTTP/1.1\r\n")
        .append(s"Host: ${hostHeader.getOrElse(s"${uri.getHost}:$port")}\r\n")
        .append(s"Content-Length: ${bytes.length}\r\n")
        .append("Connection: close\r\n")
      // Framing headers are ours; a caller restating one would emit a duplicate line.
      headers.filterNot((k, _) => reserved.contains(k.toLowerCase)).foreach { (k, v) =>
        head.append(s"$k: $v\r\n")
      }
      head.append("\r\n")

      val collected = new ByteArrayOutputStream()
      val writeError = new java.util.concurrent.atomic.AtomicReference[Option[Throwable]](None)

      // Started BEFORE the body write: see the object comment. Closing the socket the moment a
      // complete response has arrived also releases a writer otherwise blocked on a peer that has
      // stopped draining.
      val reader = new Thread(() =>
        try
          val in = sock.getInputStream
          val buf = new Array[Byte](8 * 1024)
          var n = in.read(buf)
          var complete = false
          while n > 0 && !complete do
            complete = collected.synchronized {
              collected.write(buf, 0, n)
              isComplete(collected.toByteArray)
            }
            if complete then sock.close() else n = in.read(buf)
        catch
          case _: java.io.IOException => () // reset/close once the response is in: expected here
      )
      reader.setDaemon(true)
      reader.start()

      val out = sock.getOutputStream
      out.write(head.toString.getBytes(StandardCharsets.UTF_8))
      out.flush()

      val chunk = 64 * 1024
      var offset = 0
      var writeDone = false
      while offset < bytes.length && !writeDone do
        if java.lang.System.currentTimeMillis() - startedAt > SendDeadlineMillis then
          throw new RuntimeException(
            s"over-size send to $url exceeded ${SendDeadlineMillis}ms with no response " +
              s"(wrote $offset/${bytes.length} bytes) — the peer stopped reading without answering"
          )
        try
          out.write(bytes, offset, math.min(chunk, bytes.length - offset))
          out.flush()
          offset += chunk
        catch
          // The peer rejected and closed mid-upload. Keep the cause: if no response turns up, this
          // is the only evidence of what actually went wrong.
          case e: java.io.IOException =>
            writeError.set(Some(e))
            writeDone = true

      val budget =
        math.max(1000L, SendDeadlineMillis - (java.lang.System.currentTimeMillis() - startedAt))
      reader.join(budget)
      if reader.isAlive then
        throw new RuntimeException(
          s"over-size send to $url: response never completed within ${SendDeadlineMillis}ms"
        )
      val text = collected.synchronized(new String(collected.toByteArray, StandardCharsets.UTF_8))
      parseResponse(text, writeError.get)
    finally sock.close()

  /** Whether `raw` holds a complete response: headers plus a body matching `Content-Length`. */
  private def isComplete(raw: Array[Byte]): Boolean =
    val text = new String(raw, StandardCharsets.UTF_8)
    val sep = text.indexOf("\r\n\r\n")
    sep >= 0 && contentLength(text.substring(0, sep)).exists(text.length - (sep + 4) >= _)

  private def contentLength(headPart: String): Option[Int] =
    headPart
      .split("\r\n")
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") => l.split(":", 2).lift(1)
      }
      .flatten
      .flatMap(_.trim.toIntOption)

  /** Parse a raw HTTP/1.1 response into the same shape the `HttpClient` path returns.
    *
    * Fails loudly on anything partial: a truncated body parsed into a plausible `LastResponse`
    * would let a status-only assertion pass on a response that never fully arrived.
    */
  private def parseResponse(text: String, writeError: Option[Throwable]): LastResponse =
    val sep = text.indexOf("\r\n\r\n")
    if sep < 0 then
      throw new RuntimeException(
        s"no complete HTTP response read before the peer closed (got ${text.length} bytes)",
        writeError.orNull
      )
    val (headPart, bodyPart) = (text.substring(0, sep), text.substring(sep + 4))
    val lines = headPart.split("\r\n").toList
    val status = lines.headOption
      .flatMap(_.split(" ").lift(1))
      .flatMap(_.toIntOption)
      .getOrElse(throw new RuntimeException(s"unparseable status line: ${lines.headOption}"))
    val hs = lines.tail
      .flatMap { l =>
        l.split(":", 2) match
          case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
          case _ => None
      }
      .groupMap(_._1)(_._2)
    // Chunk framing would otherwise land in the body verbatim and still satisfy a `contains` assert.
    if hs.contains("transfer-encoding") then
      throw new RuntimeException(
        s"sendOversize cannot decode Transfer-Encoding: ${hs("transfer-encoding").mkString(",")}"
      )
    contentLength(headPart).foreach { declared =>
      if bodyPart.length < declared then
        throw new RuntimeException(
          s"truncated response body: declared $declared bytes, read ${bodyPart.length}"
        )
    }
    LastResponse(status, bodyPart, hs)

/** Scenario state for Plane A suites. The interesting per-scenario values (tracked ports, the
  * pending request, the last response, the curl log) live in [[Stage]] — this only exists to
  * satisfy `ZIOSteps[R, S]`; `Default[RiftState]` derives from the `Schema`.
  */
final case class RiftState(note: String = "")
object RiftState:
  given Schema[RiftState] = DeriveSchema.gen[RiftState]

/** Imposter ports created this scenario, deleted in `afterScenario` (fresh-imposter-per-scenario).
  */
final case class TrackedPorts(ports: List[Int])

/** The imposter subsequent request steps target (the last one posted). */
final case class CurrentPort(port: Int)

/** The key prefix of the scenario's Redis flow-store imposter (feature 15). */
final case class RedisKeyPrefix(prefix: String)

/** Request headers/body staged for the next send. */
final case class PendingRequest(
    headers: Map[String, String] = Map.empty,
    body: Option[String] = None
)

/** What the engine actually served for the last request. */
final case class LastResponse(status: Int, body: String, headers: Map[String, List[String]])

/** Paste-ready curl equivalents of the HTTP steps run this scenario — appended to every failure. */
final case class CurlLog(lines: List[String])

/** The corpus `.json` files discovered by the "corpus directory is loaded" step. */
final case class LoadedCorpus(files: List[java.nio.file.Path])

/** The declared ports of the corpus imposters posted to the engine this scenario. */
final case class PostedPorts(ports: List[Int])

/** Wall-clock cost of the last request — for latency-floor / timing asserts. */
final case class ElapsedMillis(ms: Long)

/** Whether the last "expecting a transport error" send actually failed at the transport level. */
final case class TransportOutcome(failed: Boolean)

/** The `rift_requests_total` sum recorded by "the client records the request counter" — the
  * baseline a later "increased by at least N" delta assertion is computed against. The counter is
  * cumulative across the shared engine, so only a delta around the scenario's own requests is
  * meaningful.
  */
final case class RequestCounter(total: Long)

/** Result of a timed send that tolerates a transport-level failure (thrown IOException). `error`
  * carries the thrown exception's identity so a failure has a diagnostic trail.
  */
final case class TransportProbe(
    response: Option[LastResponse],
    elapsedMs: Long,
    failed: Boolean,
    error: Option[String]
)

/** A concurrent burst left running in the background (a forked fiber) so later steps can probe the
  * engine *while the requests are still in flight*, then join for the statuses.
  */
final case class ConcurrentBurst(
    requested: Int,
    fiber: Fiber.Runtime[Nothing, List[TransportProbe]]
)

/** Set by "the engine is started with the reload config file" (rift#316, feature 11) so
  * `afterScenario` knows to call `RiftHarness#revertEngine` — reverting the shared engine back to
  * plain is mandatory even when an assertion fails mid-scenario, but calling `revertEngine` on
  * EVERY scenario (it would just no-op) would add a pointless harness round-trip to the whole
  * suite, hence the flag.
  */
final case class ReloadActive(active: Boolean)

/** The port of this scenario's HTTPS upstream imposter (feature 09 `@upstream-trust`, rift#974). */
final case class UpstreamPort(port: Int)

/** The front-door port of this scenario's `--front-door` reload engine (feature 11, rift#1160). */
final case class FrontDoorPort(port: Int)

/** Shared Plane A step backbone. Mix into a `ZIOSteps[RiftHarness, S]` suite. Steps talk **directly
  * to the admin API and the imposter ports** — `MockControl` appears nowhere, which is what keeps
  * Plane A an independent oracle (a red here is an engine/tool finding, never a library bug).
  *
  * The full step catalog (admin ops, spaces, reload, concurrency, lint/verify asserts) is filled in
  * by later issues; this provides the plumbing plus the fresh-imposter / request / response-assert
  * steps the consistency gate (#5) builds on.
  */
trait RiftConformanceSupport[S] { self: ZIOSteps[RiftHarness, S] =>

  private val http = HttpClient.newHttpClient()

  // A trust-all X509TrustManager — this is a TEST harness talking to a self-signed conformance
  // imposter (rift auto-generates the cert; there's no CA to verify against), so skipping
  // verification here is correct and intentional, never used against a real endpoint.
  private val trustAllManager: X509TrustManager =
    new X509TrustManager:
      def checkClientTrusted(
          chain: Array[java.security.cert.X509Certificate],
          authType: String
      ): Unit = ()
      def checkServerTrusted(
          chain: Array[java.security.cert.X509Certificate],
          authType: String
      ): Unit = ()
      def getAcceptedIssuers: Array[java.security.cert.X509Certificate] = Array.empty

  private val insecureSslContext: SSLContext =
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAllManager), new java.security.SecureRandom())
    ctx

  private val httpInsecure = HttpClient.newBuilder().sslContext(insecureSslContext).build()

  // 4800–4819 are reserved for fixed/verbatim Plane A fixtures (e.g. the defaultForward pair);
  // the dynamic fresh-imposter pool draws from 4820–4899 so the two never collide.
  private val ProbePortLo = 4820
  private val ProbePortHi = 4899

  // Delete every imposter this scenario created so ports don't leak across scenarios. URIO hook:
  // teardown errors are swallowed (ignore).
  afterScenario {
    trackedPorts.flatMap { tp =>
      ZIO.serviceWithZIO[RiftHarness] { h =>
        // Swallow teardown errors (URIO hook) but LOG them — zio-bdd doesn't surface afterScenario
        // failures anywhere, so a silently-failing delete would leak ports until the pool exhausts.
        ZIO.foreachDiscard(tp.ports) { port =>
          deleteImposter(h.adminUrl, port)
            .tapError(e =>
              ZIO.logWarning(s"imposter cleanup failed on port $port: ${e.getMessage}")
            )
            .ignore
        }
      }
    }
  }

  // Revert the shared engine to plain if this scenario swapped in a --configfile engine (rift#316,
  // feature 11) — MUST run even when an assertion failed mid-scenario, or every later suite would
  // keep running against the reload scenario's isolated 2-imposter config. URIO hook, same
  // swallow-but-log posture as the imposter cleanup above.
  afterScenario {
    Stage.get[ReloadActive].orElseSucceed(ReloadActive(false)).flatMap { ra =>
      ZIO
        .serviceWithZIO[RiftHarness](_.revertEngine)
        .tapError(e => ZIO.logWarning(s"reload-engine revert failed: ${e.getMessage}"))
        .ignore
        .when(ra.active)
        .unit
    }
  }

  // Reap the feature-09 @upstream-trust engine (rift#974) if this scenario started one; a no-op
  // otherwise. Same swallow-but-log posture as the hooks above.
  afterScenario {
    ZIO
      .serviceWithZIO[RiftHarness](_.stopUpstreamTrustEngine)
      .tapError(e => ZIO.logWarning(s"upstream-trust engine teardown failed: ${e.getMessage}"))
      .ignore
  }

  // ── Stage accessors ─────────────────────────────────────────────────────────────────────

  protected def trackedPorts: UIO[TrackedPorts] =
    Stage.get[TrackedPorts].orElseSucceed(TrackedPorts(Nil))
  protected def pendingRequest: UIO[PendingRequest] =
    Stage.get[PendingRequest].orElseSucceed(PendingRequest())
  protected def curlLog: UIO[List[String]] =
    Stage.get[CurlLog].map(_.lines).orElseSucceed(Nil)
  protected def currentPort: IO[Throwable, Int] =
    Stage
      .get[CurrentPort]
      .map(_.port)
      .mapError(e => new IllegalStateException(s"no imposter posted yet ($e)"))
  protected def lastResponse: IO[Throwable, LastResponse] =
    Stage
      .get[LastResponse]
      .mapError(e => new IllegalStateException(s"no response — send a request first ($e)"))

  private def track(port: Int): UIO[Unit] =
    trackedPorts.flatMap(tp => Stage.put(TrackedPorts(tp.ports :+ port)))
  private def recordCurl(line: String): UIO[Unit] =
    curlLog.flatMap(ls => Stage.put(CurlLog(ls :+ line)))

  /** Assert, appending the scenario's curl repro so triage starts from a paste-ready reproduction.
    */
  protected def assertRepro(cond: Boolean, msg: String): IO[Throwable, Unit] =
    curlLog.flatMap(ls => Assertions.assertTrue(cond, s"$msg\n--- repro ---\n${ls.mkString("\n")}"))

  // ── error-response envelope (rift#611, 0.14.0 — feature 13) ──────────────────────────────

  /** One entry of the error envelope. `slug` is 0.15.0's `type` (rift#797) — `Option` because
    * pre-0.15.0 engines omit the field entirely, so a missing one fails the assertion that names it
    * rather than silently comparing against `null`.
    */
  private final case class EnvelopeEntry(code: String, slug: Option[String], message: String)

  /** Parse the standard error envelope `{"errors":[{"code","type","message"}]}` out of a body.
    *
    * `Left` names WHY the body is not a well-formed envelope (unparseable vs wrong shape) so a
    * failure reads as the actual defect rather than a bare "assertion was false" — the pre-0.14.0
    * bug this pins was exactly an unparseable body, and "body is not JSON" is the diagnosis.
    */
  private def parseErrorEnvelope(body: String): Either[String, List[EnvelopeEntry]] =
    body.fromJson[Json].left.map(m => s"body is not JSON: $m").flatMap {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("errors", Json.Arr(entries)) => entries } match
          case None => Left("no top-level 'errors' array")
          case Some(entries) if entries.isEmpty => Left("'errors' array is empty")
          case Some(entries) =>
            entries.foldLeft[Either[String, List[EnvelopeEntry]]](Right(Nil)) { (acc, entry) =>
              acc.flatMap { done =>
                entry match
                  case Json.Obj(f) =>
                    val code = f.collectFirst { case ("code", Json.Str(c)) => c }
                    val slug = f.collectFirst { case ("type", Json.Str(t)) => t }
                    val message = f.collectFirst { case ("message", Json.Str(m)) => m }
                    (code, message) match
                      case (Some(c), Some(m)) => Right(done :+ EnvelopeEntry(c, slug, m))
                      case _ => Left(s"entry lacks a string 'code'/'message': $entry")
                  case _ => Left(s"'errors' entry is not an object: $entry")
              }
            }
      case _ => Left("body root is not a JSON object")
    }

  /** Run an assertion over the last response's parsed envelope, failing with the parse diagnosis
    * when the body isn't one.
    */
  private def withEnvelope(
      f: List[EnvelopeEntry] => IO[Throwable, Unit]
  ): IO[Throwable, Unit] =
    lastResponse.flatMap { r =>
      parseErrorEnvelope(r.body) match
        case Right(entries) => f(entries)
        case Left(why) => assertRepro(false, s"not a rift error envelope ($why): '${r.body}'")
    }

  // ── admin API + imposter I/O (direct, no MockControl) ────────────────────────────────────

  /** Load a fixture imposter, assign it a fresh 48xx port, POST it to the admin API, and track it.
    */
  protected def postImposterFromFixture(name: String): ZIO[RiftHarness, Throwable, Int] =
    for
      raw <- loadFixture(name).flatMap(rewriteFixtureJson)
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      port <- postAtFreshPort(admin, raw, s"fixture $name")
      _ <- track(port) *> Stage.put(CurrentPort(port))
    yield port

  /** POST imposter JSON (no `port`) to `admin` at a fresh pool port and return the port. A non-201
    * usually means the freshly-probed port was taken between the probe and the engine's bind
    * (TOCTOU), so re-probe a new port and retry a few times. Neither tracks nor stages the port.
    */
  private def postAtFreshPort(
      admin: String,
      raw: String,
      label: String
  ): ZIO[RiftHarness, Throwable, Int] =
    val attempt =
      for
        port <- freeProbePort
        body <- ZIO.fromEither(injectPort(raw, port)).mapError(new RuntimeException(_))
        code <- postImposter(admin, body)
        _ <- ZIO
          .fail(new RuntimeException(s"POST imposter ($label) returned $code, expected 201"))
          .when(code != 201)
      yield port
    attempt.retry(Schedule.recurs(3))

  protected def sendToCurrent(method: String, path: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port)) // the harness's port→host seam
      pend <- pendingRequest
      url = s"http://$host:$port$path"
      _ <- recordCurl(curlOf(method, url, pend))
      // A body big enough that the server may answer and close before the write finishes needs the
      // read-while-writing sender, or that early response is lost as an IOException (#131).
      oversize = pend.body.exists(_.length >= RiftConformanceSupport.OversizeBodyBytes)
      _ <-
        if oversize then
          ZIO
            .attemptBlocking {
              val t0 = java.lang.System.nanoTime()
              val r = RiftConformanceSupport
                .sendOversize(url, method, pend.headers, pend.body.getOrElse(""))
              (r, (java.lang.System.nanoTime() - t0) / 1000000L)
            }
            .flatMap((r, ms) => Stage.put(r) *> Stage.put(ElapsedMillis(ms)))
        else
          timedProbe(url, method, pend.headers, pend.body).flatMap { probe =>
            probe.response match
              case Some(r) => Stage.put(r) *> Stage.put(ElapsedMillis(probe.elapsedMs))
              case None =>
                ZIO.fail(
                  new RuntimeException(s"transport failure on $url (unexpected for a plain send)")
                )
          }
    yield ()

  /** Send expecting a transport-level failure (fault scenarios): capture whether it failed, the
    * elapsed time (latency still applies), and any HTTP response (some fault kinds may still
    * surface one instead of a reset).
    */
  protected def sendExpectingTransportError(
      method: String,
      path: String
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      pend <- pendingRequest
      url = s"http://$host:$port$path"
      _ <- recordCurl(curlOf(method, url, pend))
      probe <- timedProbe(url, method, pend.headers, pend.body)
      _ <- Stage.put(TransportOutcome(probe.failed))
      _ <- Stage.put(ElapsedMillis(probe.elapsedMs))
      _ <- ZIO.foreachDiscard(probe.response)(Stage.put(_))
    yield ()

  protected def transportOutcome: IO[Throwable, TransportOutcome] =
    Stage.get[TransportOutcome].mapError(e => new IllegalStateException(s"no transport probe ($e)"))
  protected def elapsedMs: IO[Throwable, Long] =
    Stage.get[ElapsedMillis].map(_.ms).mapError(e => new IllegalStateException(s"no timing ($e)"))

  /** Fire `n` GET requests concurrently at the current imposter's path and record their statuses —
    * used to prove the engine stays bounded/responsive under concurrent load (rift#308).
    */
  protected def sendConcurrent(n: Int, path: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      pend <- pendingRequest
      url = s"http://$host:$port$path"
      _ <- recordCurl(s"(x$n concurrent, in flight) ${curlOf("GET", url, pend)}")
      // Fork so the burst runs in the background — the next steps probe the engine / a sibling
      // WHILE these are in flight (the rift#308 property). `all concurrent responses…` joins it.
      fiber <- ZIO
        .foreachPar(1 to n)(_ => timedProbe(url, "GET", pend.headers, pend.body))
        .map(_.toList)
        .forkDaemon
      _ <- Stage.put(ConcurrentBurst(n, fiber))
    yield ()

  protected def concurrentBurst: IO[Throwable, ConcurrentBurst] =
    Stage
      .get[ConcurrentBurst]
      .mapError(e => new IllegalStateException(s"no concurrent burst ($e)"))

  private def loadFixture(name: String): IO[Throwable, String] =
    loadResource(s"/rift-fixtures/$name.json")

  private def loadResource(resource: String): IO[Throwable, String] =
    ZIO.attemptBlocking {
      val is = getClass.getResourceAsStream(resource)
      if is == null then throw new RuntimeException(s"fixture not found on classpath: $resource")
      try new String(is.readAllBytes(), StandardCharsets.UTF_8)
      finally is.close()
    }

  /** Apply the harness's fixture rewrite (engine-cwd-relative data-source paths → resolvable)
    * before POSTing any imposter JSON to the engine. Identity in external mode; absolutizing in
    * embedded.
    */
  private def rewriteFixtureJson(raw: String): URIO[RiftHarness, String] =
    ZIO.serviceWith[RiftHarness](_.rewriteFixture(raw))

  // ── corpus (the numbered, byte-fidelity imposters vendored under ./corpus) ────────────────

  /** The corpus imposters directory: `$RIFT_CORPUS_ROOT/imposters` (default matches the harness's).
    */
  protected def corpusImpostersDir: Path =
    val root = Option(java.lang.System.getenv("RIFT_CORPUS_ROOT"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("corpus")
    Paths.get(root).resolve("imposters")

  /** The corpus `.json` files, sorted (add-never-renumber ⇒ stable ordering). */
  protected def corpusFiles: IO[Throwable, List[Path]] =
    ZIO.attemptBlocking {
      val dir = corpusImpostersDir
      if !Files.isDirectory(dir) then throw new RuntimeException(s"corpus dir not found: $dir")
      Files
        .list(dir)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".json"))
        .toList
        .sortBy(_.getFileName.toString)
    }

  /** The `port` declared by a corpus imposter file. */
  protected def declaredPort(file: Path): IO[Throwable, Int] =
    ZIO.attemptBlocking(Files.readString(file)).flatMap { raw =>
      raw.fromJson[Json] match
        case Right(Json.Obj(fields)) =>
          fields.collectFirst { case ("port", Json.Num(n)) => n.intValue } match
            case Some(p) => ZIO.succeed(p)
            case None => ZIO.fail(new RuntimeException(s"${file.getFileName}: no top-level port"))
        case _ => ZIO.fail(new RuntimeException(s"${file.getFileName}: not a JSON object"))
    }

  /** POST every corpus file **verbatim** (declared 45xx ports intact — byte-fidelity, no adapter)
    * and track each declared port for cleanup. Fails naming every file the engine rejected.
    */
  protected def postCorpusVerbatim: ZIO[RiftHarness, Throwable, List[Int]] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      files <- corpusFiles
      // Fail loudly on an empty corpus — otherwise the downstream "answers"/"rift-verify" scenarios
      // would pass having verified nothing (vacuous green is the one thing an oracle must never do).
      _ <- ZIO
        .fail(new RuntimeException(s"no corpus imposters found under $corpusImpostersDir"))
        .when(files.isEmpty)
      results <- ZIO.foreach(files) { file =>
        for
          raw0 <- ZIO.attemptBlocking(Files.readString(file))
          raw <- rewriteFixtureJson(raw0)
          port <- declaredPort(file)
          code <- postImposter(admin, raw)
          _ <- track(port)
        yield (file.getFileName.toString, port, code)
      }
      rejected = results.collect { case (name, _, code) if code != 201 => s"$name ($code)" }
      _ <- ZIO
        .fail(new RuntimeException(s"engine rejected corpus files: ${rejected.mkString(", ")}"))
        .when(rejected.nonEmpty)
    yield results.map(_._2)

  /** First non-empty line of `<binary> --version` — used to detect a build-dir binary that has
    * drifted from what BUILD_INFO recorded at build time.
    */
  protected def binaryVersion(binary: Path): IO[Throwable, String] =
    ZIO.attemptBlocking {
      // stdout only — build-rift.sh recorded `--version` from stdout, so match that to compare.
      val proc = new ProcessBuilder(binary.toString, "--version").start()
      val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      proc.waitFor()
      out.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
    }

  /** True if something is accepting TCP connections on `port` — protocol-agnostic, so it works for
    * the corpus's http *and* https imposters. "Serves on its declared port."
    */
  protected def portAnswers(port: Int): UIO[Boolean] =
    ZIO
      .attemptBlocking {
        val s = new Socket()
        try { s.connect(new InetSocketAddress("localhost", port), 1000); true }
        finally s.close()
      }
      .orElseSucceed(false)

  /** Merge `store` over the fixture's `_rift.flowState` (keeping e.g. its `ttlSeconds`). */
  private def withFlowStore(raw: String, store: Json.Obj): Either[String, String] =
    def obj(j: Option[Json]): Either[String, Json.Obj] = j match
      case None => Right(Json.Obj())
      case Some(o: Json.Obj) => Right(o)
      case Some(other) => Left(s"expected a JSON object, got $other")
    def merged(base: Json.Obj, over: Json.Obj): Json.Obj =
      Json.Obj(base.fields.filterNot(f => over.fields.exists(_._1 == f._1)) ++ over.fields)
    for
      root <- raw.fromJson[Json].left.map(m => s"fixture JSON parse: $m").flatMap(j => obj(Some(j)))
      rift <- obj(root.get("_rift"))
      flow <- obj(rift.get("flowState"))
    yield merged(
      root,
      Json.Obj("_rift" -> merged(rift, Json.Obj("flowState" -> merged(flow, store))))
    ).toJson

  private def injectPort(raw: String, port: Int): Either[String, String] =
    raw.fromJson[Json].left.map(m => s"fixture JSON parse: $m").flatMap {
      case Json.Obj(fields) =>
        val kept = fields.filterNot(_._1 == "port")
        Right(Json.Obj(kept :+ ("port" -> Json.Num(BigDecimal(port)))).toJson)
      case _ => Left("fixture root is not a JSON object")
    }

  // Delegated to the harness so container mode (whose imposters bind inside the container, with the
  // host ports held by Docker's 1:1 forward) can hand out from its published pool instead of a
  // host-freeness scan. External/embedded use the default host scan.
  // Skips ports this scenario already holds: the host scan binds the wildcard address, which macOS
  // allows alongside a live imposter's listener, so a second fresh imposter could be handed the
  // first one's port.
  private def freeProbePort: ZIO[RiftHarness, Throwable, Int] =
    trackedPorts.flatMap { held =>
      // Bounded by the pool size: container mode hands out a rotating cursor rather than scanning,
      // so wrap to the pool start instead of narrowing past its end.
      def from(lo: Int, attemptsLeft: Int): ZIO[RiftHarness, Throwable, Int] =
        if attemptsLeft == 0 then
          ZIO.fail(new RuntimeException(s"no free probe port in $ProbePortLo..$ProbePortHi"))
        else
          ZIO
            .serviceWithZIO[RiftHarness](_.freshImposterPort(lo, ProbePortHi))
            .flatMap { p =>
              if !held.ports.contains(p) then ZIO.succeed(p)
              else from(if p >= ProbePortHi then ProbePortLo else p + 1, attemptsLeft - 1)
            }
      from(ProbePortLo, ProbePortHi - ProbePortLo + 1)
    }

  private def postImposter(adminUrl: String, json: String): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/imposters"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build()
      http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  private def deleteImposter(adminUrl: String, port: Int): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/imposters/$port")).DELETE().build()
      val code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      // 404 = already gone (fine); any other non-2xx is a real teardown failure worth surfacing.
      if code >= 300 && code != 404 then
        throw new RuntimeException(s"DELETE /imposters/$port -> $code")
    }

  private def httpSend(
      url: String,
      method: String,
      headers: Map[String, String],
      body: Option[String]
  ): IO[Throwable, LastResponse] =
    ZIO.attemptBlocking(blockingSend(url, method, headers, body))

  // The raw JDK send — throws on a transport-level failure (connection reset, garbage-then-close).
  private def blockingSend(
      url: String,
      method: String,
      headers: Map[String, String],
      body: Option[String]
  ): LastResponse = blockingSendWith(http, url, method, headers, body)

  // Same as `blockingSend` but against an explicit client — lets HTTPS scenarios use
  // `httpInsecure` (trust-all) while every other send keeps the default verifying client.
  private def blockingSendWith(
      client: HttpClient,
      url: String,
      method: String,
      headers: Map[String, String],
      body: Option[String]
  ): LastResponse =
    val publisher =
      body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
    // A generous request timeout, well above any legitimate latency fault (700ms): if a script
    // timeout fully regresses to an infinite hang, the send fails at 10s (a clean IOException) so a
    // scenario reports a crisp failure with its curl repro instead of hanging the whole CI run.
    var builder = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(java.time.Duration.ofSeconds(10))
      .method(method.toUpperCase, publisher)
    headers.foreach((k, v) => builder = builder.header(k, v))
    val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    // HTTP header names are case-insensitive; key by lowercase so lookups are too.
    val hs = resp.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
    LastResponse(resp.statusCode, resp.body, hs)

  /** Send and time it, capturing a transport-level failure (a thrown IOException) instead of
    * failing the effect — so fault scenarios can assert "the request fails at the transport level"
    * and the elapsed time (latency faults apply even when the connection is then reset).
    */
  private def timedProbe(
      url: String,
      method: String,
      headers: Map[String, String],
      body: Option[String]
  ): UIO[TransportProbe] = timedProbeWith(http, url, method, headers, body)

  // Same as `timedProbe` but against an explicit client (see `blockingSendWith`).
  private def timedProbeWith(
      client: HttpClient,
      url: String,
      method: String,
      headers: Map[String, String],
      body: Option[String]
  ): UIO[TransportProbe] =
    ZIO.attemptBlocking {
      val t0 = java.lang.System.nanoTime()
      def elapsed = (java.lang.System.nanoTime() - t0) / 1000000L
      try
        TransportProbe(
          Some(blockingSendWith(client, url, method, headers, body)),
          elapsed,
          failed = false,
          None
        )
      catch
        // A transport-abort fault (reset / garbage-then-close) surfaces as an IOException on an
        // *established* connection. ConnectException/UnknownHostException mean the engine wasn't
        // reachable at all — a real bug, NOT a fault — so let those (and any non-IO throwable,
        // fatal error, or interrupt) propagate as a defect rather than green a fault scenario.
        case e: java.net.ConnectException => throw e
        case e: java.net.UnknownHostException => throw e
        case e: java.io.IOException =>
          TransportProbe(
            None,
            elapsed,
            failed = true,
            Some(s"${e.getClass.getSimpleName}: ${e.getMessage}")
          )
    }.orDie

  private def curlOf(method: String, url: String, pend: PendingRequest): String =
    val hs = pend.headers.map((k, v) => s"-H '$k: $v'").mkString(" ")
    val d = pend.body.map(b => s"-d '$b'").getOrElse("")
    s"curl -X ${method.toUpperCase} $hs $d '$url'".replaceAll(" +", " ").trim

  /** Post a fixture **verbatim** (keeping its declared port) — for scenarios that need a fixed port
    * another imposter references (e.g. `defaultForward`) or that the gateway addresses by number.
    */
  protected def postImposterVerbatimFixture(name: String): ZIO[RiftHarness, Throwable, Int] =
    for
      raw0 <- loadFixture(name)
      raw <- rewriteFixtureJson(raw0)
      port <- ZIO.fromEither(portOfJson(raw)).mapError(new RuntimeException(_))
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      code <- postImposter(admin, raw)
      _ <- ZIO
        .fail(new RuntimeException(s"POST verbatim fixture $name returned $code, expected 201"))
        .when(code != 201)
      _ <- track(port)
      _ <- Stage.put(CurrentPort(port))
    yield port

  private def portOfJson(raw: String): Either[String, Int] =
    raw.fromJson[Json].left.map(m => s"fixture JSON parse: $m").flatMap {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("port", Json.Num(n)) => n.intValue }
          .toRight("no top-level port")
      case _ => Left("fixture root is not a JSON object")
    }

  /** Send to a specific imposter port (not the "current" one) — for multi-imposter scenarios like
    * "a sibling imposter stays responsive".
    */
  protected def sendToPort(
      method: String,
      path: String,
      port: Int
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      pend <- pendingRequest
      url = s"http://$host:$port$path"
      _ <- recordCurl(curlOf(method, url, pend))
      probe <- timedProbe(url, method, pend.headers, pend.body)
      _ <- probe.response match
        case Some(r) => Stage.put(r) *> Stage.put(ElapsedMillis(probe.elapsedMs))
        case None => ZIO.fail(new RuntimeException(s"transport failure on $url"))
    yield ()

  /** Send to the current imposter through the single-port gateway (`{admin}/__rift/<port><path>`).
    */
  protected def sendViaGateway(method: String, path: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      pend <- pendingRequest
      url = s"$admin/__rift/$port$path"
      _ <- recordCurl(curlOf(method, url, pend))
      resp <- httpSend(url, method, pend.headers, pend.body)
      _ <- Stage.put(resp)
    yield ()

  /** Send over HTTPS (via `httpInsecure`, which does not verify the server cert) to a specific
    * imposter port — for the self-signed-TLS scenario. Mirrors `sendToCurrent`'s staging.
    */
  protected def sendHttpsToPort(
      method: String,
      path: String,
      port: Int
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      pend <- pendingRequest
      url = s"https://$host:$port$path"
      _ <- recordCurl(s"${curlOf(method, url, pend)} -k")
      probe <- timedProbeWith(httpInsecure, url, method, pend.headers, pend.body)
      _ <- probe.response match
        case Some(r) => Stage.put(r) *> Stage.put(ElapsedMillis(probe.elapsedMs))
        case None =>
          ZIO.fail(
            new RuntimeException(s"transport failure on $url (unexpected for an HTTPS send)")
          )
    yield ()

  /** Assert a cert-VERIFYING client (the default `http`) is rejected by the imposter's self-signed
    * cert — this is what proves the cert is self-signed, not merely that *some* TLS is spoken. A
    * verifying client aborts the handshake, which the JDK surfaces as an IOException.
    */
  protected def httpsVerificationRejected(
      path: String,
      port: Int
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      url = s"https://$host:$port$path"
      _ <- recordCurl(s"curl $url   # (cert-verifying; expected to be REJECTED)")
      probe <- timedProbeWith(http, url, "GET", Map.empty, None)
      _ <- assertRepro(
        probe.response.isEmpty,
        s"expected the self-signed cert to be REJECTED by a verifying client, but $url answered " +
          s"HTTP ${probe.response.map(_.status).getOrElse("-")}"
      )
    yield ()

  // ── TLS trust (feature 09): imposter mutualAuth (rift#977), outbound --upstream-ca-file (rift#974)

  /** An HTTPS client presenting the committed test client certificate `name`
    * (`rift-fixtures/tls/<name>-cert.pem` + `<name>-key.pem`, a PKCS#8 key), or no certificate for
    * `None`. It trusts any server certificate, like `httpInsecure`: these scenarios are about the
    * CLIENT's certificate, and the imposter serves rift's self-signed default.
    */
  private def mtlsClient(name: Option[String]): IO[Throwable, HttpClient] =
    name match
      case None => ZIO.succeed(httpInsecure)
      case Some(n) =>
        for
          certPem <- loadResource(s"/rift-fixtures/tls/$n-cert.pem")
          keyPem <- loadResource(s"/rift-fixtures/tls/$n-key.pem")
          client <- ZIO.attempt {
            val cert = java.security.cert.CertificateFactory
              .getInstance("X.509")
              .generateCertificate(
                new java.io.ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8))
              )
            val der = java.util.Base64.getMimeDecoder.decode(
              keyPem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
            )
            val key = java.security.KeyFactory
              .getInstance(cert.getPublicKey.getAlgorithm)
              .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der))
            val password = Array.emptyCharArray
            val store = java.security.KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("client", key, password, Array(cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
            kmf.init(store, password)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(
              kmf.getKeyManagers,
              Array[TrustManager](trustAllManager),
              new java.security.SecureRandom()
            )
            HttpClient.newBuilder().sslContext(ctx).build()
          }
        yield client

  /** An HTTPS probe of the current imposter at `path`, presenting client certificate `cert`. */
  private def mtlsProbe(
      method: String,
      path: String,
      cert: Option[String]
  ): ZIO[RiftHarness, Throwable, TransportProbe] =
    for
      port <- currentPort
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port))
      url = s"https://$host:$port$path"
      curlCert = cert.fold("")(n => s" --cert $n-cert.pem --key $n-key.pem")
      _ <- recordCurl(s"curl -k -X $method$curlCert '$url'")
      client <- mtlsClient(cert)
      probe <- timedProbeWith(client, url, method, Map.empty, None)
    yield probe

  private def mtlsSend(
      method: String,
      path: String,
      cert: Option[String]
  ): ZIO[RiftHarness, Throwable, Unit] =
    mtlsProbe(method, path, cert).flatMap(probe =>
      probe.response match
        case Some(r) => Stage.put(r)
        case None =>
          ZIO.fail(
            new RuntimeException(s"mTLS send to $path failed: ${probe.error.getOrElse("-")}")
          )
    )

  /** The handshake is refused, so no HTTP status ever arrives: a response of any status fails. */
  private def mtlsRefused(path: String, cert: Option[String]): ZIO[RiftHarness, Throwable, Unit] =
    mtlsProbe("GET", path, cert).flatMap(probe =>
      assertRepro(
        probe.response.isEmpty,
        s"expected the TLS handshake to be refused (client cert: ${cert.getOrElse("none")}), " +
          s"but got HTTP ${probe.response.map(_.status).getOrElse("-")}"
      )
    )

  When(
    "the client sends " / string / " " / string / " over HTTPS presenting client certificate " / string
  ) { (method: String, path: String, cert: String) =>
    mtlsSend(method, path, Some(cert))
  }

  Then("an HTTPS request to " / string / " presenting no client certificate is refused") {
    (path: String) => mtlsRefused(path, None)
  }

  Then(
    "an HTTPS request to " / string / " presenting client certificate " / string / " is refused"
  ) { (path: String, cert: String) =>
    mtlsRefused(path, Some(cert))
  }

  private def proxyToUpstreamJson: IO[Throwable, String] =
    Stage
      .get[UpstreamPort]
      .mapError(_ => new IllegalStateException("no HTTPS upstream imposter in this scenario"))
      .map(u =>
        // `localhost`, not hostFor: the upstream's leaf is issued for localhost, and this is
        // external-only anyway.
        s"""{"protocol":"http","stubs":[{"responses":[{"proxy":{"to":"https://localhost:${u.port}","mode":"proxyAlways"}}]}]}"""
      )

  Given("an HTTPS upstream imposter from fixture " / string) { (name: String) =>
    postImposterFromFixture(name).flatMap(p => Stage.put(UpstreamPort(p)))
  }

  // Feature 14 (rift#1194): a plain-HTTP upstream in the same engine, for proxy responses.
  Given("an upstream imposter from fixture " / string) { (name: String) =>
    postImposterFromFixture(name).flatMap(p => Stage.put(UpstreamPort(p)))
  }

  private def upstreamPort: IO[Throwable, Int] =
    Stage
      .get[UpstreamPort]
      .mapError(_ => new IllegalStateException("no upstream imposter in this scenario"))
      .map(_.port)

  // The fixture names its proxy target `http://localhost:UPSTREAM_PORT`; the placeholder becomes the
  // scenario's upstream port, which is only known once that imposter is posted.
  Given("a fresh imposter from fixture " / string / " proxying to the upstream") { (name: String) =>
    for
      up <- upstreamPort
      raw <- loadFixture(name).flatMap(rewriteFixtureJson)
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      port <- postAtFreshPort(admin, raw.replace("UPSTREAM_PORT", up.toString), s"fixture $name")
      _ <- track(port) *> Stage.put(CurrentPort(port))
    yield ()
  }

  Then("the upstream imposter has recorded " / int / " requests") { (n: Int) =>
    upstreamPort
      .flatMap(numberOfRequests)
      .flatMap(c =>
        assertRepro(c == n, s"expected the upstream to have received $n request(s), got $c")
      )
  }

  /** rift-lint over the fixture `name`, copied to a temp file first: the fixtures live on the
    * classpath, and rift-lint takes a filesystem path.
    */
  private def lintFixture(name: String): ZIO[RiftHarness, Throwable, LintReport] =
    ZIO.scoped {
      for
        raw <- loadFixture(name)
        tmp <- ZIO.acquireRelease(
          ZIO.attemptBlocking(Files.writeString(Files.createTempFile("rift-lint-", ".json"), raw))
        )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore)
        report <- ZIO.serviceWithZIO[RiftHarness](_.tools.riftLint(tmp))
      yield report
    }

  Then("rift-lint reports " / string / " for fixture " / string) { (code: String, name: String) =>
    lintFixture(name).flatMap(r =>
      assertRepro(
        r.issues.exists(_.code == code),
        s"expected rift-lint to report $code for $name, got ${r.issues.map(_.code)}: ${r.raw}"
      )
    )
  }

  Then("rift-lint reports no " / string / " for fixture " / string) {
    (code: String, name: String) =>
      lintFixture(name).flatMap(r =>
        assertRepro(
          !r.issues.exists(_.code == code) && r.exitCode == 0,
          s"expected rift-lint to report no $code (and exit 0) for $name, got " +
            s"${r.issues.map(_.code)} exit ${r.exitCode}: ${r.raw}"
        )
      )
  }

  When("a proxy imposter to the HTTPS upstream is created") {
    for
      raw <- proxyToUpstreamJson
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      port <- postAtFreshPort(admin, raw, "proxy to the HTTPS upstream")
      _ <- track(port) *> Stage.put(CurrentPort(port))
    yield ()
  }

  // Not tracked: the imposter lives on the upstream-trust engine, which afterScenario kills whole.
  When("a proxy imposter to the HTTPS upstream is created on an engine trusting the test CA") {
    for
      raw <- proxyToUpstreamJson
      admin <- ZIO.serviceWithZIO[RiftHarness](_.startUpstreamTrustEngine)
      port <- postAtFreshPort(admin, raw, "proxy to the HTTPS upstream (upstream-trust engine)")
      _ <- Stage.put(CurrentPort(port))
    yield ()
  }

  // ── metrics (rift#9, feature 09) ───────────────────────────────────────────────────────────

  /** `GET {metricsUrl}/metrics` — the raw Prometheus text exposition. */
  private def fetchMetricsText: ZIO[RiftHarness, Throwable, String] =
    for
      metricsUrl <- ZIO.serviceWith[RiftHarness](_.metricsUrl)
      resp <- httpSend(s"$metricsUrl/metrics", "GET", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"GET $metricsUrl/metrics -> ${resp.status} (expected 200)"
      )
    yield resp.body

  /** Sum the value of every `rift_requests_total{...} <n>` line in a Prometheus text exposition.
    * The metric is registered lazily by the engine — a brand-new engine's `/metrics` has NO
    * `rift_requests_total` line at all until the first data-plane request lands anywhere on it — so
    * absent-entirely reads as a legitimate baseline of 0, not an error. Parse failures on lines
    * that DO exist still fail loudly (a genuinely malformed counter is a real finding).
    */
  private def sumRiftRequestsTotal(text: String): IO[Throwable, Double] =
    // Match the counter series exactly — `rift_requests_total{labels}` or a bare `rift_requests_total `
    // — so a Prometheus-client sibling like `rift_requests_total_created` can never be summed in.
    val lines = text.linesIterator
      .filter(l => l.startsWith("rift_requests_total{") || l.startsWith("rift_requests_total "))
      .toList
    if lines.isEmpty then ZIO.succeed(0.0)
    else
      ZIO
        .attempt(lines.map(l => l.trim.split("\\s+").last.toDouble).sum)
        .mapError(e =>
          new RuntimeException(s"could not parse rift_requests_total value(s): $lines", e)
        )

  protected def requestCounterTotal: ZIO[RiftHarness, Throwable, Long] =
    fetchMetricsText.flatMap(sumRiftRequestsTotal).map(_.toLong)

  // ── spaces + FSM admin ops (rift#9) ───────────────────────────────────────────────────────

  /** POST a new stub straight into a space (spaces CRUD, bypassing the fixture). Builds a minimal
    * `equals path` / `is status+body` stub, matching the shape existing fixtures use.
    */
  protected def registerSpaceStub(
      space: String,
      path: String,
      body: String
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      stub =
        s"""{"predicates":[{"equals":{"path":"${jsonEscape(
            path
          )}"}}],"responses":[{"is":{"statusCode":200,"body":"${jsonEscape(body)}"}}]}"""
      resp <- httpSend(
        s"$admin/imposters/$port/spaces/$space/stubs",
        "POST",
        Map("Content-Type" -> "application/json"),
        Some(stub)
      )
      _ <- assertRepro(
        resp.status == 201,
        s"POST spaces/$space/stubs -> ${resp.status} (expected 201): ${resp.body}"
      )
    yield ()

  /** `GET .../spaces/{space}/stubs` — the `stubs` array's length (spaces CRUD + teardown
    * isolation).
    */
  protected def spaceStubCount(space: String): ZIO[RiftHarness, Throwable, Int] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port/spaces/$space/stubs", "GET", Map.empty, None)
      json <- parseAdminJson(resp.body)
      n <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("stubs", Json.Arr(elems)) => elems.length } match
            case Some(len) => ZIO.succeed(len)
            case None =>
              ZIO.fail(
                new RuntimeException(s"no 'stubs' array in spaces/$space response: ${resp.body}")
              )
        case _ =>
          ZIO.fail(
            new RuntimeException(s"spaces/$space/stubs response is not a JSON object: ${resp.body}")
          )
    yield n

  /** `DELETE .../spaces/{space}` — tear down a space (rift#9 teardown isolation). */
  protected def tearDownSpace(space: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port/spaces/$space", "DELETE", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"DELETE spaces/$space -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  /** `POST .../scenarios/reset` — resets every scenario on the current imposter back to `"Started"`
    * (rift#311 CAS / concurrent-settle).
    */
  protected def resetScenarios: ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/imposters/$port/scenarios/reset",
        "POST",
        Map("Content-Type" -> "application/json"),
        Some("{}")
      )
      _ <- assertRepro(
        resp.status == 200,
        s"POST scenarios/reset -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  /** `PUT .../scenarios/{name}/state` — set a named scenario's state directly (rift#514, #80). On a
    * bare-`scenarioName` stub (no `requiredScenarioState`/`newScenarioState`) this now persists,
    * proving a real flow store is provisioned rather than the pre-0.13.0 silent no-op.
    */
  protected def setScenarioState(name: String, state: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/imposters/$port/scenarios/$name/state",
        "PUT",
        Map("Content-Type" -> "application/json"),
        Some(s"""{"state":"${jsonEscape(state)}"}""")
      )
      _ <- assertRepro(
        resp.status == 200,
        s"PUT scenarios/$name/state -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  /** `GET .../scenarios[?flowId=...]` — the named scenario's `state`, or `None` if that scenario is
    * absent from the array (e.g. a flow that was never touched).
    */
  protected def scenarioState(
      name: String,
      flowId: Option[String]
  ): ZIO[RiftHarness, Throwable, Option[String]] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      qs = flowId.fold("")(f => s"?flowId=$f")
      resp <- httpSend(s"$admin/imposters/$port/scenarios$qs", "GET", Map.empty, None)
      json <- parseAdminJson(resp.body)
      state <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("scenarios", Json.Arr(elems)) => elems } match
            case Some(elems) =>
              ZIO.succeed(elems.collectFirst {
                case Json.Obj(sf)
                    if sf.collectFirst { case ("name", Json.Str(n)) => n }.contains(name) =>
                  sf.collectFirst { case ("state", Json.Str(s)) => s }
              }.flatten)
            case None =>
              ZIO.fail(new RuntimeException(s"no 'scenarios' array in response: ${resp.body}"))
        case _ =>
          ZIO.fail(new RuntimeException(s"scenarios response is not a JSON object: ${resp.body}"))
    yield state

  private def parseAdminJson(body: String): IO[Throwable, Json] =
    ZIO
      .fromEither(body.fromJson[Json])
      .mapError(m => new RuntimeException(s"invalid admin JSON: $m (body=$body)"))

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  // ── admin surface (rift#9, feature 08): stub-by-id, enable/disable, savedRequests ?match=,
  //    flow-state KV, and the atomic PUT /imposters validate-then-swap ─────────────────────

  /** `GET .../stubs/by-id/{id}` — the raw response (200 with the stub, or 404). */
  private def stubById(id: String): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port/stubs/by-id/$id", "GET", Map.empty, None)
    yield resp

  /** `POST .../enable` | `.../disable` on the current imposter. */
  private def setImposterEnabled(enabled: Boolean): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      verb = if enabled then "enable" else "disable"
      resp <- httpSend(s"$admin/imposters/$port/$verb", "POST", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"POST imposters/$port/$verb -> ${resp.status} (expected 200): ${resp.body}"
      )
      // Belt-and-braces: give the engine a moment to flip the enabled bit before the next
      // data-plane probe — mirrors the manual conformance script's `sleep 0.1` after enable/disable.
      _ <- Clock.sleep(100.millis)
    yield ()

  /** `GET .../requests[?match=header:h=v&match=header:h2=v2]` — count of recorded requests (the
    * body is a bare JSON array, or `{requests:[...]}` on some engine versions — handle both).
    */
  private def recordedRequestCount(
      matches: List[(String, String)]
  ): ZIO[RiftHarness, Throwable, Int] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      qs = matches match
        case Nil => ""
        case xs => "?" + xs.map((h, v) => s"match=header:${urlEnc(h)}=${urlEnc(v)}").mkString("&")
      resp <- httpSend(s"$admin/imposters/$port/requests$qs", "GET", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"GET imposters/$port/requests$qs -> ${resp.status} (expected 200): ${resp.body}"
      )
      json <- parseAdminJson(resp.body)
      n <- json match
        case Json.Arr(elems) => ZIO.succeed(elems.length)
        case Json.Obj(fields) =>
          fields.collectFirst { case ("requests", Json.Arr(elems)) => elems.length } match
            case Some(len) => ZIO.succeed(len)
            case None =>
              ZIO.fail(new RuntimeException(s"no 'requests' array in response: ${resp.body}"))
        case _ =>
          ZIO.fail(new RuntimeException(s"/requests response is not an array/object: ${resp.body}"))
    yield n

  /** `DELETE .../requests?match=header:h=v` — deletes only the matching recorded requests. */
  private def deleteRecordedRequests(
      header: String,
      value: String
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/imposters/$port/requests?match=header:${urlEnc(header)}=${urlEnc(value)}",
        "DELETE",
        Map.empty,
        None
      )
      _ <- assertRepro(
        resp.status == 200,
        s"DELETE imposters/$port/requests?match=header:$header=$value -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  private def urlEnc(s: String): String =
    java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** Parsed `POST .../imposters/{port}/verify` result (rift#494): the `{matched, total}` counts
    * plus, when `includeClosest` was requested and nothing matched, the best-scoring non-match —
    * its request path and the number of failed predicate clauses.
    */
  private final case class VerifyResult(
      matched: Int,
      total: Int,
      closestPath: Option[String],
      failedPredicateCount: Int
  )

  /** Server-side verify: the ENGINE evaluates a predicate set against its own recorded requests
    * (rift#494 / PR#512), distinct from the client-side `savedRequests ?match=` count. Drives a
    * single `equals`-on-path predicate — enough to prove the count and the `includeClosest` diff.
    */
  private def serverVerify(
      pathEquals: String,
      includeClosest: Boolean,
      flowId: Option[String] = None
  ): ZIO[RiftHarness, Throwable, VerifyResult] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      closestField = if includeClosest then ""","includeClosest":true""" else ""
      flowField = flowId.fold("")(f => s""","flowId":"${jsonEscape(f)}"""")
      body =
        s"""{"predicates":[{"equals":{"path":"${jsonEscape(
            pathEquals
          )}"}}]$closestField$flowField}"""
      resp <- httpSend(
        s"$admin/imposters/$port/verify",
        "POST",
        Map("Content-Type" -> "application/json"),
        Some(body)
      )
      _ <- assertRepro(
        resp.status == 200,
        s"POST imposters/$port/verify -> ${resp.status} (expected 200): ${resp.body}"
      )
      json <- parseAdminJson(resp.body)
    yield parseVerifyResult(json)

  private def parseVerifyResult(json: Json): VerifyResult =
    val fields = json match
      case Json.Obj(fs) => fs.toMap
      case _ => Map.empty[String, Json]
    def intOf(k: String): Int =
      fields.get(k).collect { case Json.Num(n) => n.intValue }.getOrElse(-1)
    val closest = fields.get("closest").collect { case Json.Obj(fs) => fs.toMap }
    val closestPath = closest
      .flatMap(_.get("request"))
      .collect { case Json.Obj(fs) => fs.toMap }
      .flatMap(_.get("path"))
      .collect { case Json.Str(s) => s }
    val failedCount = closest
      .flatMap(_.get("failedPredicates"))
      .collect { case Json.Arr(es) => es.length }
      .getOrElse(0)
    VerifyResult(intOf("matched"), intOf("total"), closestPath, failedCount)

  /** `PUT .../admin/imposters/{port}/flow-state/{flowId}/{key}` body `{"value":"..."}` — note the
    * `/admin/` prefix, distinct from every other admin-surface path in this trait.
    */
  private def putFlowState(
      flowId: String,
      key: String,
      value: String
  ): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      body = s"""{"value":"${jsonEscape(value)}"}"""
      resp <- httpSend(
        s"$admin/admin/imposters/$port/flow-state/$flowId/$key",
        "PUT",
        Map("Content-Type" -> "application/json"),
        Some(body)
      )
      _ <- assertRepro(
        resp.status == 200,
        s"PUT admin/imposters/$port/flow-state/$flowId/$key -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  /** Raw `GET .../admin/imposters/{port}/flow-state/{flowId}/{key}` response (200 + value, or 404).
    */
  private def getFlowState(flowId: String, key: String): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/admin/imposters/$port/flow-state/$flowId/$key",
        "GET",
        Map.empty,
        None
      )
    yield resp

  private def deleteFlowState(flowId: String, key: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/admin/imposters/$port/flow-state/$flowId/$key",
        "DELETE",
        Map.empty,
        None
      )
      _ <- assertRepro(
        resp.status == 200,
        s"DELETE admin/imposters/$port/flow-state/$flowId/$key -> ${resp.status} (expected 200): ${resp.body}"
      )
    yield ()

  /** The `port`s declared inside a `{"imposters":[...]}` batch — so a `PUT /imposters` replace can
    * track every survivor for `afterScenario` cleanup (the replace itself isn't auto-tracked; it
    * doesn't go through `postImposterFromFixture`).
    */
  private def portsOfBatch(raw: String): Either[String, List[Int]] =
    raw.fromJson[Json].left.map(m => s"batch JSON parse: $m").flatMap {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("imposters", Json.Arr(elems)) => elems } match
          case Some(elems) =>
            Right(
              elems
                .collect { case Json.Obj(f) =>
                  f.collectFirst { case ("port", Json.Num(n)) => n.intValue }
                }
                .flatten
                .toList
            )
          case None => Left("no top-level 'imposters' array")
      case _ => Left("batch root is not a JSON object")
    }

  /** `PUT {admin}/imposters` with a fixture's raw `{"imposters":[...]}` body — atomic
    * validate-then-swap of the WHOLE imposter set. Tracks every surviving port for cleanup.
    */
  private def replaceAllImpostersWithFixture(name: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      raw0 <- loadFixture(name)
      raw <- rewriteFixtureJson(raw0)
      ports <- ZIO.fromEither(portsOfBatch(raw)).mapError(new RuntimeException(_))
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/imposters",
        "PUT",
        Map("Content-Type" -> "application/json"),
        Some(raw)
      )
      _ <- assertRepro(
        resp.status == 200,
        s"PUT /imposters (fixture $name) -> ${resp.status} (expected 200): ${resp.body}"
      )
      _ <- ZIO.foreachDiscard(ports)(track)
    yield ()

  // A response-level `_rift.script` with intentionally broken rhai syntax — the engine's
  // validate-then-swap must reject the WHOLE batch with 400 and leave the previous set untouched.
  private val invalidImposterBatch: String =
    """{"imposters":[{"port":4810,"protocol":"http","stubs":[{"predicates":[{"equals":{"path":"/x"}}],"responses":[{"_rift":{"script":{"engine":"rhai","code":"fn respond(ctx) { broken @#$ syntax"}}}]}]}]}"""

  /** `PUT {admin}/imposters` with a batch containing an invalid response script — must 400 and
    * leave the previous imposter set untouched (validate-then-swap, never partially applied).
    */
  private def replaceAllImpostersInvalid: ZIO[RiftHarness, Throwable, Unit] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(
        s"$admin/imposters",
        "PUT",
        Map("Content-Type" -> "application/json"),
        Some(invalidImposterBatch)
      )
      _ <- assertRepro(
        resp.status == 400,
        s"PUT /imposters (invalid batch) -> ${resp.status} (expected 400): ${resp.body}"
      )
    yield ()

  // ── proxy record/replay (rift#9, feature 10) ────────────────────────────────────────────

  /** `GET {admin}/imposters/{port}` — the `stubs` array's length. Used to observe the Mountebank-
    * style proxy record/replay contract: a `proxyOnce` (or `proxyAlways`) stub carrying
    * `predicateGenerators` PREPENDS a recorded stub after the first data-plane request lands; a
    * `proxyTransparent` stub — or a `proxyOnce` stub with no `predicateGenerators` — never does.
    */
  protected def imposterStubCount(port: Int): ZIO[RiftHarness, Throwable, Int] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port", "GET", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"GET imposters/$port -> ${resp.status} (expected 200): ${resp.body}"
      )
      json <- parseAdminJson(resp.body)
      n <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("stubs", Json.Arr(elems)) => elems.length } match
            case Some(len) => ZIO.succeed(len)
            case None =>
              ZIO.fail(
                new RuntimeException(s"no 'stubs' array in imposters/$port response: ${resp.body}")
              )
        case _ =>
          ZIO.fail(
            new RuntimeException(s"imposters/$port response is not a JSON object: ${resp.body}")
          )
    yield n

  /** Proxy recording is asynchronous relative to the response reaching the client: the recorded
    * stub can land a beat after the response returns. Poll `imposterStubCount` (spaced 100ms, up to
    * ~1.4s) rather than a single fixed sleep, so the common case (the record lands almost
    * immediately) doesn't pay a flat delay, while a genuinely slow engine still gets a fair chance
    * to settle before the assertion reports the count it actually saw.
    */
  private def imposterStubCountSettled(port: Int, expected: Int): ZIO[RiftHarness, Throwable, Int] =
    def loop(remaining: Int): ZIO[RiftHarness, Throwable, Int] =
      imposterStubCount(port).flatMap { n =>
        if n == expected || remaining <= 0 then ZIO.succeed(n)
        else Clock.sleep(100.millis) *> loop(remaining - 1)
      }
    loop(14)

  /** For the NEGATIVE ("records nothing") cases: the poll-until-match `imposterStubCountSettled`
    * short-circuits immediately when the count already equals the expected value, so it can't prove
    * a stub *doesn't* get recorded a beat later. Wait out the full settle window first, THEN read —
    * so a regression that DOES record (async) is actually caught.
    */
  protected def imposterStubCountAfterSettle(port: Int): ZIO[RiftHarness, Throwable, Int] =
    Clock.sleep(1500.millis) *> imposterStubCount(port)

  /** The first stub's `predicates` array, serialized back to compact JSON — for asserting the shape
    * of a proxy-RECORDED predicate (e.g. it should include `"path":"/svc"` and `"method":"GET"`).
    * Falls back to the whole first stub if `predicates` is absent (shouldn't happen for a recorded
    * stub, but keeps the helper honest about what it actually saw rather than throwing).
    */
  private def firstStubPredicatesJson(port: Int): ZIO[RiftHarness, Throwable, String] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port", "GET", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"GET imposters/$port -> ${resp.status} (expected 200): ${resp.body}"
      )
      json <- parseAdminJson(resp.body)
      firstStub: Json <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("stubs", Json.Arr(elems)) => elems } match
            case Some(elems) if elems.nonEmpty => ZIO.succeed(elems.head)
            case Some(_) => ZIO.fail(new RuntimeException(s"imposters/$port has no stubs"))
            case None =>
              ZIO.fail(
                new RuntimeException(s"no 'stubs' array in imposters/$port response: ${resp.body}")
              )
        case _ =>
          ZIO.fail(
            new RuntimeException(s"imposters/$port response is not a JSON object: ${resp.body}")
          )
      predicates: Json = firstStub match
        case Json.Obj(sf) =>
          sf.collectFirst { case ("predicates", p @ Json.Arr(_)) => p }.getOrElse(firstStub)
        case other => other
    yield predicates.toJson

  /** Tear down an imposter mid-scenario (distinct from the automatic `afterScenario` cleanup) —
    * used by the proxyOnce record/replay scenario to prove the recorded stub replays without the
    * upstream still running. A 404 (already gone) is swallowed inside `deleteImposter` itself; any
    * other non-2xx surfaces here as a real failure.
    */
  protected def tearDownImposter(port: Int): ZIO[RiftHarness, Throwable, Unit] =
    ZIO.serviceWithZIO[RiftHarness](h => deleteImposter(h.adminUrl, port))

  // ── steps ────────────────────────────────────────────────────────────────────────────────

  Given("a fresh imposter from fixture " / string) { (name: String) =>
    postImposterFromFixture(name).unit
  }

  // A static corpus file (`corpus/imposters/<name>.json`) served at a fresh pool port rather than its
  // declared 45xx one, so a scenario can drive one corpus imposter on its own.
  Given("a fresh imposter from corpus file " / string) { (name: String) =>
    for
      raw <- ZIO
        .attemptBlocking(Files.readString(corpusImpostersDir.resolve(s"$name.json")))
        .flatMap(rewriteFixtureJson)
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      port <- postAtFreshPort(admin, raw, s"corpus file $name")
      _ <- track(port) *> Stage.put(CurrentPort(port))
    yield ()
  }

  // A raw POST of a fixture expected to be REJECTED at create time: the response is stashed for the
  // status/body steps instead of asserting 201. Tracked like the unresolvable-script door above, so a
  // regression that accepts it cannot squat a pool port.
  When("the client posts the imposter fixture " / string) { (name: String) =>
    for
      raw <- loadFixture(name).flatMap(rewriteFixtureJson)
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      port <- freeProbePort
      _ <- track(port)
      json <- ZIO.fromEither(injectPort(raw, port)).mapError(new RuntimeException(_))
      _ <- recordCurl(
        s"curl -i -X POST $admin/imposters -H 'Content-Type: application/json' -d '$json'"
      )
      resp <- httpSend(
        s"$admin/imposters",
        "POST",
        Map("Content-Type" -> "application/json"),
        Some(json)
      )
      _ <- Stage.put(resp)
    yield ()
  }

  When("the request body is " / string) { (b: String) =>
    pendingRequest.flatMap(pr => Stage.put(pr.copy(body = Some(b))))
  }

  When("the request header " / string / " is " / string) { (h: String, v: String) =>
    pendingRequest.flatMap(pr => Stage.put(pr.copy(headers = pr.headers + (h -> v))))
  }

  When("the client sends " / string / " " / string) { (m: String, p: String) =>
    sendToCurrent(m, p)
  }

  Then("the response status is " / int) { (code: Int) =>
    lastResponse.flatMap(r =>
      assertRepro(r.status == code, s"expected status $code, got ${r.status}")
    )
  }

  Then("the response body is " / string) { (body: String) =>
    lastResponse.flatMap(r =>
      assertRepro(r.body == body, s"expected body '$body', got '${r.body}'")
    )
  }

  Then("the response body contains " / string) { (sub: String) =>
    lastResponse.flatMap(r =>
      assertRepro(r.body.contains(sub), s"expected body to contain '$sub', got '${r.body}'")
    )
  }

  // Admin views (rift#1219): the replayable export vs the detail view differ by which top-level
  // fields they carry, so these assert on the parsed object, not on body substrings (a stub's own
  // body could contain "requests").
  private def lastResponseObject: ZIO[Any, Throwable, Chunk[(String, Json)]] =
    lastResponse.flatMap(r =>
      parseAdminJson(r.body).flatMap {
        case Json.Obj(fields) => ZIO.succeed(fields)
        case _ => ZIO.fail(new RuntimeException(s"response is not a JSON object: ${r.body}"))
      }
    )

  Then("the response JSON has top-level field " / string) { (field: String) =>
    lastResponseObject.flatMap(fields =>
      assertRepro(
        fields.exists(_._1 == field),
        s"expected top-level field '$field', got ${fields.map(_._1).mkString(", ")}"
      )
    )
  }

  Then("the response JSON has no top-level field " / string) { (field: String) =>
    lastResponseObject.flatMap(fields =>
      assertRepro(
        !fields.exists(_._1 == field),
        s"expected no top-level field '$field', got ${fields.map(_._1).mkString(", ")}"
      )
    )
  }

  Then("the response JSON has " / int / " stubs and none carries " / string) {
    (n: Int, field: String) =>
      lastResponseObject.flatMap { fields =>
        val stubs = fields.collectFirst { case ("stubs", Json.Arr(elems)) => elems }
        val carrying = stubs.toList.flatten.count {
          case Json.Obj(sf) => sf.exists(_._1 == field)
          case _ => false
        }
        assertRepro(
          stubs.exists(_.length == n) && carrying == 0,
          s"expected $n stubs with no '$field', got ${stubs.map(_.length)} stubs, $carrying carrying it"
        )
      }
  }

  Then("the response header " / string / " is " / string) { (h: String, v: String) =>
    lastResponse.flatMap { r =>
      val got = r.headers.get(h.toLowerCase).map(_.mkString(","))
      assertRepro(got.contains(v), s"expected header $h=$v, got ${got.getOrElse("<absent>")}")
    }
  }

  // Distinguishes a script *error* from a script *timeout*: both tag x-rift-inject-error /
  // x-rift-decorate-error, but only a timeout also carries x-rift-script-timeout (feature 04, #79).
  Then("the response has no header " / string) { (h: String) =>
    lastResponse.flatMap { r =>
      val got = r.headers.get(h.toLowerCase).map(_.mkString(","))
      assertRepro(got.isEmpty, s"expected no header $h, got ${got.getOrElse("<absent>")}")
    }
  }

  // rift 0.14.0 completed the rift#611 sweep: every error door serves the Mountebank envelope with
  // Content-Type: application/json. Both halves are asserted here because the pre-sweep defect was
  // an UNDECODABLE body — a substring assert on the same body passes either way (feature 13, #118).
  Then("the response is a rift error envelope") {
    for
      r <- lastResponse
      ct = r.headers.get("content-type").map(_.mkString(",")).getOrElse("<absent>")
      _ <- assertRepro(
        ct.toLowerCase.startsWith("application/json"),
        s"expected Content-Type: application/json, got $ct"
      )
      _ <- withEnvelope { entries =>
        assertRepro(
          entries.forall(_.message.nonEmpty),
          s"envelope carries an empty message: '${r.body}'"
        )
      }
    yield ()
  }

  // Pinned per door rather than derived from the status: the four `inject` doors carry a Mountebank
  // slug ("invalid injection", "injection timeout", …) while every other door carries the status as
  // a string, so a blanket code==status assert would be wrong.
  Then("the error envelope code is " / string) { (code: String) =>
    withEnvelope { entries =>
      val got = entries.map(_.code)
      assertRepro(
        got.contains(code),
        s"expected envelope code '$code', got ${got.mkString(", ")}"
      )
    }
  }

  // rift 0.15.0 (rift#797, filed from this harness while writing #118) added `type`: a stable
  // symbolic slug, where `code` is the status on most doors but a Mountebank slug on the inject
  // ones. `type` is what new consumers are told to branch on; asserting BOTH per door is what pins
  // 0.15.0's "additive, not a rewrite" promise.
  Then("the error envelope type is " / string) { (slug: String) =>
    withEnvelope { entries =>
      val got = entries.map(_.slug)
      assertRepro(
        got.contains(Some(slug)),
        s"expected envelope type '$slug', got ${got.map(_.getOrElse("<absent>")).mkString(", ")}"
      )
    }
  }

  // Reads the DECODED JSON string, so this can only pass if a message containing a `"` round-tripped
  // through a well-formed envelope — the rift#611 regression case.
  Then("the error envelope message contains " / string) { (sub: String) =>
    withEnvelope { entries =>
      val messages = entries.map(_.message)
      assertRepro(
        messages.exists(_.contains(sub)),
        s"expected an envelope message containing '$sub', got ${messages.mkString(" | ")}"
      )
    }
  }

  // The negative: doors that deliberately keep a non-JSON reply (the plain-text `Unknown fault`)
  // must NOT be swept into the envelope by a later change.
  Then("the response is not a JSON error envelope") {
    lastResponse.flatMap { r =>
      assertRepro(
        parseErrorEnvelope(r.body).isLeft,
        s"expected a non-envelope reply, but the body parsed as one: '${r.body}'"
      )
    }
  }

  // The over-size door trips before stub matching, so the body only has to exceed the engine's
  // request cap — generated here rather than inlined, since a 10 MiB literal has no place in a
  // feature file.
  When("the request body is " / int / " MiB of " / string) { (mib: Int, ch: String) =>
    pendingRequest.flatMap(pr => Stage.put(pr.copy(body = Some(ch * (mib * 1024 * 1024)))))
  }

  // An unresolvable script reference — a `ref:` naming no `_rift.scripts` entry, or a `file:` that
  // resolves to nothing — is rejected at CREATE time, so this door lives on the admin plane and
  // never reaches an imposter. It needs a raw POST rather than the fixture helper (which asserts
  // 201). `kind` is the script field to poison: "ref" or "file".
  When("the client posts an imposter whose script " / string / " is unresolvable") {
    (kind: String) =>
      for
        admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
        port <- freeProbePort
        // Tracked even though the POST is expected to be REJECTED: if the engine ever accepts it —
        // exactly the regression this scenario guards — an untracked imposter would squat a 48xx
        // pool port for the rest of the run and a later scenario could match against it. Teardown
        // tolerates a 404, so tracking a port that was never bound costs nothing.
        _ <- track(port)
        value = if kind == "file" then "no-such-script.rhai" else "no-such-script"
        json =
          s"""{"port":$port,"protocol":"http","stubs":[{"predicates":[{"equals":{"path":"/x"}}],""" +
            s""""responses":[{"_rift":{"script":{"engine":"rhai","$kind":"$value"}}}]}]}"""
        // Recorded BEFORE the send, like every other request step: a transport-level throw must
        // still leave a paste-ready repro behind.
        _ <- recordCurl(
          s"curl -i -X POST $admin/imposters -H 'Content-Type: application/json' -d '$json'"
        )
        resp <- httpSend(
          s"$admin/imposters",
          "POST",
          Map("Content-Type" -> "application/json"),
          Some(json)
        )
        _ <- Stage.put(resp)
      yield ()
  }

  // The never-2xx-on-failure sweep (#173): a raw admin request whose status is the assertion, so it
  // cannot go through the fixture helpers (which demand 201). `payload` names a raw file under
  // rift-fixtures/ ("-" = no body). Three placeholders, in the path and the payload alike:
  // `{port}` = the scenario's imposter; `{unknownPort}` = a port with no imposter on it;
  // `{newPort}` = a fresh port for a CREATE, which also becomes the current imposter so a pinned
  // gap can drive its data plane. Every probed port is tracked: if a door that should refuse
  // starts creating, the stray imposter is still torn down.
  When(
    "the client sends admin " / string / " " / string / " with payload " / string
  ) { (method: String, pathTemplate: String, payload: String) =>
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      body0 <-
        if payload == "-" then ZIO.none
        else loadResource(s"/rift-fixtures/$payload.json").asSome
      mentions = (p: String) => pathTemplate.contains(p) || body0.exists(_.contains(p))
      port <- if mentions("{port}") then currentPort.asSome else ZIO.none
      unknown <- if mentions("{unknownPort}") then freeProbePort.tap(track).asSome else ZIO.none
      fresh <-
        if mentions("{newPort}") then
          freeProbePort.tap(p => track(p) *> Stage.put(CurrentPort(p))).asSome
        else ZIO.none
      fill = (s: String) =>
        List("{port}" -> port, "{unknownPort}" -> unknown, "{newPort}" -> fresh).foldLeft(s) {
          case (acc, (k, v)) => v.fold(acc)(p => acc.replace(k, p.toString))
        }
      url = s"$admin${fill(pathTemplate)}"
      body = body0.map(fill)
      _ <- recordCurl(
        s"curl -i -X $method $url -H 'Content-Type: application/json'" +
          body.fold("")(b => s" -d '$b'")
      )
      resp <- httpSend(url, method, Map("Content-Type" -> "application/json"), body)
      _ <- Stage.put(resp)
    yield ()
  }

  // Flow-store backend parity (#174, feature 15): the SAME fixture posted with its
  // `_rift.flowState.backend` forced to `inmemory` or `redis`. A Redis imposter gets a key prefix of
  // its own, so scenarios sharing the one container never read each other's state, and the prefix is
  // staged for the step that proves the state really landed in Redis.
  Given("a fresh " / string / " flow-store imposter from fixture " / string) {
    (backend: String, name: String) =>
      for
        raw <- loadFixture(name).flatMap(rewriteFixtureJson)
        store <- backend match
          case "inmemory" => ZIO.succeed(Json.Obj("backend" -> Json.Str("inmemory")) -> None)
          case "redis" =>
            RedisFlowStore.url.map { url =>
              val prefix = s"rc-${java.util.UUID.randomUUID().toString.take(8)}:"
              Json.Obj(
                "backend" -> Json.Str("redis"),
                "redis" -> Json.Obj("url" -> Json.Str(url), "keyPrefix" -> Json.Str(prefix))
              ) -> Some(prefix)
            }
          case other =>
            ZIO.fail(new IllegalArgumentException(s"unknown flow-store backend '$other'"))
        json <- ZIO.fromEither(withFlowStore(raw, store._1)).mapError(new RuntimeException(_))
        admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
        port <- postAtFreshPort(admin, json, s"fixture $name on $backend")
        _ <- track(port) *> Stage.put(CurrentPort(port))
        _ <- ZIO.foreachDiscard(store._2)(p => Stage.put(RedisKeyPrefix(p)))
      yield ()
  }

  // Parity alone cannot tell a working Redis store from one that silently fell back to in-memory —
  // both would answer identically. This reads the key straight out of the container.
  Then("the Redis flow store holds key " / string / " for this imposter") { (suffix: String) =>
    for
      prefix <- Stage
        .get[RedisKeyPrefix]
        .mapError(e => new IllegalStateException(s"no Redis imposter posted yet ($e)"))
      port <- currentPort
      expected = s"${prefix.prefix}flow:$port:$suffix"
      found <- RedisFlowStore.keys(s"${prefix.prefix}*")
      _ <- assertRepro(
        found.contains(expected),
        s"expected Redis key '$expected', found ${found.mkString("[", ", ", "]")}"
      )
    yield ()
  }

  Given("a fresh verbatim imposter from fixture " / string) { (name: String) =>
    postImposterVerbatimFixture(name).unit
  }

  When("the client sends " / string / " " / string / " via the gateway") { (m: String, p: String) =>
    sendViaGateway(m, p)
  }

  When("the client sends " / string / " " / string / " to port " / int) {
    (m: String, p: String, port: Int) => sendToPort(m, p, port)
  }

  Then("the response has " / int / " " / string / " headers") { (n: Int, h: String) =>
    lastResponse.flatMap { r =>
      val cnt = r.headers.get(h.toLowerCase).map(_.size).getOrElse(0)
      assertRepro(cnt == n, s"expected $n '$h' header(s), got $cnt")
    }
  }

  Then("the response body matches " / string) { (regex: String) =>
    for
      r <- lastResponse
      rx <- ZIO
        .attempt(regex.r)
        .mapError(e => new RuntimeException(s"bad regex /$regex/: ${e.getMessage}"))
      _ <- assertRepro(rx.findFirstIn(r.body).isDefined, s"body '${r.body}' did not match /$regex/")
    yield ()
  }

  // Verifies a date TEMPLATE rendered the correct OFFSET (not just "some ISO date"): the expected
  // value is computed from wall-clock at assertion time, so it never goes stale, and it fails only
  // if the engine's date arithmetic is actually wrong.
  Then("the response body is a date offset by " / int / " " / string / " from today") {
    (offset: Int, unit: String) =>
      for
        r <- lastResponse
        got <- ZIO
          .attempt(java.time.OffsetDateTime.parse(r.body.trim).toLocalDate)
          .mapError(e =>
            new RuntimeException(s"body '${r.body}' is not an ISO date-time: ${e.getMessage}")
          )
        today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        want <- unit match
          case "days" => ZIO.succeed(today.plusDays(offset.toLong))
          case "months" => ZIO.succeed(today.plusMonths(offset.toLong))
          case other => ZIO.fail(new RuntimeException(s"unknown date unit '$other' (days|months)"))
        _ <- assertRepro(got == want, s"expected a date $offset $unit from today ($want), got $got")
      yield ()
  }

  When("the client sends " / string / " " / string / " expecting a transport error") {
    (m: String, p: String) => sendExpectingTransportError(m, p)
  }

  Then("the request fails at the transport level") {
    for
      o <- transportOutcome
      got <- lastResponse.option
      _ <- assertRepro(
        o.failed,
        s"expected a transport-level failure${got.fold("")(r => s", but got HTTP ${r.status}")}"
      )
    yield ()
  }

  Then("the response took at least " / int / " ms") { (floor: Int) =>
    elapsedMs.flatMap(ms =>
      assertRepro(ms >= floor, s"expected the request to take >= $floor ms, took $ms ms")
    )
  }

  // rift#1199: `GET /imposters/:port` writes a multi-item `_behaviors.copy` back as a `behaviors`
  // array holding one `{"copy": {...}}` element per item, the shape Mountebank itself loads.
  Then(
    "the current imposter writes stub " / int / " back with " / int / " single-item copy behaviors"
  ) { (index: Int, n: Int) =>
    for
      port <- currentPort
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port", "GET", Map.empty, None)
      json <- parseAdminJson(resp.body)
      behaviors = for
        stubs <- field(json, "stubs").collect { case Json.Arr(s) => s }
        stub <- stubs.lift(index)
        responses <- field(stub, "responses").collect { case Json.Arr(r) => r }
        first <- responses.headOption
        b <- field(first, "behaviors").collect { case Json.Arr(b) => b.toList }
      yield b
      singleCopies = behaviors.toList.flatten.count {
        case Json.Obj(fs) =>
          fs.map(_._1).toList == List("copy") && field(Json.Obj(fs), "copy")
            .exists(_.isInstanceOf[Json.Obj])
        case _ => false
      }
      _ <- assertRepro(
        behaviors.exists(_.length == n) && singleCopies == n,
        s"expected stub $index written back as $n single-item copy behaviors, got " +
          behaviors.fold("<no behaviors array>")(_.map(_.toJson).mkString("[", ",", "]"))
      )
    yield ()
  }

  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fs) => fs.collectFirst { case (`name`, v) => v }
    case _ => None

  Then("the response took at most " / int / " ms") { (ceil: Int) =>
    elapsedMs.flatMap(ms =>
      assertRepro(ms <= ceil, s"expected the request to take <= $ceil ms, took $ms ms")
    )
  }

  When("the client fires " / int / " concurrent requests to " / string) { (n: Int, p: String) =>
    sendConcurrent(n, p)
  }

  Then("all concurrent responses have status " / int) { (code: Int) =>
    concurrentBurst.flatMap { b =>
      b.fiber.join.flatMap { probes =>
        val statuses = probes.flatMap(_.response.map(_.status))
        assertRepro(
          statuses.size == b.requested && statuses.forall(_ == code),
          s"expected all ${b.requested} responses to be $code, got $statuses"
        )
      }
    }
  }

  Then("the engine remains responsive") {
    ZIO.serviceWithZIO[RiftHarness] { h =>
      ZIO
        .attemptBlocking {
          val req = HttpRequest.newBuilder(URI.create(s"${h.adminUrl}/imposters")).GET().build()
          http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
        .flatMap(code =>
          assertRepro(code == 200, s"admin API not responsive: GET /imposters -> $code")
        )
    }
  }

  When(
    "the client registers a stub in space " / string / " for path " / string / " returning " / string
  )((space: String, path: String, body: String) => registerSpaceStub(space, path, body))

  Then("space " / string / " has " / int / " stubs") { (space: String, n: Int) =>
    spaceStubCount(space).flatMap(c =>
      assertRepro(c == n, s"expected space '$space' to have $n stub(s), got $c")
    )
  }

  When("the client tears down space " / string)((space: String) => tearDownSpace(space))

  Then("scenario " / string / " is in state " / string) { (name: String, state: String) =>
    scenarioState(name, None).flatMap(s =>
      assertRepro(s.contains(state), s"expected scenario '$name' state '$state', got $s")
    )
  }

  Then("scenario " / string / " in space " / string / " is in state " / string) {
    (name: String, space: String, state: String) =>
      scenarioState(name, Some(space)).flatMap(s =>
        assertRepro(
          s.contains(state),
          s"expected scenario '$name' in space '$space' state '$state', got $s"
        )
      )
  }

  Then("scenario " / string / " in space " / string / " is untouched") {
    (name: String, space: String) =>
      scenarioState(name, Some(space)).flatMap(s =>
        assertRepro(
          s.isEmpty || s.contains("Started"),
          s"expected scenario '$name' in space '$space' to be untouched (absent or 'Started'), got $s"
        )
      )
  }

  When("the client resets scenarios") {
    resetScenarios
  }

  // Real-time wait — for observing time-bounded state (e.g. a `flowState.ttlSeconds` key expiring, #85).
  When("the client waits " / int / " ms") { (ms: Int) =>
    Clock.sleep(ms.millis)
  }

  When("the client sets scenario " / string / " state to " / string) {
    (name: String, state: String) =>
      setScenarioState(name, state)
  }

  // ── admin surface steps (rift#9, feature 08) ────────────────────────────────────────────

  Then("the stub with id " / string / " is retrievable") { (id: String) =>
    stubById(id).flatMap(resp =>
      assertRepro(
        resp.status == 200,
        s"stubs/by-id/$id -> ${resp.status} (expected 200): ${resp.body}"
      )
    )
  }

  Then("the stub with id " / string / " includes " / string) { (id: String, substr: String) =>
    stubById(id).flatMap(resp =>
      assertRepro(
        resp.status == 200 && resp.body.contains(substr),
        s"stubs/by-id/$id (status ${resp.status}) did not include '$substr': ${resp.body}"
      )
    )
  }

  Then("no stub has id " / string) { (id: String) =>
    stubById(id).flatMap(resp =>
      assertRepro(
        resp.status == 404,
        s"stubs/by-id/$id -> ${resp.status} (expected 404): ${resp.body}"
      )
    )
  }

  When("the client disables the imposter")(setImposterEnabled(false))
  When("the client enables the imposter")(setImposterEnabled(true))

  Then("recorded requests total " / int) { (n: Int) =>
    recordedRequestCount(Nil).flatMap(c =>
      assertRepro(c == n, s"expected $n recorded request(s), got $c")
    )
  }

  Then("recorded requests matching header " / string / " value " / string / " total " / int) {
    (h: String, v: String, n: Int) =>
      recordedRequestCount(List(h -> v)).flatMap(c =>
        assertRepro(c == n, s"expected $n request(s) matching header $h=$v, got $c")
      )
  }

  Then(
    "recorded requests matching header " / string / " value " / string / " and header " / string / " value " / string / " total " / int
  ) { (h1: String, v1: String, h2: String, v2: String, n: Int) =>
    recordedRequestCount(List(h1 -> v1, h2 -> v2)).flatMap(c =>
      assertRepro(c == n, s"expected $n request(s) matching header $h1=$v1 and $h2=$v2, got $c")
    )
  }

  When("the client deletes recorded requests matching header " / string / " value " / string) {
    (h: String, v: String) => deleteRecordedRequests(h, v)
  }

  Then("a verify for path " / string / " matches " / int / " of " / int / " recorded requests") {
    (path: String, matched: Int, total: Int) =>
      serverVerify(path, includeClosest = false).flatMap(r =>
        assertRepro(
          r.matched == matched && r.total == total,
          s"verify path=$path: expected matched=$matched total=$total, got matched=${r.matched} total=${r.total}"
        )
      )
  }

  Then(
    "a verify for path " / string / " in flow " / string / " matches " / int / " of " / int /
      " recorded requests"
  ) { (path: String, flowId: String, matched: Int, total: Int) =>
    serverVerify(path, includeClosest = false, flowId = Some(flowId)).flatMap(r =>
      assertRepro(
        r.matched == matched && r.total == total,
        s"verify path=$path flowId=$flowId: expected matched=$matched total=$total, " +
          s"got matched=${r.matched} total=${r.total}"
      )
    )
  }

  Then(
    "a verify for path " / string / " with includeClosest reports the closest request path " / string
  ) { (path: String, closestPath: String) =>
    serverVerify(path, includeClosest = true).flatMap(r =>
      assertRepro(
        r.matched == 0 && r.closestPath.contains(closestPath) && r.failedPredicateCount > 0,
        s"verify path=$path includeClosest: expected matched=0, closest path=$closestPath, " +
          s"failedPredicates>0; got matched=${r.matched}, closest=${r.closestPath}, " +
          s"failed=${r.failedPredicateCount}"
      )
    )
  }

  When("the client sets flow-state " / string / " in flow " / string / " to " / string) {
    (key: String, flow: String, value: String) => putFlowState(flow, key, value)
  }

  Then("flow-state " / string / " in flow " / string / " is " / string) {
    (key: String, flow: String, value: String) =>
      for
        resp <- getFlowState(flow, key)
        _ <- assertRepro(
          resp.status == 200,
          s"GET flow-state $flow/$key -> ${resp.status} (expected 200): ${resp.body}"
        )
        json <- parseAdminJson(resp.body)
        got = json match
          case Json.Obj(fields) => fields.collectFirst { case ("value", Json.Str(s)) => s }
          case _ => None
        _ <- assertRepro(
          got.contains(value),
          s"expected flow-state $flow/$key = '$value', got $got (body=${resp.body})"
        )
      yield ()
  }

  When("the client deletes flow-state " / string / " in flow " / string) {
    (key: String, flow: String) => deleteFlowState(flow, key)
  }

  Then("flow-state " / string / " in flow " / string / " is absent") {
    (key: String, flow: String) =>
      getFlowState(flow, key).flatMap(resp =>
        assertRepro(
          resp.status == 404,
          s"expected 404 for flow-state $flow/$key, got ${resp.status}: ${resp.body}"
        )
      )
  }

  When("the client replaces all imposters with fixture " / string) { (name: String) =>
    replaceAllImpostersWithFixture(name)
  }

  When("the client replaces all imposters with an invalid batch") {
    replaceAllImpostersInvalid
  }

  Then("the imposter on port " / int / " no longer serves") { (port: Int) =>
    portAnswers(port).flatMap(up =>
      assertRepro(!up, s"expected port $port to be gone after the replace, but it still answers")
    )
  }

  // ── HTTPS + metrics (rift#9, feature 09) ────────────────────────────────────────────────

  When("the client sends " / string / " " / string / " over HTTPS to port " / int) {
    (m: String, p: String, port: Int) => sendHttpsToPort(m, p, port)
  }

  Then("a cert-verifying HTTPS request to port " / int / " at " / string / " is rejected") {
    (port: Int, path: String) => httpsVerificationRejected(path, port)
  }

  Then("the metrics endpoint reports at least " / int / " requests") { (n: Int) =>
    requestCounterTotal.flatMap(total =>
      assertRepro(total >= n, s"expected metrics rift_requests_total sum >= $n, got $total")
    )
  }

  When("the client records the request counter") {
    requestCounterTotal.flatMap(total => Stage.put(RequestCounter(total)))
  }

  Then("the request counter increased by at least " / int) { (n: Int) =>
    for
      before <- Stage
        .get[RequestCounter]
        .mapError(e => new IllegalStateException(s"no recorded request counter ($e)"))
      after <- requestCounterTotal
      delta = after - before.total
      _ <- assertRepro(
        delta >= n,
        s"expected the request counter to increase by >= $n, got delta=$delta (before=${before.total}, after=$after)"
      )
    yield ()
  }

  // ── proxy record/replay steps (rift#9, feature 10) ──────────────────────────────────────

  Then("the imposter on port " / int / " has " / int / " stubs") { (port: Int, n: Int) =>
    imposterStubCountSettled(port, n).flatMap(c =>
      assertRepro(c == n, s"expected imposter on port $port to have $n stub(s), got $c")
    )
  }

  When("the client tears down the imposter on port " / int) { (port: Int) =>
    tearDownImposter(port)
  }

  Then("the imposter on port " / int / " first recorded predicate includes " / string) {
    (port: Int, substr: String) =>
      firstStubPredicatesJson(port).flatMap(json =>
        assertRepro(
          json.contains(substr),
          s"expected imposter $port's first stub predicates to include '$substr', got: $json"
        )
      )
  }

  Then("the imposter on port " / int / " first recorded predicate excludes " / string) {
    (port: Int, substr: String) =>
      firstStubPredicatesJson(port).flatMap(json =>
        assertRepro(
          !json.contains(substr),
          s"expected imposter $port's first stub predicates to EXCLUDE '$substr', got: $json"
        )
      )
  }

  Then("the imposter on port " / int / " still has " / int / " stubs after recording settles") {
    (port: Int, n: Int) =>
      imposterStubCountAfterSettle(port).flatMap(c =>
        assertRepro(c == n, s"expected imposter on port $port to STILL have $n stub(s), got $c")
      )
  }

  // ── reload (rift#316, feature 11) ────────────────────────────────────────────────────────

  // A controlled 2-imposter configfile: one records requests (runtime state that must survive an
  // incremental reload), one is static — mirrors scripts/conformance-reload.sh exactly.
  private val reloadConfigJson: String =
    """{"imposters":[""" +
      """{"port":4870,"protocol":"http","recordRequests":true,"stubs":[""" +
      """{"predicates":[{"equals":{"path":"/a"}}],""" +
      """"responses":[{"is":{"statusCode":200,"body":"A"}}]}]},""" +
      """{"port":4871,"protocol":"http","stubs":[""" +
      """{"predicates":[{"equals":{"path":"/b"}}],""" +
      """"responses":[{"is":{"statusCode":200,"body":"B"}}]}]}""" +
      """]}"""

  /** `GET {admin}/imposters/{port}` -> `numberOfRequests` — unlike `recordedRequestCount` (which
    * reads the scenario's "current" imposter via `Stage[CurrentPort]`), the reload configfile's
    * imposters are never posted through `postImposterFromFixture`, so they target an explicit port.
    */
  protected def numberOfRequests(port: Int): ZIO[RiftHarness, Throwable, Int] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/imposters/$port", "GET", Map.empty, None)
      _ <- assertRepro(
        resp.status == 200,
        s"GET imposters/$port -> ${resp.status} (expected 200): ${resp.body}"
      )
      json <- parseAdminJson(resp.body)
      n <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("numberOfRequests", Json.Num(v)) => v.intValue } match
            case Some(v) => ZIO.succeed(v)
            case None =>
              ZIO.fail(new RuntimeException(s"no 'numberOfRequests' field: ${resp.body}"))
        case _ =>
          ZIO.fail(
            new RuntimeException(s"imposters/$port response is not a JSON object: ${resp.body}")
          )
    yield n

  /** `POST {admin}/admin/reload` (no body) — stashes the change-report response as the scenario's
    * `lastResponse` so the existing "the response body is"/status steps and the report-shape
    * asserts below can all read it the same way.
    */
  private def reload: ZIO[RiftHarness, Throwable, Unit] =
    attemptReload.flatMap(resp =>
      assertRepro(
        resp.status == 200,
        s"POST admin/reload -> ${resp.status} (expected 200): ${resp.body}"
      )
    )

  /** `POST {admin}/admin/reload` whatever its outcome, stashed as `lastResponse`. */
  private def attemptReload: ZIO[RiftHarness, Throwable, LastResponse] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      resp <- httpSend(s"$admin/admin/reload", "POST", Map.empty, None)
      _ <- Stage.put(resp)
    yield resp

  /** The feature-11 `@front-door` config (rift#1160): two imposters answering `A` (4872) and `B`
    * (4873) plus a `routes` block holding `routes` (already-rendered route objects).
    */
  private def frontDoorConfigJson(routes: String*): String =
    """{"imposters":[""" +
      """{"port":4872,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"body":"A"}}]}]},""" +
      """{"port":4873,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"body":"B"}}]}]}""" +
      s"""],"routes":{"routes":[${routes.mkString(",")}]}}"""

  private def hostRoute(id: String, host: String, port: Int): String =
    s"""{"id":"$id","match":{"host":"$host"},"target":{"port":$port}}"""

  private def frontDoorPort: IO[Throwable, Int] =
    Stage
      .get[FrontDoorPort]
      .mapError(_ => new IllegalStateException("no front-door engine started in this scenario"))
      .map(_.port)

  Given(
    "the engine is started with the front door and a route from host " / string / " to port " / int
  ) { (host: String, port: Int) =>
    // Flag the revert BEFORE the swap: a start that fails half-way must still be reverted.
    Stage.put(ReloadActive(true)) *>
      ZIO
        .serviceWithZIO[RiftHarness](
          _.startReloadConfigWithFrontDoor(frontDoorConfigJson(hostRoute("r", host, port)))
        )
        .flatMap(p => Stage.put(FrontDoorPort(p)))
  }

  Given(
    "the engine is started without the front door and a route from host " / string / " to port " / int
  ) { (host: String, port: Int) =>
    Stage.put(ReloadActive(true)) *>
      ZIO.serviceWithZIO[RiftHarness](
        _.startReloadConfig(frontDoorConfigJson(hostRoute("r", host, port)))
      )
  }

  When("the reload config file routes host " / string / " to port " / int) {
    (host: String, port: Int) =>
      ZIO.serviceWithZIO[RiftHarness](
        _.rewriteReloadConfig(frontDoorConfigJson(hostRoute("r", host, port)))
      )
  }

  When("the reload config file declares two routes with the same id") {
    ZIO.serviceWithZIO[RiftHarness](
      _.rewriteReloadConfig(
        frontDoorConfigJson(hostRoute("dup", "c.test", 4872), hostRoute("dup", "d.test", 4873))
      )
    )
  }

  When("the engine reload is attempted") {
    attemptReload.unit
  }

  Then("the reload is refused naming " / string) { (reason: String) =>
    lastResponse.flatMap(r =>
      assertRepro(
        r.status >= 400 && r.body.contains(reason),
        s"expected the reload refused (status >= 400) naming '$reason', got ${r.status}: ${r.body}"
      )
    )
  }

  Then("the reload report warns about " / string) { (text: String) =>
    for
      r <- lastResponse
      json <- parseAdminJson(r.body)
      warnings = json match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("warnings", Json.Arr(ws)) =>
            ws.collect { case Json.Str(w) => w }.toList
          }
        case _ => None
      _ <- assertRepro(
        warnings.exists(_.exists(_.contains(text))),
        s"expected a reload warning naming '$text', got ${warnings.getOrElse("<no warnings>")}"
      )
    yield ()
  }

  When(
    "the client sends " / string / " " / string / " through the front door with host " / string
  ) { (method: String, path: String, host: String) =>
    for
      port <- frontDoorPort
      url = s"http://localhost:$port$path"
      _ <- recordCurl(s"curl -s -i -X $method -H 'Host: $host' '$url'")
      resp <- ZIO.attemptBlocking(
        RiftConformanceSupport.sendOversize(url, method, Map.empty, "", Some(host))
      )
      _ <- Stage.put(resp)
    yield ()
  }

  /** The length of the named top-level array (`created`/`deleted`/`replaced`/`stubPatched`) in the
    * last reload change-report response.
    */
  private def reloadReportArrayLength(field: String): ZIO[Any, Throwable, Int] =
    for
      r <- lastResponse
      json <- parseAdminJson(r.body)
      n <- json match
        case Json.Obj(fields) =>
          fields.collectFirst { case (`field`, Json.Arr(elems)) => elems.length } match
            case Some(len) => ZIO.succeed(len)
            case None =>
              ZIO.fail(new RuntimeException(s"no '$field' array in reload report: ${r.body}"))
        case _ =>
          ZIO.fail(new RuntimeException(s"reload report is not a JSON object: ${r.body}"))
    yield n

  Given("the engine is started with the reload config file") {
    ZIO.serviceWithZIO[RiftHarness](_.startReloadConfig(reloadConfigJson)) *>
      Stage.put(ReloadActive(true))
  }

  Then("imposter " / int / " has recorded " / int / " requests") { (port: Int, n: Int) =>
    numberOfRequests(port).flatMap(c =>
      assertRepro(c == n, s"expected imposter $port to have recorded $n request(s), got $c")
    )
  }

  When("the engine reloads its configuration") {
    reload
  }

  Then("the reload report " / string / " " / int / " imposters") { (field: String, n: Int) =>
    reloadReportArrayLength(field).flatMap(c =>
      assertRepro(c == n, s"expected reload report '$field' to have length $n, got $c")
    )
  }

  Then("the reload report exposes all four change fields") {
    for
      r <- lastResponse
      json <- parseAdminJson(r.body)
      keys = json match
        case Json.Obj(fields) => fields.map(_._1).toSet
        case _ => Set.empty[String]
      required = Set("created", "replaced", "stubPatched", "deleted")
      _ <- assertRepro(
        required.subsetOf(keys),
        s"expected reload report to expose $required, got keys=$keys (body=${r.body})"
      )
    yield ()
  }
}
