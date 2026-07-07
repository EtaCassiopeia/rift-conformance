package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 03 — fault injection and transport errors ported from the conformance.sh fault sections
  * (tcp reset, RANDOM_DATA_THEN_CLOSE, latency floor, error fault, precedence tcp>error>latency,
  * probability:0 no-op, and the rift#309 top-level-fault transport reset, fixed in rift 0.9.0).
  * Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/03-faults-transport.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object FaultsTransport03Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
