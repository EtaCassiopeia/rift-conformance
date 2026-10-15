package samples.rift

import samples.rift.support.{RawHttpConnection, RawRead, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The declarative `intercept` block in `--configfile` (#113, rift#655, shipped in 0.14.0).
  *
  * `12-intercept.feature` covers intercept well, but every scenario installs rules **imperatively**
  * over the admin API. The block exists precisely to delete that step — and with it the bootstrap
  * sidecar and its startup race — so one file brings the listener up with its rules already seeded.
  * That is the surface pinned here. (The engine seeds them *before* the listener binds; these cases
  * observe the settled state after boot, so they pin "seeded with no admin call", not the
  * ordering.)
  *
  * These cases cannot reuse the shared harness engine: it always passes `--intercept-port`, and
  * rift#655 makes "block **+** flag" a hard startup error rather than a silent precedence guess.
  * Each case therefore spawns its own engine from a config file and nothing else.
  *
  * Scope: this asserts the **declarative wiring** — that the listener and its rules exist at boot
  * with no admin call, that the two startup-error doors fire, that runtime rules layer on top, and
  * that `POST /admin/reload` says plainly it did not re-apply the block. The TLS-MITM *data* path
  * (CONNECT tunnel, CA trust, serve/forward) is already pinned imperatively by feature 12 and is
  * not re-litigated here.
  *
  * External-only, self-gating: a no-op pass unless `RIFT_BUILD_DIR` is set and `RIFT_MODE` is not
  * `embedded` (the embedded leg drives intercept over the C-ABI and its natives lag at 0.13.1).
  */
object ConfigInterceptBlock113Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

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

  /** Hosts of the rules the listener currently holds, in order. */
  private def ruleHosts(admin: String): Task[List[String]] =
    send(s"$admin/intercept/rules", "GET", None).map { case (_, body) =>
      """"host"\s*:\s*"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).toList
    }

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-cfg-intercept-")))(d =>
      ZIO.attemptBlocking {
        val walk = Files.walk(d)
        try walk.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        finally walk.close()
      }.ignore
    )

  private def writeConfig(dir: Path, name: String, json: String): Task[Path] =
    ZIO.attemptBlocking {
      val p = dir.resolve(name)
      Files.write(p, json.getBytes(StandardCharsets.UTF_8))
      p
    }

  /** Spawn an engine from a config file, with no `--intercept-*` flag unless one is asked for.
    *
    * `expectUp = false` is how the two startup-error cases assert: refusing to boot is the
    * behaviour under test there, so readiness is observed rather than required, and the captured
    * output is returned so the assertion can name what the engine actually said.
    */
  private def engine(
      cfg: Path,
      extra: List[String] = Nil,
      expectUp: Boolean = true,
      allowInjection: Boolean = true
  )(using buildDir: Path): ZIO[Scope, Throwable, (String, () => String)] =
    for
      port <- TestPorts.next
      log <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempFile("rift-cfg-", ".log")))(p =>
        ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie
      )
      args = List(
        buildDir.resolve("rift").toString,
        "--port",
        port.toString,
        "--nologfile",
        "--configfile",
        cfg.toString
      ) ++ (if allowInjection then List("--allow-injection") else Nil) ++ extra
      _ <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val pb = new ProcessBuilder(args*)
        pb.redirectErrorStream(true)
        pb.redirectOutput(log.toFile)
        pb.start()
      })(p =>
        ZIO.attemptBlocking {
          // destroyForcibly is asynchronous; without the wait a dying engine can still hold its
          // ports when the next sequential case probes for a free one.
          if p.isAlive then p.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
          ()
        }.orDie
      )
      admin = s"http://127.0.0.1:$port"
      readLog = () => new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
      up <- send(s"$admin/imposters", "GET", None)
        .retry(Schedule.recurs(50) && Schedule.spaced(100.millis))
        .as(true)
        .catchAll(_ => ZIO.succeed(false))
      _ <- ZIO
        .fail(new RuntimeException(s"engine never became ready on $admin\n${readLog()}"))
        .when(expectUp && !up)
    yield (admin, readLog)

  private def serveRule(host: String, body: String) =
    s"""{"host":"$host","action":{"serve":{"statusCode":200,""" +
      s""""headers":{"content-type":"text/plain"},"body":"$body"}}}"""

  private def forwardRule(host: String, port: Int) =
    s"""{"host":"$host","action":{"forward":{"port":$port}}}"""

  /** The wrapper form the block is only read from: `{"imposters":[…],"intercept":{…}}`. */
  private def wrapperConfig(imposterPort: Int, interceptPort: Int, rules: String): String =
    s"""{"imposters":[{"port":$imposterPort,"protocol":"http","stubs":[""" +
      """{"responses":[{"is":{"statusCode":200,"body":"imp"}}]}]}],""" +
      s""""intercept":{"port":$interceptPort,"rules":[$rules]}}"""

  private val buildDirOpt: Option[Path] =
    sys.env.get("RIFT_BUILD_DIR").filter(_.trim.nonEmpty).map(java.nio.file.Paths.get(_))
  private val embedded =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private def external(effect: Path ?=> ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    buildDirOpt match
      case Some(bd) if !embedded => ZIO.scoped(effect(using bd))
      case _ => ZIO.succeed(assertCompletes)

  def spec = suite("declarative intercept block in --configfile (external)")(
    test("the block brings up the listener with its rules seeded, with no admin call") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          // Two rules, including a `forward` at the imposter declared in the SAME document — the
          // point of the block is that both halves are wired to each other at boot.
          cfg <- writeConfig(
            dir,
            "declared.json",
            wrapperConfig(
              impPort,
              icPort,
              serveRule("declared.example", "from-config") + "," +
                forwardRule("forwarded.example", impPort)
            )
          )
          eng <- engine(cfg)
          admin = eng._1
          // The only calls made are READS — nothing installs a rule.
          statusRes <- send(s"$admin/intercept", "GET", None)
          hosts <- ruleHosts(admin)
          // The admin JSON only proves the block was parsed and recorded. A regression that
          // populates state but skips the bind would still answer 200 here, so connect to it.
          bound <- ZIO
            .attemptBlocking {
              val sock = new java.net.Socket()
              try
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", icPort), 3000)
                true
              finally sock.close()
            }
            .catchAll(_ => ZIO.succeed(false))
        yield assertTrue(
          bound,
          statusRes._1 == 200,
          statusRes._2.contains(s""""interceptPort": $icPort"""),
          hosts.contains("declared.example"),
          // The forward rule points at an imposter declared in the same document.
          hosts.contains("forwarded.example")
        )
      }
    },
    // rift#933: a block rule's serve body may be a JSON object, as on the admin door. It must boot,
    // list back as an object, and serve its compact rendering through the block's own listener.
    test(
      "a block rule with a JSON object body boots, lists as an object, and serves compact JSON"
    ) {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          rule =
            """{"host":"object.example","action":{"serve":{"statusCode":200,"body":{"featureX":"ON"}}}}"""
          cfg <- writeConfig(dir, "object-body.json", wrapperConfig(impPort, icPort, rule))
          eng <- engine(cfg)
          admin = eng._1
          listed <- send(s"$admin/intercept/rules", "GET", None)
          listedBody = listed._2
            .fromJson[Json]
            .toOption
            .flatMap(_.asArray)
            .flatMap(_.headOption)
            .flatMap(
              _.get(
                JsonCursor.field("action").isObject.field("serve").isObject.field("body")
              ).toOption
            )
          truststore <- ZIO.attemptBlocking {
            val req = HttpRequest
              .newBuilder(URI.create(s"$admin/intercept/truststore.p12?password=changeit"))
              .GET()
              .build()
            client.send(req, HttpResponse.BodyHandlers.ofByteArray()).body
          }
          served <- ZIO.attemptBlocking {
            val ssl = RawHttpConnection.sslTrusting(truststore, "changeit")
            val conn = RawHttpConnection.tunnel("127.0.0.1", icPort, "object.example", ssl)
            try conn.exchange(RawHttpConnection.request("GET", "object.example", "/"))
            finally conn.close()
          }
          servedBody = served match
            case RawRead.Response(r) if r.status == 200 => Some(r.bodyText)
            case _ => None
        yield assertTrue(
          listedBody.contains(Json.Obj("featureX" -> Json.Str("ON"))),
          servedBody.contains("""{"featureX":"ON"}""")
        )
      }
    },
    test("a runtime rule layers on top of the config-seeded set") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          cfg <- writeConfig(
            dir,
            "declared.json",
            wrapperConfig(impPort, icPort, serveRule("declared.example", "from-config"))
          )
          eng <- engine(cfg)
          admin = eng._1
          before <- ruleHosts(admin)
          added <- send(
            s"$admin/intercept/rules",
            "POST",
            Some(serveRule("runtime.example", "from-runtime"))
          )
          after <- ruleHosts(admin)
        yield assertTrue(
          before == List("declared.example"),
          added._1 == 201,
          after == List("declared.example", "runtime.example")
        )
      }
    },
    // rift#1271 (0.20.0): reload re-applies the block's rules and reports
    // `"intercept": {"rulesSeeded", "rulesRuntime"}`; before that it left them alone and warned. The
    // report's presence is what tells the two apart, so this pins whichever the engine has.
    test("POST /admin/reload re-applies the block's rules, or warns it did not (before 0.20.0)") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          cfg <- writeConfig(
            dir,
            "declared.json",
            wrapperConfig(impPort, icPort, serveRule("declared.example", "from-config"))
          )
          eng <- engine(cfg)
          admin = eng._1
          before <- ruleHosts(admin)
          reloaded <- send(s"$admin/admin/reload", "POST", Some("{}"))
          after <- ruleHosts(admin)
          reApplies = reloaded._2.contains("rulesSeeded")
          // An edited rule takes effect on the next reload when the engine re-applies the block.
          _ <- ZIO.when(reApplies)(
            writeConfig(
              dir,
              "declared.json",
              wrapperConfig(impPort, icPort, serveRule("edited.example", "from-edit"))
            ) *> send(s"$admin/admin/reload", "POST", Some("{}"))
          )
          edited <- ruleHosts(admin)
        yield assertTrue(
          reloaded._1 == 200,
          after == before,
          // Silence would be the bug either way: a block that never took effect must not look
          // applied, so an engine that does not re-apply must say so.
          if reApplies then
            !reloaded._2.contains("NOT re-applied") && edited == List("edited.example")
          else reloaded._2.contains("warnings") && reloaded._2.contains("NOT re-applied")
        )
      }
    },
    test("the block plus --intercept-port is a startup error naming both") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          clashPort <- TestPorts.next
          cfg <- writeConfig(
            dir,
            "declared.json",
            wrapperConfig(impPort, icPort, serveRule("declared.example", "from-config"))
          )
          eng <- engine(cfg, List("--intercept-port", clashPort.toString), expectUp = false)
          admin = eng._1
          up <- send(s"$admin/imposters", "GET", None)
            .map(_._1 == 200)
            .catchAll(_ => ZIO.succeed(false))
          text = eng._2()
        yield assertTrue(
          !up,
          text.contains("`intercept` block"),
          text.contains("--intercept-port"),
          // The env spelling matters: clap reads RIFT_INTERCEPT_* too, so a user hitting this from
          // the environment needs to be told where to look.
          text.contains("RIFT_INTERCEPT_")
        )
      }
    },
    test("a block rule carrying an inject predicate is refused when the gate is off") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          poisoned =
            s"""{"host":"declared.example","predicates":[{"inject":"function (c) { return true; }"}],""" +
              """"action":{"serve":{"statusCode":200,"headers":{"content-type":"text/plain"},"body":"x"}}}"""
          cfg <- writeConfig(dir, "poisoned.json", wrapperConfig(impPort, icPort, poisoned))
          // Gate OFF: the block's rules carry a scripting surface like any config-file imposter, so
          // the same --allowInjection answer must apply here (rift#657/#659 door parity).
          off <- engine(cfg, expectUp = false, allowInjection = false)
          offUp <- send(s"${off._1}/imposters", "GET", None)
            .map(_._1 == 200)
            .catchAll(_ => ZIO.succeed(false))
          offText = off._2()
          on <- engine(cfg)
          onUp <- send(s"${on._1}/imposters", "GET", None)
            .map(_._1 == 200)
            .catchAll(_ => ZIO.succeed(false))
        yield assertTrue(!offUp, offText.contains("allowInjection"), onUp)
      }
    },
    test("a block outside the imposters-wrapper form is a startup error naming the fix") {
      external {
        for
          dir <- tempDir
          impPort <- TestPorts.next
          icPort <- TestPorts.next
          // Single-imposter document: the block would be silently ignored, so the engine refuses.
          cfg <- writeConfig(
            dir,
            "single.json",
            s"""{"port":$impPort,"protocol":"http","stubs":[],""" +
              s""""intercept":{"port":$icPort,"rules":[${serveRule("declared.example", "x")}]}}"""
          )
          eng <- engine(cfg, expectUp = false)
          admin = eng._1
          up <- send(s"$admin/imposters", "GET", None)
            .map(_._1 == 200)
            .catchAll(_ => ZIO.succeed(false))
          text = eng._2()
        yield assertTrue(
          !up,
          text.contains("wrapper form"),
          text.contains("\"imposters\"")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
