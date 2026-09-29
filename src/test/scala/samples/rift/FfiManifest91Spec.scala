package samples.rift

import zio.*
import zio.json.*
import zio.test.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** The `ffi-manifest.json` release asset (rift#459, #91): each Rift release publishes a `{version,
  * abi, artifacts[{platform, file, sha256, url}]}` map describing the per-platform `librift_ffi`
  * cdylibs. The embedded (Panama FFM) leg loads a platform native without consulting this manifest,
  * so this gate asserts the manifest actually **resolves the cdylib this platform's embedded leg
  * would load**: for the running `os.name`/`os.arch`, the manifest lists an artifact whose `file`
  * is the expected `librift_ffi-<platform>.<ext>`, under the `v2` C-ABI at the pinned engine
  * version, with a well-formed `sha256` and a download `url`.
  *
  * Self-gating: a no-op pass when the asset can't be fetched (offline). The asset is public, so no
  * token is needed.
  */
object FfiManifest91Spec extends ZIOSpecDefault:

  // Default must track scripts/build-rift.sh's RIFT_VERSION default — this asserts the manifest of
  // the same release the binary legs fetch, so a drifting default would verify the wrong release.
  private val Version = sys.env.getOrElse("RIFT_VERSION", "0.18.0")
  private val ManifestUrl =
    s"https://github.com/achird-labs/rift/releases/download/v$Version/ffi-manifest.json"

  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  private final case class Artifact(platform: String, file: String, sha256: String, url: String)
  private object Artifact:
    given JsonDecoder[Artifact] = DeriveJsonDecoder.gen[Artifact]

  private final case class Manifest(version: String, abi: String, artifacts: List[Artifact])
  private object Manifest:
    given JsonDecoder[Manifest] = DeriveJsonDecoder.gen[Manifest]

  /** The manifest platform token for the running JVM, e.g. `darwin-aarch64` — the same token the
    * release names its `librift_ffi-<platform>` cdylibs with.
    */
  private def currentPlatform: Option[String] =
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase
    val osTok =
      if os.contains("mac") || os.contains("darwin") then Some("darwin")
      else if os.contains("linux") then Some("linux")
      else if os.contains("win") then Some("windows")
      else None
    val archTok =
      if arch == "aarch64" || arch == "arm64" then Some("aarch64")
      else if arch == "x86_64" || arch == "amd64" then Some("x86_64")
      else None
    for o <- osTok; a <- archTok yield s"$o-$a"

  private def expectedExt(platform: String): String =
    if platform.startsWith("darwin") then "dylib"
    else if platform.startsWith("windows") then "dll"
    else "so"

  // Only the network round-trip self-gates: a genuine offline/connection error → None (no-op). A
  // reachable-but-wrong asset (non-200 or schema-drifted JSON) is a HARD failure below, not a silent
  // no-op — otherwise a 404'd/renamed manifest would pass green for the wrong reason.
  private def fetchRaw: UIO[Option[(Int, String)]] =
    ZIO
      .attemptBlocking {
        val req = HttpRequest.newBuilder(URI.create(ManifestUrl)).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        (resp.statusCode, resp.body)
      }
      .asSome
      .catchAll(e =>
        ZIO.logWarning(s"ffi-manifest.json unreachable (${e.getMessage}); no-op").as(None)
      )

  private def parseManifest(status: Int, body: String): Task[Manifest] =
    if status != 200 then
      ZIO.fail(new RuntimeException(s"ffi-manifest.json fetch returned $status: $body"))
    else
      ZIO
        .fromEither(body.fromJson[Manifest])
        .mapError(e => new RuntimeException(s"bad ffi-manifest.json: $e"))

  def spec = suite("ffi-manifest.json release asset (#91)")(
    test(
      "the manifest resolves this platform's librift_ffi cdylib under abi v2 at the pinned version"
    ) {
      fetchRaw.flatMap {
        case None => ZIO.succeed(assertCompletes)
        case Some((status, body)) =>
          currentPlatform match
            case None =>
              ZIO.succeed(assertCompletes) // unsupported platform token — nothing to resolve
            case Some(platform) =>
              parseManifest(status, body).map { m =>
                val artifact = m.artifacts.find(_.platform == platform)
                assertTrue(
                  m.abi == "v2",
                  m.version == s"v$Version",
                  m.artifacts.nonEmpty,
                  artifact.isDefined,
                  artifact.exists(_.file == s"librift_ffi-$platform.${expectedExt(platform)}"),
                  artifact.exists(a =>
                    a.sha256.length == 64 && a.sha256.forall(c =>
                      c.isDigit || ('a' to 'f').contains(c)
                    )
                  ),
                  artifact.exists(_.url.nonEmpty)
                )
              }
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
