package samples.rift

import samples.rift.support.*
import zio.*
import zio.bdd.core.Assertions
import zio.bdd.core.Suite
import zio.bdd.core.step.{Stage, ZIOSteps}

import java.nio.file.Files

/** Plane A consistency gate (#5): the engine, rift-lint and rift-verify must all agree on the
  * corpus, and all come from one released build. Corpus imposters are posted **verbatim** (fixed
  * 45xx ports, `_verify` blocks intact) — never through the adapter, so this stays an independent
  * oracle. Requires `RIFT_BUILD_DIR`; corpus at `$RIFT_CORPUS_ROOT/imposters`.
  */
@Suite(
  featureDirs = Array("classpath:features/rift/01-consistency-gate.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object ConsistencyGateSuite
    extends ZIOSteps[RiftHarness, RiftState]
    with RiftConformanceSupport[RiftState]:

  override def environment: ZLayer[Any, Throwable, RiftHarness] = RiftHarness.layer

  private val Binaries = List("rift", "rift-verify", "rift-lint")

  // ── @preflight — version coherence ─────────────────────────────────────────────────────────

  Then("BUILD_INFO records a released build and all three binaries") {
    ZIO.serviceWithZIO[RiftHarness](_.buildInfo).flatMap { info =>
      val missing = Binaries.filterNot(info.versions.contains)
      Assertions.assertTrue(
        info.commit.nonEmpty && info.branch.nonEmpty && missing.isEmpty,
        s"BUILD_INFO coherence: commit='${info.commit}' branch='${info.branch}' missing=${missing.mkString(",")}"
      )
    }
  }

  // A real (falsifiable) coherence check: each binary must exist in the build dir and its live
  // `--version` must match what BUILD_INFO recorded — catching a missing or hand-swapped/stale
  // binary. (Rift's admin API exposes no version endpoint, so this is the closest to coherence.)
  Then("the engine, rift-lint and rift-verify binaries match BUILD_INFO") {
    for
      info <- ZIO.serviceWithZIO[RiftHarness](_.buildInfo)
      tools <- ZIO.serviceWith[RiftHarness](_.tools)
      buildDir = tools.lint.getParent
      problems <- ZIO.foreach(Binaries) { bin =>
        val p = buildDir.resolve(bin)
        ZIO.attemptBlocking(Files.isRegularFile(p)).flatMap {
          case false => ZIO.some(s"missing binary: $bin")
          case true =>
            binaryVersion(p).map { live =>
              val recorded = info.versions.getOrElse(bin, "<none>")
              Option
                .when(live != recorded)(s"$bin drift: on-disk '$live' != BUILD_INFO '$recorded'")
            }
        }
      }
      issues = problems.flatten
      _ <- Assertions.assertTrue(
        issues.isEmpty,
        s"binary/BUILD_INFO coherence: ${issues.mkString("; ")}"
      )
    yield ()
  }

  // ── corpus presence ────────────────────────────────────────────────────────────────────────

  // Read gate-critical accumulator state as a hard failure when the Given never populated it — an
  // empty default would let a Then run (and possibly pass) without its Given, i.e. vacuously.
  private def loadedCorpus: IO[Throwable, List[java.nio.file.Path]] =
    Stage
      .get[LoadedCorpus]
      .map(_.files)
      .mapError(e => new IllegalStateException(s"corpus not loaded ($e)"))
  private def postedPorts: IO[Throwable, List[Int]] =
    Stage
      .get[PostedPorts]
      .map(_.ports)
      .mapError(e => new IllegalStateException(s"corpus not posted ($e)"))

  Given("the corpus directory is loaded") {
    corpusFiles.flatMap(fs => Stage.put(LoadedCorpus(fs)))
  }

  Then("the corpus contains at least " / int / " imposter files") { (min: Int) =>
    loadedCorpus.flatMap { files =>
      Assertions
        .assertTrue(files.size >= min, s"corpus has ${files.size} imposter files, expected >= $min")
    }
  }

  // ── rift-lint over the corpus ───────────────────────────────────────────────────────────────

  Then("rift-lint reports 0 errors across the corpus") {
    for
      report <- ZIO.serviceWithZIO[RiftHarness](_.tools.riftLint(corpusImpostersDir))
      // Guard against a misdirected/empty lint run reporting a trivial "0 errors".
      _ <- Assertions.assertTrue(
        report.filesChecked >= 15,
        s"rift-lint only checked ${report.filesChecked} files (expected >= 15 — wrong corpus dir?)"
      )
      _ <- Assertions.assertTrue(
        report.errors == 0,
        s"rift-lint reported ${report.errors} error(s): ${RiftTools.errorOffenders(report).mkString("; ")}"
      )
    yield ()
  }

  // ── engine serves the corpus on the declared ports ──────────────────────────────────────────

  Given("every corpus imposter is posted to the engine") {
    postCorpusVerbatim.flatMap(ports => Stage.put(PostedPorts(ports)))
  }

  Then("every corpus imposter answers on its declared port") {
    for
      ports <- postedPorts
      checks <- ZIO.foreach(ports)(p => portAnswers(p).map(ok => p -> ok))
      dead = checks.collect { case (p, false) => p }
      _ <- Assertions.assertTrue(
        dead.isEmpty,
        s"imposter ports not answering: ${dead.mkString(", ")}"
      )
    yield ()
  }

  // ── rift-verify over the loaded corpus ──────────────────────────────────────────────────────

  Then("rift-verify reports 0 failures") {
    for
      admin <- ZIO.serviceWith[RiftHarness](_.adminUrl)
      report <- ZIO.serviceWithZIO[RiftHarness](_.tools.riftVerify(admin))
      // Lead with WHICH test failed and why: a bare count sends the reader to the CI log, which is
      // exactly the dead end #126 spent five occurrences in.
      // If the FAIL vocabulary ever stops matching, fall back to raw stderr rather than reporting a
      // bare count — losing the reason is the whole defect this instrumentation exists to end.
      detail =
        if report.failDetail.nonEmpty then report.failDetail.mkString("\n  ", "\n  ", "")
        else if report.rawErr.trim.nonEmpty then
          report.rawErr.linesIterator.toList
            .takeRight(40)
            .mkString("\n  (no FAIL lines matched; stderr tail)\n  ", "\n  ", "")
        else "\n  (rift-verify wrote nothing to stderr)"
      _ <- Assertions.assertTrue(
        report.failed == 0,
        s"rift-verify: ${report.failed} failed, ${report.passed} passed, " +
          s"${report.skipped} skipped$detail\n${report.raw}"
      )
    yield ()
  }
