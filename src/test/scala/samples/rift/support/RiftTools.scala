package samples.rift.support

import java.nio.file.Path

/** Typed rift-lint / rift-verify shell-outs from the SAME build as the engine.
  *
  * Minimal in Phase 0: it only carries the build directory the tools live in. #3 expands this into
  * typed `lint` / `verify` operations; keeping the seam here now lets the harness expose `tools`
  * without the two issues fighting over the type.
  */
final case class RiftTools(buildDir: Path)
