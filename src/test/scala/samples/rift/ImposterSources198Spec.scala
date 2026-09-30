package samples.rift

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import samples.rift.support.{RiftHarness, SpawnedEngine, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** Imposter sources and config preprocessing (#198; rift docs/configuration/cli.md § Imposter
  * sources, § EJS): `--imposters` multi-source merge and its startup conflicts, an `http:` source
  * re-fetched with `ETag`/`If-None-Match` on `POST /admin/reload`, YAML documents, content-based
  * format detection, and EJS `process.env` / `include` / unsupported-tag refusal / `--no-parse`.
  *
  * Every case owns a dedicated [[SpawnedEngine]]; the `http:` source is an in-spec JDK
  * `HttpServer`. External-only and self-gating like [[AdminExposureSpec]].
  */
object ImposterSources198Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def send(url: String, method: String = "GET"): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(10))
        .method(method, HttpRequest.BodyPublishers.noBody)
        .build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      (r.statusCode, r.body)
    }

  /** `GET 127.0.0.1:<port><path>` once the imposter's listener accepts connections. */
  private def get(eng: SpawnedEngine, port: Int, path: String = "/"): Task[(Int, String)] =
    RiftHarness.awaitTcpReady("127.0.0.1", port, eng.proc) *> send(s"http://127.0.0.1:$port$path")

  private def imposter(port: Int, body: String): String =
    s"""{"port":$port,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"body":"$body"}}]}]}"""

  private def wrapped(imposters: String*): String =
    imposters.mkString("""{"imposters":[""", ",", "]}")

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-sources-")))(d =>
      ZIO.attemptBlocking {
        val paths = Files.walk(d)
        try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        finally paths.close()
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8)))

  private def json(s: String): Task[Json] =
    ZIO.fromEither(s.fromJson[Json]).mapError(e => new RuntimeException(s"$e: $s"))

  private def ports(j: Json, key: String): Option[List[Int]] = j match
    case Json.Obj(fs) =>
      fs.collectFirst { case (`key`, Json.Arr(xs)) =>
        xs.toList.collect { case Json.Num(n) => n.intValue }
      }
    case _ => None

  /** A plain-http imposter source: serves `current` with its ETag, answers `304` to a matching
    * `If-None-Match`, and records every `If-None-Match` it was sent. `/loop` redirects to itself.
    */
  private final case class Source(
      port: Int,
      current: AtomicReference[(String, String)],
      ifNoneMatch: ConcurrentLinkedQueue[String]
  ):
    def url(path: String): String = s"http://127.0.0.1:$port$path"
    def seen: UIO[List[String]] = ZIO.succeed(ifNoneMatch.asScala.toList)
    def publish(etag: String, body: String): UIO[Unit] = ZIO.succeed(current.set((etag, body)))

  private def source(etag: String, body: String): ZIO[Scope, Throwable, Source] =
    for
      port <- TestPorts.next
      src = Source(port, new AtomicReference((etag, body)), new ConcurrentLinkedQueue[String]())
      _ <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
        server.createContext(
          "/",
          (ex: HttpExchange) =>
            try
              if ex.getRequestURI.getPath == "/loop" then
                ex.getResponseHeaders.add("Location", src.url("/loop"))
                ex.sendResponseHeaders(302, -1)
              else
                val (tag, doc) = src.current.get
                Option(ex.getRequestHeaders.getFirst("If-None-Match")).foreach(src.ifNoneMatch.add)
                ex.getResponseHeaders.add("ETag", tag)
                if Option(ex.getRequestHeaders.getFirst("If-None-Match")).contains(tag) then
                  ex.sendResponseHeaders(304, -1)
                else
                  val bytes = doc.getBytes(StandardCharsets.UTF_8)
                  ex.getResponseHeaders.add("Content-Type", "application/json")
                  ex.sendResponseHeaders(200, bytes.length.toLong)
                  ex.getResponseBody.write(bytes)
            finally ex.close()
        )
        try server.start()
        catch
          case e: Throwable =>
            server.stop(0)
            throw e
        server
      })(s => ZIO.succeed(s.stop(0)))
    yield src

  /** An HTTPS imposter source: a second engine whose TLS imposter (the committed
    * `tls/upstream.json` leaf, signed by the test CA) serves `document`. Returns the document's
    * `https:` URL.
    */
  private def httpsSource(cfg: samples.rift.support.RiftHarnessConfig, document: String) =
    for
      host <- SpawnedEngine.start(cfg)
      port <- TestPorts.next
      upstream <- ZIO
        .attemptBlocking(
          Option(getClass.getResourceAsStream("/rift-fixtures/tls/upstream.json")).map { is =>
            try new String(is.readAllBytes(), StandardCharsets.UTF_8)
            finally is.close()
          }
        )
        .someOrFail(new IllegalStateException("fixture rift-fixtures/tls/upstream.json missing"))
        .flatMap(json)
      tls <- upstream match
        case Json.Obj(fs) =>
          ZIO.succeed(
            Json.Obj(
              fs.filterNot((k, _) => k == "port" || k == "stubs") ++ Chunk(
                "port" -> Json.Num(port),
                "stubs" -> Json.Arr(
                  Json.Obj(
                    "responses" -> Json.Arr(
                      Json.Obj(
                        "is" -> Json.Obj(
                          "statusCode" -> Json.Num(200),
                          "body" -> Json.Str(document)
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        case other => ZIO.fail(new IllegalStateException(s"upstream.json is not an object: $other"))
      created <- ZIO.attemptBlocking {
        val req = HttpRequest
          .newBuilder(URI.create(s"${host.adminUrl}/imposters"))
          .timeout(java.time.Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(tls.toJson))
          .build()
        client.send(req, HttpResponse.BodyHandlers.ofString())
      }
      _ <- ZIO
        .fail(new RuntimeException(s"https source imposter: ${created.statusCode} ${created.body}"))
        .unless(created.statusCode == 201)
      _ <- RiftHarness.awaitTcpReady("127.0.0.1", port, host.proc)
    yield s"https://127.0.0.1:$port/imp.json"

  private def testCaFile: Task[String] =
    ZIO
      .fromOption(RiftHarness.interceptCa)
      .orElseFail(new IllegalStateException("test CA fixture rift-fixtures/intercept missing"))
      .map(_._1.toString)

  def spec = suite("imposter sources and config preprocessing (#198, external)")(
    test("--imposters merges two file sources with distinct ports") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p1 <- TestPorts.next
          p2 <- TestPorts.next
          a <- write(dir, "a.json", wrapped(imposter(p1, "from-a")))
          b <- write(dir, "b.json", wrapped(imposter(p2, "from-b")))
          eng <- SpawnedEngine.start(cfg, List("--imposters", s"file:$a,$b"))
          ra <- get(eng, p1)
          rb <- get(eng, p2)
        yield assertTrue(ra == (200, "from-a"), rb == (200, "from-b"))
      }
    },
    test("a port declared by two sources is a startup error naming the port") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          a <- write(dir, "a.json", wrapped(imposter(p, "a")))
          b <- write(dir, "b.json", wrapped(imposter(p, "b")))
          r <- SpawnedEngine.refused(cfg, List("--imposters", s"file:$a,file:$b"))
        yield assertTrue(r.exitCode != 0, r.output.contains(s"both declare port $p"))
      }
    },
    test("--imposters together with --configfile is refused") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p1 <- TestPorts.next
          p2 <- TestPorts.next
          a <- write(dir, "a.json", wrapped(imposter(p1, "a")))
          b <- write(dir, "b.json", wrapped(imposter(p2, "b")))
          r <- SpawnedEngine
            .refused(cfg, List("--imposters", a.toString, "--configfile", b.toString))
        yield assertTrue(r.exitCode != 0, r.output.contains("pass only one"))
      }
    },
    test("an unregistered scheme is a startup error listing the registered ones") {
      SpawnedEngine.external { cfg =>
        for r <- SpawnedEngine.refused(cfg, List("--imposters", "ftp:x"))
        yield assertTrue(
          r.exitCode != 0,
          r.output.contains("`ftp:`"),
          r.output.contains("known schemes: file, http, https")
        )
      }
    },
    test(
      "an http source serves, reload revalidates with If-None-Match, and a changed ETag applies"
    ) {
      SpawnedEngine.external { cfg =>
        for
          p1 <- TestPorts.next
          p2 <- TestPorts.next
          src <- source("\"v1\"", wrapped(imposter(p1, "v1")))
          eng <- SpawnedEngine.start(cfg, List("--imposters", src.url("/imp.json")))
          before <- get(eng, p1)
          unchanged <- send(s"${eng.adminUrl}/admin/reload", "POST")
          unchangedJson <- json(unchanged._2)
          revalidated <- src.seen
          _ <- src.publish("\"v2\"", wrapped(imposter(p1, "v2"), imposter(p2, "added")))
          changed <- send(s"${eng.adminUrl}/admin/reload", "POST")
          changedJson <- json(changed._2)
          // A replaced imposter is rebound; poll (bounded) until the new listener answers.
          afterP1 <- get(eng, p1)
            .orElseSucceed((0, "unreachable"))
            .filterOrElseWith(_ == (200, "v2"))(ZIO.fail(_))
            .retry(Schedule.spaced(100.millis) && Schedule.recurs(50))
            .merge
          afterP2 <- get(eng, p2)
        yield assertTrue(
          before == (200, "v1"),
          unchanged._1 == 200,
          unchanged._2.contains("No source changed"),
          // rift#1237 (fixed in 0.19.0): this 304 short-circuit reports the four diff fields as
          // empty port arrays, like every other reload (up to 0.18.1 the integer 0).
          List("created", "deleted", "replaced", "stubPatched")
            .forall(k => ports(unchangedJson, k).contains(Nil)),
          revalidated.contains("\"v1\""),
          changed._1 == 200,
          ports(changedJson, "created").contains(List(p2)),
          ports(changedJson, "deleted").contains(Nil),
          (ports(changedJson, "replaced").toList.flatten ++
            ports(changedJson, "stubPatched").toList.flatten) == List(p1),
          afterP1 == (200, "v2"),
          afterP2 == (200, "added")
        )
      }
    },
    test("an http source that redirects forever is refused at the redirect cap") {
      SpawnedEngine.external { cfg =>
        for
          src <- source("\"x\"", "{}")
          r <- SpawnedEngine.refused(cfg, List("--imposters", src.url("/loop")))
        yield assertTrue(r.exitCode != 0, r.output.contains("too many redirects (limit 10)"))
      }
    },
    test("an https source trusted via --upstream-ca-file serves") {
      SpawnedEngine.external { cfg =>
        for
          p <- TestPorts.next
          url <- httpsSource(cfg, wrapped(imposter(p, "over-https")))
          ca <- testCaFile
          eng <- SpawnedEngine.start(cfg, List("--imposters", url, "--upstream-ca-file", ca))
          r <- get(eng, p)
        yield assertTrue(r == (200, "over-https"))
      }
    },
    test("an EJS include in an https-fetched document is refused") {
      SpawnedEngine.external { cfg =>
        for
          p <- TestPorts.next
          url <- httpsSource(
            cfg,
            s"""{"imposters":[{"port":$p,"protocol":"http","stubs":[<% include 'part.json' %>]}]}"""
          )
          ca <- testCaFile
          r <- SpawnedEngine.refused(cfg, List("--imposters", url, "--upstream-ca-file", ca))
        yield assertTrue(
          r.exitCode != 0,
          r.output.contains("is not honoured in a document fetched from https://")
        )
      }
    },
    test("a YAML bare sequence serves; a YAML mapping root is refused") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          seq <- write(
            dir,
            "seq.yaml",
            s"""- port: $p
               |  protocol: http
               |  stubs:
               |    - responses:
               |        - is:
               |            statusCode: 200
               |            body: yaml-ok
               |""".stripMargin
          )
          mapping <- write(dir, "map.yaml", s"imposters:\n  - port: $p\n    protocol: http\n")
          served <- ZIO.scoped(
            SpawnedEngine.start(cfg, List("--configfile", seq.toString)).flatMap(get(_, p))
          )
          refused <- SpawnedEngine.refused(cfg, List("--configfile", mapping.toString))
        yield assertTrue(
          served == (200, "yaml-ok"),
          refused.exitCode != 0,
          refused.output.contains("expected a sequence")
        )
      }
    },
    test("a .yaml-named file whose content is JSON loads as JSON") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          f <- write(dir, "actually-json.yaml", wrapped(imposter(p, "json-in-yaml")))
          eng <- SpawnedEngine.start(cfg, List("--configfile", f.toString))
          r <- get(eng, p)
        yield assertTrue(r == (200, "json-in-yaml"))
      }
    },
    test(
      "EJS process.env: set → its value; unset with a default → the default; unset → empty + warning"
    ) {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p1 <- TestPorts.next
          p2 <- TestPorts.next
          plain <- write(dir, "env.json", wrapped(imposter(p1, "<%= process.env.RIFT_T_BODY %>")))
          dflt <- write(
            dir,
            "env-default.json",
            wrapped(imposter(p2, "<%= process.env.RIFT_T_BODY || 'dflt' %>"))
          )
          set <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, List("--configfile", plain.toString), Map("RIFT_T_BODY" -> "from-env"))
              .flatMap(get(_, p1))
          )
          defaulted <- ZIO.scoped(
            SpawnedEngine.start(cfg, List("--configfile", dflt.toString)).flatMap(get(_, p2))
          )
          unset <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, List("--configfile", plain.toString))
              .flatMap(eng => get(eng, p1).zip(eng.output))
          )
        yield assertTrue(
          set == (200, "from-env"),
          defaulted == (200, "dflt"),
          unset._1 == 200,
          unset._2 == "",
          unset._3.contains("`RIFT_T_BODY` is unset")
        )
      }
    },
    test("EJS include inlines a stub file") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          _ <- write(
            dir,
            "part.json",
            """{"predicates":[{"equals":{"path":"/inc"}}],"responses":[{"is":{"statusCode":200,"body":"included"}}]}"""
          )
          f <- write(
            dir,
            "with-include.json",
            s"""{"imposters":[{"port":$p,"protocol":"http","stubs":[
               |<% include 'part.json' %>
               |]}]}""".stripMargin
          )
          eng <- SpawnedEngine.start(cfg, List("--configfile", f.toString))
          r <- get(eng, p, "/inc")
        yield assertTrue(r == (200, "included"))
      }
    },
    test(
      "an unsupported EJS tag fails the load naming the tag and file:line; --no-parse serves it verbatim"
    ) {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          f <- write(
            dir,
            "comment-tag.json",
            s"""{"imposters":[{"port":$p,"protocol":"http",
               |"stubs":[{"responses":[{"is":{"statusCode":200,"body":"<%# x %>"}}]}]}]}""".stripMargin
          )
          refused <- SpawnedEngine.refused(cfg, List("--configfile", f.toString))
          verbatim <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, List("--configfile", f.toString, "--no-parse"))
              .flatMap(get(_, p))
          )
        yield assertTrue(
          refused.exitCode != 0,
          refused.output.contains("unsupported EJS tag `<%# x %>`"),
          refused.output.contains(s"$f:2"),
          verbatim == (200, "<%# x %>")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
