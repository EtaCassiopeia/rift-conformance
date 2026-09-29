package samples.rift

import samples.rift.support.EmbeddedAdmin
import samples.rift.support.EmbeddedAdmin.Handle
import samples.rift.support.FfiParity.*
import samples.rift.support.{RawHttpConnection, RawRead, RiftPin, TestPorts}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.{SSLContext, TrustManagerFactory}

/** The C-ABI long tail (rift docs/embedding/ffi.md, #205): the per-space plane, flow state,
  * `rift_apply_config`, build identity (`rift_build_info`, `rift_abi_version`, `rift_last_error`),
  * the `rift_serve_admin` options, and the intercept control plane over FFI.
  *
  * Same discipline as [[FfiAdminParity75Spec]]: where an admin route answers the same question, the
  * FFI result is compared with it on one engine (JSON parsed, keys order-insensitive, `_links`
  * stripped), and every case also pins a literal expectation, so two equally wrong answers cannot
  * agree their way to a pass. Cases that need a handle in a particular state (`rift_serve_admin`
  * options, intercept, `rift_apply_config`) open their own with `EmbeddedAdmin.bare`.
  *
  * Self-gating: a no-op pass unless `RIFT_MODE=embedded` AND a `librift_ffi` resolves for this JVM
  * (JDK 22+).
  */
object FfiLongTail205Spec extends ZIOSpecDefault:

  private val SpaceHeader = "X-Space"

  private val FlowByHeader: (String, Json) =
    "_rift" -> Json.Obj(
      "flowState" -> Json.Obj(
        "backend" -> Json.Str("inmemory"),
        "flowIdSource" -> Json.Str(s"header:$SpaceHeader")
      )
    )

  private def obj(fields: (String, Json)*): Json = Json.Obj(Chunk.fromIterable(fields))

  /** An imposter document with one catch-all stub answering `body`. */
  private def answering(port: Int, body: String): Json =
    obj(
      "protocol" -> Json.Str("http"),
      "port" -> Json.Num(port),
      "stubs" -> Json.Arr(
        obj("responses" -> Json.Arr(obj("is" -> obj("body" -> Json.Str(body)))))
      )
    )

  /** The two documents `rift_apply_config` / `PUT /imposters` reconcile through: `{A, B}`, then
    * `{B', C}` — one created, one deleted, one changed.
    */
  private def firstSet(a: Int, b: Int): Json =
    obj("imposters" -> Json.Arr(answering(a, "A"), answering(b, "B")))
  private def secondSet(b: Int, c: Int): Json =
    obj("imposters" -> Json.Arr(answering(b, "B2"), answering(c, "C")))

  private def len(j: Json): Int = arr(j).size

  private def bodyAt(port: Int, path: String = "/"): Task[String] =
    http("GET", s"http://127.0.0.1:$port$path").map(_.body)

  /** Run `f` on a fresh handle, gated like [[withEngine]]. */
  private def withBare(f: Handle => ZIO[Scope, Throwable, TestResult]): Task[TestResult] =
    if !sys.env.getOrElse("RIFT_MODE", "external").trim.equalsIgnoreCase("embedded") ||
      !EmbeddedAdmin.available
    then ZIO.succeed(assertCompletes)
    else ZIO.scoped(EmbeddedAdmin.bare.flatMap(f))

  /** The failure message of `effect`, or `None` if it succeeded. */
  private def failure[A](effect: Task[A]): UIO[Option[String]] =
    effect.either.map(_.left.toOption.map(_.getMessage))

  private def resource(path: String): Task[String] =
    ZIO.attemptBlocking {
      val is = getClass.getResourceAsStream(path)
      if is == null then throw new RuntimeException(s"missing test resource $path")
      try new String(is.readAllBytes(), StandardCharsets.UTF_8)
      finally is.close()
    }

  private def tempFile(content: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val p = Files.createTempFile("rift-ffi-long-tail-", ".pem")
        Files.write(p, content.getBytes(StandardCharsets.UTF_8))
        p
      }
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).orDie)

  /** A TLS context trusting only the CA certificate in `pem`. */
  private def trusting(pem: String): SSLContext =
    val cert = CertificateFactory
      .getInstance("X.509")
      .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    ks.load(null, null)
    ks.setCertificateEntry("intercept-ca", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)
    ctx

  def spec = suite("C-ABI long tail (embedded, #205)")(
    suite("per-space plane")(
      test(
        "rift_space_* add, list, serve, record and delete a space's stubs like the HTTP routes"
      ) {
        withEngine { admin =>
          val ffi = admin.handle
          val spaces = s"${admin.adminUrl}/imposters"
          for
            // Stubs match first-to-last and a space stub is appended, so the global stub is on
            // another path: a request outside the space must not reach the space stub.
            port <- create(admin, Chunk(stub("global", "/g", "global")), FlowByHeader)
            _ <- ffi.spaceAddStub(port, "s1", stub("sp", "/x", "S1"))
            ffiList <- ffi.spaceListStubs(port, "s1")
            httpList <- httpJson("GET", s"$spaces/$port/spaces/s1/stubs")
            inSpace <- http("GET", s"http://127.0.0.1:$port/x", headers = Map(SpaceHeader -> "s1"))
            outside <- bodyAt(port, "/x")
            global <- http("GET", s"http://127.0.0.1:$port/g", headers = Map(SpaceHeader -> "s1"))
            ffiRecorded <- ffi.spaceRecorded(port, "s1")
            httpRecorded <- httpJson(
              "GET",
              s"$spaces/$port/savedRequests?match=header:$SpaceHeader=s1"
            )
            _ <- ffi.spaceDelete(port, "s1")
            afterDelete <- httpJson("GET", s"$spaces/$port/spaces/s1/stubs")
          yield assertTrue(
            norm(ffiList) == norm(httpList),
            str(field(ffiList, "space")).contains("s1"),
            len(field(ffiList, "stubs")) == 1,
            inSpace.body == "S1",
            outside != "S1",
            global.body == "global",
            norm(ffiRecorded) == norm(httpRecorded),
            len(ffiRecorded) == 2,
            len(field(afterDelete, "stubs")) == 0
          )
        }
      },
      test("rift_space_add_stub refuses a body that is not a stub, naming why in rift_last_error") {
        withEngine { admin =>
          for
            port <- create(admin, Chunk(stub("global", "/x", "global")), FlowByHeader)
            err <- failure(admin.handle.spaceAddStubRaw(port, "s1", """{"responses":"nope"}"""))
          yield assertTrue(
            err.exists(_.startsWith("rift engine call failed: ")),
            err.exists(_.contains("invalid stub JSON"))
          )
        }
      }
    ),
    suite("flow state")(
      test("rift_flow_state_put/get/delete agree with the flow-state admin route") {
        withEngine { admin =>
          val ffi = admin.handle
          for
            port <- create(admin, Chunk.empty, FlowByHeader)
            url = s"${admin.adminUrl}/admin/imposters/$port/flow-state/f/k"
            _ <- ffi.flowStatePut(port, "f", "k", Json.Num(42))
            got <- ffi.flowStateGet(port, "f", "k")
            viaHttp <- httpJson("GET", url)
            _ <- ffi.flowStateDelete(port, "f", "k")
            gone <- ffi.flowStateGet(port, "f", "k")
            httpGone <- http("GET", url).map(_.status)
          yield assertTrue(
            norm(got) == norm(
              obj(
                "found" -> Json.Bool(true),
                "flowId" -> Json.Str("f"),
                "key" -> Json.Str("k"),
                "value" -> Json.Num(42)
              )
            ),
            field(viaHttp, "value") == Json.Num(42),
            field(gone, "found") == Json.Bool(false),
            field(gone, "value") == Json.Null,
            httpGone == 404
          )
        }
      }
    ),
    suite("rift_apply_config")(
      test("reconciles the whole set like PUT /imposters on a second handle") {
        withBare { viaFfi =>
          for
            viaHttp <- EmbeddedAdmin.bare
            served <- viaHttp.serveAdmin(
              obj("host" -> Json.Str("127.0.0.1"), "port" -> Json.Num(0))
            )
            adminUrl <- ZIO
              .fromOption(str(field(served, "adminUrl")))
              .orElseFail(
                new RuntimeException(s"no adminUrl: $served")
              )
            ports <- ZIO.foreach(List.fill(6)(()))(_ => TestPorts.next)
            (a1, b1, c1, a2, b2, c2) = (ports(0), ports(1), ports(2), ports(3), ports(4), ports(5))
            _ <- viaFfi.applyConfig(firstSet(a1, b1))
            report <- viaFfi.applyConfig(secondSet(b1, c1))
            _ <- httpJson("PUT", s"$adminUrl/imposters", Some(firstSet(a2, b2).toJson))
            _ <- httpJson("PUT", s"$adminUrl/imposters", Some(secondSet(b2, c2).toJson))
            ffiBodies <- ZIO.foreach(List(b1, c1))(bodyAt(_))
            httpBodies <- ZIO.foreach(List(b2, c2))(bodyAt(_))
            ffiGone <- http("GET", s"http://127.0.0.1:$a1/").either.map(_.isLeft)
            httpGone <- http("GET", s"http://127.0.0.1:$a2/").either.map(_.isLeft)
            changed = len(field(report, "replaced")) + len(field(report, "stubPatched"))
          yield assertTrue(
            len(field(report, "created")) == 1,
            len(field(report, "deleted")) == 1,
            changed == 1,
            ffiBodies == List("B2", "C"),
            ffiBodies == httpBodies,
            ffiGone,
            httpGone
          )
        }
      }
    ),
    suite("build identity")(
      test("rift_abi_version is 2 and rift_build_info matches the pin and GET /config") {
        withEngine { admin =>
          val ffi = admin.handle
          for
            abi <- ffi.abiVersion
            info <- ffi.buildInfo
            config <- httpJson("GET", s"${admin.adminUrl}/config")
            options = arr(field(info, "serveOptions")).flatMap(str).toSet
          yield assertTrue(
            abi == 2,
            str(field(info, "version")).contains(RiftPin.version),
            Set("apiKey", "requireAdminAuth", "noParse", "upstreamTlsSkipVerify").subsetOf(options),
            field(info, "serveOptions") == field(config, "serveOptions")
          )
        }
      },
      test("rift_last_error is set by a failed call and cleared by the next call") {
        withEngine { admin =>
          for
            afterFailure <- admin.handle.lastErrorAfterFailure(thenSucceed = false)
            afterSuccess <- admin.handle.lastErrorAfterFailure(thenSucceed = true)
          yield assertTrue(afterFailure.exists(_.nonEmpty), afterSuccess.isEmpty)
        }
      }
    ),
    suite("rift_serve_admin options")(
      test("apiKey gates the admin plane") {
        withBare { h =>
          for
            served <- h.serveAdmin(obj("port" -> Json.Num(0), "apiKey" -> Json.Str("k")))
            url = str(field(served, "adminUrl")).getOrElse("")
            without <- http("GET", s"$url/imposters").map(_.status)
            withKey <- http("GET", s"$url/imposters", headers = Map("Authorization" -> "k"))
              .map(_.status)
          yield assertTrue(without == 401, withKey == 200)
        }
      },
      test("an unknown option is refused, naming the key") {
        withBare(h =>
          failure(h.serveAdmin(obj("bogus" -> Json.Num(1)))).map(e =>
            assertTrue(e.exists(_.contains("bogus")))
          )
        )
      },
      test("noParse without configFile is refused, and accepted with one") {
        withBare { h =>
          for
            refused <- failure(h.serveAdmin(obj("noParse" -> Json.Bool(true))))
            other <- EmbeddedAdmin.bare
            file <- tempFile("""{"imposters":[]}""")
            accepted <- failure(
              other.serveAdmin(
                obj(
                  "port" -> Json.Num(0),
                  "noParse" -> Json.Bool(true),
                  "configFile" -> Json.Str(file.toString)
                )
              )
            )
          yield assertTrue(
            refused.exists(_.contains("noParse")),
            refused.exists(_.contains("configFile")),
            accepted.isEmpty
          )
        }
      },
      test("upstreamCaFile and upstreamCaPem together are refused") {
        withBare { h =>
          for
            pem <- resource("/rift-fixtures/intercept/ca-cert.pem")
            file <- tempFile(pem)
            e <- failure(
              h.serveAdmin(
                obj(
                  "upstreamCaFile" -> Json.Str(file.toString),
                  "upstreamCaPem" -> Json.Str(pem)
                )
              )
            )
          yield assertTrue(
            e.exists(_.contains("upstreamCaFile")),
            e.exists(_.contains("upstreamCaPem"))
          )
        }
      },
      test("an inline config is served") {
        withBare { h =>
          for
            port <- TestPorts.next
            config = obj("imposters" -> Json.Arr(answering(port, "inline")))
            _ <- h.serveAdmin(obj("port" -> Json.Num(0), "config" -> config))
            body <- bodyAt(port)
          yield assertTrue(body == "inline")
        }
      },
      test("requireAdminAuth refuses an off-host bind with no apiKey, and serves one with a key") {
        val offHost = Chunk(
          "host" -> Json.Str("0.0.0.0"),
          "port" -> Json.Num(0),
          "requireAdminAuth" -> Json.Bool(true)
        )
        withBare { h =>
          for
            refused <- failure(h.serveAdmin(Json.Obj(offHost)))
            other <- EmbeddedAdmin.bare
            accepted <- failure(
              other.serveAdmin(Json.Obj(offHost :+ ("apiKey" -> Json.Str("k"))))
            )
          yield assertTrue(
            refused.exists(_.startsWith("rift engine call failed: ")),
            refused.exists(_.contains("with no API key")),
            accepted.isEmpty
          )
        }
      }
    ),
    suite("intercept over FFI")(
      test("start with rules and returnCaKey, a double start fails, stop is idempotent") {
        withBare { h =>
          val rule = obj(
            "host" -> Json.Str("ffi.example.com"),
            "predicates" -> Json.Arr(obj("equals" -> obj("path" -> Json.Str("/r")))),
            "action" -> obj(
              "serve" -> obj("statusCode" -> Json.Num(200), "body" -> Json.Str("ffi"))
            )
          )
          for
            served <- h.serveAdmin(obj("port" -> Json.Num(0)))
            adminUrl = str(field(served, "adminUrl")).getOrElse("")
            started <- h.startInterceptWith(
              obj(
                "host" -> Json.Str("127.0.0.1"),
                "port" -> Json.Num(0),
                "rules" -> Json.Arr(rule),
                "returnCaKey" -> Json.Bool(true)
              )
            )
            port = field(started, "interceptPort") match
              case Json.Num(n) => n.intValue
              case _ => 0
            caPem = str(field(started, "caCertPem")).getOrElse("")
            rules <- h.interceptListRules
            servedBody <- ZIO.attemptBlocking {
              val conn =
                RawHttpConnection.tunnel("127.0.0.1", port, "ffi.example.com", trusting(caPem))
              try
                conn.exchange(RawHttpConnection.request("GET", "ffi.example.com", "/r")) match
                  case RawRead.Response(r) => Some((r.status, r.bodyText))
                  case RawRead.Closed => None
              finally conn.close()
            }
            again <- failure(h.startInterceptWith(obj("port" -> Json.Num(0))))
            status <- httpJson("GET", s"$adminUrl/intercept")
            _ <- h.stopIntercept
            afterStop <- http("GET", s"$adminUrl/intercept").map(_.status)
            stoppedTwice <- failure(h.stopIntercept)
          yield assertTrue(
            port > 0,
            caPem.contains("BEGIN CERTIFICATE"),
            str(field(started, "caKeyPem")).exists(_.contains("PRIVATE KEY")),
            len(rules) == 1,
            servedBody.contains((200, "ffi")),
            again.exists(_.startsWith("rift engine call failed: ")),
            field(status, "interceptPort") == Json.Num(port),
            afterStop == 404,
            stoppedTwice.isEmpty
          )
        }
      }
    )
  ).provideShared(engine) @@ TestAspect.sequential
