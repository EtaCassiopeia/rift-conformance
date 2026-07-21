package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 13 — the rift 0.14.0 error-response envelope (rift#611): every engine error door serves
  * `{"errors":[{"code","message"}]}` with `Content-Type: application/json`, the message round-trips
  * serde-escaped (the undecodable-quote regression), and the doors that deliberately keep a
  * plain-text reply are pinned as negatives so a later sweep can't quietly absorb them.
  *
  * Doors whose scenario already exists elsewhere assert the envelope in place (04-scripting-bounds
  * for the script/inject doors); this feature owns the cross-cutting ones — strictBehaviors
  * shellTransform/binary, `defaultForward` 502, the over-size 413, the disabled 503, and the
  * admin-plane 400. Requires `RIFT_BUILD_DIR`.
  *
  * scenarioParallelism=1 like every other Plane A suite: the scenarios share one engine and probe
  * free imposter ports, so concurrent scenarios can pick the same port and end up sending to each
  * other's imposter (which answers 200 + `x-rift-no-match`, silently masking an error door).
  */
@Suite(
  featureDirs = Array("src/test/resources/features/rift/13-error-envelope.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object ErrorEnvelope13Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
