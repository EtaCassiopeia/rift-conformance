package samples.rift

import samples.rift.support.RiftTools
import zio.*
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.ServerSocket
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Replay of rift's own shipped `examples` documents as a **load-and-behave** oracle (#120).
  *
  * rift#608 shipped an engine whose `_behaviors` were silently dropped: `examples/latency-testing`
  * still *loaded* cleanly, so every load-only check stayed green while `/random-latency` answered
  * with no delay at all. The lesson is that "the document was accepted" is not the property worth
  * asserting — "the document does what it advertises" is. So each example is loaded **verbatim**
  * and then its headline behavior is driven and asserted to actually take effect.
  *
  * Three gates, in the order a regression would trip them:
  *   1. every shipped example loads → 201 (and the engine really registered it), 2. each example's
  *      advertised behavior fires — asserted on the response **body**, not just the status, so a
  *      wrong-stub match cannot pass, 3. `rift-lint` over the same directory → 0 errors.
  *
  * Inputs are vendored under `corpus/rift-examples/` (see its PROVENANCE.md): the release tarball
  * ships no `examples/`, and vendoring keeps this spec hermetic. `scripts/sync-rift-examples.sh
  * --check` is what catches a stale copy at pin-bump time.
  *
  * Up to v0.18.0 four of the six examples declare port 4545, so they cannot be co-loaded. One
  * engine serves all of them: load → drive → `DELETE /imposters/{port}` → next. Each example is
  * served on the port its own document declares (read from the file, not hardcoded here), so a pin
  * bump that re-vendors examples with new ports (rift#1218 moves three off 4545) needs no code
  * change here. Rewriting the ports would stop the oracle from replaying the examples *as shipped*.
  *
  * Self-gating on `RIFT_BUILD_DIR` like the other tool gates. External-leg only (spawns a real
  * engine + `rift-lint`): absent from the enumerated `engineEmbedded`/`engineContainer` aliases,
  * whose natives still lag the pin.
  */
object ShippedExamples120Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()
  private def buildDir: Option[Path] = sys.env.get("RIFT_BUILD_DIR").map(Paths.get(_))
  private val examplesDir = Paths.get("corpus/rift-examples")

  /** A driven request and what the example promises it answers. `bodyContains` is the part that
    * makes the gate discriminating — several stubs in the same document share a status, so status
    * alone would pass on a wrong-stub match.
    */
  private final case class Drive(
      method: String,
      path: String,
      status: Int,
      bodyContains: String,
      body: Option[String] = None,
      headers: Map[String, String] = Map.empty,
      minMillis: Long = 0L,
      expectNoMatch: Boolean = false
  )

  private final case class Example(file: String, drives: List[Drive])

  private val Json_ = Map("Content-Type" -> "application/json")

  /** The headline behavior of each shipped example. Every expectation here was confirmed against a
    * live 0.15.0 engine before being written down — this table states what the examples *do*, and
    * is deliberately independent of the documents so that an upstream edit shows up as a failure.
    */
  private val examples = List(
    Example(
      "basic-api.json",
      List(
        // Plain-text "OK", not a JSON envelope — and unique among this document's four bodies.
        Drive("GET", "/health", 200, "OK"),
        Drive("GET", "/api/users", 200, "Bob"),
        // The `and`+`matches` regex stub, not the bare-collection stub above it: the single-user
        // body is what proves the path regex actually discriminated.
        Drive("GET", "/api/users/42", 200, "alice@example.com"),
        Drive("POST", "/api/users", 201, "Created", Some("""{"name":"Zed"}"""), Json_),
        // Negative control for the no-match guard itself: every other drive treats
        // `x-rift-no-match` as a failure, which would go quietly inert if the engine ever renamed
        // or dropped the header. This asserts the header IS still emitted on a genuine miss.
        Drive("GET", "/no-such-path", 200, "", expectNoMatch = true)
      )
    ),
    Example(
      "error-testing.json",
      List(
        Drive("GET", "/success", 200, "\"ok\""),
        Drive("GET", "/error/400", 400, "INVALID_INPUT"),
        Drive("GET", "/error/401", 401, "Unauthorized"),
        Drive("GET", "/error/403", 403, "Forbidden"),
        Drive("GET", "/error/404", 404, "Not Found"),
        Drive("GET", "/error/429", 429, "Too Many Requests"),
        Drive("GET", "/error/500", 500, "Internal Server Error"),
        Drive("GET", "/error/502", 502, "Bad Gateway"),
        Drive("GET", "/error/503", 503, "Service Unavailable"),
        Drive("GET", "/error/504", 504, "Gateway Timeout")
      )
    ),
    Example(
      "authentication-api.json",
      List(
        Drive(
          "POST",
          "/auth/login",
          200,
          "Bearer",
          Some("""{"username":"admin","password":"secret123"}"""),
          Json_
        ),
        // `contains` username AND `not`/`equals` on the password — the negated-predicate path that
        // rift#657 showed the injection classifier could see straight through.
        Drive(
          "POST",
          "/auth/login",
          401,
          "AUTH_INVALID_CREDENTIALS",
          Some("""{"username":"admin","password":"wrong"}"""),
          Json_
        ),
        Drive(
          "GET",
          "/auth/validate",
          200,
          "\"valid\"",
          None,
          Map("Authorization" -> "Bearer abc")
        ),
        // Same path, no header: `not`/`exists` on headers has to flip the answer.
        Drive("GET", "/auth/validate", 401, "AUTH_MISSING_TOKEN")
      )
    ),
    Example(
      "feature-flags-api.json",
      List(
        // Matched on the per-flag `description`, NOT the flag id: the collection stub below returns
        // a body containing every flag id, so asserting "DARK_MODE" would still pass if path
        // discrimination broke and the collection answered instead.
        Drive("GET", "/features/DARK_MODE", 200, "Enable dark mode UI theme"),
        Drive("GET", "/features/BETA_FEATURE", 200, "New experimental feature"),
        Drive("GET", "/features/UNKNOWN_FOO", 404, "not found"),
        Drive("GET", "/features", 200, "\"features\"")
      )
    ),
    Example(
      "task-management-api.json",
      List(
        // `"count":3`, not just `"count"` — the filtered stub also carries a count field.
        Drive("GET", "/tasks", 200, "\"count\":3"),
        Drive("GET", "/tasks/task-001", 200, "alice@example.com"),
        // Matching is first-match-wins. Up to v0.17 these two stubs were shadowed by more general
        // ones listed before them (achird-labs/rift#805, surfaced by this spec); the v0.18.0
        // examples list the specific stubs first (rift#808), so they answer as advertised.
        Drive("GET", "/tasks?status=OPEN", 200, "\"count\":1"),
        Drive("GET", "/tasks/task-999", 404, "TASK_NOT_FOUND"),
        // jsonpath `$.name` present vs absent — the 201/400 split is the predicate doing real work.
        Drive("POST", "/tasks", 201, "task-new-001", Some("""{"name":"New"}"""), Json_),
        Drive("POST", "/tasks", 400, "VALIDATION_ERROR", Some("""{"nope":"x"}"""), Json_),
        // Full message, not "updated" — the task-detail body carries an `updatedAt` field.
        Drive(
          "PUT",
          "/tasks/task-001",
          200,
          "Task updated successfully",
          Some("""{"name":"U"}"""),
          Json_
        ),
        Drive("DELETE", "/tasks/task-001", 204, "")
      )
    ),
    Example(
      "latency-testing.json",
      List(
        Drive("GET", "/slow-100ms", 200, "100ms delay", minMillis = 100),
        Drive("GET", "/slow-500ms", 200, "500ms delay", minMillis = 500),
        // `{"wait": {"inject": "function(){…}"}}` — the exact shape rift#608 silently dropped, and
        // the reason this spec asserts elapsed time at all. Floor is the function's own minimum.
        Drive("GET", "/random-latency", 200, "Random latency", minMillis = 100)
      )
    )
    // Deliberately not driven: `/timeout` (wait 35000) would dominate the suite's runtime, `/slow-2s`
    // adds 2s to prove nothing the 100ms/500ms floors don't already prove, and `/fast` has no
    // behavior to take effect. Same for authentication's user-not-found and logout stubs: they are
    // plain status responses with no predicate or behavior machinery behind them.
  )

  private def freePort: Task[Int] =
    ZIO.attemptBlocking {
      val s = new ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  private def spawnEngine(bd: Path, port: Int, metricsPort: Int): ZIO[Scope, Throwable, Process] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val log = Files.createTempFile(s"shipped-examples-engine-$port-", ".log").toFile
        log.deleteOnExit()
        val pb = new ProcessBuilder(
          bd.resolve("rift").toString,
          "--port",
          port.toString,
          // latency-testing's `wait` uses `inject`; without this the document is refused outright.
          "--allow-injection",
          "--nologfile",
          "--metrics-port",
          metricsPort.toString,
          "--loglevel",
          "error"
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        pb.start()
      }
    )(proc =>
      // Not `.ignore`: a rift process that refuses to die leaks for the rest of the JVM's life and
      // would otherwise leave nothing to correlate a later port conflict against.
      ZIO
        .attemptBlocking(if proc.isAlive then proc.destroyForcibly(): Unit)
        .catchAll(e => ZIO.logWarning(s"failed to kill engine pid=${proc.pid()}: ${e.getMessage}"))
    )

  private def awaitAdmin(port: Int, proc: Process): Task[Unit] =
    val probe = ZIO.attemptBlocking {
      val req =
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/imposters")).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
    ZIO
      .fail(new RuntimeException("engine exited before admin came up"))
      .when(!proc.isAlive)
      .zipRight(probe.filterOrFail(_ == 200)(new RuntimeException("admin not 200 yet")))
      .retry(Schedule.spaced(250.millis) && Schedule.recurs(80))
      .unit

  private def readExample(file: String): Task[String] =
    ZIO.attemptBlocking(Files.readString(examplesDir.resolve(file)))

  /** Each vendored file is a `{"imposters":[…]}` wrapper; the engine's create door takes a single
    * imposter, so unwrap without otherwise touching the document.
    */
  private def imposterOf(raw: String): Task[String] =
    ZIO
      .fromEither(
        for
          j <- raw.fromJson[Json].left.map(e => s"unparseable example: $e")
          arr <- j.get(JsonCursor.field("imposters").isArray).left.map(e => s"no imposters[]: $e")
          first <- arr.elements.headOption.toRight("imposters[] is empty")
        yield first.toString
      )
      .mapError(new RuntimeException(_))

  /** The port the example document itself declares. Every shipped example pins one; a document
    * without it would be auto-assigned a port the drives could not know, so that is a failure.
    */
  private def declaredPort(file: String, imposter: String): Task[Int] =
    ZIO
      .fromEither(
        imposter
          .fromJson[Json]
          .flatMap(_.get(JsonCursor.field("port").isNumber))
          .map(_.value.intValue)
          .left
          .map(e => s"$file: no numeric top-level port in its imposter ($e)")
      )
      .mapError(new RuntimeException(_))

  private def adminPost(adminPort: Int, body: String): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$adminPort/imposters"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

  private def adminDelete(adminPort: Int, port: Int): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$adminPort/imposters/$port"))
        .DELETE()
        .build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  private def registeredPorts(adminPort: Int): Task[List[Int]] =
    for
      body <- ZIO.attemptBlocking {
        val req =
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$adminPort/imposters")).GET().build()
        client.send(req, HttpResponse.BodyHandlers.ofString()).body()
      }
      ports <- ZIO
        .fromEither(
          for
            j <- body.fromJson[Json].left.map(e => s"bad /imposters response: $e")
            arr <- j.get(JsonCursor.field("imposters").isArray).left.map(e => s"no imposters[]: $e")
            // Propagated, not skipped: silently dropping an imposter whose `port` is missing or
            // non-numeric would resurface as a misleading "port not registered" instead of naming
            // the malformed admin response.
            parsed = arr.elements.toList.map(
              _.get(JsonCursor.field("port").isNumber).map(_.value.intValue)
            )
            ports <- parsed.collectFirst { case Left(e) => e } match
              case Some(e) => Left(s"imposter entry without a numeric port: $e")
              case None => Right(parsed.collect { case Right(p) => p })
          yield ports
        )
        .mapError(new RuntimeException(_))
    yield ports

  private final case class Answer(status: Int, body: String, elapsedMillis: Long, noMatch: Boolean)

  private def drive(port: Int, d: Drive): Task[Answer] =
    ZIO.attemptBlocking {
      val builder = d.headers.foldLeft(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port${d.path}"))
      )((b, h) => b.header(h._1, h._2))
      val publisher =
        d.body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
      val req =
        builder.method(d.method, publisher).timeout(java.time.Duration.ofSeconds(25)).build()
      val started = java.lang.System.nanoTime()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      val elapsed = (java.lang.System.nanoTime() - started) / 1000000L
      // x-rift-no-match means the engine fell through to its default response — the failure mode a
      // status-only assertion would happily accept.
      val noMatch = resp.headers().firstValue("x-rift-no-match").isPresent
      Answer(resp.statusCode(), resp.body(), elapsed, noMatch)
    }

  /** Load one example verbatim, drive it, then remove it so the next example can claim the port. */
  private def replay(adminPort: Int, ex: Example): Task[List[String]] =
    for
      raw <- readExample(ex.file)
      imposter <- imposterOf(raw)
      port <- declaredPort(ex.file, imposter)
      created <- adminPost(adminPort, imposter)
      ports <- registeredPorts(adminPort)
      // `onError` only — a drive that throws (connection reset, the 25s HTTP timeout) still frees
      // the port, or the next example declaring the same port fails to bind and buries the real cause. On the
      // success path this does not run, so the explicit delete below is the one whose status counts.
      answers <- ZIO
        .foreach(ex.drives)(d => drive(port, d).map(d -> _))
        .onError(_ => adminDelete(adminPort, port).ignore)
      deleted <- adminDelete(adminPort, port)
    yield
      val loadFailures =
        // A failed DELETE is reported here rather than discarded: examples may share a port, so
        // otherwise it resurfaces as a bogus "create returned 400" against the *next* example.
        Option
          .when(deleted != 200 && deleted != 204)(
            s"${ex.file}: DELETE /imposters/${port} returned $deleted"
          )
          .toList ++
          Option
            .when(created != 201)(s"${ex.file}: create returned $created, expected 201")
            .toList ++
          Option.when(!ports.contains(port))(
            s"${ex.file}: port ${port} not registered (got $ports)"
          )
      val driveFailures = answers.flatMap { (d, a) =>
        val where = s"${ex.file} ${d.method} ${d.path}"
        Option
          .when(a.noMatch != d.expectNoMatch)(
            if d.expectNoMatch then s"$where: expected a no-match miss, but a stub answered"
            else s"$where: no stub matched (x-rift-no-match)"
          )
          .toList ++
          Option.when(a.status != d.status)(s"$where: status ${a.status}, expected ${d.status}") ++
          Option.when(!a.body.contains(d.bodyContains))(
            s"$where: body did not contain '${d.bodyContains}' (got '${a.body.take(80)}')"
          ) ++
          Option.when(a.elapsedMillis < d.minMillis)(
            s"$where: answered in ${a.elapsedMillis}ms, expected >= ${d.minMillis}ms"
          )
      }
      loadFailures ++ driveFailures

  private def withEngine[A](use: Int => Task[A]): ZIO[Scope, Throwable, Option[A]] =
    buildDir match
      case None => ZIO.none
      case Some(bd) =>
        for
          port <- freePort
          // Each freePort closes its socket before returning, so two calls can hand back the same
          // number — which would spawn the engine with --port == --metrics-port and fail to bind.
          metricsPort <- freePort.repeatUntil(_ != port)
          proc <- spawnEngine(bd, port, metricsPort)
          _ <- awaitAdmin(port, proc)
          a <- use(port)
        yield Some(a)

  /** Every vendored example file, from disk. The drive table is hand-curated, so the failure mode
    * this guards is a pin bump syncing a *new* example that nobody adds a `Drive` list for: it
    * would lint clean and never be driven, and the suite would still report all green.
    */
  private def exampleFilesOnDisk: Task[Set[String]] =
    ZIO.attemptBlocking {
      val stream = Files.list(examplesDir)
      try
        stream
          .iterator()
          .asScala
          .map(_.getFileName.toString)
          .filter(_.endsWith(".json"))
          .toSet
      finally stream.close()
    }

  def spec = suite("rift's shipped examples replay (#120)")(
    test("every shipped example loads and behaves as advertised") {
      ZIO
        .scoped {
          withEngine { adminPort =>
            ZIO.foreach(examples)(replay(adminPort, _)).map(_.flatten)
          }
        }
        .zip(exampleFilesOnDisk)
        .map {
          case (None, _) => assertCompletes
          case (Some(failures), onDisk) =>
            // Bound as plain Booleans first: assertTrue's macro reports a Set/size comparison
            // written inline as a non-Boolean rather than evaluating it.
            val everyFileIsDriven = examples.map(_.file).toSet == onDisk
            assertTrue(everyFileIsDriven, failures == List.empty[String])
        }
    },
    test("rift-lint reports zero errors over the shipped examples") {
      buildDir match
        case None => ZIO.succeed(assertCompletes)
        case Some(bd) =>
          // One lint run PER FILE: each example is a standalone config, loaded alone, and four of
          // them share port 4545 by design. Since rift 0.18.0 (#1094) a directory run reports E002
          // for a port shared across its files, so linting the folder as one run flags a conflict
          // no real user of these examples can hit. rift#1218 gives each example its own port; once
          // the pin carries it, lint the directory as one run again (#160).
          val tools = RiftTools.fromBuildDir(bd)
          exampleFilesOnDisk.flatMap { onDisk =>
            ZIO.foreach(onDisk.toList.sorted)(f => tools.riftLint(examplesDir.resolve(f))).map {
              reports =>
                val offenders = reports.flatMap(RiftTools.errorOffenders)
                // Exactly one file per run: a run that checked 0 files would otherwise read green.
                val lintedEveryFile = reports.forall(_.filesChecked == 1)
                assertTrue(
                  reports.forall(_.exitCode == 0),
                  offenders == List.empty[String],
                  reports.map(_.errors).sum == 0,
                  lintedEveryFile,
                  reports.size == onDisk.size
                )
            }
          }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
