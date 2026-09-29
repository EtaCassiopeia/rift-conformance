package samples.rift

import samples.rift.support.EmbeddedAdmin
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Embedded-only gate for `rift_serve_admin`'s `allowInjection` option (rift 0.13.0, rift#492/#511,
  * #82). The option defaults to `false` and mirrors the external engine's `--allowInjection` CLI
  * gate: with it off, the in-process admin plane rejects an inject imposter with `400 invalid
  * injection`; with it on, the same imposter is accepted (`201`). Direct FFI ops stay ungated by
  * design, so this asserts only the admin-plane path.
  *
  * Self-gating: a no-op pass unless `RIFT_MODE=embedded` AND a `librift_ffi` resolves for this JVM
  * (JDK 22+ with the natives on the classpath), mirroring [[RiftEmbeddedHarnessSpec]]. Each case
  * serves its own short-lived admin plane in an isolated scope (engine create→serve→release, the
  * same lifecycle the embedded harness runs per suite).
  */
object AllowInjectionGate82Spec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  // A minimal inject imposter — the JS `inject` response is exactly what `allowInjection` gates.
  private val InjectImposter =
    """{"protocol":"http","stubs":[{"responses":[{"inject":"function (config) { return { statusCode: 200, body: \"hi\" }; }"}]}]}"""

  private def postJson(url: String, body: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      (resp.statusCode, resp.body)
    }

  // A seeded empty-imposter config so `serve_admin` has a source; deleted when the scope closes.
  private val emptyConfig: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-embedded-inject-gate-", ".json")
        Files.write(p, """{"imposters":[]}""".getBytes(StandardCharsets.UTF_8))
        p
      }
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)

  private val embedded: Boolean =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private def whenEmbedded(effect: => ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    if !embedded || !EmbeddedAdmin.available then ZIO.succeed(assertCompletes)
    else ZIO.scoped(effect)

  def spec = suite("serve_admin allowInjection gate (embedded)")(
    test("allowInjection:false rejects an inject imposter with 400 invalid injection") {
      whenEmbedded {
        for
          cfg <- emptyConfig
          admin <- EmbeddedAdmin.serve(0, cfg, allowInjection = false)
          res <- postJson(s"${admin.adminUrl}/imposters", InjectImposter)
        yield
          val (code, body) = res
          assertTrue(code == 400, body.contains("invalid injection"))
      }
    },
    test("allowInjection:true accepts the same inject imposter with 201") {
      whenEmbedded {
        for
          cfg <- emptyConfig
          admin <- EmbeddedAdmin.serve(0, cfg, allowInjection = true)
          res <- postJson(s"${admin.adminUrl}/imposters", InjectImposter)
        yield assertTrue(res._1 == 201)
      }
    }
  )
