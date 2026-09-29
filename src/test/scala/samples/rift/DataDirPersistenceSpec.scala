package samples.rift

import samples.rift.support.{RiftHarness, SpawnedEngine, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** `--datadir` persistence (#199; rift docs/configuration/cli.md § Data directory): a created
  * imposter is persisted as `<datadir>/<port>.json` and removed on delete, a restart reloads the
  * directory, a file not named `<port>.json` is skipped at startup and refuses a reload, a stale
  * `.tmp` is deleted at startup, and `--configfile` imposters are never persisted and win over a
  * data-directory file for the same port (#1122/#1125/#1128/#1158).
  *
  * Every case owns dedicated [[SpawnedEngine]]s sharing one temp directory. External-only and
  * self-gating like [[AdminExposureSpec]].
  */
object DataDirPersistenceSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def send(
      url: String,
      method: String = "GET",
      body: Option[String] = None
  ): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .method(
          method,
          body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
        )
        .build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      (r.statusCode, r.body)
    }

  private def imposter(port: Int, body: String): String =
    s"""{"port":$port,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"body":"$body"}}]}]}"""

  private def get(eng: SpawnedEngine, port: Int): Task[(Int, String)] =
    RiftHarness.awaitTcpReady("127.0.0.1", port, eng.proc) *> send(s"http://127.0.0.1:$port/")

  private def listens(port: Int): UIO[Boolean] =
    send(s"http://127.0.0.1:$port/").as(true).catchAll(_ => ZIO.succeed(false))

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-datadir-")))(d =>
      ZIO.attemptBlocking {
        val paths = Files.walk(d)
        try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        finally paths.close()
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8)))

  private def exists(p: Path): Task[Boolean] = ZIO.attemptBlocking(Files.exists(p))

  private def declaredPort(p: Path): Task[Option[Int]] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
      .map(_.fromJson[Json].toOption.flatMap {
        case Json.Obj(fs) => fs.collectFirst { case ("port", Json.Num(n)) => n.intValue }
        case _ => None
      })

  private def datadir(dir: Path): List[String] = List("--datadir", dir.toString)

  def spec = suite("--datadir persistence (#199, external)")(
    test("POST persists <port>.json declaring the port; DELETE removes it") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          eng <- SpawnedEngine.start(cfg, datadir(dir))
          created <- send(s"${eng.adminUrl}/imposters", "POST", Some(imposter(p, "persisted")))
          file = dir.resolve(s"$p.json")
          persisted <- exists(file)
          port <- declaredPort(file)
          deleted <- send(s"${eng.adminUrl}/imposters/$p", "DELETE")
          gone <- exists(file).map(!_)
        yield assertTrue(created._1 == 201, persisted, port.contains(p), deleted._1 == 200, gone)
      }
    },
    test("a restart with the same --datadir serves the persisted imposter") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          created <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, datadir(dir))
              .flatMap(eng =>
                send(s"${eng.adminUrl}/imposters", "POST", Some(imposter(p, "survived")))
              )
          )
          restarted <- SpawnedEngine.start(cfg, datadir(dir))
          r <- get(restarted, p)
        yield assertTrue(created._1 == 201, r == (200, "survived"))
      }
    },
    test(
      "a file not named <port>.json is skipped at startup and refuses a reload, naming the file"
    ) {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          _ <- write(dir, "foo.json", imposter(p, "misnamed"))
          eng <- SpawnedEngine.start(cfg, datadir(dir))
          served <- listens(p)
          out <- eng.output
          reload <- send(s"${eng.adminUrl}/admin/reload", "POST")
          rule = s"a datadir file must be named $p.json"
        yield assertTrue(
          !served,
          out.contains("foo.json"),
          out.contains(rule),
          reload._1 == 500,
          reload._2.contains("Reload failed (imposters unchanged)"),
          reload._2.contains("foo.json"),
          reload._2.contains(rule)
        )
      }
    },
    test("a stale .tmp left by an interrupted write is deleted at startup") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          p <- TestPorts.next
          tmp <- write(dir, s"$p.json.tmp", "{\"port\":")
          before <- exists(tmp)
          _ <- SpawnedEngine.start(cfg, datadir(dir))
          after <- exists(tmp)
        yield assertTrue(before, !after)
      }
    },
    test(
      "--configfile imposters are never persisted and win over a datadir file for the same port"
    ) {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          cfgDir <- tempDir
          shared <- TestPorts.next
          configOnly <- TestPorts.next
          _ <- write(dir, s"$shared.json", imposter(shared, "from-datadir"))
          config <- write(
            cfgDir,
            "config.json",
            s"""{"imposters":[${imposter(shared, "from-config")},${imposter(
                configOnly,
                "config-only"
              )}]}"""
          )
          eng <- SpawnedEngine.start(cfg, datadir(dir) ++ List("--configfile", config.toString))
          sharedBody <- get(eng, shared)
          configOnlyBody <- get(eng, configOnly)
          configOnlyPersisted <- exists(dir.resolve(s"$configOnly.json"))
        yield assertTrue(
          sharedBody == (200, "from-config"),
          configOnlyBody == (200, "config-only"),
          !configOnlyPersisted
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
