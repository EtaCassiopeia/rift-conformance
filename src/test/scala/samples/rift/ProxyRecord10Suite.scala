package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 10 — proxy record/replay: a `proxyOnce` stub carrying `predicateGenerators` PREPENDS a
  * recorded stub after the first data-plane request and replays it once the upstream is gone; the
  * same config WITHOUT `predicateGenerators` never records; `proxyTransparent` forwards but records
  * nothing; a multi-level `proxyAlways` chain resolves through every hop; `predicateGenerators` can
  * capture the query string into the recorded predicate. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/10-proxy-record.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object ProxyRecord10Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
