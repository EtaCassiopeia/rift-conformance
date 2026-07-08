package samples.rift

import samples.rift.support.*
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Acceptance gate for `RIFT_MODE=container` (#12): the harness starts a rift Docker container and
  * serves its admin API over published (1:1) ports, so Plane A addresses it exactly as external
  * mode.
  *
  * Self-gating: a no-op pass unless `RIFT_MODE=container` (so it stays green in the
  * external/embedded runs that also include `samples.rift.*`). The `engineContainer` alias sets
  * `RIFT_MODE=container`, and the runner only reaches it when Docker is present and `RIFT_IT=1`.
  */
object RiftContainerHarnessSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def status(url: String): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  def spec = suite("RiftHarness (container mode)")(
    test("starts a rift container and serves its admin API over published ports") {
      val container = sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("container")
      if !container then ZIO.succeed(assertCompletes)
      else
        ZIO.scoped {
          RiftHarness.layer.build.flatMap { env =>
            val h = env.get[RiftHarness]
            for code <- status(s"${h.adminUrl}/imposters")
            yield assertTrue(code == 200, h.adminUrl.startsWith("http://"))
          }
        }
    }
  )
