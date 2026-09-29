package samples.rift

import samples.rift.support.TestPorts
import zio.*
import zio.test.*

import java.net.ServerSocket

/** Process-free tests for the `TestPorts` allocator (#184): the fix for
  * `ConfigInterceptBlock113Spec`'s CI flake, `intercept: bind failed: Address already in use`, a
  * TOCTOU between a `ServerSocket(0)`-probed port and the spawned rift's own bind.
  *
  * These build a small, deterministic [[TestPorts.Allocator]] directly (a known base/size/start),
  * rather than exercising the randomized-start `TestPorts.next` singleton, so the assertions don't
  * have to race the real 12000-port range.
  */
object TestPortsSpec extends ZIOSpecDefault:

  def spec = suite("TestPorts")(
    test("allocations are distinct across many calls") {
      val allocator = new TestPorts.Allocator(base = 20000, size = 200, startOffset = 0)
      for ports <- ZIO.foreach(1 to 150)(_ => allocator.next)
      yield assertTrue(ports.distinct.size == ports.size)
    },
    test("every allocation falls inside the given range") {
      val allocator = new TestPorts.Allocator(base = 20000, size = 200, startOffset = 0)
      for ports <- ZIO.foreach(1 to 150)(_ => allocator.next)
      yield assertTrue(ports.forall(p => p >= 20000 && p < 20200))
    },
    test("the public range sits outside both OSes' ephemeral ports") {
      // Linux: 32768-60999. macOS/BSD: 49152-65535. Neither overlaps [20000, 32000).
      assertTrue(
        TestPorts.RangeStart == 20000,
        TestPorts.RangeEnd == 32000,
        TestPorts.RangeEnd <= 32768
      )
    },
    test("a port with a live listener is skipped, even as the allocator's first candidate") {
      // A known base + a startOffset of 0 makes the FIRST slot the allocator tries exactly the one
      // this test holds open — deterministic, unlike racing the real range for a live collision.
      val allocator = new TestPorts.Allocator(base = 20200, size = 3, startOffset = 0)
      ZIO.scoped {
        for
          held <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(new ServerSocket(20200)))
          port <- allocator.next
        yield assertTrue(port != 20200, port == 20201)
      }
    },
    test("exhausting the range fails loudly rather than repeating a dispensed port") {
      val allocator = new TestPorts.Allocator(base = 20300, size = 2, startOffset = 0)
      for
        first <- allocator.next
        second <- allocator.next
        third <- allocator.next.exit
      yield assertTrue(
        first != second,
        third.isFailure,
        third.causeOption.exists(_.squashTrace.getMessage.contains("no free port"))
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
