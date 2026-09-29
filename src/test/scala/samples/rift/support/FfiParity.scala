package samples.rift.support

import samples.rift.support.EmbeddedAdmin.Admin
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Shared plumbing for the embedded FFI ↔ admin-HTTP parity specs (#75, #92, #94): one in-process
  * engine per spec whose admin HTTP API and FFI handle drive the SAME engine, an HTTP client for
  * the admin and data planes, and JSON helpers that compare parsed values order-insensitively.
  *
  * Self-gating: [[engine]] is `None` unless `RIFT_MODE=embedded` AND a `librift_ffi` resolves for
  * this JVM (JDK 22+), and [[withEngine]] then passes, so the specs no-op on every other leg.
  */
object FfiParity:

  private val client = HttpClient.newHttpClient()

  final case class Resp(status: Int, body: String)

  def http(
      method: String,
      url: String,
      body: Option[String] = None,
      headers: Map[String, String] = Map.empty
  ): Task[Resp] =
    ZIO.attemptBlocking {
      val publisher =
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
      headers.foreach((k, v) => builder.header(k, v))
      val req = builder.method(method, publisher).build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      Resp(resp.statusCode, resp.body)
    }

  /** An HTTP call that must answer `expected`; its body parsed as JSON (an empty body is `null`).
    */
  def httpJson(
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
  def norm(j: Json): Json = j match
    case Json.Obj(fields) =>
      Json.Obj(fields.filterNot(_._1 == "_links").map((k, v) => k -> norm(v)).sortBy(_._1))
    case Json.Arr(elems) => Json.Arr(elems.map(norm))
    case other => other

  def field(j: Json, name: String): Json = j match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, v) => v }.getOrElse(Json.Null)
    case _ => Json.Null

  def arr(j: Json): Chunk[Json] = j match
    case Json.Arr(elems) => elems
    case _ => Chunk.empty

  def str(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _ => None

  def fieldNames(j: Json): Set[String] = j match
    case Json.Obj(fields) => fields.map(_._1).toSet
    case _ => Set.empty

  def freePort: Task[Int] =
    ZIO.attemptBlocking {
      val s = new ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  def stub(id: String, path: String, body: String): Json =
    Json.Obj(
      "id" -> Json.Str(id),
      "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str(path)))),
      "responses" -> Json.Arr(
        Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(200), "body" -> Json.Str(body)))
      )
    )

  def imposterJson(port: Int, stubs: Chunk[Json], extra: (String, Json)*): String =
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

  /** Create an imposter over HTTP on a fresh port; yields the port. */
  def create(admin: Admin, stubs: Chunk[Json], extra: (String, Json)*): Task[Int] =
    for
      port <- freePort
      _ <- httpJson(
        "POST",
        s"${admin.adminUrl}/imposters",
        Some(imposterJson(port, stubs, extra*)),
        201
      )
    yield port

  def hit(port: Int, path: String): Task[Int] =
    http("GET", s"http://127.0.0.1:$port$path").map(_.status)

  def httpImposter(admin: Admin, port: Int): Task[Json] =
    httpJson("GET", s"${admin.adminUrl}/imposters/$port")

  // ── engine: one per spec, shared by every case ──

  private val embedded: Boolean =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private val emptyConfig: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-ffi-parity-", ".json")
        Files.write(p, """{"imposters":[]}""".getBytes(StandardCharsets.UTF_8))
        p
      }
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)

  val engine: ZLayer[Any, Throwable, Option[Admin]] =
    ZLayer.scoped {
      if !embedded || !EmbeddedAdmin.available then ZIO.none
      else emptyConfig.flatMap(cfg => EmbeddedAdmin.serve(0, cfg)).asSome
    }

  def withEngine(
      f: Admin => Task[TestResult]
  ): ZIO[Option[Admin], Throwable, TestResult] =
    ZIO.serviceWithZIO[Option[Admin]](_.fold(ZIO.succeed(assertCompletes))(f))
