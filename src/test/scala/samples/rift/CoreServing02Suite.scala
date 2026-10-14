package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 02 — core serving behaviors ported from conformance.sh (predicates, repeat cycling,
  * copy, FSM, multi-value headers, date templates, defaultForward, single-port gateway). Requires
  * `RIFT_BUILD_DIR` (external mode).
  */
@Suite(
  featureDirs = Array("classpath:features/rift/02-core-serving.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object CoreServing02Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
