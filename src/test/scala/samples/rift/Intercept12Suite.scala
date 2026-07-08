package samples.rift

import samples.rift.support.{LastResponse, RiftConformanceSupport, RiftHarness, RiftState}
import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.Suite
import zio.bdd.core.step.{Stage, ZIOSteps}

import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.jdk.CollectionConverters.*

/** Feature 12 — the intercept / TLS-MITM forward-proxy (rift 0.10.0 admin API + 0.11.0 FFI). The
  * harness starts rift with an intercept listener ([[RiftHarness.interceptEndpoint]]); these steps
  * drive its `/intercept` admin API (rules + CA/truststore export) and then issue a real HTTPS
  * request **through the intercept proxy** with a JDK `HttpClient` — `CONNECT`-tunnelled and
  * TLS-terminated by rift with a leaf minted from the exported CA — asserting the decrypted request
  * was matched (served inline, or forwarded to an imposter).
  *
  * External mode only (it starts an `--intercept-port` listener); the embedded/container aliases do
  * not list this suite. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/12-intercept.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Intercept12Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer

  private val http = HttpClient.newHttpClient()
  private val TrustPassword = "changeit"

  // Rules are host-scoped and accumulate on the shared listener; clear them after every scenario so
  // the count assertions and cross-scenario isolation hold even if one fails mid-way.
  afterScenario {
    ZIO
      .serviceWithZIO[RiftHarness](h => deleteRules(h.adminUrl))
      .tapError(e => ZIO.logWarning(s"intercept rules cleanup failed: ${e.getMessage}"))
      .ignore
  }

  // ── admin API (/intercept/rules, ca.pem, truststore.p12) ──────────────────────────────────

  private def postRule(adminUrl: String, ruleJson: String): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/intercept/rules"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(ruleJson))
        .build()
      http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

  private def deleteRules(adminUrl: String): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/intercept/rules")).DELETE().build()
      val code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      // 404 = nothing to clear (fine); any other non-2xx is a real admin failure worth surfacing —
      // a silently-ignored DELETE would leak rules into the next scenario (and mask a broken API).
      if code >= 300 && code != 404 then
        throw new RuntimeException(s"DELETE /intercept/rules -> $code")
    }

  private def rulesCount(adminUrl: String): IO[Throwable, Int] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder(URI.create(s"$adminUrl/intercept/rules")).GET().build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      // Assert 200 first: a non-200 must fail loudly, never default to a vacuous "0 rules".
      if resp.statusCode != 200 then
        throw new RuntimeException(
          s"GET /intercept/rules -> ${resp.statusCode}: ${resp.body.take(80)}"
        )
      val body = resp.body.trim
      // The list endpoint returns a JSON array of `{host, ...}` rule objects; count them by the `host`
      // key rather than pulling in a parser for this one assertion. Safe here because the only rule
      // bodies posted in this feature contain no literal "host" text.
      if body == "[]" || body.isEmpty then 0 else body.split("\"host\"", -1).length - 1
    }

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** Build an SSLContext that trusts the intercept CA, by loading the PKCS#12 truststore the admin
    * API exports — exactly what a real SUT would do to trust the MITM leaf certs.
    */
  private def interceptTrust(adminUrl: String): IO[Throwable, SSLContext] =
    ZIO.attemptBlocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$adminUrl/intercept/truststore.p12?password=$TrustPassword"))
        .GET()
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if resp.statusCode != 200 then
        throw new RuntimeException(s"GET /intercept/truststore.p12 -> ${resp.statusCode}")
      val bytes = resp.body()
      val ks = KeyStore.getInstance("PKCS12")
      ks.load(new ByteArrayInputStream(bytes), TrustPassword.toCharArray)
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, tmf.getTrustManagers, new SecureRandom())
      ctx
    }

  /** Issue an HTTPS GET to `host``path` THROUGH the intercept listener (as an HTTP proxy: the JDK
    * client sends `CONNECT host:443`, which rift intercepts and TLS-MITMs). No DNS for `host` is
    * needed — the proxy tunnels by name.
    */
  private def httpsViaIntercept(
      host: String,
      path: String
  ): ZIO[RiftHarness, Throwable, LastResponse] =
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      ep <- ZIO.serviceWithZIO[RiftHarness](_.interceptEndpoint)
      hostPort <- ZIO
        .fromOption(ep)
        .orElseFail(
          new RuntimeException("no intercept listener — RIFT_MODE does not support intercept")
        )
      (iHost, iPort) = hostPort
      ssl <- interceptTrust(admin)
      resp <- ZIO.attemptBlocking {
        val client = HttpClient
          .newBuilder()
          .sslContext(ssl)
          .proxy(ProxySelector.of(new InetSocketAddress(iHost, iPort)))
          .build()
        val req = HttpRequest.newBuilder(URI.create(s"https://$host$path")).GET().build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        val hs = r.headers.map.asScala.map((k, v) => k.toLowerCase -> v.asScala.toList).toMap
        LastResponse(r.statusCode, r.body, hs)
      }
    yield resp

  // ── steps ─────────────────────────────────────────────────────────────────────────────────

  Then("the intercept CA PEM is exported") {
    ZIO.serviceWithZIO[RiftHarness] { h =>
      ZIO
        .attemptBlocking {
          val req =
            HttpRequest.newBuilder(URI.create(s"${h.adminUrl}/intercept/ca.pem")).GET().build()
          http.send(req, HttpResponse.BodyHandlers.ofString())
        }
        .flatMap(r =>
          Assertions.assertTrue(
            r.statusCode == 200 && r.body.contains("BEGIN CERTIFICATE"),
            s"GET /intercept/ca.pem -> ${r.statusCode}, body head='${r.body.take(40)}'"
          )
        )
    }
  }

  Then("the intercept truststore is exported with password " / string) { (password: String) =>
    ZIO.serviceWithZIO[RiftHarness] { h =>
      ZIO
        .attemptBlocking {
          val req = HttpRequest
            .newBuilder(URI.create(s"${h.adminUrl}/intercept/truststore.p12?password=$password"))
            .GET()
            .build()
          http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        }
        .flatMap { r =>
          val echoed = r.headers.firstValue("x-truststore-password").orElse("")
          Assertions.assertTrue(
            r.statusCode == 200 && r.body.nonEmpty && echoed == password,
            s"truststore.p12 -> ${r.statusCode}, ${r.body.length} bytes, x-truststore-password='$echoed' (expected '$password')"
          )
        }
    }
  }

  Given(
    "an intercept rule serves " / int / " " / string / " for host " / string / " path " / string
  ) { (status: Int, body: String, host: String, path: String) =>
    val rule =
      s"""{"host":"${jsonEscape(host)}","predicates":[{"equals":{"path":"${jsonEscape(
          path
        )}"}}],"action":{"serve":{"statusCode":$status,"headers":{"content-type":"text/plain"},"body":"${jsonEscape(
          body
        )}"}}}"""
    ZIO.serviceWithZIO[RiftHarness](h =>
      postRule(h.adminUrl, rule).flatMap(code =>
        Assertions.assertTrue(code == 201, s"POST /intercept/rules (serve) -> $code (expected 201)")
      )
    )
  }

  Given("an intercept rule forwards host " / string / " to the current imposter") {
    (host: String) =>
      for
        port <- currentPort
        rule = s"""{"host":"${jsonEscape(host)}","action":{"forward":{"port":$port}}}"""
        code <- ZIO.serviceWithZIO[RiftHarness](h => postRule(h.adminUrl, rule))
        _ <- Assertions.assertTrue(
          code == 201,
          s"POST /intercept/rules (forward) -> $code (expected 201)"
        )
      yield ()
  }

  When("an HTTPS GET to " / string / " " / string / " through the intercept proxy") {
    (host: String, path: String) => httpsViaIntercept(host, path).flatMap(Stage.put(_))
  }

  Then("the intercept rules count is " / int) { (n: Int) =>
    ZIO.serviceWithZIO[RiftHarness](h =>
      rulesCount(h.adminUrl).flatMap(c =>
        Assertions.assertTrue(c == n, s"expected $n intercept rule(s), got $c")
      )
    )
  }

  When("the intercept rules are cleared") {
    ZIO.serviceWithZIO[RiftHarness](h => deleteRules(h.adminUrl))
  }
