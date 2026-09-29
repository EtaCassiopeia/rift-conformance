package samples.mock.support

import zio.*
import zio.bdd.mock.*
import zio.bdd.mock.wiremock.WireMock
import zio.bdd.mock.rift.Rift
import zio.bdd.mock.rift.embedded.EmbeddedRift
import samples.rift.support.RiftHarness

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
  *   - `rift-external` — a real rift engine spawned by the Plane A `RiftHarness` (no Docker, no FFM
  *     floor). Advertises (and runs) the same capability surface as `rift`, minus raw WireMock-only
  *     scenarios.
  */
object Backends:

  /** The backend name the harness selected, for diagnostics. */
  val selected: String = sys.env.getOrElse("MOCK_BACKEND", "wiremock")

  private val provisioning: ULayer[Provisioning] = ZLayer(Provisioning.make)

  // Imposter port pool for `rift-external` (DESIGN §6.4) — disjoint from the corpus's 45xx and
  // Plane A's in-harness probe pool (48xx).
  private val RiftExternalPortBase = 4600
  private val RiftExternalPoolSize = 40

  /** Ports a scenario may pin with `onPort` on the selected backend, or `None` when any free port
    * will do. Since zio-bdd 1.5.0 a pooled adapter (`Rift.connect`) refuses an authored port
    * outside its pool (zio-bdd #213/#303), so a pinned port must come from inside it.
    */
  val pinnablePorts: Option[Range] =
    Option.when(selected == "rift-external")(
      RiftExternalPortBase until RiftExternalPortBase + RiftExternalPoolSize
    )

  val mockControl: ZLayer[Any, Throwable, MockControl] =
    selected match
      case "wiremock" => provisioning >>> WireMock.correlated()
      case "wiremock-pi" => provisioning >>> WireMock.perInstance
      case "rift" =>
        provisioning >>> Rift
          .managed()
          .mapError(e => new RuntimeException(s"Rift: $e"))
      case "embedded" =>
        provisioning >>> EmbeddedRift.layer.mapError(e =>
          new RuntimeException(s"Embedded Rift: $e")
        )
      case "rift-external" =>
        // Same engine build as Plane A: the MockControl adapter connects to the RiftHarness-spawned
        // process (no Docker, identity host mapping). Imposter port pool 46xx (DESIGN §6.4 —
        // disjoint from corpus 45xx and Plane A probes 48xx).
        //
        // `Rift.connect(...)` requires `Provisioning`, unrelated to `RiftHarness` — so its ZLayer
        // can't be reached via `ZLayer.service[RiftHarness].flatMap` (flatMap's continuation input
        // must be a *subtype* of the original layer's input). Instead run the RiftHarness read and
        // the inner layer's construction as one scoped effect, sourcing adminUrl/hostFor at build
        // time. Since zio-bdd 1.5.0 the SDK handshakes with the admin API while the layer builds,
        // so an unreachable engine fails here with a `MockError`.
        //
        // `Rift.connect`'s `hostFor` contract wants the FULL `http://host:port` for the (possibly
        // remapped, e.g. Docker-published) port — see zio-bdd's own
        // `ProxyRecordConformanceSpec` (`hostFor = p => s"http://${host}:${mappedPort(p)}"`).
        // `RiftHarness.hostFor` only returns the bare hostname (Plane A prepends the scheme and
        // appends the port itself, unmapped/identity), so wrap it here to match zio-bdd-rift's
        // contract instead of changing `RiftHarness`'s own (Plane-A-only) convention.
        val ports = (RiftExternalPortBase until RiftExternalPortBase + RiftExternalPoolSize).toList
        val connectLayer: ZLayer[RiftHarness & Provisioning, Throwable, MockControl] =
          ZLayer.scoped {
            for
              harness <- ZIO.service[RiftHarness]
              hostFor = (port: Int) => s"http://${harness.hostFor(port)}:$port"
              env <- Rift
                .connect(harness.adminUrl, ports)(hostFor)
                .build
                .mapError(e => new RuntimeException(s"Rift (external): $e"))
            yield env.get[MockControl]
          }
        (RiftHarness.layer ++ provisioning) >>> connectLayer
      case other =>
        ZLayer.fail(
          new IllegalArgumentException(
            s"unknown MOCK_BACKEND='$other' (wiremock|wiremock-pi|rift|embedded|rift-external)"
          )
        )
