package samples.rift.support

import zio.*
import zio.json.*

import java.nio.file.{Files, Path}

/** The `BUILD_INFO` written by scripts/build-rift.sh (#1), plus the engine-reported version.
  *
  * `engineVersion` is left `None` here: querying the live engine and asserting it agrees with
  * `versions` is the job of the @preflight version-coherence scenario (#5). This type just makes
  * the recorded build identity available to the harness.
  */
final case class RiftBuildInfo(
    commit: String,
    branch: String,
    builtAt: String,
    binaries: Map[String, String],
    versions: Map[String, String],
    engineVersion: Option[String]
)

object RiftBuildInfo:

  private final case class OnDisk(
      commit: String,
      branch: String,
      builtAt: String,
      binaries: Map[String, String],
      versions: Map[String, String]
  )
  private object OnDisk:
    given JsonDecoder[OnDisk] = DeriveJsonDecoder.gen[OnDisk]

  /** Read and parse `$buildDir/BUILD_INFO`. Fails loudly if it is absent or malformed — that means
    * the artifacts were not produced by build-rift.sh, which must not be papered over.
    */
  def fromBuildDir(buildDir: Path, engineVersion: Option[String]): IO[Throwable, RiftBuildInfo] =
    val file = buildDir.resolve("BUILD_INFO")
    for
      raw <- ZIO.attemptBlocking(Files.readString(file))
      od <- ZIO
        .fromEither(raw.fromJson[OnDisk])
        .mapError(msg => new RuntimeException(s"malformed BUILD_INFO at $file: $msg"))
    yield RiftBuildInfo(od.commit, od.branch, od.builtAt, od.binaries, od.versions, engineVersion)
