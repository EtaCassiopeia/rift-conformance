package samples.rift

import samples.rift.support.TestPorts
import zio.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Intercept startup flags that are refused, or honoured, before any request is served (#190).
  *
  *   - `--intercept-auth` without `--intercept-port` is a startup error naming both flags (no
  *     listener would use the credential), and so is a value with no `:`.
  *   - `--intercept-ca-cert-pem` is exclusive with the file-based `--intercept-ca-cert`.
  *   - `--intercept-ca-cert-pem`/`--intercept-ca-key-pem` alone load that CA, so `GET
  *     /intercept/ca.pem` exports it.
  *
  * A PEM value starts with `-----`, which the CLI parser would read as a flag if passed as a
  * separate argument, so the PEM flags are given in `--flag=value` form.
  *
  * External-only and self-gating: a no-op pass unless `RIFT_BUILD_DIR` is set and `RIFT_MODE` is
  * not `embedded`.
  */
object InterceptStartupSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def resource(path: String): String =
    val is = getClass.getResourceAsStream(path)
    if is == null then throw new IllegalStateException(s"missing test resource $path")
    try new String(is.readAllBytes(), StandardCharsets.UTF_8)
    finally is.close()

  private lazy val caCert = resource("/rift-fixtures/intercept/ca-cert.pem")
  private lazy val caKey = resource("/rift-fixtures/intercept/ca-key.pem")

  /** Spawn `rift --port <free> --nologfile <args>`, reaped with the scope, plus its captured
    * output.
    */
  private def spawn(args: List[String])(using
      buildDir: Path
  ): ZIO[Scope, Throwable, (Process, Int, () => String)] =
    for
      port <- TestPorts.next
      log <- ZIO.acquireRelease(
        ZIO.attemptBlocking(Files.createTempFile("rift-intercept-startup-", ".log"))
      )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)
      cmd = List(buildDir.resolve("rift").toString, "--port", port.toString, "--nologfile") ++ args
      proc <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val pb = new ProcessBuilder(cmd*)
          pb.redirectErrorStream(true)
          pb.redirectOutput(log.toFile)
          pb.start()
        }
      )(p => ZIO.attemptBlocking { if p.isAlive then p.destroyForcibly(); () }.orDie)
    yield (proc, port, () => new String(Files.readAllBytes(log), StandardCharsets.UTF_8))

  private def tempFile(content: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(
        Files.writeString(Files.createTempFile("rift-intercept-ca-", ".pem"), content)
      )
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)

  /** The exit code of an engine expected to refuse startup, or `None` if it is still up after 15 s.
    */
  private def exitCode(proc: Process): Task[Option[Int]] =
    ZIO.attemptBlocking(
      if proc.waitFor(15, TimeUnit.SECONDS) then Some(proc.exitValue()) else None
    )

  private def get(url: String): Task[(Int, String)] =
    ZIO
      .attemptBlocking {
        val req = HttpRequest
          .newBuilder(URI.create(url))
          .timeout(java.time.Duration.ofSeconds(5))
          .GET()
          .build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        (r.statusCode, r.body)
      }

  private val buildDirOpt: Option[Path] =
    sys.env.get("RIFT_BUILD_DIR").filter(_.trim.nonEmpty).map(java.nio.file.Paths.get(_))
  private val embedded =
    sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded")

  private def external(effect: Path ?=> ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    buildDirOpt match
      case Some(bd) if !embedded => ZIO.scoped(effect(using bd))
      case _ => ZIO.succeed(assertCompletes)

  def spec = suite("intercept startup flags (external)")(
    test("--intercept-auth without --intercept-port refuses to start, naming both flags") {
      external {
        for
          eng <- spawn(List("--intercept-auth", "u:p"))
          code <- exitCode(eng._1)
          out = eng._3()
        yield assertTrue(
          code.exists(_ != 0),
          out.contains("--intercept-auth"),
          out.contains("--intercept-port")
        )
      }
    },
    test("--intercept-auth with no ':' refuses to start") {
      external {
        for
          interceptPort <- TestPorts.next
          eng <- spawn(
            List("--intercept-auth", "nocolon", "--intercept-port", interceptPort.toString)
          )
          code <- exitCode(eng._1)
          out = eng._3()
        yield assertTrue(code.exists(_ != 0), out.contains("user:pass"))
      }
    },
    test("--intercept-ca-cert-pem together with --intercept-ca-cert refuses to start") {
      external {
        for
          interceptPort <- TestPorts.next
          // A complete file pair, so the only fault on the command line is the conflict.
          certFile <- tempFile(caCert)
          keyFile <- tempFile(caKey)
          eng <- spawn(
            List(
              "--intercept-port",
              interceptPort.toString,
              "--intercept-ca-cert",
              certFile.toString,
              "--intercept-ca-key",
              keyFile.toString,
              s"--intercept-ca-cert-pem=$caCert"
            )
          )
          code <- exitCode(eng._1)
          out = eng._3()
        yield assertTrue(
          code.exists(_ != 0),
          out.contains("'--intercept-ca-cert <FILE>' cannot be used with '--intercept-ca-cert-pem")
        )
      }
    },
    test("--intercept-ca-cert-pem/--intercept-ca-key-pem load that CA for export") {
      external {
        for
          interceptPort <- TestPorts.next
          eng <- spawn(
            List(
              "--intercept-port",
              interceptPort.toString,
              s"--intercept-ca-cert-pem=$caCert",
              s"--intercept-ca-key-pem=$caKey"
            )
          )
          admin = s"http://127.0.0.1:${eng._2}"
          exported <- get(s"$admin/intercept/ca.pem")
            .retry(Schedule.recurs(50) && Schedule.spaced(100.millis))
        yield assertTrue(exported._1 == 200, exported._2.trim == caCert.trim)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
