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
// evicts it up to riftJavaVersion (a patch-level override) — keep that override until a zio-bdd
// release (1.5.2) declares rift-java 0.3.4 itself. The rift-java 0.3.4
// natives embed rift 0.19.0, the same engine scripts/build-rift.sh fetches for the binary legs
// (external + container Plane A, rift-external Plane B), so the binary/native engine-version split
// is closed again. Bump RIFT_VERSION, zioBddVersion and riftJavaVersion together.
//
// rift-java's `RiftFfi` binds the whole C-ABI (v2 admin symbols, `rift_verify`,
// `rift_stub_warnings`, intercept list/clear/ca-pem), so the binding gap of #96 is gone; what the
// harness wires up is in EmbeddedAdmin.
val riftJavaVersion = "0.3.4"

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

// The Plane A specification — `features/rift/`, `rift-fixtures/`, `rift-lint-fixtures/`,
// `rift-scripts/` — is one resource root, read from RIFT_FEATURES_DIR when set (the engine repo's
// own copy, achird-labs/rift#1338) and from the in-repo `src/test/plane-a` otherwise (#236). Exactly
// one of the two is ever on the classpath: zio-bdd resolves a `classpath:` feature with
// `getResources`, so with both present every feature would load twice.
val planeATree = settingKey[File]("Root of the Plane A features and fixtures")
planeATree := sys.env
  .get("RIFT_FEATURES_DIR")
  .filter(_.nonEmpty)
  .map(file)
  .getOrElse(baseDirectory.value / "src" / "test" / "plane-a")
Test / unmanagedResourceDirectories += planeATree.value
Test / javaOptions += s"-Drift.planeATree=${planeATree.value.getAbsolutePath}"

// Run suites one at a time: the mock corpus shares one backend server per run (a Correlated
// WireMock instance), so concurrent suites would race on it; Plane A's single-engine-per-run
// harness needs the same serialization.
Test / parallelExecution := false

// Plane A consistency gate (feature 01). The parity checkpoint (#6) and future retirements run this
// on the same build as the shell gate. (The full alias set — run-all, mock legs — lands in #15.)
addCommandAlias("engineGate", "testOnly samples.rift.ConsistencyGateSuite")

// ── Features ported to the engine repo's Rust runner (#237, achird-labs/rift#1346…#1350) ─────────
// conformance/ported.txt names the feature files the Rust runner (achird-labs/rift conformance/runner)
// now owns. A suite whose every `classpath:features/rift/…` file is listed leaves the external,
// embedded and lint legs here, so the two runners never both own a feature in a mode both run. The
// container leg keeps them until the Rust runner has a container mode (achird-labs/rift#1467).
// Suites are found by reading the `classpath:` names out of their sources, so the list stays in the
// feature-file vocabulary the engine repo uses.
val portedFeatures: Set[String] =
  IO.readLines(file("conformance/ported.txt"))
    .map(_.trim)
    .filter(l => l.nonEmpty && !l.startsWith("#"))
    .toSet

val portedSuites: Seq[String] = {
  val featureRef = "classpath:features/rift/([^\"]+)".r
  (file("src/test/scala/samples/rift") * "*.scala").get.sorted.flatMap { f =>
    val named = featureRef.findAllMatchIn(IO.read(f)).map(_.group(1)).toSet
    if (named.nonEmpty && named.subsetOf(portedFeatures)) Some(f.base) else None
  }
}

def withoutPorted(suites: Seq[String]): Seq[String] = suites.filterNot(portedSuites.contains)
// sbt's testOnly drops a class named with a leading `-`.
val portedExclusions: String = portedSuites.map(" -samples.rift." + _).mkString

// Full Plane A pass (issue #15): every samples.rift.* suite, not just the gate, minus the ported
// ones. This is what scripts/conformance-engine.sh and scripts/run-all.sh's "engine (Plane A)"
// layer invoke.
addCommandAlias("engineConformance", "testOnly samples.rift.*" + portedExclusions)

// ── Plane A mode legs (#18 embedded, #12 container, #206 enrolment) ─────────────────────────────
// One engine, three deliveries: the binary (external, `engineConformance` above), the rift-java
// natives (embedded) and the `zainalpour/rift-proxy` image (container). Every Plane A feature runs
// in every mode that can run it; a scenario a mode's harness cannot drive carries a tag that mode's
// alias excludes (below, one line of reason per tag). The BDD suites are enumerated rather than
// globbed so each mode's scope stays explicit; the zio-test specs run in a separate `testOnly` step
// because zio-bdd's `--exclude-tags` arg is not theirs to parse.
//
// Features in no mode leg besides external, and why:
//   09-gateway-https-metrics — HTTPS/mTLS client to the imposter plus the metrics listener: embedded
//     has no metrics listener (DESIGN §3.1); container revisits it with the TLS work.
//   16-front-door — every scenario spawns its own `--front-door` engine (`@front-door`).
// 11-reload runs embedded only: the container harness has no config file to reload (withConfigFile
// is unsupported by design).
val planeAEveryModeSuites: Seq[String] = Seq(
  "ConsistencyGateSuite", // 01
  "RiftSmokeSuite", // 00
  "CoreServing02Suite",
  "FaultsTransport03Suite",
  "ScriptingBounds04Suite",
  "Templating05Suite",
  "Behaviors06Suite",
  "SpacesFsm07Suite",
  "AdminSurface08Suite",
  "ProxyRecord10Suite",
  "Intercept12Suite",
  "ErrorEnvelope13Suite",
  "BehaviorsResponseTypes14Suite",
  "Metrics17Suite",
  "MatchDiagnostics18Suite",
  "FlowStateDeclarative19Suite",
  "TlsTermination20Suite",
  "AdminRest21Suite",
  "MountebankCompat22Suite"
)

// Tags whose scenarios start a dedicated engine with CLI flags or env vars — only external mode can
// spawn one: intercept-caller-ca / intercept-runtime / intercept-auth / intercept-ws (feature 12),
// upstream-trust (09), front-door (11, 16), admin-isolated (21, a second raw-spawned engine),
// tls-flags (20), rift-debug-env (05, 19: a `--debug` engine).
val dedicatedEngineTags: Seq[String] = Seq(
  "intercept-caller-ca",
  "intercept-runtime",
  "intercept-auth",
  "intercept-ws",
  "upstream-trust",
  "front-door",
  "admin-isolated",
  "tls-flags",
  "rift-debug-env"
)

def planeATestOnly(classes: Seq[String]): String =
  "testOnly " + classes.map("samples.rift." + _).mkString(" ")
def planeABddStep(suites: Seq[String], excludeTags: Seq[String]): String =
  planeATestOnly(suites) + " -- --exclude-tags " + excludeTags.mkString(",")

// Plane A embedded pass (RIFT_MODE=embedded, JDK 22+): the in-process FFM engine. Extra exclusions:
//   intercept-http   — the FFI binds start-intercept/add-rules/export-truststore, not the HTTP-only
//                      ca.pem + rules-list/clear routes (#53).
//   metrics-port     — no separate Prometheus listener in-process (DESIGN §3.1); 17's admin
//                      GET /metrics scenarios still run.
//   no-config-source — the embedded harness always reloads from a config file (11, #199).
// Specs: the embedded harness acceptance spec, the FFI parity specs (#75/#92/#94/#205: C-ABI calls
// diffed against the admin HTTP API; no-ops outside embedded) and AdminEventsCursor122Spec (#122,
// #201: /events on a fresh in-process engine per case). RiftHarnessSpec is external-only (it asserts
// on a spawned child in `ps`).
addCommandAlias(
  "engineEmbedded",
  "; " + planeABddStep(
    withoutPorted(planeAEveryModeSuites :+ "Reload11Suite"),
    dedicatedEngineTags ++ Seq("intercept-http", "metrics-port", "no-config-source")
  ) + " ; " + planeATestOnly(
    Seq(
      "RiftEmbeddedHarnessSpec",
      "RiftToolsParseSpec",
      "FfiAdminParity75Spec",
      "FfiStubWarnings92Spec",
      "FfiVerifyParity94Spec",
      "FfiLongTail205Spec",
      "AdminEventsCursor122Spec"
    )
  )
)

// Plane A container pass (RIFT_MODE=container, RIFT_IT=1 + Docker): the image-run engine behind
// ports published 1:1 (admin 2525, intercept 8443, metrics 4790, imposters 4500–4599 + 4800–4899)
// with `corpus/data` mounted at the image workdir. The harness spec runs FIRST as the "does the
// container start + serve admin" smoke: a `;`-chain aborts at the first failure, so a broken
// container fails there with the crispest signal. Extra exclusions:
//   full-corpus-verify — `rift-verify --verify-dynamic` binds ephemeral high ports inside the
//                        container that the host-side tool cannot reach (#71).
//   loopback-client    — predicates on client address 127.0.0.1, which Docker's forward rewrites (22).
//   connect-refused    — asserts a raw TCP connect is refused; a published port always accepts (08, 21).
//   host-port          — the engine auto-assigns a port (≥49152) outside the published pool (08).
//   tcp-rst            — a published Docker port turns an RST into a clean EOF, so a raw reset
//                        reads as an empty reply (03, rift#1234).
// AdminEventsCursor122Spec needs a fresh engine per case, which the one shared container is not.
addCommandAlias(
  "engineContainer",
  "; " + planeATestOnly(Seq("RiftContainerHarnessSpec", "RiftToolsParseSpec")) + " ; " +
    planeABddStep(
      planeAEveryModeSuites,
      dedicatedEngineTags ++ Seq(
        "full-corpus-verify",
        "loopback-client",
        "connect-refused",
        "host-port",
        "tcp-rst"
      )
    )
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
  """;set Test / javaOptions += "-Drift.dryRun=true";testOnly samples.planea.PlaneATreeSpec samples.rift.*Suite""" +
    portedExclusions + " -- --dry-run"
)

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
