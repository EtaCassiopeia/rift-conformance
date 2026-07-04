package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 11 — incremental reload (rift#316): `POST /admin/reload` reconciles the running
  * imposters toward a `--configfile` source INCREMENTALLY — an identical config reloads with an
  * empty `{created,replaced,stubPatched,deleted}` change report and preserves runtime state
  * (recorded requests) on unchanged imposters, rather than a wholesale stop/recreate. This
  * exercises `RiftHarness#startReloadConfig`/`revertEngine` (#10), which swap the SHARED engine for
  * a `--configfile`-backed one and revert it back to plain afterward — `afterScenario` in
  * `RiftConformanceSupport` guarantees the revert runs even if an assertion fails mid-scenario, so
  * every suite after this one still sees the ordinary shared engine on the same admin URL. Requires
  * `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/11-reload.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Reload11Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
