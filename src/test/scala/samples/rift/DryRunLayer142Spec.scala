package samples.rift

import samples.rift.support.{RiftHarness, RiftHarnessConfig, RiftHarnessDryRun}
import zio.*
import zio.test.*

import java.net.URI

/** #142 — the `lintFeatures` dry-run gate. Pins the two invariants the fix relies on. First,
  * `RiftHarness.layer` under dry-run yields an engine-free harness — it must build WITHOUT
  * `RIFT_BUILD_DIR`, which is exactly what let `lintFeatures` go red on a clean checkout. Second,
  * the dry-run harness fails LOUDLY if a member is ever reached (it is not a silent null object) —
  * so a non-dry code path that hit it by mistake surfaces as a defect, not a wrong answer served
  * quietly.
  *
  * These run in the normal `engineConformance` sweep too; they touch no engine and no global system
  * property, so they are backend- and mode-agnostic.
  */
object DryRunLayer142Spec extends ZIOSpecDefault:

  def spec = suite("lintFeatures dry-run gate (#142)")(
    test("isDryRun keys off the exact property value") {
      assertTrue(
        RiftHarnessConfig.isDryRun(Some("true")),
        !RiftHarnessConfig.isDryRun(Some("false")),
        !RiftHarnessConfig.isDryRun(Some("1")),
        !RiftHarnessConfig.isDryRun(None)
      )
    },
    // Exercises the PRODUCTION wiring — `RiftHarness.layerFor(true)`, the exact branch `layer` takes
    // under `-Drift.dryRun=true` — not the stub layer directly. A revert of that branch fails here
    // even though `isDryRun` and the stub stay correct in isolation (the regression the PR prevents).
    test("layerFor(true) builds with no RIFT_BUILD_DIR and yields the engine-free stub") {
      // buildInfo is a step-only member ⇒ dies iff it's the dry-run stub, not a real engine.
      ZIO
        .serviceWithZIO[RiftHarness](_.buildInfo)
        .provide(RiftHarness.layerFor(true))
        .exit
        .map(exit => assertTrue(exit.isFailure))
    },
    // The behaviour that actually lets the afterScenario hooks no-op: cleanup-reachable members are
    // inert (a valid dead-loopback URL, identity host, a succeeding revert), never a thrown defect.
    test("layerFor(true) answers cleanup-reachable members inertly so hooks stay no-ops") {
      ZIO
        .serviceWithZIO[RiftHarness] { h =>
          // Bind the Java-static result to a plain Boolean BEFORE assertTrue: calling `URI.create`
          // inside the assertTrue macro trips NoClassDefFoundError on the companion (java/net/URI$).
          val adminParsesToDeadPort = URI.create(h.adminUrl).getPort == 1
          h.revertEngine.as(
            assertTrue(
              h.adminUrl == RiftHarnessDryRun.DeadUrl,
              h.metricsUrl == RiftHarnessDryRun.DeadUrl,
              adminParsesToDeadPort, // parses as a real (dead) URL, not a placeholder string
              h.hostFor(45001) == "localhost"
            )
          )
        }
        .provide(RiftHarness.layerFor(true))
    }
  )
