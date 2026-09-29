package samples.rift.support

import java.io.{
  BufferedInputStream,
  ByteArrayInputStream,
  ByteArrayOutputStream,
  EOFException,
  IOException,
  InputStream
}
import java.net.{InetSocketAddress, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.concurrent.atomic.AtomicReference
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{SSLContext, SSLSocket, TrustManagerFactory}

/** One HTTP/1.1 response exactly as framed on the wire. Header names are lower-cased and kept in
  * wire order, so a repeated header stays repeated.
  */
final case class RawResponse(status: Int, headers: List[(String, String)], body: Array[Byte]):
  def bodyText: String = new String(body, UTF_8)
  def toLastResponse: LastResponse = LastResponse(status, bodyText, headers.groupMap(_._1)(_._2))

/** What the next read on a connection produced: a complete response, or the peer closing (EOF or
  * reset) before the first byte of one. A close part-way through a response is an error, not this.
  */
enum RawRead:
  case Response(response: RawResponse)
  case Closed

/** A raw HTTP/1.1 client over one socket, for the intercept-tunnel contracts that
  * `java.net.http.HttpClient` hides: request framing (exact `content-length`, `transfer-encoding:
  * chunked`), connection reuse, and what the server does to the socket after a refusal.
  *
  * [[RawHttpConnection.tunnel]] opens the path a real SUT takes through the intercept listener —
  * `CONNECT host:443`, then TLS over the tunnel, trusting the intercept CA.
  * [[RawHttpConnection.plain]] talks to a listener before any TLS (the pre-tunnel refusals).
  *
  * The client never reconnects: every exchange on an instance goes over the socket it was opened
  * with, so a second answered exchange proves the server kept that connection alive.
  */
final class RawHttpConnection private (socket: Socket):
  private val in = new BufferedInputStream(socket.getInputStream)
  private val out = socket.getOutputStream
  @volatile private var answered = 0

  /** Responses read so far on this one connection. */
  def responsesRead: Int = answered

  /** Write `request` and read the response to it.
    *
    * The write runs on its own thread while this one reads: a server that refuses early (a `413`
    * over the body cap) answers while the body is still streaming, and may close without draining
    * it. Writing first and reading after would lose that response to the reset (#131). The call
    * returns only once the write has finished or failed, so the connection is idle for the next
    * exchange and no bytes of this request can interleave with the next one.
    */
  def exchange(request: Array[Byte]): RawRead =
    val writeError = new AtomicReference[Option[Throwable]](None)
    val writer = new Thread(() =>
      try
        out.write(request)
        out.flush()
      catch case e: IOException => writeError.set(Some(e))
    )
    writer.setDaemon(true)
    writer.start()
    val read =
      try readNext()
      catch
        case e: SocketTimeoutException =>
          writeError.get.foreach(e.addSuppressed)
          throw e
    writer.join(RawHttpConnection.TimeoutMillis.toLong)
    if writer.isAlive then
      close()
      throw new IllegalStateException(
        s"the peer answered ($read) but stopped reading the rest of the request without closing"
      )
    read

  /** Read the next response without writing anything — e.g. to observe that the server closed. */
  def readNext(): RawRead =
    RawHttpConnection.readHead(in) match
      case None => RawRead.Closed
      case Some((status, _)) if status / 100 == 1 => readNext() // skip interim responses
      case Some((status, headers)) =>
        val body = RawHttpConnection.readBody(in, status, headers)
        answered += 1
        RawRead.Response(RawResponse(status, headers, body))

  def close(): Unit = socket.close()

object RawHttpConnection:
  /** Connect, read and write-drain bound: generous for CI, far below the scenario timeout. */
  val TimeoutMillis: Int = 10000

  /** A TLS context trusting only the CAs in a PKCS#12 truststore — what a real SUT does with the
    * intercept listener's exported `truststore.p12` to trust the MITM leaf certificates.
    */
  def sslTrusting(truststore: Array[Byte], password: String): SSLContext =
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(new ByteArrayInputStream(truststore), password.toCharArray)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, new SecureRandom())
    ctx

  /** A TLS context trusting only the one CA certificate in `pem` — for an intercept CA that is
    * handed back as PEM (`returnCaKey`, `GET /intercept/ca.pem`) rather than as a truststore.
    */
  def sslTrustingPem(pem: String): SSLContext =
    val cert = java.security.cert.CertificateFactory
      .getInstance("X.509")
      .generateCertificate(new ByteArrayInputStream(pem.getBytes(UTF_8)))
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    ks.load(null, null)
    ks.setCertificateEntry("intercept-ca", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, new SecureRandom())
    ctx

  /** `CONNECT target:443`, with any extra header lines (e.g. `Proxy-Authorization`). */
  def connectRequest(target: String, headers: List[(String, String)] = Nil): Array[Byte] =
    val lines = s"CONNECT $target:443 HTTP/1.1" :: s"Host: $target:443" ::
      headers.map((k, v) => s"$k: $v")
    (lines.mkString("\r\n") + "\r\n\r\n").getBytes(ISO_8859_1)

  /** A plaintext connection to `host:port` — the intercept listener before any tunnel exists. */
  def plain(host: String, port: Int): RawHttpConnection =
    new RawHttpConnection(connect(host, port))

  /** `CONNECT target:443` through the proxy at `proxyHost:proxyPort`, then a TLS handshake over the
    * tunnel for `target` (SNI + hostname verification) with `ssl`, offering only `http/1.1` in
    * ALPN. `connectHeaders` ride on the `CONNECT` itself. Fails unless the proxy answers the
    * `CONNECT` with `200`.
    */
  def tunnel(
      proxyHost: String,
      proxyPort: Int,
      target: String,
      ssl: SSLContext,
      connectHeaders: List[(String, String)] = Nil
  ): RawHttpConnection =
    val raw = connect(proxyHost, proxyPort)
    try
      raw.getOutputStream.write(connectRequest(target, connectHeaders))
      raw.getOutputStream.flush()
      // Read the CONNECT reply unbuffered: nothing after its blank line may be consumed here, as
      // those bytes belong to the TLS handshake.
      readHead(raw.getInputStream) match
        case Some((200, _)) => ()
        case other => throw new IllegalStateException(s"CONNECT $target:443 -> $other, not 200")
      val tls = ssl.getSocketFactory.createSocket(raw, target, 443, true).asInstanceOf[SSLSocket]
      val params = tls.getSSLParameters
      params.setEndpointIdentificationAlgorithm("HTTPS")
      params.setApplicationProtocols(Array("http/1.1"))
      tls.setSSLParameters(params)
      tls.startHandshake()
      new RawHttpConnection(tls)
    catch
      case e: Throwable =>
        raw.close()
        throw e

  /** An HTTP/1.1 request framed by `content-length` (omitted for a bodiless GET/HEAD/DELETE). */
  def request(
      method: String,
      host: String,
      path: String,
      headers: List[(String, String)] = Nil,
      body: Array[Byte] = Array.emptyByteArray
  ): Array[Byte] =
    val framing =
      if body.isEmpty && Set("GET", "HEAD", "DELETE").contains(method) then Nil
      else List("Content-Length" -> body.length.toString)
    head(method, host, path, headers ++ framing) ++ body

  /** An HTTP/1.1 request whose body is sent `transfer-encoding: chunked`, one chunk per element. */
  def chunkedRequest(
      method: String,
      host: String,
      path: String,
      chunks: List[String]
  ): Array[Byte] =
    val body = chunks
      .filter(_.nonEmpty) // a zero-length chunk would end the body early
      .map { c =>
        val bytes = c.getBytes(UTF_8)
        s"${bytes.length.toHexString}\r\n".getBytes(ISO_8859_1) ++ bytes ++ "\r\n".getBytes(
          ISO_8859_1
        )
      }
      .foldLeft(Array.emptyByteArray)(_ ++ _)
    head(method, host, path, List("Transfer-Encoding" -> "chunked")) ++ body ++
      "0\r\n\r\n".getBytes(ISO_8859_1)

  private def head(
      method: String,
      host: String,
      path: String,
      headers: List[(String, String)]
  ): Array[Byte] =
    val lines = s"$method $path HTTP/1.1" :: s"Host: $host" :: headers.map((k, v) => s"$k: $v")
    (lines.mkString("\r\n") + "\r\n\r\n").getBytes(ISO_8859_1)

  private def connect(host: String, port: Int): Socket =
    val s = new Socket()
    try
      s.connect(new InetSocketAddress(host, port), TimeoutMillis)
      s.setSoTimeout(TimeoutMillis)
      s
    catch
      case e: Throwable =>
        s.close()
        throw e

  /** Status line + headers, or `None` if the peer closed (EOF or reset) before sending a byte. A
    * read timeout is never a close: it propagates, so a hung server fails loudly instead of looking
    * like one that closed.
    */
  private def readHead(in: InputStream): Option[(Int, List[(String, String)])] =
    val first =
      try in.read()
      catch
        case e: SocketTimeoutException => throw e
        case _: IOException => -1 // reset before any response byte: the peer closed
    if first < 0 then None
    else
      val statusLine = first.toChar.toString + readLine(in)
      val status = statusLine.split(' ') match
        case Array(v, code, _*) if v.startsWith("HTTP/1.") && code.forall(_.isDigit) => code.toInt
        case _ => throw new IOException(s"not an HTTP/1.x status line: '$statusLine'")
      val headers = Iterator
        .continually(readLine(in))
        .takeWhile(_.nonEmpty)
        .map { line =>
          line.split(":", 2) match
            case Array(k, v) => k.trim.toLowerCase -> v.trim
            case _ => throw new IOException(s"malformed header line: '$line'")
        }
        .toList
      Some(status -> headers)

  private def readBody(in: InputStream, status: Int, headers: List[(String, String)]): Array[Byte] =
    def header(name: String) = headers.collectFirst { case (`name`, v) => v }
    if status == 204 || status == 304 then Array.emptyByteArray
    else if header("transfer-encoding").exists(_.toLowerCase.contains("chunked")) then
      val body = new ByteArrayOutputStream()
      Iterator
        .continually(Integer.parseInt(readLine(in).takeWhile(_ != ';').trim, 16))
        .takeWhile(_ > 0)
        .foreach { size =>
          body.write(readExactly(in, size))
          readLine(in) // the CRLF closing the chunk
        }
      Iterator.continually(readLine(in)).takeWhile(_.nonEmpty).foreach(_ => ()) // trailers
      body.toByteArray
    else
      header("content-length") match
        case Some(n) => readExactly(in, n.toInt)
        case None => in.readAllBytes() // close-delimited
  end readBody

  private def readExactly(in: InputStream, n: Int): Array[Byte] =
    val bytes = in.readNBytes(n)
    if bytes.length < n then
      throw new EOFException(s"connection closed after ${bytes.length}/$n body bytes")
    bytes

  private def readLine(in: InputStream): String =
    val line = new ByteArrayOutputStream()
    var b = in.read()
    while b >= 0 && b != '\n' do
      if b != '\r' then line.write(b)
      b = in.read()
    if b < 0 then
      throw new EOFException(s"connection closed mid-line: '${line.toString(ISO_8859_1)}'")
    line.toString(ISO_8859_1)
