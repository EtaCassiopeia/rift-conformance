package samples.rift

import samples.rift.support.RiftConformanceSupport
import zio.*
import zio.test.*

import java.io.{BufferedOutputStream, InputStream}
import java.net.{ServerSocket, Socket}

/** Issue #131 — an over-size POST must still read the engine's `413`, not throw.
  *
  * The engine answers `413` and closes as soon as it has read past its 10 MiB cap, while the client
  * is still streaming the remaining megabytes. A client that writes the whole body before looking
  * at the socket takes that close as a reset (on Linux) and throws an `IOException` out of `send()`
  * — so the step failed before any assertion ran. That is the CI flake.
  *
  * `Expect: 100-continue` does **not** fix it: probing a live 0.15.0 engine shows it answers `100
  * Continue` unconditionally rather than pre-rejecting on `Content-Length`, so the body streams and
  * the cap still trips mid-upload. (curl survives because it reads the socket *while* uploading,
  * which is the behaviour actually being restored here.)
  *
  * The real race is Linux-only, so this spec does not depend on it: the server below reproduces the
  * behaviour deterministically on any platform — read a little, answer `413`, close at once, and
  * never drain the rest. A sender that writes-then-reads fails against it; one that watches the
  * socket while writing passes.
  */
object OversizeSend131Spec extends ZIOSpecDefault:

  /** A server that behaves like the engine's over-size door: reads a small prefix, replies `413`
    * with the standard envelope, then closes without draining the remaining body.
    */
  private def rejectingServer: ZIO[Scope, Throwable, Int] =
    ZIO
      .acquireRelease(ZIO.attempt(new ServerSocket(0)))(s => ZIO.succeed(s.close()))
      .tap { server =>
        ZIO.attemptBlocking {
          val sock: Socket = server.accept()
          try
            val in: InputStream = sock.getInputStream
            val buf = new Array[Byte](8 * 1024)
            // Read only a prefix — the point is that the rest of the body is never drained.
            var seen = 0
            while seen < 16 * 1024 && in.read(buf) > 0 do seen += buf.length
            val body = """{"errors":[{"code":"413","type":"request too large",""" +
              """"message":"Request body exceeds maximum size of 10485760 bytes"}]}"""
            val out = new BufferedOutputStream(sock.getOutputStream)
            out.write(
              (s"HTTP/1.1 413 Payload Too Large\r\nContent-Type: application/json\r\n" +
                s"Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                .getBytes("UTF-8")
            )
            out.flush()
          finally sock.close()
        }.forkScoped
      }
      .map(_.getLocalPort)

  def spec = suite("over-size send reads the early 413")(
    test("a body far larger than the server will drain still yields the 413 envelope") {
      ZIO.scoped {
        for
          port <- rejectingServer
          // Large enough that the server's close lands long before the write completes.
          resp <- ZIO.attemptBlocking(
            RiftConformanceSupport.sendOversize(
              s"http://127.0.0.1:$port/echo",
              "POST",
              Map("Content-Type" -> "text/plain"),
              "a" * (32 * 1024 * 1024)
            )
          )
          ctype = resp.headers.get("content-type").map(_.mkString(","))
        yield assertTrue(
          resp.status == 413,
          resp.body.contains("exceeds maximum size"),
          ctype.contains("application/json")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
