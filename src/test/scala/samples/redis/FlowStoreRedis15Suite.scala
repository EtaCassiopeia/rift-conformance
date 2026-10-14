package samples.redis

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 15 — flow-store backend parity (#174): the same flow-state scenarios against an
  * in-memory and a Redis-backed imposter, asserting identical literal results.
  *
  * Lives outside `samples.rift` on purpose: `engineConformance` is `testOnly samples.rift.*`, and
  * this suite needs Docker (a `redis:7-alpine` container, see
  * [[samples.rift.support.RedisFlowStore]]), which the plain Plane A leg does not have. It runs
  * only through the `engineRedis` alias with `RIFT_IT=1` and the external engine. zio-bdd has no
  * per-run skip, so gating by package is what keeps the Docker-free legs green.
  *
  * scenarioParallelism=1 like every other Plane A suite: scenarios probe free imposter ports on one
  * shared engine.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/15-flow-store-redis.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object FlowStoreRedis15Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
