package samples.rift

import samples.rift.support.RiftPin
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** `RiftPin` reads the engine pin out of `scripts/build-rift.sh` (#213), so the specs that need the
  * pinned version stop carrying hand-bumped copies of it.
  */
object RiftPinSpec extends ZIOSpecDefault:

  def spec = suite("RiftPin")(
    test("parses the RIFT_VERSION default assignment") {
      val script =
        "#!/bin/bash\n# RIFT_VERSION (default: 9.9.9)\nRIFT_VERSION=\"${RIFT_VERSION:-1.2.3}\"\n"
      assertTrue(RiftPin.parseDefault(script).contains("1.2.3"))
    },
    test("yields nothing when the assignment is absent") {
      assertTrue(RiftPin.parseDefault("RIFT_VERSION=1.2.3\n").isEmpty)
    },
    test("finds a release version in the real build-rift.sh") {
      for text <- ZIO.attemptBlocking(
          new String(
            Files.readAllBytes(Paths.get("scripts", "build-rift.sh")),
            StandardCharsets.UTF_8
          )
        )
      yield assertTrue(RiftPin.parseDefault(text).exists(_.matches("""\d+\.\d+\.\d+""")))
    }
  )
