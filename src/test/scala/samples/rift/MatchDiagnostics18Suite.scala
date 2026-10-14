package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 18 — match diagnostics (#193): the `_rift.warnings` stub analysis on `GET
  * /imposters/{port}`, the `X-Rift-Debug` report, and the journal's `matchOutcome`. Runs on the
  * external and embedded legs. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/18-match-diagnostics.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object MatchDiagnostics18Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
