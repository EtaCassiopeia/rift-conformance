package samples.rift

import samples.rift.support.EmbeddedAdmin.{Admin, StubRef}
import samples.rift.support.FfiParity.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** FFI ↔ admin-HTTP parity for `rift_stub_warnings` (rift#423, #92).
  *
  * The engine analyses an imposter's stubs for overlap once per stub mutation and caches the
  * result. The C-ABI returns it as a bare JSON array; the admin HTTP API exposes the same analysis
  * as `_rift.warnings` on `GET /imposters/{port}`, so the two are compared on one in-process engine
  * (see [[samples.rift.support.FfiParity]]). Documented shape difference: the HTTP detail omits
  * `_rift.warnings` entirely when there is nothing to warn about, where the FFI answers `[]`.
  *
  * Every case also pins the literal warning types, so parity between two equally wrong analyses
  * cannot pass. Self-gating: a no-op pass outside `RIFT_MODE=embedded` on JDK 22+.
  */
object FfiStubWarnings92Spec extends ZIOSpecDefault:

  private val catchAll: Json = Json.Obj(
    "id" -> Json.Str("all"),
    "responses" -> Json.Arr(Json.Obj("is" -> Json.Obj("statusCode" -> Json.Num(200))))
  )

  /** A catch-all ahead of a specific stub, and two stubs with the same predicate set. */
  private val Overlapping = Chunk(catchAll, stub("a", "/a", "A"), stub("a-dup", "/a", "A2"))

  private val Clean = Chunk(stub("a", "/a", "A"), stub("b", "/b", "B"))

  /** The HTTP admin plane's view of the same analysis: `_rift.warnings`, absent → `None`. */
  private def httpWarnings(admin: Admin, port: Int): Task[Option[Json]] =
    httpImposter(admin, port).map { imp =>
      field(field(imp, "_rift"), "warnings") match
        case Json.Null => None
        case w => Some(w)
    }

  private def types(warnings: Json): Set[String] =
    arr(warnings).flatMap(w => str(field(w, "warningType"))).toSet

  def spec = suite("FFI ↔ admin HTTP parity: rift_stub_warnings (embedded, #92)")(
    test("overlapping stubs: the FFI warnings equal the HTTP _rift.warnings") {
      withEngine { admin =>
        for
          port <- create(admin, Overlapping)
          ffi <- admin.handle.stubWarnings(port)
          http <- httpWarnings(admin, port)
        yield assertTrue(
          http.map(norm).contains(norm(ffi)),
          // The catch-all is flagged twice (it matches everything, and is not last) and shadows
          // the stubs behind it; the second `/a` stub duplicates the first's predicates.
          types(ffi) ==
            Set("catch_all", "catch_all_not_last", "potentially_shadowed", "exact_duplicate"),
          arr(ffi)
            .filter(w => str(field(w, "warningType")).contains("catch_all"))
            .map(w => str(field(w, "stubId"))) == Chunk(Some("all")),
          arr(ffi).forall(w => str(field(w, "message")).exists(_.nonEmpty))
        )
      }
    },
    test("a clean imposter: the FFI answers [] and HTTP omits _rift.warnings") {
      withEngine { admin =>
        for
          port <- create(admin, Clean)
          ffi <- admin.handle.stubWarnings(port)
          http <- httpWarnings(admin, port)
        yield assertTrue(ffi == Json.Arr(), http.isEmpty)
      }
    },
    test("the analysis is recomputed on stub mutation: add a duplicate, then delete it") {
      withEngine { admin =>
        val ffi = admin.handle
        for
          port <- create(admin, Clean)
          _ <- ffi.addStub(port, stub("a-dup", "/a", "A2"))
          afterAdd <- ffi.stubWarnings(port)
          httpAfterAdd <- httpWarnings(admin, port)
          _ <- ffi.deleteStub(port, StubRef.ById("a-dup"))
          afterDelete <- ffi.stubWarnings(port)
          httpAfterDelete <- httpWarnings(admin, port)
        yield assertTrue(
          types(afterAdd) == Set("exact_duplicate"),
          httpAfterAdd.map(norm).contains(norm(afterAdd)),
          afterDelete == Json.Arr(),
          httpAfterDelete.isEmpty
        )
      }
    },
    test("an unknown port is an FFI error carrying the engine's message") {
      withEngine { admin =>
        for
          unknown <- freePort
          failure <- admin.handle
            .stubWarnings(unknown)
            .either
            .map(_.left.toOption.map(_.getMessage))
        yield assertTrue(failure.exists(_.startsWith("rift engine call failed: ")))
      }
    }
  ).provideShared(engine) @@ TestAspect.sequential
