package samples.rift

import samples.rift.support.{RiftHarnessConfig, RiftTools}
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The `--allowInjection` door × payload matrix (#116, child of #115).
  *
  * rift's injection gate has been re-opened at a new door four times — rift#610 (fail-open on an
  * unparseable `_behaviors`), rift#612 (`--configfile`/`--datadir`/`POST /admin/reload` bypass),
  * rift#616 (FFI `serve_admin`), rift#657 (`POST /intercept/rules`). The maintainer's invariant
  * from the rift#612 triage is the thing under test: **the same document must get the same security
  * answer through every door.** A per-door test is what turns that sentence into a gate.
  *
  * Every engine here is spawned **without** `--allow-injection`, which the shared `RiftHarness`
  * cannot do — it always passes the flag, which is exactly why COVERAGE.md recorded this gate as
  * unexercised. Each case therefore owns a short-lived engine on its own admin port.
  *
  * External-only, and self-gating: a no-op pass unless `RIFT_BUILD_DIR` is set and `RIFT_MODE` is
  * not `embedded` (the embedded leg's natives lag at 0.13.1 and its gate is `serve_admin`'s option,
  * covered separately by [[AllowInjectionGate82Spec]]).
  *
  * Every door answers identically — `400` with `type: "invalid injection"` — so the invariant holds
  * in wording as well as in outcome, and each case is paired with a gate-**on** control proving the
  * refusal comes from the gate rather than from a schema rejection. That pairing is load-bearing:
  * the first cut of this spec sent a bare stub to the add-stub door and an `inject` *action* to the
  * intercept door, both of which are schema errors that are refused with `type: "bad data"` whether
  * the gate is open or shut — two assertions that looked green and tested nothing.
  */
object AllowInjectionMatrix116Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private val Inject = """function (config) { return { statusCode: 200, body: \"x\" }; }"""
  private val InjectPred = """function (config) { return true; }"""

  /** Payloads that must be refused at any door, gate off. Each is a full stub body. */
  private val ScriptedStubs: List[(String, String)] = List(
    "inject response" -> s"""{"responses":[{"inject":"$Inject"}]}""",
    "inject predicate" ->
      s"""{"predicates":[{"inject":"$InjectPred"}],"responses":[{"is":{"statusCode":200}}]}""",
    // rift#657 asked for exactly this: the classifier must see through `not`/`or` nesting.
    "nested not/or inject predicate" -> (
      s"""{"predicates":[{"or":[{"equals":{"path":"/a"}},{"not":{"inject":"$InjectPred"}}]}],""" +
        """"responses":[{"is":{"statusCode":200}}]}"""
    ),
    "_behaviors.decorate" ->
      """{"responses":[{"is":{"statusCode":200},"_behaviors":{"decorate":"function (r,s) { s.body='x'; }"}}]}""",
    "_behaviors.shellTransform" ->
      """{"responses":[{"is":{"statusCode":200},"_behaviors":{"shellTransform":"/bin/cat"}}]}""",
    "non-numeric wait" ->
      """{"responses":[{"is":{"statusCode":200},"_behaviors":{"wait":"function(){ return 1; }"}}]}""",
    "_rift.script" ->
      """{"responses":[{"_rift":{"script":{"engine":"rhai","code":"fn respond(ctx) { http(200,#{}) }"}}}]}""",
    // rift#610: an unparseable `_behaviors` must be treated as the DANGEROUS class, not as
    // "no script surface here". A classifier that fails open is the bug that shipped.
    "unparseable _behaviors (fail-closed)" ->
      """{"responses":[{"is":{"statusCode":200},"_behaviors":{"decorate":12345}}]}""",
    "predicateGenerators.inject" -> (
      """{"responses":[{"proxy":{"to":"http://127.0.0.1:1","mode":"proxyOnce",""" +
        s""""predicateGenerators":[{"inject":"$InjectPred"}]}}]}"""
    )
  )

  private val CleanStub = """{"responses":[{"is":{"statusCode":200,"body":"ok"}}]}"""

  private def imposter(port: Int, stub: String): String =
    s"""{"port":$port,"protocol":"http","stubs":[$stub]}"""

  private def send(url: String, method: String, body: Option[String]): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val publisher =
        body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody)
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(java.time.Duration.ofSeconds(10))
        .method(method, publisher)
        .build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      (r.statusCode, r.body)
    }

  private def errorType(body: String): Option[String] =
    """"type"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1))

  /** Spawn an engine on a free admin port with the given extra args and NO `--allow-injection`.
    *
    * Returns the admin URL plus the process's captured output, so the startup-door cases can assert
    * on the abort/skip message the engine prints rather than only on whether it bound a port.
    */
  private def engine(extraArgs: List[String], gateOn: Boolean = false, expectUp: Boolean = true)(
      using buildDir: Path
  ): ZIO[Scope, Throwable, (String, () => String)] =
    for
      port <- ZIO.attemptBlocking {
        val s = new java.net.ServerSocket(0)
        try s.getLocalPort
        finally s.close()
      }
      log <- ZIO.acquireRelease(
        ZIO.attemptBlocking(Files.createTempFile("rift-gate-", ".log"))
      )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)
      args = List(buildDir.resolve("rift").toString, "--port", port.toString, "--nologfile") ++
        (if gateOn then List("--allow-injection") else Nil) ++ extraArgs
      proc <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val pb = new ProcessBuilder(args*)
          pb.redirectErrorStream(true)
          pb.redirectOutput(log.toFile)
          pb.start()
        }
      )(p => ZIO.attemptBlocking { if p.isAlive then p.destroyForcibly(); () }.orDie)
      admin = s"http://127.0.0.1:$port"
      // An engine that refuses to start is a legitimate outcome here (the --configfile door), so
      // readiness is polled rather than required.
      _ <- send(s"$admin/imposters", "GET", None)
        .retry(Schedule.recurs(50) && Schedule.spaced(100.millis))
        .ignore
      _ <- ZIO.attemptBlocking(if proc.isAlive then () else ())
    yield (admin, () => new String(Files.readAllBytes(log), StandardCharsets.UTF_8))

  private def alive(admin: String): Task[Boolean] =
    send(s"$admin/imposters", "GET", None).map(_._1 == 200).catchAll(_ => ZIO.succeed(false))

  private def portsOf(admin: String): Task[List[Int]] =
    send(s"$admin/imposters", "GET", None).map { case (_, body) =>
      """"port"\s*:\s*(\d+)""".r.findAllMatchIn(body).map(_.group(1).toInt).toList.sorted
    }

  private def writeJson(dir: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking {
      val p = dir.resolve(name)
      Files.write(p, content.getBytes(StandardCharsets.UTF_8))
      p
    }

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-gate-")))(d =>
      ZIO.attemptBlocking {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      }.orDie
    )

  private val buildDirOpt: Option[Path] =
    sys.env.get("RIFT_BUILD_DIR").filter(_.trim.nonEmpty).map(java.nio.file.Paths.get(_))
  private val embedded =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private def external(effect: Path ?=> ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    buildDirOpt match
      case Some(bd) if !embedded => ZIO.scoped(effect(using bd))
      case _ => ZIO.succeed(assertCompletes)

  def spec = suite("allowInjection gate parity — door × payload matrix (external)")(
    // ── admin doors ────────────────────────────────────────────────────────────────────────────
    test("POST /imposters refuses every scripted payload and accepts a clean one") {
      external {
        for
          eng <- engine(Nil)
          admin = eng._1
          refused <- ZIO.foreach(ScriptedStubs.zipWithIndex) { case ((label, stub), i) =>
            send(s"$admin/imposters", "POST", Some(imposter(9100 + i, stub)))
              .map { case (code, body) => (label, code, errorType(body)) }
          }
          clean <- send(s"$admin/imposters", "POST", Some(imposter(9199, CleanStub)))
        yield
          val bad = refused.filterNot { case (_, code, t) =>
            code == 400 && t.contains("invalid injection")
          }
          assertTrue(
            bad.isEmpty,
            refused.size == 9,
            clean._1 == 201
          )
      }
    },
    test("PUT /imposters refuses a scripted batch") {
      external {
        for
          eng <- engine(Nil)
          admin = eng._1
          res <- send(
            s"$admin/imposters",
            "PUT",
            Some(s"""{"imposters":[${imposter(9200, ScriptedStubs.head._2)}]}""")
          )
        yield assertTrue(res._1 == 400, errorType(res._2).contains("invalid injection"))
      }
    },
    test("PUT /imposters accepts the same scripted batch with the gate ON") {
      external {
        for
          eng <- engine(Nil, gateOn = true)
          admin = eng._1
          res <- send(
            s"$admin/imposters",
            "PUT",
            Some(s"""{"imposters":[${imposter(9201, ScriptedStubs.head._2)}]}""")
          )
        yield assertTrue(res._1 == 200 || res._1 == 201)
      }
    },
    test("POST /imposters/{port}/stubs accepts the same scripted stub with the gate ON") {
      external {
        for
          eng <- engine(Nil, gateOn = true)
          admin = eng._1
          created <- send(s"$admin/imposters", "POST", Some(imposter(9301, CleanStub)))
          added <- send(
            s"$admin/imposters/9301/stubs",
            "POST",
            Some(s"""{"stub":${ScriptedStubs.head._2}}""")
          )
        yield assertTrue(created._1 == 201, added._1 == 200)
      }
    },
    test("POST /imposters/{port}/stubs refuses adding a scripted stub to a clean imposter") {
      external {
        for
          eng <- engine(Nil)
          admin = eng._1
          created <- send(s"$admin/imposters", "POST", Some(imposter(9300, CleanStub)))
          // This door takes a `{"stub": …}` envelope; a bare stub is a SCHEMA error refused either
          // way, which would make the assertion pass with the gate wide open.
          added <- send(
            s"$admin/imposters/9300/stubs",
            "POST",
            Some(s"""{"stub":${ScriptedStubs.head._2}}""")
          )
        yield assertTrue(
          created._1 == 201,
          added._1 == 400,
          errorType(added._2).contains("invalid injection")
        )
      }
    },
    // ── startup doors ──────────────────────────────────────────────────────────────────────────
    test("--configfile aborts startup, naming the file and the offending port") {
      external {
        for
          dir <- tempDir
          cfg <- writeJson(
            dir,
            "poisoned.json",
            s"""{"imposters":[${imposter(9400, ScriptedStubs.head._2)}]}"""
          )
          eng <- engine(List("--configfile", cfg.toString), expectUp = false)
          admin = eng._1
          out = eng._2
          up <- alive(admin)
          text = out()
        yield assertTrue(
          !up,
          text.contains(cfg.toString),
          text.contains("9400"),
          text.contains("--allowInjection")
        )
      }
    },
    test("--configfile with the gate ON serves the same file") {
      external {
        for
          dir <- tempDir
          cfg <- writeJson(
            dir,
            "poisoned.json",
            s"""{"imposters":[${imposter(9401, ScriptedStubs.head._2)}]}"""
          )
          eng <- engine(List("--configfile", cfg.toString), gateOn = true)
          admin = eng._1
          up <- alive(admin)
        yield assertTrue(up)
      }
    },
    // Also the restart-persistence case from the rift#612 rationale: an imposter that was persisted
    // while the gate was on must be SKIPPED, not executed, when the engine restarts without it.
    test("--datadir skips only the scripted file and still serves the clean one") {
      external {
        for
          dir <- tempDir
          _ <- writeJson(dir, "scripted.json", imposter(9500, ScriptedStubs.head._2))
          _ <- writeJson(dir, "clean.json", imposter(9501, CleanStub))
          eng <- engine(List("--datadir", dir.toString))
          admin = eng._1
          out = eng._2
          up <- alive(admin)
          ports <- portsOf(admin)
          text = out()
        yield assertTrue(
          up,
          ports.contains(9501),
          !ports.contains(9500),
          text.contains("skipped"),
          text.contains("scripted.json")
        )
      }
    },
    test("POST /admin/reload refuses a poisoned config and leaves running imposters untouched") {
      external {
        for
          dir <- tempDir
          cfg <- writeJson(dir, "cfg.json", s"""{"imposters":[${imposter(9600, CleanStub)}]}""")
          eng <- engine(List("--configfile", cfg.toString))
          admin = eng._1
          before <- portsOf(admin)
          _ <- writeJson(
            dir,
            "cfg.json",
            s"""{"imposters":[${imposter(9601, ScriptedStubs.head._2)}]}"""
          )
          reloaded <- send(s"$admin/admin/reload", "POST", Some("{}"))
          after <- portsOf(admin)
        yield assertTrue(
          before == List(9600),
          reloaded._1 == 400,
          errorType(reloaded._2).contains("invalid injection"),
          // The bypass rift#612 fixed was a reload mutating state it had no right to touch.
          after == before
        )
      }
    },
    // ── intercept door (rift#657/#659, landed in 0.14.0) ───────────────────────────────────────
    test("POST /intercept/rules refuses an inject rule and accepts a clean serve rule") {
      external {
        for
          // Released before the engine spawns — it must be, since the engine binds this port. A
          // collision with the engine's own admin probe surfaces as a loud bind failure in the
          // captured log, never as a silent pass.
          interceptPort <- ZIO.attemptBlocking {
            val s = new java.net.ServerSocket(0)
            try s.getLocalPort
            finally s.close()
          }
          eng <- engine(List("--intercept-port", interceptPort.toString))
          admin = eng._1
          clean <- send(
            s"$admin/intercept/rules",
            "POST",
            Some(
              """{"host":"example.com","action":{"serve":{"statusCode":200,""" +
                """"headers":{"content-type":"text/plain"},"body":"ok"}}}"""
            )
          )
          // `inject` is not an action variant at all (that shape is a schema error refused with or
          // without the gate); a rule's injectable surface is its predicates.
          injected <- send(
            s"$admin/intercept/rules",
            "POST",
            Some(
              s"""{"host":"example.com","predicates":[{"inject":"$InjectPred"}],""" +
                """"action":{"serve":{"statusCode":200,"headers":{"content-type":"text/plain"},"body":"ok"}}}"""
            )
          )
        yield assertTrue(
          clean._1 == 201,
          injected._1 == 400,
          errorType(injected._2).contains("invalid injection")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
