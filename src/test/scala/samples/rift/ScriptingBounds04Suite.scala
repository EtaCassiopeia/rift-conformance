package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 04 — scripting engines and runaway bounds ported from conformance-ext.sh (rhai
  * flow_store counter, lua, js inject, and the rift#308 runaway-timeout + concurrency-resilience).
  * Requires `RIFT_BUILD_DIR`. scenarioParallelism=1 deliberately saturates the blocking-script
  * threads.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/04-scripting-bounds.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object ScriptingBounds04Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
