package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 07 — spaces (flow-id partitioning by header) and FSM declarative-scenario state:
  * per-space vs global stub routing, spaces CRUD, teardown isolation, space×FSM no-bleed, and
  * sequential + concurrent (rift#311) scenario-state transitions. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/07-spaces-and-fsm.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object SpacesFsm07Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
