package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 14 — `_behaviors` and `repeat` on inject, proxy and fault responses (rift#1184, fixed by
  * rift#1192/#1194): decorate/wait on inject results, decorate on live and recorded proxy
  * responses, a block `repeat` cycling every response type, and the matching rift-lint rules. Kept
  * apart from feature 06, which covers `is` responses. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/14-behaviors-response-types.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object BehaviorsResponseTypes14Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
