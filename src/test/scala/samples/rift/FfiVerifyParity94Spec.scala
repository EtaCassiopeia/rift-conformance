package samples.rift

import samples.rift.support.EmbeddedAdmin.Admin
import samples.rift.support.FfiParity.*
import samples.rift.support.TestPorts
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** FFI ↔ admin-HTTP parity for `rift_verify` (rift#494/#512, #94).
  *
  * `rift_verify(port, body)` takes the same body as `POST /imposters/{port}/verify` and answers the
  * same `{matched,total,requests?,closest?}` envelope, so each case sends one body through both
  * doors on one in-process engine (see [[samples.rift.support.FfiParity]]) and compares the parsed
  * results, key-order-insensitively. Every case also pins the literal counts, so parity between two
  * equally wrong answers cannot pass.
  *
  * The journal is partitioned by the `X-Space` header (`flowState.flowIdSource`): `blue` sends
  * `/hi` twice and `/other` once, `green` sends `/hi` once. Self-gating: a no-op pass outside
  * `RIFT_MODE=embedded` on JDK 22+.
  */
object FfiVerifyParity94Spec extends ZIOSpecDefault:

  private val FlowByHeader: (String, Json) =
    "_rift" -> Json.Obj(
      "flowState" -> Json.Obj(
        "backend" -> Json.Str("inmemory"),
        "flowIdSource" -> Json.Str("header:X-Space")
      )
    )

  /** An imposter answering `/hi`, with the four requests above recorded; yields its port. */
  private def recorded(admin: Admin): Task[Int] =
    for
      port <- create(admin, Chunk(stub("hi", "/hi", "hi")), FlowByHeader)
      _ <- ZIO.foreachDiscard(
        List("blue" -> "/hi", "blue" -> "/hi", "blue" -> "/other", "green" -> "/hi")
      ) { (space, path) =>
        http("GET", s"http://127.0.0.1:$port$path", headers = Map("X-Space" -> space))
      }
    yield port

  private def verifyBody(path: String, extra: (String, Json)*): Json =
    Json.Obj(
      Chunk(
        "predicates" -> Json.Arr(Json.Obj("equals" -> Json.Obj("path" -> Json.Str(path))))
      ) ++ extra
    )

  /** The same body through both doors: `(ffi, http)`, both normalised. */
  private def both(admin: Admin, port: Int, body: Json): Task[(Json, Json)] =
    for
      ffi <- admin.handle.verify(port, body)
      http <- httpJson("POST", s"${admin.adminUrl}/imposters/$port/verify", Some(body.toJson))
    yield (norm(ffi), norm(http))

  private def counts(result: Json): (Json, Json) =
    (field(result, "matched"), field(result, "total"))

  def spec = suite("FFI ↔ admin HTTP parity: rift_verify (embedded, #94)")(
    test("a match count across every flow") {
      withEngine { admin =>
        for
          port <- recorded(admin)
          res <- both(admin, port, verifyBody("/hi"))
          (ffi, http) = res
        yield assertTrue(ffi == http, counts(ffi) == (Json.Num(3), Json.Num(4)))
      }
    },
    test("flowId scopes matched and total to one flow; an unseen flow is 0 of 0") {
      withEngine { admin =>
        def inFlow(f: String) = verifyBody("/hi", "flowId" -> Json.Str(f))
        for
          port <- recorded(admin)
          blue <- both(admin, port, inFlow("blue"))
          green <- both(admin, port, inFlow("green"))
          absent <- both(admin, port, inFlow("absent"))
        yield assertTrue(
          blue._1 == blue._2,
          counts(blue._1) == (Json.Num(2), Json.Num(3)),
          green._1 == green._2,
          counts(green._1) == (Json.Num(1), Json.Num(1)),
          absent._1 == absent._2,
          counts(absent._1) == (Json.Num(0), Json.Num(0))
        )
      }
    },
    test("includeRequests within a flow returns the same matching requests") {
      withEngine { admin =>
        for
          port <- recorded(admin)
          res <- both(
            admin,
            port,
            verifyBody("/hi", "flowId" -> Json.Str("green"), "includeRequests" -> Json.Bool(true))
          )
          (ffi, http) = res
          requests = arr(field(ffi, "requests"))
        yield assertTrue(
          ffi == http,
          requests.size == 1,
          requests.map(r => str(field(r, "path"))) == Chunk(Some("/hi"))
        )
      }
    },
    test("includeClosest reports the same best non-match with a failedPredicates diff") {
      withEngine { admin =>
        for
          port <- recorded(admin)
          res <- both(admin, port, verifyBody("/absent", "includeClosest" -> Json.Bool(true)))
          (ffi, http) = res
          closest = field(ffi, "closest")
        yield assertTrue(
          ffi == http,
          counts(ffi) == (Json.Num(0), Json.Num(4)),
          str(field(field(closest, "request"), "path")).nonEmpty,
          arr(field(closest, "failedPredicates")).nonEmpty
        )
      }
    },
    test("an unknown port is an FFI error carrying the engine's message") {
      withEngine { admin =>
        for
          unknown <- TestPorts.next
          failure <- admin.handle
            .verify(unknown, verifyBody("/hi"))
            .either
            .map(_.left.toOption.map(_.getMessage))
        yield assertTrue(failure.exists(_.startsWith("rift engine call failed: ")))
      }
    }
  ).provideShared(engine) @@ TestAspect.sequential
