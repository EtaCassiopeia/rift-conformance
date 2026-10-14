package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 19 — declarative flow state (#194): every `_rift.stateOps` op on the default in-memory
  * store, the fail-loud `flowState` config doors, the clear-flow admin route and the
  * carried-not-executed `sequencing`/`dataset` blocks. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/19-flow-state-declarative.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object FlowStateDeclarative19Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
