package samples.rift.support

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.model.{ExposedPort, Ports}
import org.testcontainers.containers.wait.strategy.Wait
import zio.*

/** Container mode (#12): the engine runs in a Docker container on a published image (`RIFT_IMAGE`),
  * driven over HTTP through published ports — the Plane A oracle proving the transport surface
  * across the container boundary. Docker-gated (the runner SKIPs when `RIFT_IT!=1` or docker is
  * absent).
  *
  * Ports are published **1:1** (container port N → host port N), not testcontainers' default random
  * mapping: every Plane A addressing path assumes identity — `portAnswers`/the send steps hit
  * `localhost:<declaredPort>`, and the host-side `rift-verify` drives imposter ports directly — so
  * a randomly-mapped port would be unreachable at its declared number. (This is the "pre-mapped
  * port pool, identity" path zio-bdd's own `Rift.managed` scaladoc names as the CI alternative to
  * random mapping.) The transport surface is still exercised end-to-end: requests cross the real
  * host→container socket boundary.
  */
private final class RiftHarnessContainerLive(
    val adminUrl: String,
    val metricsUrl: String,
    host: String,
    interceptPort: Int,
    info: RiftBuildInfo,
    val tools: RiftTools
) extends RiftHarness:

  def hostFor(imposterPort: Int): String = host // identity — ports are published 1:1 to the host

  def buildInfo: UIO[RiftBuildInfo] = ZIO.succeed(info)

  // The container starts a `--intercept-port` listener published 1:1, so feature 12 drives it the
  // same way external mode does (HTTP /intercept admin on adminUrl + a CONNECT proxy to this port).
  override def interceptEndpoint: UIO[Option[(String, Int)]] =
    ZIO.succeed(Some((host, interceptPort)))

  // Hand out ports from the published pool by cycling a shared counter — NOT a host-freeness scan
  // (the default): the imposter binds inside the container, while the host port is held by Docker's
  // forward, so every host port in the range looks "busy". `postImposterFromFixture` retries a
  // non-201 POST (a still-in-use port), and the pool (80 ports) far exceeds the live imposter count,
  // so cycling settles on a free one. Only ports the container actually published are handed out.
  override def freshImposterPort(lo: Int, hi: Int): IO[Throwable, Int] =
    ZIO.succeed {
      val span = hi - lo + 1
      lo + Math.floorMod(RiftHarnessContainer.portCursor.getAndIncrement(), span)
    }

  private def unsupported(op: String): IO[Throwable, Nothing] =
    ZIO.fail(
      new UnsupportedOperationException(
        s"RIFT_MODE=container does not support $op — feature 11 (reload) runs external/embedded, and " +
          "the container suite alias (engineContainer) excludes it"
      )
    )

  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit] = unsupported("withConfigFile")
  def startReloadConfig(json: String): Task[Unit] = unsupported("startReloadConfig")
  // No-op: nothing is ever pending (reload is never started here), so the afterScenario cleanup hook
  // that calls this unconditionally stays safe.
  def revertEngine: Task[Unit] = ZIO.unit

object RiftHarnessContainer:
  private val AdminPort = 2525
  // The opt-in intercept / TLS-MITM listener (feature 12): started with `--intercept-port` and
  // published 1:1 so a JDK CONNECT-proxy client can reach it at its declared port (#51).
  private val InterceptPort = 8443

  // Container ports published 1:1 to the host (see the class doc). 2525 admin; 8443 intercept; 4500–
  // 4599 covers the corpus imposters (declared 4501–4520) with margin; 4800–4899 covers the fixed
  // Plane A fixtures (4800–4819) and the dynamic probe pool (4820–4899).
  private val PublishedPorts: List[Int] =
    AdminPort :: InterceptPort :: ((4500 to 4599) ++ (4800 to 4899)).toList

  // Default tracks build-rift.sh's pinned rift 0.13.1, so the containerised engine and the host
  // rift-lint/rift-verify tools (from RIFT_BUILD_DIR) are one released build. Override with RIFT_IMAGE.
  private val DefaultImage = "zainalpour/rift-proxy:v0.15.0"

  // Process-wide singleton: zio-bdd builds the `environment` layer more than once per run, and a
  // container on FIXED host ports cannot be started twice (the second bind fails "port already
  // allocated"). External mode tolerates rebuilds via choosePort's ephemeral fallback and embedded
  // via port 0; container mode's identity ports force a single shared container instead. Reaped by a
  // JVM shutdown hook (below) plus testcontainers' Ryuk, so `acquire` registers no per-scope
  // finalizer that would stop a container a sibling build still uses.
  private val lock = new AnyRef
  @volatile private var shared: GenericContainer = null

  // Round-robin cursor over the probe pool, shared across every RiftHarnessContainerLive built this
  // run (see freshImposterPort). Non-negative modulo keeps it in range even after Int overflow.
  private[support] val portCursor = new java.util.concurrent.atomic.AtomicInteger(0)

  def acquire(cfg: RiftHarnessConfig): ZIO[Scope, Throwable, RiftHarness] =
    for
      image <- envOr("RIFT_IMAGE", DefaultImage)
      container <- ZIO.attemptBlocking(obtain(image))
      host = container.host
      adminUrl = s"http://$host:${container.mappedPort(AdminPort)}"
      info <- RiftBuildInfo.fromBuildDir(cfg.buildDir, engineVersion = None)
    yield RiftHarnessContainerLive(
      adminUrl,
      adminUrl,
      host,
      container.mappedPort(InterceptPort),
      info,
      RiftTools.fromBuildDir(cfg.buildDir)
    )

  private def obtain(image: String): GenericContainer =
    lock.synchronized {
      if shared != null then shared
      else
        val gc = GenericContainer(
          dockerImage = image,
          exposedPorts = PublishedPorts,
          command = Seq(
            "--port",
            AdminPort.toString,
            "--allow-injection",
            "--intercept-port",
            InterceptPort.toString
          ),
          waitStrategy = Wait.forHttp("/imposters").forPort(AdminPort)
        )
        // Force fixed 1:1 host bindings (testcontainers otherwise maps to random host ports).
        gc.container.withCreateContainerCmdModifier { (cmd: CreateContainerCmd) =>
          val bindings = new Ports()
          PublishedPorts.foreach(p => bindings.bind(ExposedPort.tcp(p), Ports.Binding.bindPort(p)))
          cmd.getHostConfig.withPortBindings(bindings)
          ()
        }
        gc.start()
        val hook = new Thread(() =>
          try gc.stop()
          catch {
            // A stop failure at JVM exit would otherwise vanish (and leak the fixed-port container,
            // making the NEXT run fail with an opaque "port already allocated"). Ryuk is the backstop;
            // surface the reason on stderr so a leak is at least traceable. stderr is safe in a hook.
            case e: Throwable =>
              java.lang.System.err
                .println(s"[RiftHarnessContainer] shutdown-hook container stop failed: $e")
          }
        )
        java.lang.Runtime.getRuntime.addShutdownHook(hook)
        shared = gc
        gc
    }

  private def envOr(name: String, default: String): UIO[String] =
    ZIO
      .attempt(Option(java.lang.System.getenv(name)).map(_.trim).filter(_.nonEmpty))
      .map(_.getOrElse(default))
      .orDie
