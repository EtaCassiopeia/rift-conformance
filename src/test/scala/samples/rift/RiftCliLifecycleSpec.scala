package samples.rift

import samples.rift.support.{RiftHarness, RiftHarnessConfig, SpawnedEngine, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** The `rift` process lifecycle surface (#189; rift docs/configuration/cli.md § healthcheck,
  * save/replay, stop, § RC file, § Log Levels, § Signal Handling, § Exit Codes): the `healthcheck`,
  * `save`, `replay` and `stop` subcommands, `--pidfile`, graceful `SIGTERM`, `--rcfile`, startup
  * refusals of a bad log level, and the CLI parse-error exit code.
  *
  * Every engine is a dedicated [[SpawnedEngine]] (reaped by its scope); subcommands run bounded to
  * exit. External-only and self-gating like [[AdminExposureSpec]]. Timing asserts are upper bounds
  * on generous budgets only.
  */
object RiftCliLifecycleSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()
  private val Key = "lifecycle-key"

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
        .header("Authorization", Key)
        .method(
          method,
          body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
        )
        .build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      (r.statusCode, r.body)
    }

  private def refusedConnection(url: String): UIO[Boolean] =
    send(url).as(false).catchAll(_ => ZIO.succeed(true))

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-cli-")))(d =>
      ZIO.attemptBlocking {
        val paths = Files.walk(d)
        try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        finally paths.close()
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8)))

  private def read(p: Path): Task[String] =
    ZIO.attemptBlocking(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))

  private def json(s: String): Task[Json] =
    ZIO.fromEither(s.fromJson[Json]).mapError(e => new RuntimeException(s"$e: $s"))

  private def portArgs(port: Int): List[String] = List("--port", port.toString)

  /** An engine with an imposter whose first stub was created with it and whose second was added
    * through `POST …/stubs` (the rift#1219 live-stub projection `save` must capture), plus a proxy
    * imposter for `--remove-proxies`. Returns `(engine, stubPort, proxyPort)`.
    */
  private def seeded(cfg: RiftHarnessConfig) =
    for
      eng <- SpawnedEngine.start(cfg, List("--api-key", Key))
      stubPort <- TestPorts.next
      proxyPort <- TestPorts.next
      created <- send(
        s"${eng.adminUrl}/imposters",
        "POST",
        Some(
          s"""{"port":$stubPort,"protocol":"http","stubs":[{"predicates":[{"equals":{"path":"/first"}}],""" +
            """"responses":[{"is":{"statusCode":200,"body":"first"}}]}]}"""
        )
      )
      added <- send(
        s"${eng.adminUrl}/imposters/$stubPort/stubs",
        "POST",
        Some(
          """{"stub":{"predicates":[{"equals":{"path":"/added"}}],"responses":[{"is":{"statusCode":200,"body":"added-live"}}]}}"""
        )
      )
      proxy <- send(
        s"${eng.adminUrl}/imposters",
        "POST",
        Some(
          s"""{"port":$proxyPort,"protocol":"http","stubs":[{"responses":[{"proxy":{"to":"http://127.0.0.1:1"}}]}]}"""
        )
      )
      _ <- ZIO
        .fail(new RuntimeException(s"seeding failed: $created $added $proxy"))
        .unless(created._1 == 201 && added._1 == 200 && proxy._1 == 201)
    yield (eng, stubPort, proxyPort)

  def spec = suite("rift CLI lifecycle (#189, external)")(
    test("healthcheck exits 0 against a live engine and 1 against a closed port") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          live <- SpawnedEngine.run(cfg, portArgs(eng.adminPort) :+ "healthcheck")
          closed <- TestPorts.next
          start <- Clock.nanoTime
          dead <- SpawnedEngine.run(cfg, portArgs(closed) ++ List("healthcheck", "--timeout", "1"))
          elapsedMs <- Clock.nanoTime.map(t => (t - start) / 1000000)
        // A closed loopback port refuses at once, so the bound only pins "fails fast", not the
        // `--timeout` value itself.
        yield assertTrue(live.exitCode == 0, dead.exitCode == 1, elapsedMs < 3000)
      }
    },
    test("healthcheck sends MB_APIKEY to the derived URL but never to an explicit --url") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, List("--api-key", Key))
          noKey <- SpawnedEngine.run(cfg, portArgs(eng.adminPort) :+ "healthcheck")
          keyed <- SpawnedEngine.run(
            cfg,
            portArgs(eng.adminPort) :+ "healthcheck",
            Map("MB_APIKEY" -> Key)
          )
          explicitUrl <- SpawnedEngine.run(
            cfg,
            List("healthcheck", "--url", s"${eng.adminUrl}/health"),
            Map("MB_APIKEY" -> Key)
          )
        yield assertTrue(
          noKey.exitCode == 1,
          keyed.exitCode == 0,
          explicitUrl.exitCode == 1,
          explicitUrl.output.contains("never sent the API key")
        )
      }
    },
    test("save writes the live stubs, --remove-proxies drops proxies, and replay serves the file") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          saved = dir.resolve("saved.json")
          noProxies = dir.resolve("no-proxies.json")
          ports <- ZIO.scoped {
            for
              seed <- seeded(cfg)
              (eng, stubPort, proxyPort) = seed
              env = Map("MB_APIKEY" -> Key)
              s1 <- SpawnedEngine.run(
                cfg,
                portArgs(eng.adminPort) ++ List("save", "--savefile", saved.toString),
                env
              )
              s2 <- SpawnedEngine.run(
                cfg,
                portArgs(eng.adminPort) ++ List(
                  "save",
                  "--savefile",
                  noProxies.toString,
                  "--remove-proxies"
                ),
                env
              )
              _ <- ZIO
                .fail(new RuntimeException(s"save failed: $s1 / $s2"))
                .unless(s1.exitCode == 0 && s2.exitCode == 0)
            yield (stubPort, proxyPort)
          }
          (stubPort, _) = ports
          savedText <- read(saved)
          savedJson <- json(savedText)
          noProxiesText <- read(noProxies)
          // The seeding engine is gone (its scope closed), so the replay can bind the same ports.
          adminPort <- TestPorts.next
          metricsPort <- TestPorts.next
          replay <- SpawnedEngine.startRaw(
            cfg,
            List(
              "--nologfile",
              "--metrics-port",
              metricsPort.toString,
              "--port",
              adminPort.toString,
              "replay",
              "--configfile",
              saved.toString
            ),
            adminPort,
            metricsPort
          )
          _ <- RiftHarness.awaitTcpReady("127.0.0.1", stubPort, replay.proc)
          first <- send(s"http://127.0.0.1:$stubPort/first")
          added <- send(s"http://127.0.0.1:$stubPort/added")
        yield assertTrue(
          savedJson match
            case Json.Obj(fields) =>
              fields.exists((k, v) => k == "imposters" && v.isInstanceOf[Json.Arr])
            case _ => false
          ,
          savedText.contains("added-live"),
          savedText.contains("\"proxy\""),
          !noProxiesText.contains("\"proxy\""),
          noProxiesText.contains("added-live"),
          first == (200, "first"),
          added == (200, "added-live")
        )
      }
    },
    test("save against a keyed engine without the key fails and writes no file") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          out = dir.resolve("never.json")
          eng <- SpawnedEngine.start(cfg, List("--api-key", Key))
          r <- SpawnedEngine
            .run(cfg, portArgs(eng.adminPort) ++ List("save", "--savefile", out.toString))
          exists <- ZIO.attemptBlocking(Files.exists(out))
        yield assertTrue(r.exitCode == 1, r.output.contains("401"), !exists)
      }
    },
    test("--pidfile holds the pid; stop exits 0, the engine exits, the pidfile is removed") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          pf = dir.resolve("rift.pid")
          adminPort <- TestPorts.next
          metricsPort <- TestPorts.next
          eng <- SpawnedEngine.startRaw(
            cfg,
            List(
              "--nologfile",
              "--port",
              adminPort.toString,
              "--metrics-port",
              metricsPort.toString,
              "--pidfile",
              pf.toString
            ),
            adminPort,
            metricsPort
          )
          recorded <- read(pf).map(_.trim)
          stop <- SpawnedEngine.run(cfg, List("stop", "--pidfile", pf.toString), budgetSeconds = 6)
          exited <- ZIO.attemptBlocking(eng.proc.waitFor(6, TimeUnit.SECONDS))
          pfGone <- ZIO.attemptBlocking(!Files.exists(pf))
        yield assertTrue(
          recorded == eng.proc.pid().toString,
          stop.exitCode == 0,
          exited,
          pfGone
        )
      }
    },
    test("stop refuses a pidfile holding 0 and treats a dead pid as stale (exit 0)") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          zero <- write(dir, "zero.pid", "0\n")
          deadPid <- ZIO.attemptBlocking {
            val p = new ProcessBuilder("true").start()
            p.waitFor()
            p.pid()
          }
          stale <- write(dir, "stale.pid", s"$deadPid\n")
          // Were the pid-0 refusal ever lost, `stop` would signal its whole process group, test
          // JVM included: the run dies loudly rather than passing, which is acceptable here.
          refusedZero <- SpawnedEngine.run(cfg, List("stop", "--pidfile", zero.toString))
          staleStop <- SpawnedEngine.run(cfg, List("stop", "--pidfile", stale.toString))
        yield assertTrue(
          refusedZero.exitCode != 0,
          refusedZero.output.contains("non-positive pid"),
          staleStop.exitCode == 0
        )
      }
    },
    test("SIGTERM shuts the engine down gracefully with exit code 0") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          exited <- ZIO.attemptBlocking {
            eng.proc.destroy()
            eng.proc.waitFor(5, TimeUnit.SECONDS)
          }
        yield assertTrue(Option.when(exited)(eng.proc.exitValue()).contains(0))
      }
    },
    test("--rcfile supplies port and allowInjection; a CLI flag overrides it") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          rcPort <- TestPorts.next
          metricsPort <- TestPorts.next
          rc <- write(dir, "rc.json", s"""{"port":$rcPort,"allowInjection":true}""")
          fromRc <- ZIO.scoped(
            SpawnedEngine
              .startRaw(
                cfg,
                List(
                  "--nologfile",
                  "--metrics-port",
                  metricsPort.toString,
                  "--rcfile",
                  rc.toString
                ),
                rcPort,
                metricsPort
              )
              .flatMap(eng => send(s"${eng.adminUrl}/config").flatMap(r => json(r._2)))
          )
          flagPort <- TestPorts.next
          metrics2 <- TestPorts.next
          overridden <- ZIO.scoped(
            SpawnedEngine
              .startRaw(
                cfg,
                List(
                  "--nologfile",
                  "--metrics-port",
                  metrics2.toString,
                  "--rcfile",
                  rc.toString,
                  "--port",
                  flagPort.toString
                ),
                flagPort,
                metrics2
              )
              .flatMap(eng =>
                send(s"${eng.adminUrl}/health")
                  .zip(refusedConnection(s"http://127.0.0.1:$rcPort/health"))
              )
          )
          options = fromRc match
            case Json.Obj(fs) => fs.collectFirst { case ("options", o: Json.Obj) => o }
            case _ => None
        yield assertTrue(
          options.flatMap(_.get("allowInjection")).contains(Json.Bool(true)),
          options.flatMap(_.get("port")).contains(Json.Num(rcPort)),
          overridden._1 == 200,
          overridden._3
        )
      }
    },
    test("an unreadable rcfile and a wrong-typed rcfile value abort startup") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          missing = dir.resolve("missing-rc.json")
          bad <- write(dir, "bad-rc.json", """{"port":"x"}""")
          r1 <- SpawnedEngine.refused(cfg, List("--rcfile", missing.toString))
          r2 <- SpawnedEngine.refused(cfg, List("--rcfile", bad.toString))
        yield assertTrue(
          r1.exitCode != 0,
          r1.output.contains(missing.toString),
          r2.exitCode != 0,
          r2.output.contains("'port' must be an integer")
        )
      }
    },
    test("an unknown --loglevel and an invalid RUST_LOG are startup refusals") {
      SpawnedEngine.external { cfg =>
        for
          level <- SpawnedEngine.refused(cfg, List("--loglevel", "verbose"))
          rustLog <- SpawnedEngine.refused(cfg, Nil, Map("RUST_LOG" -> "=="))
        yield assertTrue(
          level.exitCode != 0,
          level.output.contains("not a log level"),
          rustLog.exitCode != 0,
          rustLog.output.contains("invalid filter directive")
        )
      }
    },
    test("an unknown flag is a CLI parse error: exit 2") {
      SpawnedEngine.external { cfg =>
        for r <- SpawnedEngine.run(cfg, List("--no-such-flag"))
        yield assertTrue(r.exitCode == 2)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
