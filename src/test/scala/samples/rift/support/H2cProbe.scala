package samples.rift.support

import java.io.{ByteArrayOutputStream, DataInputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** How one HTTP/2 stream ended, as seen on the wire. */
enum H2Outcome:
  /** The server answered: `status` is known only when HPACK sent it as one indexed byte. */
  case Answered(status: Option[Int], body: String)

  /** The server reset the stream with `RST_STREAM` carrying `errorCode`. */
  case Reset(errorCode: Long)

  /** The connection ended (GOAWAY, EOF, timeout) before the stream did. */
  case ConnectionLost(reason: String)

/** A cleartext HTTP/2 (h2c prior-knowledge) client just rich enough to show what a server does to
  * two requests sent one after the other on ONE connection (rift#1234: a TCP fault reached over
  * HTTP/2 resets only its own stream). Hand-encoded frames, HPACK from the static table only; the
  * JDK client has no cleartext prior-knowledge mode.
  */
object H2cProbe:

  /** RST_STREAM / GOAWAY error code INTERNAL_ERROR (RFC 9113 §7). */
  val InternalError: Long = 2L

  private val TimeoutMillis = 5000

  /** The static-table `:status` values HPACK can send as one indexed byte (RFC 7541 appendix A). */
  private val IndexedStatus =
    Map(8 -> 200, 9 -> 204, 10 -> 206, 11 -> 304, 12 -> 400, 13 -> 404, 14 -> 500)

  private def frame(tpe: Int, flags: Int, stream: Int, payload: Array[Byte]): Array[Byte] =
    val n = payload.length
    Array[Byte]((n >> 16).toByte, (n >> 8).toByte, n.toByte, tpe.toByte, flags.toByte) ++
      ByteBuffer.allocate(4).putInt(stream).array() ++ payload

  /** A literal header field without indexing, name from static-table `index`, raw (non-Huffman)
    * value — valid for values under 127 bytes.
    */
  private def literal(index: Int, value: String): Array[Byte] =
    val v = value.getBytes(ISO_8859_1)
    require(v.length < 127, s"header value too long for this probe: $value")
    Array[Byte](index.toByte, v.length.toByte) ++ v

  // :method GET (2), :scheme http (6), then :path (4) and :authority (1) as literals.
  private def requestHeaders(path: String, authority: String): Array[Byte] =
    Array[Byte](0x82.toByte, 0x86.toByte) ++ literal(4, path) ++ literal(1, authority)

  /** GET `first`, wait for its stream to end, then GET `second` on the same connection. */
  def twoStreams(
      host: String,
      port: Int,
      authority: String,
      first: String,
      second: String
  ): (H2Outcome, H2Outcome) =
    val socket = new Socket()
    try
      socket.connect(new InetSocketAddress(host, port), TimeoutMillis)
      socket.setSoTimeout(TimeoutMillis)
      val out = socket.getOutputStream
      val in = new DataInputStream(socket.getInputStream)
      out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(ISO_8859_1))
      out.write(frame(4, 0, 0, Array.emptyByteArray))
      out.flush()

      def exchange(stream: Int, path: String): H2Outcome =
        out.write(frame(1, 0x5, stream, requestHeaders(path, authority))) // END_STREAM|END_HEADERS
        out.flush()
        val body = new ByteArrayOutputStream()
        var status = Option.empty[Int]
        var outcome = Option.empty[H2Outcome]
        try
          while outcome.isEmpty do
            val len =
              (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte()
            val tpe = in.readUnsignedByte()
            val flags = in.readUnsignedByte()
            val id = in.readInt() & 0x7fffffff
            val payload = in.readNBytes(len)
            if tpe == 4 && id == 0 && (flags & 0x1) == 0 then
              out.write(frame(4, 0x1, 0, Array.emptyByteArray)) // ACK the server's SETTINGS
              out.flush()
            else if tpe == 6 && (flags & 0x1) == 0 then
              out.write(frame(6, 0x1, 0, payload)) // PING ACK
              out.flush()
            else if tpe == 7 then
              outcome = Some(H2Outcome.ConnectionLost(s"GOAWAY before stream $stream ended"))
            else if id == stream then
              if tpe == 3 then
                outcome = Some(
                  H2Outcome.Reset(ByteBuffer.wrap(payload).getInt.toLong & 0xffffffffL)
                )
              else
                if tpe == 9 || (tpe == 1 && (flags & 0x28) != 0) then
                  throw new IllegalStateException(s"frame type $tpe/flags $flags not handled")
                if tpe == 1 then
                  status = payload.headOption
                    .filter(b => (b & 0x80) != 0)
                    .map(_ & 0x7f)
                    .flatMap(IndexedStatus.get)
                if tpe == 0 then body.write(payload)
                if (tpe == 0 || tpe == 1) && (flags & 0x1) != 0 then
                  outcome = Some(H2Outcome.Answered(status, body.toString(UTF_8)))
        catch
          case e: java.io.IOException =>
            outcome = Some(
              H2Outcome.ConnectionLost(s"${e.getClass.getSimpleName}: ${e.getMessage}")
            )
        outcome.getOrElse(H2Outcome.ConnectionLost("no outcome"))

      val a = exchange(1, first)
      val b = a match
        case H2Outcome.ConnectionLost(_) => H2Outcome.ConnectionLost("connection already lost")
        case _ => exchange(3, second)
      (a, b)
    finally socket.close()
