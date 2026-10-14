package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 05 — response templating ported from conformance-ext.sh
  * (request.method/path/query/header variables and the {{NOW}}, {{DAYS±N}}, {{MONTHS±N}} date
  * tokens). Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/05-templating.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Templating05Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
