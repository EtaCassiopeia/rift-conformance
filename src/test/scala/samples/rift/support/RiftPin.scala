package samples.rift.support

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The rift release this repo is pinned to: `RIFT_VERSION` when set, else the default in
  * `scripts/build-rift.sh` (`RIFT_VERSION="${RIFT_VERSION:-X}"`), the one place the pin lives. A
  * spec that needs the pinned version reads it here instead of hard-coding a copy that drifts on
  * the next bump.
  */
object RiftPin:

  private val DefaultLine = """RIFT_VERSION="\$\{RIFT_VERSION:-([^}"]+)\}"""".r

  /** Parse the default out of the script text; `None` if the assignment is not there. */
  def parseDefault(script: String): Option[String] =
    DefaultLine.findFirstMatchIn(script).map(_.group(1).trim).filter(_.nonEmpty)

  /** Throws if neither source yields a version: a spec asserting against an unknown pin must fail
    * loudly, not compare against a guess.
    */
  lazy val version: String =
    sys.env
      .get("RIFT_VERSION")
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse {
        val script = Paths.get("scripts", "build-rift.sh")
        parseDefault(new String(Files.readAllBytes(script), StandardCharsets.UTF_8))
          .getOrElse(throw new IllegalStateException(s"no RIFT_VERSION default in $script"))
      }
