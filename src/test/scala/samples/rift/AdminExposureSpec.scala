package samples.rift

import samples.rift.support.{RiftHarness, SpawnedEngine, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Admin-plane authentication and exposure (#188; rift docs/configuration/cli.md § API-key
  * authentication, § Unauthenticated admin plane on a public interface, and docs/api/index.md § GET
  * /config).
  *
  * The shared harness engine never passes `--api-key`, `--local-only`, `--require-admin-auth` or
  * `--host`, so each case owns a dedicated engine ([[SpawnedEngine]]). Startup refusals are
  * asserted on the exit code and the message the engine prints. External-only and self-gating: a
  * no-op pass unless `RIFT_BUILD_DIR` is set and `RIFT_MODE` is external.
  */
object AdminExposureSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()
  private val Key = "conformance-key"

  private def send(
      url: String,
      method: String = "GET",
      body: Option[String] = None,
      auth: Option[String] = None
  ): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val b = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .method(
          method,
          body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
        )
      auth.foreach(b.header("Authorization", _))
      val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
      (r.statusCode, r.body)
    }

  private def json(body: String): Task[Json] =
    ZIO.fromEither(body.fromJson[Json]).mapError(e => new RuntimeException(s"$e: $body"))

  private def field(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) {
      case (Some(o: Json.Obj), k) => o.get(k)
      case _ => None
    }

  private def str(j: Json, path: String*): Option[String] =
    field(j, path*).collect { case Json.Str(s) => s }

  private def errorType(body: String): Task[Option[String]] =
    json(body).map { j =>
      field(j, "errors")
        .collect { case Json.Arr(es) => es.headOption }
        .flatten
        .flatMap(e => str(e, "type"))
    }

  private def strings(j: Option[Json]): List[String] =
    j.collect { case Json.Arr(xs) => xs.toList.collect { case Json.Str(s) => s } }.getOrElse(Nil)

  def spec = suite("admin-plane auth and exposure (#188, external)")(
    test("--api-key gates /health and /imposters: no key and a wrong key 401, the key 200") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, List("--api-key", Key))
          results <- ZIO.foreach(List("/health", "/imposters")) { path =>
            for
              none <- send(s"${eng.adminUrl}$path")
              noneType <- errorType(none._2)
              wrong <- send(s"${eng.adminUrl}$path", auth = Some("not-the-key"))
              wrongType <- errorType(wrong._2)
              right <- send(s"${eng.adminUrl}$path", auth = Some(Key))
            yield (path, none._1, noneType, wrong._1, wrongType, right)
          }
          healthBody <- json(results.head._6._2)
        yield
          val gated = results.map { case (path, none, noneType, wrong, wrongType, right) =>
            assertTrue(
              none == 401,
              noneType.contains("unauthorized"),
              wrong == 401,
              wrongType.contains("unauthorized"),
              right._1 == 200
            ).label(path)
          }
          gated.reduce(_ && _) && assertTrue(str(healthBody, "status").contains("ok"))
      }
    },
    test("--api-key leaves the data plane, /__rift/{port}/… and /metrics ungated") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, List("--api-key", Key))
          port <- TestPorts.next
          created <- send(
            s"${eng.adminUrl}/imposters",
            "POST",
            Some(
              s"""{"port":$port,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"body":"gated-not"}}]}]}"""
            ),
            auth = Some(Key)
          )
          gateway <- send(s"${eng.adminUrl}/__rift/$port/x")
          direct <- send(s"http://127.0.0.1:$port/x")
          metrics <- send(s"${eng.metricsUrl}/metrics")
        yield assertTrue(
          created._1 == 201,
          gateway == (200, "gated-not"),
          direct == (200, "gated-not"),
          metrics._1 == 200
        )
      }
    },
    test("MB_APIKEY in the environment gates the admin plane like --api-key") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, extraEnv = Map("MB_APIKEY" -> Key))
          none <- send(s"${eng.adminUrl}/health")
          keyed <- send(s"${eng.adminUrl}/health", auth = Some(Key))
        yield assertTrue(none._1 == 401, keyed._1 == 200)
      }
    },
    test("a blank --api-key is a startup error naming the blank value") {
      SpawnedEngine.external { cfg =>
        for r <- SpawnedEngine.refused(cfg, List("--api-key", "  "))
        yield assertTrue(r.exitCode != 0, r.output.contains("blank value"))
      }
    },
    test("--require-admin-auth refuses a keyless 0.0.0.0 bind, naming the flags") {
      SpawnedEngine.external { cfg =>
        for r <- SpawnedEngine.refused(
            cfg,
            List("--require-admin-auth", "--host", "0.0.0.0")
          )
        yield assertTrue(
          r.exitCode != 0,
          r.output.contains("--require-admin-auth"),
          r.output.contains("--api-key")
        )
      }
    },
    test("--require-admin-auth serves on a loopback bind and with a key on 0.0.0.0") {
      SpawnedEngine.external { cfg =>
        for
          loopback <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, List("--require-admin-auth", "--host", "127.0.0.1"))
              .flatMap(eng => send(s"${eng.adminUrl}/health"))
          )
          keyed <- ZIO.scoped(
            SpawnedEngine
              .start(cfg, List("--require-admin-auth", "--host", "0.0.0.0", "--api-key", Key))
              .flatMap(eng => send(s"${eng.adminUrl}/health", auth = Some(Key)))
          )
        yield assertTrue(loopback._1 == 200, keyed._1 == 200)
      }
    },
    test("--local-only is reflected as options.localOnly and serves on 127.0.0.1") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, List("--local-only"))
          health <- send(s"${eng.adminUrl}/health")
          config <- send(s"${eng.adminUrl}/config").flatMap(r => json(r._2))
        yield assertTrue(
          health._1 == 200,
          field(config, "options", "localOnly").contains(Json.Bool(true))
        )
      }
    },
    test("--host localhost is refused as not an IP literal") {
      SpawnedEngine.external { cfg =>
        for r <- SpawnedEngine.refused(cfg, List("--host", "localhost"))
        yield assertTrue(r.exitCode != 0, r.output.contains("not an IP literal"))
      }
    },
    test("--ip-whitelist is parsed but never enforced: /config reports [\"*\"] and warns") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg, List("--ip-whitelist", "10.0.0.1"))
          health <- send(s"${eng.adminUrl}/health")
          config <- send(s"${eng.adminUrl}/config").flatMap(r => json(r._2))
          out <- eng.output
        yield assertTrue(
          health._1 == 200,
          strings(field(config, "options", "ipWhitelist")) == List("*"),
          out.contains("--ip-whitelist"),
          out.contains("NOT enforced")
        )
      }
    },
    test("GET /config on a harness-started engine: real port, serveOptions, engine version") {
      SpawnedEngine.external { _ =>
        for
          h <- RiftHarness.layer.build.map(_.get[RiftHarness])
          info <- h.buildInfo
          config <- send(s"${h.adminUrl}/config").flatMap(r => json(r._2))
          adminPort = h.adminUrl.substring(h.adminUrl.lastIndexOf(':') + 1).toInt
          serveOptions = strings(field(config, "serveOptions"))
        yield assertTrue(
          field(config, "options", "port").contains(Json.Num(adminPort)),
          serveOptions.contains("apiKey"),
          serveOptions.contains("requireAdminAuth"),
          info.versions.get("rift").map(_.stripPrefix("rift ")) == str(config, "version"),
          str(config, "commit").exists(_.nonEmpty)
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
