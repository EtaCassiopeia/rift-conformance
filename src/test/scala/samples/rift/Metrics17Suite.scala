package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 17 — metrics (#192): the Prometheus families on `--metrics-port` (`@metrics-port`,
  * external only) and the admin `GET /metrics` (every leg), asserted as per-imposter deltas.
  * Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/17-metrics.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Metrics17Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
