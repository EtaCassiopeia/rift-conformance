package samples.rift

import samples.rift.support.{EmbeddedAdmin, RiftHarness, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.io.BufferedReader
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** The 0.13.6 admin streaming/cursor surface (#122): `GET /events` and `GET
  * /imposters/{port}/savedRequests?since=`.
  *
  * Both were driven by SDK authors wanting to tail activity without re-reading the whole journal —
  * `rift-scala`'s `ZStream` and the fs2 equivalent. The cursor is also the same endpoint whose
  * array contract the rift#606 `200 {}` bug corrupted, so pinning its **shape** has double value: a
  * consumer that decodes `[...]` breaks the moment an object is returned instead.
  *
  * #201 extends it to the rest of `docs/api/index.md` § GET /events: the `types`/`port`/`match`
  * filters, the lifecycle `imposter` events, the `savedRequests/stream` alias, the stream's
  * response headers and the `400` on an unknown `types` value.
  *
  * External mode spawns a short-lived engine per case; embedded mode (`RIFT_MODE=embedded`) builds
  * the in-process harness, which serves the same admin HTTP — the embedded natives carry 0.18.1
  * since rift-java 0.3.3. Self-gating: a no-op pass without `RIFT_BUILD_DIR` (external) or without
  * an FFM binding (embedded).
  */
object AdminEventsCursor122Spec extends ZIOSpecDefault:

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

  /** Top-level JSON array elements, counted structurally rather than by a substring. */
  private def arrayLength(body: String): Option[Int] =
    val t = body.trim
    if !t.startsWith("[") then None
    else
      var depth = 0
      var inStr = false
      var esc = false
      var count = 0
      t.foreach { c =>
        if inStr then
          if esc then esc = false
          else if c == '\\' then esc = true
          else if c == '"' then inStr = false
        else
          c match
            case '"' => inStr = true
            case '[' | '{' => depth += 1; if depth == 2 then count += 1
            case ']' | '}' => depth -= 1
            case _ => ()
      }
      Some(count)

  private val buildDirOpt = sys.env.get("RIFT_BUILD_DIR").filter(_.trim.nonEmpty)
  private val embedded =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private def external(effect: ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    val runnable = if embedded then EmbeddedAdmin.available else buildDirOpt.nonEmpty
    if !runnable then ZIO.succeed(assertCompletes) else ZIO.scoped(effect)

  /** An engine's admin URL, scoped to one case: the in-process harness when embedded, else a
    * short-lived spawned engine.
    */
  private def engine: ZIO[Scope, Throwable, String] =
    if embedded then RiftHarness.layer.build.map(_.get[RiftHarness].adminUrl)
    else spawned

  private def spawned: ZIO[Scope, Throwable, String] =
    for
      buildDir <- ZIO.fromOption(buildDirOpt).orElseFail(new RuntimeException("no RIFT_BUILD_DIR"))
      port <- TestPorts.next
      _ <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val pb = new ProcessBuilder(
          s"$buildDir/rift",
          "--port",
          port.toString,
          "--nologfile",
          "--allow-injection"
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.start()
      })(p =>
        ZIO.attemptBlocking {
          if p.isAlive then p.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
          ()
        }.orDie
      )
      admin = s"http://127.0.0.1:$port"
      _ <- send(s"$admin/imposters", "GET", None)
        .retry(Schedule.recurs(50) && Schedule.spaced(100.millis))
    yield admin

  /** A recording imposter plus `n` requests driven at it. */
  private def recordingImposter(admin: String, n: Int): Task[Int] =
    for
      port <- TestPorts.next
      created <- send(
        s"$admin/imposters",
        "POST",
        Some(
          s"""{"port":$port,"protocol":"http","recordRequests":true,""" +
            """"stubs":[{"responses":[{"is":{"statusCode":200,"body":"ok"}}]}]}"""
        )
      )
      _ <- ZIO
        .fail(new RuntimeException(s"imposter create -> ${created._1}: ${created._2.take(200)}"))
        .when(created._1 != 201)
      _ <- ZIO.foreachDiscard(0 until n)(i => send(s"http://127.0.0.1:$port/r$i", "GET", None))
    yield port

  /** One complete SSE frame: its `event:` name and its `data:` payload. */
  private final case class Frame(event: String, data: String):
    def json: Option[Json] = data.fromJson[Json].toOption
    def field(name: String): Option[Json] = json.collect { case Json.Obj(fs) =>
      fs.collectFirst { case (`name`, v) => v }
    }.flatten
    def port: Option[Int] = field("port").collect { case Json.Num(n) => n.intValue }
    def str(name: String): Option[String] = field(name).collect { case Json.Str(s) => s }
    def method: Option[String] =
      field("request").collect { case Json.Obj(fs) =>
        fs.collectFirst { case ("method", Json.Str(m)) => m }
      }.flatten

  private final case class Tail(status: Int, headers: Map[String, String], frames: List[Frame])

  /** Subscribe to `url`, run `drive` once the opening `hello` frame is in (so no event can be
    * missed), then collect frames until `enough` holds and close. Bounded twice: at most 60 frames,
    * and a named 30 s timeout — the stream never ends by itself.
    */
  private def tail(url: String)(drive: Task[Unit])(enough: List[Frame] => Boolean): Task[Tail] =
    for
      subscribed <- ZIO.succeed(new java.util.concurrent.CountDownLatch(1))
      fib <- ZIO.attemptBlocking {
        val req = HttpRequest
          .newBuilder(URI.create(url))
          .header("Accept", "text/event-stream")
          .timeout(java.time.Duration.ofSeconds(20))
          .GET()
          .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        val headers = List("content-type", "cache-control")
          .flatMap(h => Option(resp.headers.firstValue(h).orElse(null)).map(h -> _))
          .toMap
        val reader = new BufferedReader(new java.io.InputStreamReader(resp.body()))
        try
          val frames = scala.collection.mutable.ListBuffer.empty[Frame]
          var event = ""
          val data = new StringBuilder
          var line = reader.readLine()
          var finished = false
          while line != null && !finished do
            if line.isEmpty then
              if event.nonEmpty || data.nonEmpty then
                frames += Frame(event, data.toString)
                if frames.size == 1 then subscribed.countDown()
                finished = frames.size >= 60 || (frames.size > 1 && enough(frames.toList.tail))
              event = ""
              data.clear()
            else if line.startsWith("event:") then event = line.drop(6).trim
            else if line.startsWith("data:") then data.append(line.drop(5).trim)
            if !finished then line = reader.readLine()
          Tail(resp.statusCode, headers, frames.toList)
        finally
          subscribed.countDown()
          reader.close()
          resp.body().close()
      }.fork
      _ <- ZIO
        .attemptBlocking(subscribed.await(30, java.util.concurrent.TimeUnit.SECONDS))
        .filterOrFail(identity)(new RuntimeException(s"SSE hello frame never arrived on $url"))
      _ <- drive
      res <- fib.join.timeoutFail(new RuntimeException(s"SSE tail of $url incomplete in 30s"))(
        30.seconds
      )
    yield res

  private def requestEvents(fs: List[Frame]): List[Frame] = fs.filter(_.event == "request")

  private def hit(port: Int, method: String, path: String): Task[Unit] =
    send(s"http://127.0.0.1:$port$path", method, if method == "POST" then Some("x") else None).unit

  def spec = suite("admin events stream + savedRequests cursor")(
    test("savedRequests is a JSON array and ?since= returns exactly the tail after that index") {
      external {
        for
          admin <- engine
          port <- recordingImposter(admin, 3)
          all <- send(s"$admin/imposters/$port/savedRequests", "GET", None)
          since1 <- send(s"$admin/imposters/$port/savedRequests?since=1", "GET", None)
          since0 <- send(s"$admin/imposters/$port/savedRequests?since=0", "GET", None)
          past <- send(s"$admin/imposters/$port/savedRequests?since=99", "GET", None)
        yield assertTrue(
          all._1 == 200,
          // The shape assertion is the point: rift#606 served `{}` where an array belongs, which a
          // consumer only discovers in its own decoder.
          arrayLength(all._2).contains(3),
          arrayLength(since0._2).contains(3),
          arrayLength(since1._2).contains(2),
          // Past the end is an empty array, not an error and not an object.
          past._1 == 200,
          arrayLength(past._2).contains(0)
        )
      }
    },
    test("GET /events streams a hello frame then a request event carrying the journal index") {
      external {
        for
          admin <- engine
          port <- recordingImposter(admin, 0)
          // Subscribe first, then drive traffic, then read a bounded number of lines and close —
          // an unbounded read would hang the suite on a stream that is designed never to end.
          // The reader signals once the `hello` frame is in, so traffic is driven against a stream
          // that is provably subscribed — a fixed sleep would race a cold/loaded box, and a missed
          // event is never re-delivered.
          subscribed <- ZIO.succeed(new java.util.concurrent.CountDownLatch(1))
          framesFib <- ZIO.attemptBlocking {
            val req = HttpRequest
              .newBuilder(URI.create(s"$admin/events"))
              .header("Accept", "text/event-stream")
              .timeout(java.time.Duration.ofSeconds(20))
              .GET()
              .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            val reader = new BufferedReader(new java.io.InputStreamReader(resp.body()))
            try
              val buf = scala.collection.mutable.ListBuffer.empty[String]
              var blanks = 0
              var line = reader.readLine()
              // Stop on COMPLETE frames, not on the second `event:` line — an SSE frame's `id:` and
              // `data:` follow its `event:`, so stopping at the header drops the payload asserted.
              // Crucially the next read only happens when the bound still allows one: reading first
              // and testing afterwards would block on a third line the test never causes, on a
              // stream designed never to end.
              while line != null && blanks < 2 && buf.size < 60 do
                buf += line
                if line.isEmpty then
                  blanks += 1
                  if blanks == 1 then subscribed.countDown()
                line = if blanks >= 2 || buf.size >= 60 then null else reader.readLine()
              (
                resp.statusCode,
                Option(resp.headers.firstValue("content-type").orElse(null)),
                buf.toList
              )
            finally
              reader.close()
              resp.body().close()
          }.fork
          _ <- ZIO
            .attemptBlocking(subscribed.await(30, java.util.concurrent.TimeUnit.SECONDS))
            .filterOrFail(identity)(new RuntimeException("SSE hello frame never arrived"))
          _ <- send(s"http://127.0.0.1:$port/streamed", "GET", None)
          // (2) A named failure beats a bare 3-minute suite timeout with no diagnosis.
          res <- framesFib.join
            .timeoutFail(new RuntimeException("no second SSE frame within 30s"))(30.seconds)
          (status, ctype, frames) = res
          text = frames.mkString("\n")
        yield assertTrue(
          status == 200,
          ctype.exists(_.contains("text/event-stream")),
          // The stream opens by declaring itself, so a subscriber knows it is connected before any
          // traffic happens — that is what removes the poll.
          text.contains("event: hello"),
          text.contains("\"engineVersion\""),
          text.contains("event: request"),
          // The journal index rides in the payload; the SSE `id:` is the stream's own sequence, so
          // both are asserted for what they actually are rather than assumed to be the same number.
          text.contains("\"index\""),
          frames.exists(_.startsWith("id:"))
        )
      }
    },
    // `allDeleted` is left out: DELETE /imposters would wipe the shared embedded engine, and
    // feature 21's isolated sidecar covers that door.
    test("types=lifecycle streams created, stubsChanged, enabledChanged, deleted for an imposter") {
      external {
        for
          admin <- engine
          port <- TestPorts.next
          t <- tail(s"$admin/events?types=lifecycle") {
            for
              _ <- send(
                s"$admin/imposters",
                "POST",
                Some(s"""{"port":$port,"protocol":"http","stubs":[]}""")
              )
              _ <- send(
                s"$admin/imposters/$port/stubs",
                "POST",
                Some("""{"stub":{"responses":[{"is":{"statusCode":200}}]}}""")
              )
              _ <- send(s"$admin/imposters/$port/disable", "POST", None)
              _ <- send(s"$admin/imposters/$port", "DELETE", None)
            yield ()
          }(_.exists(f => f.port.contains(port) && f.str("action").contains("deleted")))
          mine = t.frames.filter(_.port.contains(port))
        yield assertTrue(
          mine.forall(_.event == "imposter"),
          mine.flatMap(_.str("action")) ==
            List("created", "stubsChanged", "enabledChanged", "deleted"),
          // types=lifecycle carries no request events
          requestEvents(t.frames).isEmpty
        )
      }
    },
    // Traffic goes to the unwatched port FIRST: a broken filter would then deliver it before the
    // watched port's events and fail the assertion, rather than slip past the frame bound.
    test("port= narrows the request stream to one imposter") {
      external {
        for
          admin <- engine
          p1 <- recordingImposter(admin, 0)
          p2 <- recordingImposter(admin, 0)
          t <- tail(s"$admin/events?types=requests&port=$p1") {
            hit(p2, "GET", "/other") *> hit(p1, "GET", "/one") *> hit(p1, "GET", "/two")
          }(fs => requestEvents(fs).size >= 2)
          reqs = requestEvents(t.frames)
        yield assertTrue(reqs.size == 2, reqs.forall(_.port.contains(p1)))
      }
    },
    test("match=method=POST narrows the request stream to POSTs") {
      external {
        for
          admin <- engine
          p <- recordingImposter(admin, 0)
          t <- tail(s"$admin/events?types=requests&match=method=POST") {
            hit(p, "GET", "/g") *> hit(p, "POST", "/p1") *> hit(p, "POST", "/p2")
          }(fs => requestEvents(fs).size >= 2)
          reqs = requestEvents(t.frames)
        yield assertTrue(reqs.size == 2, reqs.forall(_.method.contains("POST")))
      }
    },
    test("savedRequests/stream is the port-scoped request stream") {
      external {
        for
          admin <- engine
          p1 <- recordingImposter(admin, 0)
          p2 <- recordingImposter(admin, 0)
          t <- tail(s"$admin/imposters/$p1/savedRequests/stream") {
            hit(p2, "GET", "/other") *> hit(p1, "GET", "/one") *> hit(p1, "GET", "/two")
          }(fs => requestEvents(fs).size >= 2)
          reqs = requestEvents(t.frames)
        yield assertTrue(
          t.frames.headOption.exists(_.event == "hello"),
          t.frames.headOption.flatMap(_.port).contains(p1),
          reqs.size == 2,
          reqs.forall(_.port.contains(p1))
        )
      }
    },
    test("the stream answers text/event-stream with Cache-Control: no-cache") {
      external {
        for
          admin <- engine
          p <- recordingImposter(admin, 0)
          t <- tail(s"$admin/events?types=requests")(hit(p, "GET", "/h"))(
            requestEvents(_).nonEmpty
          )
        yield assertTrue(
          t.status == 200,
          t.headers.get("content-type").exists(_.startsWith("text/event-stream")),
          t.headers.get("cache-control").contains("no-cache")
        )
      }
    },
    // TODO(rift#1226): tighten to the `errors` envelope once the SSE doors stop answering a bare
    // `{"error":…}` body; until then only the status is the contract.
    test("an unknown types value is a 400") {
      external {
        for
          admin <- engine
          r <- send(s"$admin/events?types=bogus", "GET", None)
        yield assertTrue(r._1 == 400)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
