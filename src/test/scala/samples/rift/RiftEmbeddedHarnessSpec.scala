package samples.rift

import samples.rift.support.*
import zio.*
import zio.bdd.mock.rift.embedded.EmbeddedAdmin
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Acceptance gate for `RIFT_MODE=embedded` (#18): the in-process FFM oracle serves a real admin
  * API and spawns **zero** child processes — no rift engine, no Docker. The zero-spawn check looks
  * for any live process whose command line carries `--allow-injection`, the flag the external-mode
  * harness passes to every rift child it spawns; embedded mode must leave that set empty.
  *
  * Self-gating: a no-op pass unless `RIFT_MODE=embedded` AND a `librift_ffi` resolves for this JVM
  * (JDK 22+ with the natives on the classpath). The `engineEmbedded` alias runs it under those
  * conditions; the runner SKIPs the whole embedded layer on JDK < 22.
  */
object RiftEmbeddedHarnessSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def status(url: String): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  /** Command lines of live rift engine children (spawned engines carry `--allow-injection`).
    * Precise on purpose: the test JVM's own command line contains "rift" (the project path) but
    * never that flag, so this can't false-positive on ourselves.
    */
  private def spawnedRiftEngines: UIO[List[String]] =
    ZIO.succeed {
      ProcessHandle
        .allProcesses()
        .iterator()
        .asScala
        .flatMap(_.info().commandLine().toScala)
        .filter(_.contains("--allow-injection"))
        .toList
    }

  def spec = suite("RiftHarness (embedded mode)")(
    test("serves a real admin API in-process and spawns no rift child") {
      val embedded = sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")
      if !embedded || !EmbeddedAdmin.available then
        // Not exercising embedded here (external run, or no FFM on this JVM) — nothing to assert.
        ZIO.succeed(assertCompletes)
      else
        ZIO.scoped {
          RiftHarness.layer.build.flatMap { env =>
            val h = env.get[RiftHarness]
            for
              code <- status(s"${h.adminUrl}/imposters")
              engines <- spawnedRiftEngines
            yield assertTrue(
              code == 200,
              h.adminUrl.startsWith("http://127.0.0.1:") || h.adminUrl.startsWith(
                "http://localhost:"
              ),
              engines.isEmpty
            )
          }
        }
    }
  )
