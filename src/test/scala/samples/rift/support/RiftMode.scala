package samples.rift.support

/** How the harness obtains a running engine. External (Phase 0) spawns a real rift child; embedded
  * (#18) runs the engine in-process over Panama FFM (zero spawn, needs JDK 22+); container (#12)
  * runs the engine in a Docker container on a published image (needs Docker, RIFT_IT-gated).
  */
enum RiftMode:
  case External
  case Embedded
  case Container

object RiftMode:
  def fromString(s: String): Either[String, RiftMode] =
    s.trim.toLowerCase match
      case "" | "external" => Right(External)
      case "embedded" => Right(Embedded)
      case "container" => Right(Container)
      case other => Left(s"unsupported RIFT_MODE=$other (external|embedded|container)")
