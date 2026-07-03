package samples.rift

import samples.rift.support.*
import zio.*
import zio.test.*

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Acceptance gate for the RiftHarness external-mode lifecycle (#2). The lifecycle test spawns the
  * real engine built by scripts/build-rift.sh — it requires `RIFT_BUILD_DIR` in the environment.
  */
object RiftHarnessSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def status(url: String): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  /** The live rift-http-proxy whose command line targets `--port <port>` — this is the exact
    * "orphan in ps" check the acceptance criterion asks for, done via ProcessHandle. Returns the
    * whole command line too so the spawn flags can be asserted.
    */
  private def engineOnPort(port: Int): UIO[Option[(Long, String)]] =
    ZIO.succeed {
      ProcessHandle
        .allProcesses()
        .iterator()
        .asScala
        .flatMap(ph => ph.info().commandLine().toScala.map(cl => (ph.pid(), cl)))
        .find((_, cl) => cl.contains("rift-http-proxy") && cl.contains(s"--port $port"))
    }

  private def isAlive(pid: Long): UIO[Boolean] =
    ZIO.succeed(ProcessHandle.of(pid).toScala.exists(_.isAlive))

  private def portOf(adminUrl: String): Int =
    adminUrl.substring(adminUrl.lastIndexOf(':') + 1).toInt

  def spec = suite("RiftHarness (external mode)")(
    test("engine starts, GET /imposters returns 200, and scope close leaves no orphan") {
      for
        observed <- ZIO.scoped {
          RiftHarness.layer.build.flatMap { env =>
            val h = env.get[RiftHarness]
            val port = portOf(h.adminUrl)
            for
              code <- status(s"${h.adminUrl}/imposters")
              engine <- engineOnPort(port)
            yield (code, engine)
          }
        }
        (code, engine) = observed
        // The scope has closed: the finalizer (and, on abort, the shutdown hook) must have killed it.
        orphanAlive <- engine match
          case Some((pid, _)) => isAlive(pid)
          case None           => ZIO.succeed(false)
      yield assertTrue(code == 200) &&
        assertTrue(engine.isDefined) &&
        assertTrue(engine.exists((_, cl) => cl.contains("--allow-injection"))) &&
        assertTrue(!orphanAlive)
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(90.seconds),
    // The prefer-when-free path is already exercised by the lifecycle test above (the engine binds
    // 2525 when it is free). Here we pin the fallback branch deterministically: a busy preferred
    // port must yield a different one. The decoy socket stays open across the assertion, so we
    // never rely on a just-closed port becoming immediately rebindable (that timing is racy).
    test("choosePort falls back to a different port when the preferred one is busy") {
      ZIO.scoped {
        for
          sock <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(new ServerSocket(0)))
          busy = sock.getLocalPort
          fb <- RiftHarness.choosePort(busy)
        yield assertTrue(fb != busy) && assertTrue(fb > 0)
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)
  )
