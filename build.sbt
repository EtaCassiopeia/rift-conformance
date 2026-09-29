ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.github.etacassiopeia"

// Track zio-bdd's own ZIO: a lower pin here gets zio-test evicted upward while zio-test-sbt stays
// behind, and the forked test runner dies with NoSuchMethodError before any spec runs.
val zioVersion = "2.1.21"
val zioBddVersion = "1.5.1"

// zio-bdd 1.5.0 re-based its Rift adapters onto the official SDK (zio-bdd#336): `zio-bdd-rift` sits
// on `rift-scala-zio`, and the in-process engine is rift-java's `rift-java-embedded` plus its
// `rift-java-natives` classifier jar (added below). The `zio-bdd-rift-embedded*` artifacts are
// gone (#145).
//
// ONE ENGINE VERSION: riftJavaVersion MUST equal the `rift-java-core` on the test classpath — the
// embedded engine and the core meet across the FFI boundary. zio-bdd 1.5.1's zio-bdd-rift still
// declares rift-java-core 0.3.2 (via rift-scala-zio 0.3.0), so core is declared explicitly below and
// evicts it up to riftJavaVersion (a patch-level override). The rift-java 0.3.3
// natives embed rift 0.18.1, the same engine scripts/build-rift.sh fetches for the binary legs
// (external + container Plane A, rift-external Plane B), so the binary/native engine-version split
// is closed again. Bump RIFT_VERSION, zioBddVersion and riftJavaVersion together.
//
// rift-java's `RiftFfi` binds the whole C-ABI (v2 admin symbols, `rift_verify`,
// `rift_stub_warnings`, intercept list/clear/ca-pem), so the binding gap of #96 is gone; what the
// harness wires up is in EmbeddedAdmin.
val riftJavaVersion = "0.3.3"

// The RiftHarness, the Plane A steps, and their specs live under src/test — this build hosts the
// conformance harness, not a shipped library, so its dependencies are test-scoped.
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-json" % "0.7.3",
  "dev.zio" %% "zio-process" % "0.7.2",
  "io.github.etacassiopeia" %% "zio-bdd" % zioBddVersion % Test,
  "io.github.etacassiopeia" %% "zio-bdd-mock" % zioBddVersion % Test, // Plane B: portable MockControl SPI, DSL, model
  "io.github.etacassiopeia" %% "zio-bdd-wiremock" % zioBddVersion % Test, // Plane B: default backend (in-process, no Docker)
  // Plane B: gated backend (container / embedded FFM). Needs JDK 17+ (rift-java-core is class 61).
  "io.github.etacassiopeia" %% "zio-bdd-rift" % zioBddVersion % Test,
  // Pins rift-java-core to riftJavaVersion on every JDK: zio-bdd-rift declares 0.3.2, and only the
  // JDK 22+ rift-java-embedded dependency below would otherwise evict it (see ONE ENGINE VERSION).
  "io.github.achird-labs" % "rift-java-core" % riftJavaVersion % Test,
  // RiftHarnessContainer (Plane A container mode) drives Docker through testcontainers-scala. Up to
  // zio-bdd 1.4.4 it arrived transitively via zio-bdd-rift; 1.5.0 moved to rift-java-testcontainers,
  // so it is declared here, at the version 1.4.4 carried.
  "com.dimafeng" %% "testcontainers-scala-core" % "0.41.4" % Test,
  "dev.zio" %% "zio-test" % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test
)

// Local-only: Rift's testcontainers path pulls docker-java 3.3.x (via testcontainers-java
// 1.19.1), which can't handshake with Docker Desktop 29. Override to a compatible docker-java for
// the `MOCK_BACKEND=rift` leg (Docker container, issue #12). Harmless otherwise.
dependencyOverrides ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.21.4",
  "com.github.docker-java" % "docker-java-api" % "3.4.2",
  "com.github.docker-java" % "docker-java-transport-zerodep" % "3.4.2",
  "com.github.docker-java" % "docker-java-transport" % "3.4.2"
)

// Two frameworks coexist: ZTestFramework runs the ZIOSpecDefault specs (RiftHarness/RiftTools),
// ZIOBDDFramework runs the Gherkin Plane A suites. Each selects its own classes by fingerprint.
Test / testFrameworks := Seq(
  new TestFramework("zio.test.sbt.ZTestFramework"),
  new TestFramework("zio.bdd.ZIOBDDFramework")
)

// The harness spawns a real child engine and installs a JVM shutdown hook; fork so the hook
// and process teardown run in an isolated JVM, and run suites serially (one engine per run).
Test / fork := true

// Embedded FFI (MOCK_BACKEND=embedded Plane B, and RIFT_MODE=embedded Plane A #18): the in-process
// Rift engine via Panama FFM. Gated on a JDK **22+** JVM: `rift-java-embedded` is Java-22 bytecode
// (class 66) on the stable FFM API. On JDK 17/21 the embedded backend is unavailable
// (`EmbeddedRift.available` is false) and the corpus runs on wiremock instead.
lazy val ffmEnabled: Boolean =
  System.getProperty("java.specification.version", "11").split("\\.").head.toInt >= 22

// rift-java-natives publishes one classifier per platform (darwin-aarch64, linux-x86_64, ...).
lazy val riftNativesClassifier: Option[String] = {
  val os = System.getProperty("os.name", "").toLowerCase
  val arch = System.getProperty("os.arch", "") match {
    case "amd64" | "x86_64" => "x86_64"
    case "aarch64" | "arm64" => "aarch64"
    case other => other
  }
  if (os.contains("mac")) Some(s"darwin-$arch")
  else if (os.contains("linux")) Some(s"linux-$arch")
  else None
}

libraryDependencies ++= (
  if (ffmEnabled)
    ("io.github.achird-labs" % "rift-java-embedded" % riftJavaVersion % Test) +:
      riftNativesClassifier.toSeq.map(c =>
        ("io.github.achird-labs" % "rift-java-natives" % riftJavaVersion % Test).classifier(c)
      )
  else Nil
)
Test / javaOptions ++= (
  if (ffmEnabled) Seq("--enable-native-access=ALL-UNNAMED") else Nil
)
// EmbeddedAdmin's C-ABI binding names FFM types, so it compiles only where rift-java-embedded is on
// the classpath.
Test / unmanagedSourceDirectories ++= (
  if (ffmEnabled) Seq(baseDirectory.value / "src" / "test" / "java-jdk22") else Nil
)

// Run suites one at a time: the mock corpus shares one backend server per run (a Correlated
// WireMock instance), so concurrent suites would race on it; Plane A's single-engine-per-run
// harness needs the same serialization.
Test / parallelExecution := false

// Plane A consistency gate (feature 01). The parity checkpoint (#6) and future retirements run this
// on the same build as the shell gate. (The full alias set — run-all, mock legs — lands in #15.)
addCommandAlias("engineGate", "testOnly samples.rift.ConsistencyGateSuite")

// Full Plane A pass (issue #15): every samples.rift.* suite, not just the gate. This is what
// scripts/conformance-engine.sh and scripts/run-all.sh's "engine (Plane A)" layer invoke.
addCommandAlias("engineConformance", "testOnly samples.rift.*")

// Plane A embedded pass (issue #18, RIFT_MODE=embedded): the subset of suites the in-process FFM
// oracle supports. Excludes 09-gateway-https-metrics and 10-proxy-record (the §3.1 `container`/
// `external` surface — a separate metrics port and the record/replay proxy plane), and the
// external-only RiftHarnessSpec (which asserts on a spawned child in `ps`, absent by design here).
// Enumerated explicitly rather than tag-filtered because 03-faults-transport shares `@transport`
// with 09/10 yet MUST run. Includes 12-intercept (#53) with `--exclude-tags intercept-http` — the
// embedded FFI binds start-intercept/add-rules/export-truststore but not the HTTP-only ca.pem +
// rules-list/clear routes, so those two scenarios (tagged @intercept-http) are skipped here.
// 11-reload's @front-door scenarios (rift#1160) need an engine spawned with `--front-door`, which
// only external mode can do, so they are excluded too. `upstream-trust` (feature 09, rift#974) spawns
// a second engine the same way; 09 is in neither the embedded nor the container list, but the tag is
// excluded from both so enrolling 09 later cannot pull it in. The
// zio-test specs run in a second step (the zio-bdd `--exclude-tags` arg is not theirs to parse).
// 13-error-envelope (#118) is likewise absent: its doors (strictBehaviors shellTransform, the
// `defaultForward` 502, the over-size-body cap) were only verified against the spawned engine, and
// this list is an allowlist — enrolling an unverified suite would make the embedded job red for a
// reason that has nothing to do with the envelope contract it exists to pin.
// 14-behaviors-response-types (#154) was verified green on the embedded leg before being enrolled.
// FfiAdminParity75Spec (#75), FfiStubWarnings92Spec (#92), FfiVerifyParity94Spec (#94) and
// FfiLongTail205Spec (#205) diff C-ABI calls against the admin HTTP API on one engine; they no-op
// outside RIFT_MODE=embedded, so only this alias runs them.
// 21-admin-rest (#200) runs here too, minus `@admin-isolated`: its DELETE /imposters scenarios need a
// second, raw-spawned engine (the recipe `upstream-trust` uses), which only external mode can start.
addCommandAlias(
  "engineEmbedded",
  "; testOnly samples.rift.ConsistencyGateSuite samples.rift.CoreServing02Suite " +
    "samples.rift.FaultsTransport03Suite samples.rift.ScriptingBounds04Suite " +
    "samples.rift.Templating05Suite samples.rift.Behaviors06Suite samples.rift.SpacesFsm07Suite " +
    "samples.rift.AdminSurface08Suite samples.rift.Reload11Suite samples.rift.Intercept12Suite " +
    "samples.rift.BehaviorsResponseTypes14Suite samples.rift.AdminRest21Suite " +
    "-- --exclude-tags intercept-http,intercept-caller-ca,intercept-runtime,front-door,upstream-trust," +
    "admin-isolated" +
    " ; testOnly samples.rift.RiftEmbeddedHarnessSpec samples.rift.RiftToolsParseSpec" +
    " samples.rift.FfiAdminParity75Spec samples.rift.FfiStubWarnings92Spec" +
    " samples.rift.FfiVerifyParity94Spec samples.rift.FfiLongTail205Spec"
)

// Plane A container pass (issue #12, RIFT_MODE=container): the acceptance subset the Docker-run
// engine is scoped to — feature 01 (gate) + 03 (faults-transport) + 12 (intercept/TLS-MITM, #51 —
// the container publishes an `--intercept-port` 1:1) — plus the container harness acceptance spec.
// Docker/RIFT_IT-gated by scripts/conformance-engine.sh. Excludes 02/04–11: the image-run engine is
// only asserted for the gate + transport + intercept surfaces (DESIGN §3.1). The full-corpus SERVE
// scenario (@full-corpus) now runs in container: its historical blocker — published images lacking
// the `lua` feature the corpus exercised — is void (rift 0.12.0 removed Lua #451; 05-scripting is
// rhai + JS, both built-in), and a green RIFT_IT run against the v0.13.1 image confirmed it (#71);
// the container leg tracks RIFT_VERSION (now the 0.15.0 image), re-verified in CI on the bump.
// `--exclude-tags full-corpus-verify` drops ONLY the rift-verify scenario: `rift-verify
// --verify-dynamic` spins up ephemeral imposters on dynamic high ports that bind inside the
// container's network namespace and are unreachable from the host-side tool over the 1:1
// published-port topology — a standing container-mode constraint, not a lua/image gap (#71). The
// container harness spec runs FIRST as the "does the container start + serve admin" smoke test — a
// `;`-chain aborts at the first failure, so putting it first means a broken container fails there
// with the crispest signal rather than as an opaque BDD "Container startup failed". The zio-test
// specs are a separate step because the zio-bdd `--exclude-tags` arg is not theirs to parse.
addCommandAlias(
  "engineContainer",
  "; testOnly samples.rift.RiftContainerHarnessSpec samples.rift.RiftToolsParseSpec" +
    " ; testOnly samples.rift.ConsistencyGateSuite samples.rift.FaultsTransport03Suite " +
    "samples.rift.Intercept12Suite -- --exclude-tags full-corpus-verify,intercept-caller-ca,intercept-runtime,upstream-trust"
)

// `lintFeatures` — the dry-run step-matching gate (#142): loads the feature suites and checks every
// Gherkin step resolves to a step definition, spawning no engine. Two things make the scope narrower
// than a bare `samples.* -- --dry-run`:
//   • `-Drift.dryRun=true` makes RiftHarness.layer yield RiftHarnessDryRun instead of fetching an
//     engine — zio-bdd builds a suite's `environment` layer even under --dry-run (it provides the
//     layer, then skips step bodies), so without this the Plane A suites demand RIFT_BUILD_DIR. Set
//     on Test/javaOptions (the forked test JVM) so bare `sbt lintFeatures` works with no wrapper.
//   • Scoped to `samples.rift.*Suite` (the BDD suites) — the `*Spec` classes are zio-test, ignore
//     --dry-run, and would really run (needing an engine). PLANE B IS DEFERRED: `samples.mock.*`
//     can't be dry-run cleanly yet because a scenario carrying both `@rift` and `@mock(orders)` has
//     its Rift-only fixture provisioned on the wiremock backend even under `--exclude-tags rift`
//     (upstream zio-bdd tag-exclusion bug). Re-add `samples.mock.*Suite` here once that lands.
// The `set` persists for the sbt SESSION: after `lintFeatures` an interactive session keeps
// -Drift.dryRun=true, so a subsequent real run in the SAME session would get RiftHarnessDryRun. That
// surfaces as a LOUD "reached under --dry-run" defect (never a false green), and CI/scripts run it in
// a fresh `sbt -batch` so it can't leak there; interactive users chaining a real run should
// `reload` (or start a new sbt) first.
addCommandAlias(
  "lintFeatures",
  """;set Test / javaOptions += "-Drift.dryRun=true";testOnly samples.rift.*Suite samples.redis.*Suite -- --dry-run"""
)

// Flow-store backend parity (#174): feature 15 drives the same flow-state scenarios against an
// in-memory and a Redis-backed imposter. It needs Docker (a redis:7-alpine container the harness
// starts itself) and the external engine, so it lives in `samples.redis` — outside
// `engineConformance`'s `samples.rift.*` — and runs only here, with RIFT_IT=1.
addCommandAlias("engineRedis", "testOnly samples.redis.*")
addCommandAlias("mockSmoke", "testOnly samples.mock.* -- --include-tags smoke")
// `rift` is excluded alongside scripting/templating/proxyrecord/intercept — matching zio-bdd-samples'
// scripts/conformance.sh (`--exclude-tags scripting,templating,proxyrecord,rift`) plus `intercept`
// (#54, a capability WireMock doesn't advertise) — because several @rift-tagged scenarios (samples
// 09/17/19) assert raw-Rift-only behavior that a WireMock backend cannot satisfy by design (see
// COVERAGE.md's "Plane B" notes); without this exclusion `mockConformance` is red on
// MOCK_BACKEND=wiremock for reasons unrelated to library conformance.
addCommandAlias(
  "mockConformance",
  "testOnly samples.mock.* -- --exclude-tags scripting,templating,proxyrecord,rift,intercept,fixedport"
)
// The embedded (in-process Rift FFI) backend has different capabilities than wiremock: it CAN
// run scripting/templating (a real Rift engine) but not the wiremock-native / stateful / negotiation
// scenarios — so it carries its own tag-exclusion set (mirrors scripts/conformance-embedded.sh).
addCommandAlias(
  "mockEmbedded",
  "testOnly samples.mock.* -- --exclude-tags stateful,stateinspection,rift,wiremock,negotiation,native"
)
// `rift-external` (issue #14) is a real rift engine spawned by the Plane A RiftHarness (no
// Docker, no FFM floor) — it advertises and runs the full capability surface, so it only excludes
// `wiremock` (the two raw-WireMock-only scenarios, samples 17/19, that assert WireMock-specific
// behavior a rift engine can't and shouldn't satisfy) and `intercept` (#54 — the rift-external
// MockControl is connected without an intercept proxy, so it doesn't advertise the Intercept
// capability). Mirrors scripts/conformance-rift-external.sh.
addCommandAlias(
  "mockRiftExternal",
  "testOnly samples.mock.* -- --exclude-tags wiremock,intercept"
)
