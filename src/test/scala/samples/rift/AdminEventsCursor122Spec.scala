package samples.rift

import samples.rift.support.TestPorts
import zio.*
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
  * Runs against the shared harness engine over plain HTTP, so it needs no bespoke spawn. External
  * only, self-gating on `RIFT_BUILD_DIR` + non-embedded like the sibling specs — the embedded leg's
  * natives predate both endpoints.
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
    if buildDirOpt.isEmpty || embedded then ZIO.succeed(assertCompletes)
    else ZIO.scoped(effect)

  /** Spawn an engine, returning its admin URL. Short-lived and scoped, one per case. */
  private def engine: ZIO[Scope, Throwable, String] =
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

  def spec = suite("admin events stream + savedRequests cursor (external)")(
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
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
