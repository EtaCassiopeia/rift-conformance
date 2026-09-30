package samples.rift

import samples.rift.support.{RiftTools, SpawnedEngine, TestPorts, VerifyReport}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** `rift-lint` options and diagnostic codes, and `rift-verify` flags (#204; rift
  * docs/features/linting.md, docs/features/stub-analysis.md § The `_verify` annotation,
  * docs/configuration/cli.md § rift-verify). The `rift script run` options are in
  * [[RiftScriptTooling88Spec]].
  *
  * Lint cases run the binary over the per-code files in `rift-lint-fixtures/` (see its README).
  * Verify cases run against a dedicated [[SpawnedEngine]] seeded by the spec. External-only and
  * self-gating like [[AdminExposureSpec]].
  */
object RiftToolsCli204Spec extends ZIOSpecDefault:

  private val fixtures: Path = Paths.get("src/test/resources/rift-lint-fixtures")

  /** Each fixture must report its code at this severity. */
  private val Codes: List[(String, String)] =
    List("E013", "E025", "E041", "E043", "E044", "E045", "E047", "E048", "E049", "E050", "E051")
      .map(c => s"$c.json" -> "error") ++
      List("E046.yaml" -> "error") ++
      List("W012", "W013", "W014", "W016", "W018").map(c => s"$c.json" -> "warning") ++
      List("I005.json" -> "info")

  private val HeaderNumber =
    """{"port":4990,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,"headers":{"X-N":7}}}]}]}"""

  private def tools(cfg: samples.rift.support.RiftHarnessConfig): RiftTools =
    RiftTools.fromBuildDir(cfg.buildDir)

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-tools-")))(d =>
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

  private def copyFixture(name: String, dir: Path, as: String): Task[Path] =
    ZIO.attemptBlocking(
      Files.copy(fixtures.resolve(name), dir.resolve(as), StandardCopyOption.REPLACE_EXISTING)
    )

  // ── rift-verify seeding ──────────────────────────────────────────────────────────────────

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

  /** Create an imposter on a fresh port from a template whose `port` is `$port`. */
  private def seed(eng: SpawnedEngine, template: String): Task[Int] =
    for
      port <- TestPorts.next
      r <- send(
        s"${eng.adminUrl}/imposters",
        "POST",
        Some(template.replace("$port", port.toString))
      )
      _ <- ZIO.fail(new RuntimeException(s"seeding failed: ${r._1} ${r._2}")).unless(r._1 == 201)
    yield port

  private def imposterJson(eng: SpawnedEngine, port: Int): Task[Json] =
    send(s"${eng.adminUrl}/imposters/$port").flatMap(r =>
      ZIO.fromEither(r._2.fromJson[Json]).mapError(new RuntimeException(_))
    )

  private def numberOfRequests(j: Json): Option[Int] = j match
    case Json.Obj(fs) => fs.collectFirst { case ("numberOfRequests", Json.Num(n)) => n.intValue }
    case _ => None

  /** The recorded requests' `requestFrom` and header maps, in arrival order. */
  private def recorded(j: Json): List[(String, Map[String, String])] = j match
    case Json.Obj(fs) =>
      fs.collectFirst { case ("requests", Json.Arr(rs)) =>
        rs.toList.collect { case Json.Obj(r) =>
          val from = r.collectFirst { case ("requestFrom", Json.Str(s)) => s }.getOrElse("")
          val headers = r
            .collectFirst { case ("headers", Json.Obj(hs)) =>
              hs.collect { case (k, Json.Str(v)) => k -> v }.toMap
            }
            .getOrElse(Map.empty)
          from -> headers
        }
      }.getOrElse(Nil)
    case _ => Nil

  private def imposterCount(r: VerifyReport): Option[Int] =
    r.raw.fromJson[Json].toOption.flatMap {
      case Json.Obj(fs) => fs.collectFirst { case ("imposters", Json.Num(n)) => n.intValue }
      case _ => None
    }

  private val Static =
    """{"port":$port,"protocol":"http","recordRequests":true,"stubs":[{"predicates":[{"equals":{"path":"/st"}}],"responses":[{"is":{"statusCode":201,"body":"b"}}]}]}"""

  /** The second stub is shadowed by the first, so its own request gets the first stub's body. */
  private val Shadowed =
    """{"port":$port,"protocol":"http","stubs":[""" +
      """{"predicates":[{"equals":{"path":"/a"}}],"responses":[{"is":{"statusCode":200,"body":"a"}}]},""" +
      """{"predicates":[{"startsWith":{"path":"/a"}}],"responses":[{"is":{"statusCode":200,"body":"shadowed"}}]}]}"""

  private def cycling(expected: String): String =
    """{"port":$port,"protocol":"http","stubs":[{"predicates":[{"equals":{"path":"/ok"}}],""" +
      """"responses":[{"is":{"statusCode":200,"body":"exact"}},{"is":{"statusCode":200,"body":"second"}}],""" +
      s""""_verify":{"sequence":[{"request":{"path":"/ok"},"expect":{"bodyEquals":"$expected"}}]}}]}"""

  private val MalformedVerify =
    """{"port":$port,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200}},{"is":{"statusCode":201}}],"_verify":{"sequence":"x"}}]}"""

  private val Proxy =
    """{"port":$port,"protocol":"http","stubs":[{"responses":[{"proxy":{"to":"http://127.0.0.1:1"}}]}]}"""

  private val Spaced =
    """{"port":$port,"protocol":"http","recordRequests":true,"_rift":{"flowState":{"flowIdSource":"header:X-Space"}},""" +
      """"stubs":[{"space":"s1","predicates":[{"equals":{"path":"/data"}}],"responses":[{"is":{"statusCode":200,"body":"owner=s1"}}]}]}"""

  private val Dynamic = List("--skip-dynamic", "--verify-dynamic")

  def spec = suite("rift-lint and rift-verify options (#204, external)")(
    // ── rift-lint ──────────────────────────────────────────────────────────────────────────
    test("each diagnostic-code fixture reports its code at the documented severity") {
      SpawnedEngine.external { cfg =>
        ZIO
          .foreach(Codes) { (file, severity) =>
            tools(cfg)
              .riftLint(fixtures.resolve(file))
              .map { report =>
                val code = file.takeWhile(_ != '.')
                assertTrue(report.issues.exists(i => i.code == code && i.severity == severity))
                  .label(
                    s"$file → $code ($severity); got ${report.issues.map(i => s"${i.code}:${i.severity}")}"
                  )
              }
          }
          .map(_.reduce(_ && _))
      }
    },
    test("a warnings-only file exits 0, exits 1 under --strict, and -e hides the warning") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          file <- copyFixture("W016.json", dir, "warnings-only.json")
          plain <- tools(cfg).riftLint(file)
          strict <- tools(cfg).riftLint(file, List("--strict"))
          text <- tools(cfg).riftLintRaw(file, Nil)
          errorsOnly <- tools(cfg).riftLintRaw(file, List("-e"))
        yield assertTrue(
          plain.exitCode == 0,
          plain.warnings == 1,
          strict.exitCode == 1,
          (text.stdout + text.stderr).contains("W016"),
          !(errorsOnly.stdout + errorsOnly.stderr).contains("W016"),
          errorsOnly.exitCode == 0
        )
      }
    },
    test(
      "--fix quotes a numeric is.headers value in a single-imposter file, which then lints clean"
    ) {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          file <- write(dir, "header-number.json", HeaderNumber)
          before <- tools(cfg).riftLint(file)
          _ <- tools(cfg).riftLintRaw(file, List("--fix"))
          fixed <- read(file)
          after <- tools(cfg).riftLint(file)
        yield assertTrue(
          before.issues.exists(_.code == "E019"),
          fixed.contains("\"X-N\": \"7\""),
          after.errors == 0
        )
      }
    },
    test("--fix leaves a file with a repeated key and a YAML file untouched, saying why") {
      SpawnedEngine.external { cfg =>
        val dup = HeaderNumber.replace(
          "\"protocol\":\"http\"",
          "\"protocol\":\"http\",\"protocol\":\"http\""
        )
        val yaml =
          """- port: 4990
            |  protocol: http
            |  stubs:
            |    - responses:
            |        - is:
            |            statusCode: 200
            |            headers:
            |              X-N: 7
            |""".stripMargin
        for
          dir <- tempDir
          dupFile <- write(dir, "repeated-key.json", dup)
          yamlFile <- write(dir, "header-number.yaml", yaml)
          dupRun <- tools(cfg).riftLintRaw(dupFile, List("--fix"))
          yamlRun <- tools(cfg).riftLintRaw(yamlFile, List("--fix"))
          dupAfter <- read(dupFile)
          yamlAfter <- read(yamlFile)
        yield assertTrue(
          dupAfter == dup,
          (dupRun.stdout + dupRun.stderr).contains("repeated key: 'protocol'"),
          yamlAfter == yaml,
          (yamlRun.stdout + yamlRun.stderr).contains("--fix rewrites JSON only")
        )
      }
    },
    // rift#1238 (fixed in 0.19.0): `--fix` repairs every imposter in an `{"imposters":[…]}` wrapper
    // and a bare array, keeping the file's shape. Up to 0.18.1 it applied nothing to either.
    test(
      "--fix repairs an {\"imposters\":[…]} wrapper and a bare array, keeping the shape (rift#1238)"
    ) {
      SpawnedEngine.external { cfg =>
        val wrapped = s"""{"imposters":[$HeaderNumber]}"""
        val array = s"""[$HeaderNumber]"""
        for
          dir <- tempDir
          wFile <- write(dir, "wrapped.json", wrapped)
          aFile <- write(dir, "array.json", array)
          wRun <- tools(cfg).riftLintRaw(wFile, List("--fix"))
          aRun <- tools(cfg).riftLintRaw(aFile, List("--fix"))
          wAfter <- read(wFile)
          aAfter <- read(aFile)
          wLint <- tools(cfg).riftLint(wFile)
          aLint <- tools(cfg).riftLint(aFile)
        yield assertTrue(
          (wRun.stdout + wRun.stderr).contains("Applied 1 fixes"),
          (aRun.stdout + aRun.stderr).contains("Applied 1 fixes"),
          wAfter.trim.startsWith("{") && wAfter.contains("\"imposters\""),
          aAfter.trim.startsWith("["),
          wAfter.contains("\"X-N\": \"7\""),
          aAfter.contains("\"X-N\": \"7\""),
          wLint.errors == 0,
          aLint.errors == 0
        )
      }
    },
    // rift#1245 (fixed in 0.19.0): W004 stands down for a JSON Content-Type body that is JSON only
    // once rendered (a `_rift.templated` body); the same body with nothing rendering it still warns.
    test("W004 stands down for a templated JSON body and still fires untemplated (rift#1245)") {
      SpawnedEngine.external { cfg =>
        def imposter(rift: String) =
          """{"port":4991,"protocol":"http","stubs":[{"responses":[{"is":{"statusCode":200,""" +
            """"headers":{"Content-Type":"application/json"},""" +
            """"body":"{ \"visits\": {{ state.visits }} }"}""" + rift + "}]}]}"
        for
          dir <- tempDir
          templated <- write(dir, "templated.json", imposter(""","_rift":{"templated":true}"""))
          plain <- write(dir, "plain.json", imposter(""))
          t <- tools(cfg).riftLint(templated)
          p <- tools(cfg).riftLint(plain)
        yield assertTrue(!t.issues.exists(_.code == "W004"), p.issues.exists(_.code == "W004"))
      }
    },
    test("an unset process.env tag is W013; --no-parse lints the file verbatim with no W013") {
      SpawnedEngine.external { cfg =>
        val file = fixtures.resolve("W013.json")
        for
          rendered <- tools(cfg).riftLint(file)
          verbatim <- tools(cfg).riftLint(file, List("--no-parse"))
        yield assertTrue(
          rendered.issues.exists(_.code == "W013"),
          verbatim.issues.isEmpty,
          verbatim.exitCode == 0
        )
      }
    },
    test("a directory is read one level deep") {
      SpawnedEngine.external { cfg =>
        for
          dir <- tempDir
          _ <- copyFixture("W016.json", dir, "top.json")
          _ <- ZIO.attemptBlocking(Files.createDirectory(dir.resolve("nested")))
          _ <- copyFixture("E025.json", dir, "nested/deep.json")
          report <- tools(cfg).riftLint(dir)
        yield assertTrue(
          report.filesChecked == 1,
          report.issues.map(_.code) == List("W016"),
          report.exitCode == 0
        )
      }
    },
    // ── rift-verify ────────────────────────────────────────────────────────────────────────
    test("-p limits verification to one imposter") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Static)
          _ <- seed(eng, Static)
          one <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString))
          all <- tools(cfg).riftVerify(eng.adminUrl, Nil)
        yield assertTrue(
          imposterCount(one).contains(1),
          imposterCount(all).contains(2),
          one.passed == 1
        )
      }
    },
    test("--status-only passes a stub whose body differs; without it the stub fails") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Shadowed)
          full <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString))
          statusOnly <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString, "--status-only"))
        yield assertTrue(full.failed == 1, statusOnly.failed == 0, statusOnly.passed == 2)
      }
    },
    test("--dry-run sends nothing; -c prints the curl it sends") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Static)
          dry <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString, "--dry-run"))
          afterDry <- imposterJson(eng, p).map(numberOfRequests)
          curl <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString, "-c"))
          afterCurl <- imposterJson(eng, p).map(numberOfRequests)
        yield assertTrue(
          dry.failed == 0,
          afterDry.contains(0),
          (curl.raw + curl.rawErr).contains("curl -X GET 'http://"),
          (curl.raw + curl.rawErr).contains(s":$p/st'"),
          afterCurl.contains(1)
        )
      }
    },
    test("--gateway sends through the admin gateway, which records a :0 requestFrom") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Static)
          direct <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString))
          gateway <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString, "--gateway"))
          from <- imposterJson(eng, p).map(recorded(_).map(_._1))
        yield assertTrue(
          direct.passed == 1,
          gateway.passed == 1,
          from.size == 2,
          !from.head.endsWith(":0"),
          from(1) == "127.0.0.1:0"
        )
      }
    },
    test("--space/--flow-id-header verifies a space-scoped stub with the space header") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Spaced)
          r <- tools(cfg).riftVerify(
            eng.adminUrl,
            List("-p", p.toString, "--space", "s1", "--flow-id-header", "X-Space")
          )
          headers <- imposterJson(eng, p).map(recorded(_).map(_._2))
        yield assertTrue(
          r.passed == 1,
          r.failed == 0,
          headers.lastOption.flatMap(_.get("X-Space")).contains("s1")
        )
      }
    },
    test("_verify bodyEquals passes on the exact body and fails on a one-character diff") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          exact <- seed(eng, cycling("exact"))
          diff <- seed(eng, cycling("exacT"))
          ok <- tools(cfg).riftVerify(eng.adminUrl, List("-p", exact.toString) ++ Dynamic)
          bad <- tools(cfg).riftVerify(eng.adminUrl, List("-p", diff.toString) ++ Dynamic)
        yield assertTrue(ok.passed == 1, ok.failed == 0, bad.failed == 1, bad.passed == 0)
      }
    },
    test("a proxy stub is skipped under --skip-dynamic and passes with --verify-dynamic") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, Proxy)
          skipped <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString, "--skip-dynamic"))
          verified <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString) ++ Dynamic)
        yield assertTrue(
          skipped.skipped == 1,
          skipped.passed == 0,
          verified.passed == 1,
          verified.failed == 0
        )
      }
    },
    test("a malformed _verify fails its stub") {
      SpawnedEngine.external { cfg =>
        for
          eng <- SpawnedEngine.start(cfg)
          p <- seed(eng, MalformedVerify)
          // The same cycling stub without `_verify` is a plain SKIP: the failure is the annotation's.
          control <- seed(eng, MalformedVerify.replace(""","_verify":{"sequence":"x"}""", ""))
          r <- tools(cfg).riftVerify(eng.adminUrl, List("-p", p.toString) ++ Dynamic)
          c <- tools(cfg).riftVerify(eng.adminUrl, List("-p", control.toString) ++ Dynamic)
        yield assertTrue(r.failed == 1, r.exitCode != 0, c.failed == 0, c.exitCode == 0)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
