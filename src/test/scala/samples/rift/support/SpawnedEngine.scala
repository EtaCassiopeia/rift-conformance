package samples.rift.support

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** A short-lived, dedicated `rift` child for a spec that needs CLI flags the shared harness engine
  * never passes (`--api-key`, `--local-only`, `--datadir`, …). Spawned through
  * [[RiftHarness.rawSpawn]] on [[TestPorts]] ports; its combined stdout/stderr goes to [[log]] so a
  * case can assert on what the engine printed.
  */
final case class SpawnedEngine(proc: Process, adminPort: Int, metricsPort: Int, log: Path):
  val adminUrl: String = s"http://127.0.0.1:$adminPort"
  val metricsUrl: String = s"http://127.0.0.1:$metricsPort"
  def output: Task[String] =
    ZIO.attemptBlocking(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))

/** How a rift process ended: its exit code and everything it printed. */
final case class Exited(exitCode: Int, output: String)

object SpawnedEngine:

  /** How long a startup-refusal case waits for the engine to exit on its own. */
  private val ExitBudgetSeconds = 10L

  /** The external config when this is an external-mode run with a build, else `None` — the
    * self-gate the dedicated-spawn specs use to no-op on the embedded/container legs and without a
    * build. Only the ABSENCE of `RIFT_BUILD_DIR` (or a non-external `RIFT_MODE`) skips; a config
    * that is present but broken fails loudly instead of passing every case unrun.
    */
  val externalConfig: IO[Throwable, Option[RiftHarnessConfig]] =
    if sys.env.get("RIFT_BUILD_DIR").exists(_.trim.nonEmpty) then
      RiftHarnessConfig.fromEnv.map(cfg => Option.when(cfg.mode == RiftMode.External)(cfg))
    else ZIO.none

  /** Run `effect` against the external config, or pass trivially when this is not an external run.
    */
  def external[R](
      effect: RiftHarnessConfig => ZIO[R & Scope, Throwable, zio.test.TestResult]
  ): ZIO[R, Throwable, zio.test.TestResult] =
    externalConfig.flatMap {
      case Some(cfg) => ZIO.scoped[R](effect(cfg))
      case None => ZIO.succeed(zio.test.assertCompletes)
    }

  private val tempLog: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempFile("rift-spawned-", ".log")))(p =>
      ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie
    )

  private def spawn(
      cfg: RiftHarnessConfig,
      extraArgs: List[String],
      extraEnv: Map[String, String]
  ): ZIO[Scope, Throwable, SpawnedEngine] =
    for
      adminPort <- RiftHarness.freePort
      metricsPort <- RiftHarness.freePort
      log <- tempLog
      proc <- ZIO.acquireRelease(
        RiftHarness.rawSpawn(
          cfg,
          adminPort,
          metricsPort,
          None,
          None,
          extraArgs = extraArgs,
          extraEnv = extraEnv,
          logTo = Some(log)
        )
      )(p => RiftHarness.killProcess(p).orDie)
    yield SpawnedEngine(proc, adminPort, metricsPort, log)

  /** Spawn and wait until the metrics and admin listeners accept connections. A TCP probe (not a
    * `GET`) because a keyed engine answers every unauthenticated admin request `401`.
    */
  def start(
      cfg: RiftHarnessConfig,
      extraArgs: List[String] = Nil,
      extraEnv: Map[String, String] = Map.empty
  ): ZIO[Scope, Throwable, SpawnedEngine] =
    for
      eng <- spawn(cfg, extraArgs, extraEnv)
      _ <- (RiftHarness.awaitTcpReady("127.0.0.1", eng.metricsPort, eng.proc) *>
        RiftHarness.awaitTcpReady("127.0.0.1", eng.adminPort, eng.proc))
        .tapError(_ => eng.output.flatMap(out => ZIO.logError(s"rift output:\n$out")).ignore)
    yield eng

  /** Spawn an engine expected to REFUSE to start: wait (bounded) for it to exit and return its exit
    * code and output. Fails if it is still running after the budget — a refusal that never comes is
    * the regression, not a pass.
    */
  def refused(
      cfg: RiftHarnessConfig,
      extraArgs: List[String],
      extraEnv: Map[String, String] = Map.empty
  ): ZIO[Scope, Throwable, Exited] =
    for
      eng <- spawn(cfg, extraArgs, extraEnv)
      exited <- ZIO.attemptBlocking(eng.proc.waitFor(ExitBudgetSeconds, TimeUnit.SECONDS))
      out <- eng.output
      _ <- ZIO.unless(exited)(
        ZIO.fail(
          new RuntimeException(
            s"rift ${extraArgs.mkString(" ")} still running after ${ExitBudgetSeconds}s:\n$out"
          )
        )
      )
    yield Exited(eng.proc.exitValue(), out)
