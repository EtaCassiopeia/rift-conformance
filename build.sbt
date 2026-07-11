ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.github.etacassiopeia"

val zioVersion = "2.1.17"
val zioBddVersion = "1.4.2"

// zio-bdd 1.4.2 is a public Maven Central release (no local publishLocal / sibling checkout
// needed) and itself pins rift v0.13.1 (zio-bdd#316), matching the engine this harness fetches.
// Since 1.4.1 the embedded C-ABI (rift#411/#410) exposes serveAdmin (the real in-process admin API),
// flow-state/space ops, and intercept — enough for the Plane A embedded oracle (RIFT_MODE=embedded,
// issue #18). 1.4.2 also ships self-contained embedded natives (no system-LuaJIT dlopen; zio-bdd#312).

// The RiftHarness, the Plane A steps, and their specs live under src/test — this build hosts the
// conformance harness, not a shipped library, so its dependencies are test-scoped.
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-json" % "0.7.3",
  "dev.zio" %% "zio-process" % "0.7.2",
  "io.github.etacassiopeia" %% "zio-bdd" % zioBddVersion % Test,
  "io.github.etacassiopeia" %% "zio-bdd-mock" % zioBddVersion % Test, // Plane B: portable MockControl SPI, DSL, model
  "io.github.etacassiopeia" %% "zio-bdd-wiremock" % zioBddVersion % Test, // Plane B: default backend (in-process, no Docker)
  "io.github.etacassiopeia" %% "zio-bdd-rift" % zioBddVersion % Test, // Plane B: gated backend (container / embedded FFM)
  // 1.3.1 split the embedded-FFM Scala API (EmbeddedRift / EmbeddedRiftMockControl) out of
  // zio-bdd-rift into its own module (it was part of zio-bdd-rift under 1.2.0-SNAPSHOT). Needed
  // unconditionally so Backends.scala's `import zio.bdd.mock.rift.embedded.EmbeddedRift` compiles
  // on every JDK; only the FFM binding + native library (below) are JDK 22+-gated.
  "io.github.etacassiopeia" %% "zio-bdd-rift-embedded" % zioBddVersion % Test,
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
// Rift engine via Panama FFM.
// Gated on a JDK **22+** JVM: 1.4.2 publishes the FFM binding only as `zio-bdd-rift-embedded-jdk21`,
// but that jar is compiled to Java-22 bytecode (class 66), so it won't load on JDK 21 despite the
// name (upstream: zio-bdd#247). FFM is a stable API on 22+, so no --enable-preview is needed (and it
// would in fact conflict with the class-66 binding). On JDK 11/17/21 the embedded backend is
// unavailable and the corpus runs on wiremock instead.
lazy val ffmEnabled: Boolean =
  System.getProperty("java.specification.version", "11").split("\\.").head.toInt >= 22

libraryDependencies ++= (
  if (ffmEnabled)
    Seq(
      "io.github.etacassiopeia" %% "zio-bdd-rift-embedded-jdk21" % zioBddVersion % Test,
      "io.github.etacassiopeia" % "zio-bdd-rift-embedded-natives" % zioBddVersion % Test
    )
  else Nil
)
Test / javaOptions ++= (
  if (ffmEnabled) Seq("--enable-native-access=ALL-UNNAMED") else Nil
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
// rules-list/clear routes, so those two scenarios (tagged @intercept-http) are skipped here. The
// zio-test specs run in a second step (the zio-bdd `--exclude-tags` arg is not theirs to parse).
addCommandAlias(
  "engineEmbedded",
  "; testOnly samples.rift.ConsistencyGateSuite samples.rift.CoreServing02Suite " +
    "samples.rift.FaultsTransport03Suite samples.rift.ScriptingBounds04Suite " +
    "samples.rift.Templating05Suite samples.rift.Behaviors06Suite samples.rift.SpacesFsm07Suite " +
    "samples.rift.AdminSurface08Suite samples.rift.Reload11Suite samples.rift.Intercept12Suite " +
    "-- --exclude-tags intercept-http,intercept-caller-ca,intercept-runtime" +
    " ; testOnly samples.rift.RiftEmbeddedHarnessSpec samples.rift.RiftToolsParseSpec"
)

// Plane A container pass (issue #12, RIFT_MODE=container): the acceptance subset the Docker-run
// engine is scoped to — feature 01 (gate) + 03 (faults-transport) + 12 (intercept/TLS-MITM, #51 —
// the container publishes an `--intercept-port` 1:1) — plus the container harness acceptance spec.
// Docker/RIFT_IT-gated by scripts/conformance-engine.sh. Excludes 02/04–11: the image-run engine is
// only asserted for the gate + transport + intercept surfaces (DESIGN §3.1). The full-corpus SERVE
// scenario (@full-corpus) now runs in container: its historical blocker — published images lacking
// the `lua` feature the corpus exercised — is void (rift 0.12.0 removed Lua #451; 05-scripting is
// rhai + JS, both built-in), and a green RIFT_IT run against the v0.13.1 image confirms it (#71).
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
    "samples.rift.Intercept12Suite -- --exclude-tags full-corpus-verify,intercept-caller-ca,intercept-runtime"
)

// Plane B (issue #13) harness entry points (mirror scripts/*.sh). `lintFeatures` is the dry-run
// step-matching validation across both planes; the mock aliases drive the corpus on the backend
// chosen by the MOCK_BACKEND env var (see samples.mock.support.Backends).
addCommandAlias("lintFeatures", "testOnly samples.* -- --dry-run")
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
