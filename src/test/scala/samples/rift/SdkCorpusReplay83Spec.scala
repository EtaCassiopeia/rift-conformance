package samples.rift

import samples.rift.support.{RiftPin, RiftTools, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.process.Command
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Replay of the upstream SDK conformance corpus release artifact (rift#460/#524, #83). Each Rift
  * release publishes `sdk-conformance-<version>.tar.gz` — the engine-canonical DSL↔engine parity
  * oracle (imposter fixtures with `_verify` transcripts + `manifest.json`). This gate downloads the
  * pinned artifact (via `scripts/fetch-sdk-corpus.sh`, cached), asserts its `manifest.json` matches
  * the fixtures on disk, then loads the whole corpus into a dedicated engine and drives
  * `rift-verify` over it — the reference replayer — asserting every `_verify` sequence holds.
  *
  * Self-gating: a no-op pass when the corpus can't be fetched (offline / no gh+curl) or
  * `RIFT_BUILD_DIR` is unset, mirroring how the other tool/embedded gates skip cleanly out of their
  * environment — but a fetch that *fails* is logged (exit code + stderr), so a CI no-op is visible
  * rather than mistaken for a real run. The engine runs with `--allow-injection` and its cwd set to
  * the extracted `corpus/` so the fixtures' relative `data/` and injection-module references
  * resolve, per the corpus README. External-leg only (spawns a real engine + `rift-verify`).
  */
object SdkCorpusReplay83Spec extends ZIOSpecDefault:

  // The pinned release (RiftPin: RIFT_VERSION, else scripts/build-rift.sh's default), so the corpus
  // replayed is the one shipped with the engine under test.
  private val Version = RiftPin.version

  private final case class Fixture(
      file: String,
      port: Int,
      name: String,
      requires: List[String],
      hasVerify: Boolean
  )
  private object Fixture:
    given JsonDecoder[Fixture] = DeriveJsonDecoder.gen[Fixture]

  private final case class Manifest(
      schemaVersion: Int,
      engineVersion: String,
      fixtures: List[Fixture]
  )
  private object Manifest:
    given JsonDecoder[Manifest] = DeriveJsonDecoder.gen[Manifest]

  private final case class ImpostersEnvelope(imposters: List[Json])
  private object ImpostersEnvelope:
    given JsonDecoder[ImpostersEnvelope] = DeriveJsonDecoder.gen[ImpostersEnvelope]

  private val client = HttpClient.newHttpClient()

  private def buildDir: Option[Path] = sys.env.get("RIFT_BUILD_DIR").map(Paths.get(_))

  /** The extracted corpus root: `RIFT_SDK_CORPUS_DIR` if it already holds a manifest, else
    * self-bootstrap via the fetch script. `None` when the artifact can't be obtained (offline); a
    * fetch that runs but *fails* is logged with its exit code + stderr so a CI no-op is never
    * silent.
    */
  private def resolveCorpusDir: UIO[Option[Path]] =
    sys.env.get("RIFT_SDK_CORPUS_DIR").map(Paths.get(_)) match
      case Some(p) if Files.isRegularFile(p.resolve("manifest.json")) => ZIO.some(p)
      case _ =>
        val fetch =
          for
            proc <- Command("bash", "scripts/fetch-sdk-corpus.sh").run
            drained <- proc.stdout.string <&> proc.stderr.string
            code <- proc.exitCode
            _ <- ZIO
              .logWarning(
                s"SDK corpus fetch exited ${code.code}; replay will no-op. stderr:\n${drained._2}"
              )
              .when(code.code != 0)
          yield
            if code.code == 0 then
              drained._1.linesIterator
                .collectFirst {
                  case l if l.trim.startsWith("export RIFT_SDK_CORPUS_DIR=") =>
                    Paths.get(
                      l.trim
                        .stripPrefix("export RIFT_SDK_CORPUS_DIR=")
                        .trim
                        .stripPrefix("\"")
                        .stripSuffix("\"")
                    )
                }
                .filter(p => Files.isRegularFile(p.resolve("manifest.json")))
            else None
        fetch.catchAll(e =>
          ZIO.logWarning(s"SDK corpus fetch errored (${e.getMessage}); replay will no-op").as(None)
        )

  private def readManifest(dir: Path): Task[Manifest] =
    for
      raw <- ZIO.attemptBlocking(Files.readString(dir.resolve("manifest.json")))
      m <- ZIO
        .fromEither(raw.fromJson[Manifest])
        .mapError(e => new RuntimeException(s"bad manifest.json: $e"))
    yield m

  /** Merge the manifest's imposter files into a single `{"imposters":[…]}` config for
    * `--configfile`.
    */
  private def combinedConfig(dir: Path, manifest: Manifest): Task[Path] =
    ZIO.attemptBlocking {
      val bodies = manifest.fixtures.map(f => Files.readString(dir.resolve(f.file)))
      val json = bodies.mkString("""{"imposters":[""", ",", "]}")
      val out = Files.createTempFile("sdk-corpus-combined-", ".json")
      out.toFile.deleteOnExit()
      Files.writeString(out, json)
      out
    }

  private def spawnEngine(
      bd: Path,
      corpusDir: Path,
      config: Path,
      port: Int,
      metricsPort: Int
  ): ZIO[Scope, Throwable, Process] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val log = Files.createTempFile(s"sdk-corpus-engine-$port-", ".log").toFile
        log.deleteOnExit()
        val pb = new ProcessBuilder(
          bd.resolve("rift").toString,
          "--port",
          port.toString,
          "--allow-injection",
          "--nologfile",
          "--metrics-port",
          metricsPort.toString,
          "--configfile",
          config.toString,
          "--loglevel",
          "error"
        )
        pb.directory(corpusDir.toFile)
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        pb.start()
      }
    )(proc => ZIO.attemptBlocking(if proc.isAlive then proc.destroyForcibly()).ignore)

  private def awaitAdmin(port: Int, proc: Process): Task[Unit] =
    val probe = ZIO.attemptBlocking {
      val req =
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/imposters")).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
    val ready =
      ZIO
        .fail(new RuntimeException("engine exited before admin came up"))
        .when(!proc.isAlive)
        .zipRight(probe.filterOrFail(_ == 200)(new RuntimeException("admin not 200 yet")))
    ready.retry(Schedule.spaced(250.millis) && Schedule.recurs(80)).unit

  /** Count the imposters the engine actually registered — guards against a silent partial load (bad
    * merge, port collision) making the downstream rift-verify verdict vacuously pass.
    */
  private def loadedImposterCount(port: Int): Task[Int] =
    for
      body <- ZIO.attemptBlocking {
        val req =
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/imposters")).GET().build()
        client.send(req, HttpResponse.BodyHandlers.ofString()).body()
      }
      env <- ZIO
        .fromEither(body.fromJson[ImpostersEnvelope])
        .mapError(e => new RuntimeException(s"bad /imposters response: $e"))
    yield env.imposters.size

  def spec = suite("SDK conformance corpus replay (#83)")(
    test("manifest.json matches the fixtures on disk") {
      resolveCorpusDir.flatMap {
        case None => ZIO.succeed(assertCompletes)
        case Some(dir) =>
          for
            manifest <- readManifest(dir)
            checks <- ZIO.foreach(manifest.fixtures) { f =>
              ZIO.attemptBlocking {
                val fp = dir.resolve(f.file)
                val exists = Files.isRegularFile(fp)
                val hasVerifyOnDisk = exists && Files.readString(fp).contains("\"_verify\"")
                (f, exists, hasVerifyOnDisk)
              }
            }
            onDisk <- ZIO.attemptBlocking {
              val d = dir.resolve("corpus/imposters")
              val stream = Files.list(d)
              try
                stream
                  .iterator()
                  .asScala
                  .map(_.getFileName.toString)
                  .filter(_.endsWith(".json"))
                  .toSet
              finally stream.close()
            }
          yield
            val manifestFiles =
              manifest.fixtures.map(f => Paths.get(f.file).getFileName.toString).toSet
            assertTrue(
              manifest.schemaVersion == 1,
              manifest.engineVersion == s"v$Version",
              manifest.fixtures.nonEmpty,
              checks.forall(_._2),
              checks.forall((f, _, hasV) => f.hasVerify == hasV),
              manifestFiles == onDisk
            )
      }
    },
    test("rift-verify replays the corpus with zero failures") {
      resolveCorpusDir.zip(ZIO.succeed(buildDir)).flatMap {
        case (Some(dir), Some(bd)) =>
          ZIO.scoped {
            for
              manifest <- readManifest(dir)
              config <- combinedConfig(dir, manifest)
              port <- TestPorts.next
              metricsPort <- TestPorts.next
              proc <- spawnEngine(bd, dir.resolve("corpus"), config, port, metricsPort)
              _ <- awaitAdmin(port, proc)
              loaded <- loadedImposterCount(port)
              report <- RiftTools.fromBuildDir(bd).riftVerify(s"http://127.0.0.1:$port")
            yield assertTrue(
              loaded == manifest.fixtures.size,
              report.exitCode == 0,
              report.failed == 0,
              report.passed >= manifest.fixtures.count(_.hasVerify)
            )
          }
        case _ => ZIO.succeed(assertCompletes)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
