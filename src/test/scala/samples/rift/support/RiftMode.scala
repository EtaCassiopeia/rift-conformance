package samples.rift.support

/** How the harness obtains a running engine. External is the Phase 0 mode (spawn a real rift
  * child); container (#12) and embedded (#18) arrive later.
  */
enum RiftMode:
  case External

object RiftMode:
  def fromString(s: String): Either[String, RiftMode] =
    s.trim.toLowerCase match
      case "" | "external" => Right(External)
      case other => Left(s"unsupported RIFT_MODE=$other (external only until #12)")
