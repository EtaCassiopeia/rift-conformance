package samples.mock.support

import zio.*
import zio.bdd.mock.*
import zio.bdd.mock.wiremock.WireMock
import zio.bdd.mock.rift.Rift
import zio.bdd.mock.rift.embedded.EmbeddedRift
import zio.http.Client

/** Runtime backend selection for the mock corpus.
  *
  * A suite's `environment` is fixed at compile time, so we pick the backend from the `MOCK_BACKEND`
  * env var inside this shared layer — the one seam every mock suite wires to (`override def
  * environment = Backends.mockControl`). Capability differences between backends are handled by the
  * harness excluding tags per backend (e.g. WireMock runs skip `@scripting`), never by branching
  * here.
  *
  *   - `wiremock` (default) — in-process, zero Docker, Correlated isolation.
  *   - `wiremock-pi` — WireMock in PerInstance isolation.
  *   - `rift` — Rift container via testcontainers (needs Docker; the harness gates this behind
  *     RIFT_IT). Advertises every capability.
  *   - `embedded` — in-process Rift via Panama FFM (needs JDK 22+; the harness gates this behind
  *     that JVM floor). Core port + Faults/Scripting/ProxyRecord/Templating.
  */
object Backends:

  /** The backend name the harness selected, for diagnostics. */
  val selected: String = sys.env.getOrElse("MOCK_BACKEND", "wiremock")

  private val provisioning: ULayer[Provisioning] = ZLayer(Provisioning.make)

  val mockControl: ZLayer[Any, Throwable, MockControl] =
    selected match
      case "wiremock" => provisioning >>> WireMock.correlated()
      case "wiremock-pi" => provisioning >>> WireMock.perInstance
      case "rift" =>
        (Client.default ++ provisioning) >>> Rift
          .managed()
          .mapError(e => new RuntimeException(s"Rift: $e"))
      case "embedded" =>
        provisioning >>> EmbeddedRift.layer.mapError(e =>
          new RuntimeException(s"Embedded Rift: $e")
        )
      case other =>
        ZLayer.fail(
          new IllegalArgumentException(
            s"unknown MOCK_BACKEND='$other' (wiremock|wiremock-pi|rift|embedded)"
          )
        )
