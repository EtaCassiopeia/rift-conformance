ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.github.etacassiopeia"

val zioVersion = "2.1.17"
val zioBddVersion = "1.2.0-SNAPSHOT"

// zio-bdd is consumed as a locally published snapshot (mavenLocal); re-resolve snapshots so a
// fresh `publish-lib` is always picked up.
resolvers += Resolver.mavenLocal
ThisBuild / updateOptions := updateOptions.value.withLatestSnapshots(true)

// The RiftHarness, the Plane A steps, and their specs live under src/test — this build hosts the
// conformance harness, not a shipped library, so its dependencies are test-scoped.
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-json" % "0.7.3",
  "dev.zio" %% "zio-process" % "0.7.2",
  "io.github.etacassiopeia" %% "zio-bdd" % zioBddVersion % Test,
  "dev.zio" %% "zio-test" % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test
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
Test / parallelExecution := false
