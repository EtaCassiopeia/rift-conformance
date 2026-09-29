package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 16 — front-door routing and single-port gateway dispatch (rift
  * docs/features/front-door.md, gateway.md, #187). The `@front-door` scenarios swap the shared
  * engine for a `--configfile` engine started with `--front-door`
  * (`startReloadConfigWithFrontDoor`, reverted in `afterScenario` exactly like feature 11) and
  * route by `Host`/path/header/method; the boot validation rows spawn a throwaway engine and await
  * its exit (`bootFrontDoorEngine`). Both are external mode only. The untagged gateway scenarios
  * run on the shared engine. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/16-front-door.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object FrontDoor16Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
