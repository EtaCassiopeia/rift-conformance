package samples.rift

import samples.rift.support.TestPorts
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}

/** File-based script authoring (rift#441, #87): a `_rift.script` can reference its code by `file:`
  * (a path resolved under the engine's `--scripts-dir` root) or `ref:` (a named entry in the
  * imposter's own `_rift.scripts` map). This gate spawns a dedicated engine with `--scripts-dir`
  * pointed at a committed script library (`src/test/resources/rift-scripts`) and drives, over the
  * admin API:
  *   - `file: "greet.rhai"` → the imposter is accepted and serves the file's `respond()`;
  *   - `ref: "greet"` against an inline `_rift.scripts` library → accepted and served;
  *   - a path-traversal `file: "../../etc/passwd"` → rejected (the resolved path may not escape the
  *     root).
  *
  * External-leg only (spawns a real engine) and self-gating: a no-op pass when `RIFT_BUILD_DIR` is
  * unset, mirroring the other tool gates.
  */
object FileScriptAuthoring87Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def buildDir: Option[Path] = sys.env.get("RIFT_BUILD_DIR").map(Paths.get(_))

  private val scriptsDir: Path = Paths.get("src/test/resources/rift-scripts").toAbsolutePath

  private def spawnEngine(
      bd: Path,
      adminPort: Int,
      metricsPort: Int
  ): ZIO[Scope, Throwable, Process] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val log = Files.createTempFile(s"scripts-dir-engine-$adminPort-", ".log").toFile
        log.deleteOnExit()
        val pb = new ProcessBuilder(
          bd.resolve("rift").toString,
          "--port",
          adminPort.toString,
          "--allow-injection",
          "--nologfile",
          "--metrics-port",
          metricsPort.toString,
          "--scripts-dir",
          scriptsDir.toString,
          "--loglevel",
          "error"
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        pb.start()
      }
    )(proc => ZIO.attemptBlocking(if proc.isAlive then proc.destroyForcibly()).ignore)

  private def send(method: String, url: String, body: Option[String]): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
      val req = body match
        case Some(js) => b.method(method, HttpRequest.BodyPublishers.ofString(js)).build()
        case None => b.method(method, HttpRequest.BodyPublishers.noBody()).build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      (resp.statusCode, resp.body)
    }

  private def awaitAdmin(adminPort: Int, proc: Process): Task[Unit] =
    val ready =
      ZIO
        .fail(new RuntimeException("engine exited before admin came up"))
        .when(!proc.isAlive)
        .zipRight(
          send("GET", s"http://127.0.0.1:$adminPort/imposters", None)
            .filterOrFail(_._1 == 200)(new RuntimeException("admin not ready"))
        )
    ready.retry(Schedule.spaced(250.millis) && Schedule.recurs(80)).unit

  private def fileImposter(port: Int) =
    s"""{"protocol":"http","port":$port,"stubs":[{"predicates":[{"equals":{"path":"/f"}}],""" +
      s""""responses":[{"_rift":{"script":{"engine":"rhai","file":"greet.rhai"}}}]}]}"""

  private def refImposter(port: Int) =
    s"""{"protocol":"http","port":$port,""" +
      s""""_rift":{"scripts":{"greet":{"engine":"rhai","code":"fn respond(ctx){ http(200,\\"from-named-ref\\") }"}}},""" +
      s""""stubs":[{"predicates":[{"equals":{"path":"/r"}}],"responses":[{"_rift":{"script":{"ref":"greet"}}}]}]}"""

  private def traversalImposter(port: Int) =
    s"""{"protocol":"http","port":$port,"stubs":[{"predicates":[{"equals":{"path":"/esc"}}],""" +
      s""""responses":[{"_rift":{"script":{"engine":"rhai","file":"../../etc/passwd"}}}]}]}"""

  private def withEngine[A](f: Int => ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    buildDir match
      case None => ZIO.succeed(assertCompletes)
      case Some(bd) =>
        ZIO.scoped {
          for
            adminPort <- TestPorts.next
            metricsPort <- TestPorts.next
            proc <- spawnEngine(bd, adminPort, metricsPort)
            _ <- awaitAdmin(adminPort, proc)
            r <- f(adminPort)
          yield r
        }

  def spec = suite("file-based script authoring (#87)")(
    test("a file: script reference resolves under --scripts-dir and serves") {
      withEngine { adminPort =>
        for
          port <- TestPorts.next
          created <- send(
            "POST",
            s"http://127.0.0.1:$adminPort/imposters",
            Some(fileImposter(port))
          )
          served <- send("GET", s"http://127.0.0.1:$port/f", None)
        yield assertTrue(created._1 == 201, served._1 == 200, served._2 == "from-file-lib")
      }
    },
    test("a ref: script reference resolves a named _rift.scripts entry and serves") {
      withEngine { adminPort =>
        for
          port <- TestPorts.next
          created <- send("POST", s"http://127.0.0.1:$adminPort/imposters", Some(refImposter(port)))
          served <- send("GET", s"http://127.0.0.1:$port/r", None)
        yield assertTrue(created._1 == 201, served._1 == 200, served._2 == "from-named-ref")
      }
    },
    test("a file: path escaping --scripts-dir is rejected") {
      withEngine { adminPort =>
        for
          port <- TestPorts.next
          created <- send(
            "POST",
            s"http://127.0.0.1:$adminPort/imposters",
            Some(traversalImposter(port))
          )
        yield assertTrue(
          created._1 == 400,
          created._2.contains("escapes the scripts root")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
