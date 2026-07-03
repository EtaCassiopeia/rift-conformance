package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 06 — response behaviors ported from conformance.sh + conformance-ext.sh (copy from
  * header / via jsonpath, CSV `lookup`, `wait` timing, `shellTransform`, JS `decorate`, and the
  * rift#272 Set-Cookie-survives-copy check). Requires `RIFT_BUILD_DIR`; the CSV `lookup` needs the
  * engine's cwd to be `$RIFT_CORPUS_ROOT` (RiftHarness sets this).
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/06-behaviors.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Behaviors06Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
