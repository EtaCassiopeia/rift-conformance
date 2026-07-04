package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 08 — admin surface: stub-by-id lookup, enable/disable (503/200), the X-Rift-Debug
  * introspection header, savedRequests `?match=` filtering (single + AND-stacked) and targeted
  * delete, flow-state KV lifecycle (rift#190), and the atomic `PUT /imposters` validate-then-swap
  * (valid swap + invalid-batch rejection leaving the previous set untouched). Requires
  * `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/08-admin-surface.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object AdminSurface08Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
