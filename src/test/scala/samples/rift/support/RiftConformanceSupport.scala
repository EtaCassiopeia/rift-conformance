package samples.rift.support

import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.step.{Stage, ZIOSteps}
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
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
  private val ProbePortLo = 4800
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
      resp <- httpSend(url, method, pend.headers, pend.body)
      _ <- Stage.put(resp)
    yield ()

  private def loadFixture(name: String): IO[Throwable, String] =
    ZIO.attemptBlocking {
      val resource = s"/rift-fixtures/$name.json"
      val is = getClass.getResourceAsStream(resource)
      if is == null then throw new RuntimeException(s"fixture not found on classpath: $resource")
      try new String(is.readAllBytes(), StandardCharsets.UTF_8)
      finally is.close()
    }

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
    ZIO.attemptBlocking {
      val publisher =
        body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
      var builder = HttpRequest.newBuilder(URI.create(url)).method(method.toUpperCase, publisher)
      headers.foreach((k, v) => builder = builder.header(k, v))
      val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      // HTTP header names are case-insensitive; key by lowercase so lookups are too.
      val hs = resp.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
      LastResponse(resp.statusCode, resp.body, hs)
    }

  private def curlOf(method: String, url: String, pend: PendingRequest): String =
    val hs = pend.headers.map((k, v) => s"-H '$k: $v'").mkString(" ")
    val d = pend.body.map(b => s"-d '$b'").getOrElse("")
    s"curl -X ${method.toUpperCase} $hs $d '$url'".replaceAll(" +", " ").trim

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
}
