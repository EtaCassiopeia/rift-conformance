package samples.rift.support

import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.step.{Stage, ZIOSteps}
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}

import java.net.{InetSocketAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

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

  // ── admin API + imposter I/O (direct, no MockControl) ────────────────────────────────────

  /** Load a fixture imposter, assign it a fresh 48xx port, POST it to the admin API, and track it.
    */
  protected def postImposterFromFixture(name: String): ZIO[RiftHarness, Throwable, Int] =
    loadFixture(name).flatMap { raw =>
      // Pick a port and POST it; a non-201 usually means the freshly-probed port was taken between
      // the probe and the engine's bind (TOCTOU) — re-probe a new port and retry a few times.
      val attempt =
        for
          port <- freeProbePort
          body <- ZIO.fromEither(injectPort(raw, port)).mapError(new RuntimeException(_))
          admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
          code <- postImposter(admin, body)
          _ <- ZIO
            .fail(
              new RuntimeException(s"POST imposter (fixture $name) returned $code, expected 201")
            )
            .when(code != 201)
        yield port
      attempt.retry(Schedule.recurs(3)).flatMap { port =>
        track(port) *> Stage.put(CurrentPort(port)).as(port)
      }
    }

  protected def sendToCurrent(method: String, path: String): ZIO[RiftHarness, Throwable, Unit] =
    for
      port <- currentPort
      host <- ZIO.serviceWith[RiftHarness](_.hostFor(port)) // the harness's port→host seam
      pend <- pendingRequest
      url = s"http://$host:$port$path"
      _ <- recordCurl(curlOf(method, url, pend))
      probe <- timedProbe(url, method, pend.headers, pend.body)
      _ <- probe.response match
        case Some(r) => Stage.put(r) *> Stage.put(ElapsedMillis(probe.elapsedMs))
        case None =>
          ZIO.fail(new RuntimeException(s"transport failure on $url (unexpected for a plain send)"))
    yield ()

  /** Send expecting a transport-level failure (fault scenarios): capture whether it failed, the
    * elapsed time (latency still applies), and any HTTP response (the top-level-fault @gap returns
    * a 502 rather than a reset).
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
    ZIO.attemptBlocking {
      val resource = s"/rift-fixtures/$name.json"
      val is = getClass.getResourceAsStream(resource)
      if is == null then throw new RuntimeException(s"fixture not found on classpath: $resource")
      try new String(is.readAllBytes(), StandardCharsets.UTF_8)
      finally is.close()
    }

  // ── corpus (the numbered, byte-fidelity imposters in rift-imposter-samples) ───────────────

  /** The corpus imposters directory: `$RIFT_CORPUS_ROOT/imposters` (default matches the harness's).
    */
  protected def corpusImpostersDir: Path =
    val root = Option(java.lang.System.getenv("RIFT_CORPUS_ROOT"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("../rift-imposter-samples")
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
          raw <- ZIO.attemptBlocking(Files.readString(file))
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

  private def injectPort(raw: String, port: Int): Either[String, String] =
    raw.fromJson[Json].left.map(m => s"fixture JSON parse: $m").flatMap {
      case Json.Obj(fields) =>
        val kept = fields.filterNot(_._1 == "port")
        Right(Json.Obj(kept :+ ("port" -> Json.Num(BigDecimal(port)))).toJson)
      case _ => Left("fixture root is not a JSON object")
    }

  private def freeProbePort: IO[Throwable, Int] =
    ZIO.attemptBlocking {
      (ProbePortLo to ProbePortHi)
        .find { p =>
          try { new ServerSocket(p).close(); true }
          catch { case _: Throwable => false }
        }
        .getOrElse(throw new RuntimeException(s"no free probe port in $ProbePortLo..$ProbePortHi"))
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
    val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
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
  ): UIO[TransportProbe] =
    ZIO.attemptBlocking {
      val t0 = java.lang.System.nanoTime()
      def elapsed = (java.lang.System.nanoTime() - t0) / 1000000L
      try
        TransportProbe(
          Some(blockingSend(url, method, headers, body)),
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
      raw <- loadFixture(name)
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

  // ── steps ────────────────────────────────────────────────────────────────────────────────

  Given("a fresh imposter from fixture " / string) { (name: String) =>
    postImposterFromFixture(name).unit
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

  Then("the response header " / string / " is " / string) { (h: String, v: String) =>
    lastResponse.flatMap { r =>
      val got = r.headers.get(h.toLowerCase).map(_.mkString(","))
      assertRepro(got.contains(v), s"expected header $h=$v, got ${got.getOrElse("<absent>")}")
    }
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
}
