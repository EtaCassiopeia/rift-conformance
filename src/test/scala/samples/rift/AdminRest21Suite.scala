package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 21 — the admin REST long tail of `docs/api/index.md` (#200): the system routes (`/`,
  * `/health`, `/logs`), the listing views (port order, `list=true`, `removeProxies`), teardown
  * before return, stub insert-at-index / duplicate-id 409 / by-id replace and delete, space and
  * scenario routes, the 64 MiB admin body cap, and `DELETE /imposters` on a dedicated engine
  * (`@admin-isolated`, external only). Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/21-admin-rest.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object AdminRest21Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
