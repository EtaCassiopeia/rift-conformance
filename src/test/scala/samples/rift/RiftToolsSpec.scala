package samples.rift

import samples.rift.support.*
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}

/** Acceptance gate for RiftTools (#3): the real rift-lint / rift-verify binaries from the harness
  * build, driven over the corpus and a running engine. Requires `RIFT_BUILD_DIR` (and
  * `RIFT_CORPUS_ROOT` when the corpus is not at the default `../rift-imposter-samples`).
  */
object RiftToolsSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def imposters: Path =
    val root = Option(java.lang.System.getenv("RIFT_CORPUS_ROOT"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("../rift-imposter-samples")
    Paths.get(root).resolve("imposters")

  private def postImposter(adminUrl: String, file: Path): Task[Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/imposters"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Files.readString(file)))
        .build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  def spec = suite("RiftTools")(
    test("riftLint reports zero errors across the corpus") {
      for
        tools <- ZIO.serviceWith[RiftHarness](_.tools)
        report <- tools.riftLint(imposters)
      yield assertTrue(report.errors == 0) &&
        assertTrue(report.filesChecked >= 15) &&
        assertTrue(report.exitCode == 0) &&
        assertTrue(report.raw.contains("files_checked"))
    },
    test("riftVerify passes and reports no failures against a posted corpus imposter") {
      for
        h <- ZIO.service[RiftHarness]
        posted <- postImposter(h.adminUrl, imposters.resolve("01-basic-rest.json"))
        report <- h.tools.riftVerify(h.adminUrl)
      yield assertTrue(posted == 201) &&
        assertTrue(report.passed > 0) &&
        assertTrue(report.failed == 0) &&
        assertTrue(report.exitCode == 0) &&
        assertTrue(report.raw.contains("Passed"))
    }
  ).provideShared(RiftHarness.layer)
    @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
