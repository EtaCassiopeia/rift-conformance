package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 22 — Mountebank-compatible predicates, imposter options, response forms and behaviors
  * that the corpus loads but never asserts (#203). Runs on every leg. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/22-mountebank-compat.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object MountebankCompat22Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
