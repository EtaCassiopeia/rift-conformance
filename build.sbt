ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.github.etacassiopeia"

val zioVersion = "2.1.17"

// The RiftHarness and its acceptance spec live under src/test — this build hosts the
// conformance harness, not a shipped library, so its dependencies are test-scoped.
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-json" % "0.7.3",
  "dev.zio" %% "zio-test" % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test
)

// zio-test only for Phase 0; the Gherkin ZIOBDDFramework is added with the feature files (#4+).
Test / testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

// The harness spawns a real child engine and installs a JVM shutdown hook; fork so the hook
// and process teardown run in an isolated JVM, and run suites serially (one engine per run).
Test / fork := true
Test / parallelExecution := false
