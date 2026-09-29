package samples.rift.support

import java.io.{ByteArrayInputStream, DataInputStream, EOFException, InputStream, OutputStream}
import java.net.{InetAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.security.{KeyFactory, KeyStore, MessageDigest, SecureRandom}
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLServerSocket}

/** A minimal TLS WebSocket echo origin (RFC 6455) for feature 12 `@intercept-ws` (rift#997): the
  * real origin an unmatched `Upgrade: websocket` handshake is relayed to through the intercept
  * tunnel. It serves `cert` (a leaf for `localhost`, PKCS#8 `key`), answers every handshake with
  * `101`, and echoes each text frame back as an unmasked text frame until the peer closes.
  *
  * It binds an ephemeral loopback port itself and holds it, so there is no probe-then-bind window.
  */
final class WsEchoOrigin private (server: SSLServerSocket):
  private val accepted = ConcurrentHashMap.newKeySet[Socket]()
  private val failure = new AtomicReference[Option[Throwable]](None)

  def port: Int = server.getLocalPort

  /** The first failure while serving a connection (handshake, bad upgrade request), if any — so a
    * scenario that sees the tunnel close can say why the origin gave up.
    */
  def lastError: Option[Throwable] = failure.get

  /** Stop accepting and close every connection still open. */
  def close(): Unit =
    server.close()
    accepted.forEach(_.close())

  private val acceptor = new Thread(() =>
    try
      while true do
        val s = server.accept()
        s.setSoTimeout(RawHttpConnection.TimeoutMillis)
        accepted.add(s)
        val t = new Thread(() =>
          try WsEchoOrigin.serve(s)
          catch
            case _: EOFException | _: SocketException => () // the peer went away: a normal end
            case e: Exception => failure.compareAndSet(None, Some(e)); ()
          finally
            s.close()
            accepted.remove(s); ()
        )
        t.setDaemon(true)
        t.start()
    catch case _: SocketException => () // the server socket was closed
  )
  acceptor.setDaemon(true)
  acceptor.start()

object WsEchoOrigin:
  private val Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  def start(certPem: String, keyPem: String): WsEchoOrigin =
    val cert = CertificateFactory
      .getInstance("X.509")
      .generateCertificate(new ByteArrayInputStream(certPem.getBytes(UTF_8)))
    val der = Base64.getMimeDecoder.decode(
      keyPem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
    )
    val key = KeyFactory
      .getInstance(cert.getPublicKey.getAlgorithm)
      .generatePrivate(new PKCS8EncodedKeySpec(der))
    val password = Array.emptyCharArray
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, null)
    store.setKeyEntry("origin", key, password, Array(cert))
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(store, password)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, new SecureRandom())
    val server = ctx.getServerSocketFactory
      .createServerSocket(0, 16, InetAddress.getLoopbackAddress)
      .asInstanceOf[SSLServerSocket]
    new WsEchoOrigin(server)

  /** The `Sec-WebSocket-Accept` value for a handshake `key`. */
  def acceptFor(key: String): String =
    Base64.getEncoder.encodeToString(
      MessageDigest.getInstance("SHA-1").digest((key + Guid).getBytes(ISO_8859_1))
    )

  /** A masked (client-to-server) text frame. */
  def clientTextFrame(text: String): Array[Byte] =
    val payload = text.getBytes(UTF_8)
    require(payload.length < 126, "only short frames are needed here")
    val mask = Array[Byte](1, 2, 3, 4)
    Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++
      payload.zipWithIndex.map((b, i) => (b ^ mask(i % 4)).toByte)

  /** Read one frame: `(opcode, payload)`, unmasking a masked one. */
  def readFrame(in: InputStream): (Int, Array[Byte]) =
    val data = new DataInputStream(in)
    val b0 = data.readUnsignedByte()
    val b1 = data.readUnsignedByte()
    val len = (b1 & 0x7f) match
      case 126 => data.readUnsignedShort().toLong
      case 127 => data.readLong()
      case n => n.toLong
    val mask = if (b1 & 0x80) != 0 then Some(data.readNBytes(4)) else None
    val payload = data.readNBytes(len.toInt)
    if payload.length != len then throw new EOFException("truncated WebSocket frame")
    (b0 & 0x0f, mask.fold(payload)(m => payload.zipWithIndex.map((b, i) => (b ^ m(i % 4)).toByte)))

  private def serve(socket: Socket): Unit =
    val in = socket.getInputStream
    val out = socket.getOutputStream
    val head = readHead(in)
    val key = head
      .split("\r\n")
      .collectFirst {
        case l if l.toLowerCase.startsWith("sec-websocket-key:") => l.drop(18).trim
      }
      .getOrElse(throw new IllegalStateException(s"no Sec-WebSocket-Key in: $head"))
    out.write(
      ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        s"Sec-WebSocket-Accept: ${acceptFor(key)}\r\n\r\n").getBytes(ISO_8859_1)
    )
    out.flush()
    echo(in, out)

  private def echo(in: InputStream, out: OutputStream): Unit =
    val (opcode, payload) = readFrame(in)
    if opcode == 1 then
      out.write(Array[Byte](0x81.toByte, payload.length.toByte) ++ payload)
      out.flush()
      echo(in, out)

  private def readHead(in: InputStream): String =
    val sb = new StringBuilder
    while !sb.endsWith("\r\n\r\n") do
      val c = in.read()
      if c < 0 then throw new EOFException("closed before the handshake")
      sb.append(c.toChar)
    sb.toString
