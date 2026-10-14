package samples.rift

import samples.rift.support.{RiftConformanceSupport, RiftHarness, RiftState}
import zio.ZLayer
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Feature 09 — gateway routing, binary-mode responses, self-signed HTTPS, and Prometheus metrics:
  * the single-port gateway (`{admin}/__rift/<port><path>`), a `_mode: binary` response's base64
  * body decoded on the wire, an HTTPS imposter's auto-generated self-signed cert served over TLS,
  * and the dedicated `--metrics-port` exposing `rift_requests_total` incremented once per
  * data-plane request. Requires `RIFT_BUILD_DIR`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/09-gateway-https-metrics.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object GatewayHttpsMetrics09Suite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:
  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer
