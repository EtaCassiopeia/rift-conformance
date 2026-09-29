package samples.rift

import samples.rift.support.TestPorts
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Mountebank v2 fidelity — EJS `stringify` preprocessing (rift#438, #89). `--configfile` templates
  * are EJS-preprocessed by default (unless `--no-parse`): `<%- stringify('relative/path') %>`
  * inlines the referenced file's contents, JSON-escaped, into the surrounding string (the template
  * supplies the quotes). This gate writes a temp `config.ejs` whose response body is `"<%-
  * stringify('greeting.txt') %>"` alongside a `greeting.txt`, spawns an engine with that
  * `--configfile`, and asserts the served body is the file's inlined content — proving the EJS
  * `stringify` directive is rendered at load, not left literal.
  *
  * External-leg only (spawns a real engine), self-gating on `RIFT_BUILD_DIR`.
  */
object MbEjsStringify89Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()
  private val Inlined = "hello-from-ejs-stringify"

  private def buildDir: Option[Path] = sys.env.get("RIFT_BUILD_DIR").map(Paths.get(_))

  /** A temp dir holding `greeting.txt` + a `config.ejs` that inlines it via `stringify` on the
    * `imposterPort` imposter; deleted when the scope closes. `stringify` resolves relative to the
    * config file's own directory, so both live together.
    */
  private def ejsConfigDir(imposterPort: Int): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val dir = Files.createTempDirectory("rift-ejs-")
        Files.writeString(dir.resolve("greeting.txt"), Inlined, StandardCharsets.UTF_8)
        val ejs =
          s"""{"imposters":[{"protocol":"http","port":$imposterPort,"stubs":[""" +
            s"""{"predicates":[{"equals":{"path":"/e"}}],""" +
            s""""responses":[{"is":{"statusCode":200,"body":"<%- stringify('greeting.txt') %>"}}]}]}]}"""
        Files.writeString(dir.resolve("config.ejs"), ejs, StandardCharsets.UTF_8)
        dir
      }
    )(dir =>
      ZIO.attemptBlocking {
        Files.deleteIfExists(dir.resolve("greeting.txt"))
        Files.deleteIfExists(dir.resolve("config.ejs"))
        Files.deleteIfExists(dir)
      }.ignore
    )

  private def spawnEngine(
      bd: Path,
      configFile: Path,
      adminPort: Int,
      metricsPort: Int
  ): ZIO[Scope, Throwable, Process] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val log = Files.createTempFile(s"ejs-engine-$adminPort-", ".log").toFile
        log.deleteOnExit()
        val pb = new ProcessBuilder(
          bd.resolve("rift").toString,
          "--port",
          adminPort.toString,
          "--allow-injection",
          "--nologfile",
          "--metrics-port",
          metricsPort.toString,
          "--configfile",
          configFile.toString,
          "--loglevel",
          "error"
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        pb.start()
      }
    )(proc => ZIO.attemptBlocking(if proc.isAlive then proc.destroyForcibly()).ignore)

  private def get(url: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      (resp.statusCode, resp.body)
    }

  private def awaitReady(url: String, proc: Process): Task[Unit] =
    val ready =
      ZIO
        .fail(new RuntimeException("engine exited before it came up"))
        .when(!proc.isAlive)
        .zipRight(get(url).filterOrFail(_._1 == 200)(new RuntimeException("not ready")))
    ready.retry(Schedule.spaced(250.millis) && Schedule.recurs(80)).unit

  def spec = suite("Mountebank v2 EJS stringify (#89)")(
    test("a --configfile EJS stringify directive inlines the referenced file into the response") {
      buildDir match
        case None => ZIO.succeed(assertCompletes)
        case Some(bd) =>
          ZIO.scoped {
            for
              imposterPort <- TestPorts.next
              adminPort <- TestPorts.next
              metricsPort <- TestPorts.next
              dir <- ejsConfigDir(imposterPort)
              proc <- spawnEngine(bd, dir.resolve("config.ejs"), adminPort, metricsPort)
              _ <- awaitReady(s"http://127.0.0.1:$imposterPort/e", proc)
              served <- get(s"http://127.0.0.1:$imposterPort/e")
            yield assertTrue(served._1 == 200, served._2 == Inlined)
          }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
