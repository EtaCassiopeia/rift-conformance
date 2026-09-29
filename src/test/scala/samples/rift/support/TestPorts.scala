package samples.rift.support

import zio.*

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A fresh port for a SEPARATELY SPAWNED rift process, or for an admin-API/`--configfile` document
  * that hands a port to an ALREADY-RUNNING engine to bind (an imposter/intercept port) — never for
  * the harness's own probing.
  *
  * The pattern this replaces — `new ServerSocket(0)`, read `getLocalPort`, close — has two problems
  * as a *test* port source (issue #184, `ConfigInterceptBlock113Spec` flaking on CI with
  * `intercept: bind failed: Address already in use`):
  *
  * '''Ephemeral-range collision.''' Port `0` hands back (and immediately releases) a port drawn
  * from the OS's ephemeral range — Linux `32768–60999`, macOS `49152–65535`. That is the SAME pool
  * the JVM's own outgoing HTTP connections (admin-API calls, readiness polling) and every other
  * process on the box draw from. Between the probe-and-release and the spawned rift's own bind, an
  * unrelated outgoing connection can claim that exact port, and rift's bind loses the TOCTOU race.
  *
  * '''No never-repeat guarantee.''' Two back-to-back `ServerSocket(0)` calls can return the same
  * port (the OS may reissue one it just closed), which callers have had to work around ad hoc (e.g.
  * `ShippedExamples120Spec`'s old `freePort.repeatUntil(_ != port)`).
  *
  * `TestPorts.next` draws from a fixed range, `[20000, 32000)`, that sits OUTSIDE both OS ephemeral
  * pools above, starts at a random offset per JVM (so repeated/parallel runs don't all pile onto
  * the low end of the range), and advances monotonically so one JVM run never hands back a port it
  * has already dispensed — until the range itself is exhausted, which fails loudly rather than
  * silently wrapping into a port some other still-running case in this same JVM believes it owns.
  */
object TestPorts:
  val RangeStart = 20000
  val RangeEnd = 32000 // exclusive
  private val RangeSize = RangeEnd - RangeStart

  /** The default allocator: one per JVM, its scan position randomized so repeated/parallel runs
    * don't all start scanning from the same end of the range.
    */
  private lazy val default =
    new Allocator(RangeStart, RangeSize, scala.util.Random.nextInt(RangeSize))

  /** A fresh port in `[20000, 32000)`— not currently bound, not currently listening, and never
    * handed out before by this JVM's default allocator. `Task`, like the rest of the harness's port
    * helpers (`RiftHarness.freePort`/`choosePort`): the failure modes here (a `SecurityException`
    * from `ServerSocket`, or the range being exhausted) are exceptional, not part of the domain
    * contract, so a bare `Throwable` is the honest error type rather than a narrower one that would
    * have to be widened right back at every call site.
    */
  def next: Task[Int] = default.next

  /** `true` iff nothing answers a connect to `127.0.0.1:port` (refused/timed out) AND a fresh
    * `ServerSocket` can bind it. The connect check comes first for the same reason
    * [[RiftHarness]]'s port-freeness check does: `ServerSocket` sets `SO_REUSEADDR` and binds the
    * wildcard, which can succeed beside a process already listening on `0.0.0.0` (observed on
    * macOS) — the bind check alone would call a port "free" that very much is not.
    */
  private[support] def isFree(port: Int): IO[Throwable, Boolean] =
    ZIO.attemptBlocking {
      val probe = new Socket()
      val listening =
        try
          probe.connect(new InetSocketAddress("127.0.0.1", port), 200)
          true
        catch case _: java.io.IOException => false // refused/timed out: nobody is listening
        finally probe.close()
      if listening then false
      else
        try
          val s = new ServerSocket(port)
          s.close()
          true
        catch case _: java.io.IOException => false
    }

  /** Package-private constructor (not the singleton `next` above) so `TestPortsSpec` — like
    * `RiftHarnessSpec` reaching `RiftHarness.choosePort` — can build one with a known, small range
    * and a fixed start from `samples.rift`: deterministic assertions instead of racing the real,
    * randomized 12000-port range.
    *
    * @param base
    *   the first port in the range
    * @param size
    *   the range's width; ports are `[base, base + size)`
    * @param startOffset
    *   where the scan cursor begins, as an offset into `[0, size)`
    */
  private[rift] final class Allocator(base: Int, size: Int, startOffset: Int):
    private val cursor = new AtomicInteger(startOffset)
    // Ports this allocator has already handed out, this JVM run — checked (and claimed atomically
    // via `add`'s return value) before a candidate is returned, so two racing callers can never both
    // walk away believing they own the same port.
    private val dispensed = ConcurrentHashMap.newKeySet[Int]()

    def next: Task[Int] =
      def loop(triesLeft: Int): Task[Int] =
        if triesLeft <= 0 then
          ZIO.fail(
            new IllegalStateException(
              s"TestPorts: no free port left in [$base, ${base + size}) — every port in the range " +
                "has already been dispensed or is busy this JVM run"
            )
          )
        else
          val offset = Math.floorMod(cursor.getAndIncrement(), size)
          val port = base + offset
          if dispensed.contains(port) then loop(triesLeft - 1)
          else
            isFree(port).flatMap { free =>
              // `dispensed.add` is the atomic claim: if a racing caller's `next` reached this same
              // port first, ITS `add` returned true and this one must retry rather than double-hand
              // out the port.
              if free && dispensed.add(port) then ZIO.succeed(port) else loop(triesLeft - 1)
            }
      loop(size)
