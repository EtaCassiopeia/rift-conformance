package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Plane A smoke suite (#4 acceptance): drives the feature files under features/rift against a real
  * engine started by RiftHarness. Requires `RIFT_BUILD_DIR` (external mode).
  */
// Target the specific feature file, not the whole features/rift directory — each suite owns its own
// feature so a suite never tries to run another suite's scenarios (which it has no steps for).
@Suite(
  featureDirs = Array("src/test/resources/features/rift/00-smoke.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object RiftSmokeSuite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
