package samples.rift.support

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import zio.*

/** A `redis:7-alpine` container backing the Redis flow-store parity feature (#174, feature 15).
  *
  * Docker-gated: only `RIFT_IT=1` may start it, so a run without Docker fails with a message naming
  * the gate rather than a testcontainers stack trace. The container is started lazily by the first
  * scenario that needs it and shared for the rest of the JVM (zio-bdd builds a suite's env layer
  * more than once, so a layer-scoped container would be torn down under a sibling build). It is
  * stopped by a JVM shutdown hook, with testcontainers' Ryuk as the backstop, so nothing outlives
  * the run. The host port is testcontainers' random mapping — the engine runs on the host and
  * reaches Redis through it, so no fixed port can collide with a parallel run.
  */
object RedisFlowStore:

  val Image = "redis:7-alpine"
  private val RedisPort = 6379

  private val lock = new AnyRef
  @volatile private var shared: GenericContainer = null

  /** The `redis://host:port` URL an imposter's `_rift.flowState.redis.url` should point at. */
  def url: Task[String] =
    container.map(c => s"redis://${c.host}:${c.mappedPort(RedisPort)}")

  /** Every key matching `pattern` (a Redis glob), read with `redis-cli --scan` inside the container
    * so the harness needs no Redis client of its own.
    */
  def keys(pattern: String): Task[List[String]] =
    container.flatMap { c =>
      ZIO.attemptBlocking {
        val r = c.container.execInContainer("redis-cli", "--scan", "--pattern", pattern)
        if r.getExitCode != 0 then
          throw new RuntimeException(s"redis-cli --scan failed (${r.getExitCode}): ${r.getStderr}")
        r.getStdout.linesIterator.map(_.trim).filter(_.nonEmpty).toList.sorted
      }
    }

  private def container: Task[GenericContainer] =
    for
      gate <- System.env("RIFT_IT")
      _ <- ZIO
        .fail(
          new IllegalStateException(
            "the Redis flow-store feature needs Docker: run it with RIFT_IT=1 (sbt engineRedis)"
          )
        )
        .unless(gate.map(_.trim).contains("1"))
      c <- ZIO.attemptBlocking(obtain())
    yield c

  private def obtain(): GenericContainer =
    lock.synchronized {
      if shared != null then shared
      else
        val gc = GenericContainer(
          dockerImage = Image,
          exposedPorts = Seq(RedisPort),
          waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
        )
        gc.start()
        java.lang.Runtime.getRuntime.addShutdownHook(
          new Thread(() =>
            try gc.stop()
            catch {
              // Ryuk reaps the container regardless; stderr is the only channel left at JVM exit.
              case e: Throwable =>
                java.lang.System.err.println(s"[RedisFlowStore] shutdown-hook stop failed: $e")
            }
          )
        )
        shared = gc
        gc
    }
