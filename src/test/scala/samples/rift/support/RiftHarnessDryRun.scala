package samples.rift.support

import zio.*

/** The Plane A environment layer used under **`--dry-run`** (the `lintFeatures` step-matching
  * gate).
  *
  * A dry run validates that every Gherkin step has a matching step definition; it never executes a
  * step body, so it needs no engine. But zio-bdd builds a suite's `environment` layer
  * unconditionally and — importantly — still runs the per-scenario **cleanup hooks**
  * (`afterScenario`) even under `--dry-run`. So the real [[RiftHarness.layer]] would call
  * `RiftHarnessConfig.fromEnv`, demand `RIFT_BUILD_DIR`, and fetch an engine purely to be thrown
  * away — which is why `lintFeatures` was red on a clean checkout (#142). When `-Drift.dryRun=true`
  * is set (the `lintFeatures` command sets it) [[RiftHarness.layer]] yields this stub instead,
  * spawning nothing.
  *
  * The member split follows exactly what a dry run touches:
  *
  *   - **Cleanup-reachable** members — the ones the `afterScenario` hooks read even with no steps
  *     run — return **inert** values so those hooks complete as no-ops: `adminUrl`/`metricsUrl`
  *     point at a dead loopback port (the intercept cleanup's `DELETE` is best-effort and
  *     `.ignore`d, so a connection-refused is swallowed), `hostFor` is identity, and `revertEngine`
  *     is `unit` (nothing was swapped, so nothing to revert). This is not error-swallowing: under a
  *     dry run there is genuinely nothing to tear down, so "no-op" is the correct answer.
  *   - **Step-only** members — `buildInfo`, `tools`, `withConfigFile`, `startReloadConfig` — are
  *     reached ONLY from a step body. Under a dry run that cannot happen, so each **fails loudly**:
  *     a `sys.error`/`ZIO.die` turns "a step ran when it shouldn't have" into a visible defect
  *     rather than a quiet wrong answer.
  */
private final class RiftHarnessDryRun extends RiftHarness:
  private def reached(member: String): String =
    s"RiftHarnessDryRun.$member: reached under --dry-run — a step body ran with no engine"
  private def stepOnly(member: String): Nothing = sys.error(reached(member))
  private def stepOnlyZIO(member: String): UIO[Nothing] = ZIO.dieMessage(reached(member))

  // Cleanup-reachable — inert so afterScenario hooks are no-ops.
  def adminUrl: String = RiftHarnessDryRun.DeadUrl
  def metricsUrl: String = RiftHarnessDryRun.DeadUrl
  def hostFor(imposterPort: Int): String = "localhost"
  def revertEngine: Task[Unit] = ZIO.unit

  // Step-only — fail loud if a dry run ever reaches them.
  def tools: RiftTools = stepOnly("tools")
  def buildInfo: UIO[RiftBuildInfo] = stepOnlyZIO("buildInfo")
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] = stepOnlyZIO("withConfigFile")
  def startReloadConfig(json: String): Task[Unit] = stepOnlyZIO("startReloadConfig")

object RiftHarnessDryRun:
  /** A guaranteed-dead endpoint the cleanup-reachable members point at: loopback port 1 refuses
    * instantly (no DNS, no hang), so the best-effort cleanup HTTP the `afterScenario` hooks issue
    * fails fast and is `.ignore`d. Public so the gate spec can pin it.
    */
  val DeadUrl = "http://127.0.0.1:1"

  val layer: ULayer[RiftHarness] = ZLayer.succeed(new RiftHarnessDryRun)
