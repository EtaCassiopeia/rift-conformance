package samples.rift.support

import zio.*
import zio.json.*
import zio.process.Command

import java.nio.file.Path

/** A single finding reported by rift-lint (`-o json`). */
final case class LintIssue(
    severity: String,
    code: String,
    message: String,
    file: String,
    location: Option[String],
    suggestion: Option[String]
)
object LintIssue:
  given JsonDecoder[LintIssue] = DeriveJsonDecoder.gen[LintIssue]

/** Parsed rift-lint result. `raw` and `exitCode` are kept so a failing gate can print exactly what
  * the tool said.
  */
final case class LintReport(
    errors: Int,
    warnings: Int,
    filesChecked: Int,
    issues: List[LintIssue],
    exitCode: Int,
    raw: String
)

/** Parsed `rift-verify -o json` summary. `raw`/`exitCode` preserved for failure messages.
  *
  * `failDetail` carries the per-test `FAIL` lines rift-verify writes to **stderr** (in `-o json`
  * mode stdout is the summary alone — rift#347). Without it a red gate reports only a count, which
  * is how #126 went five occurrences without anyone learning why it failed.
  */
final case class VerifyReport(
    passed: Int,
    failed: Int,
    skipped: Int,
    exitCode: Int,
    raw: String,
    failDetail: List[String],
    rawErr: String
)

/** Typed shell-outs to rift-lint and rift-verify — the SAME binaries built alongside the engine, so
  * lint/verify judgements always come from the commit under test (version coherence, DESIGN §6).
  */
final case class RiftTools(lint: Path, verify: Path, engine: Path):

  /** `rift script check <target>` — statically validate a script (or a config's `_rift.script`
    * entries): engine syntax, entrypoint presence/arity, v1-shape deprecation. Exits non-zero on
    * any error (issue #360, #88). No server started. `raw` keeps stdout+stderr so a failing gate
    * quotes exactly what the tool said.
    */
  def riftScriptCheck(target: Path): IO[Throwable, RiftTools.RawRun] =
    runCapture(Command(engine.toString, "script", "check", target.toString))

  /** `rift script run <target>` — execute a script against a default request and print the
    * decision, the mutated flow state, captured `ctx.logger` output and the duration (the
    * debug-mode script trace, #88). No server running.
    */
  def riftScriptRun(target: Path): IO[Throwable, RiftTools.RawRun] =
    runCapture(Command(engine.toString, "script", "run", target.toString))

  /** `rift-lint -o json <path>` — since rift#347 stdout is pure JSON (the banner goes to stderr),
    * so counts come straight from the tool's own JSON summary.
    */
  def riftLint(path: Path): IO[Throwable, LintReport] =
    runCapture(Command(lint.toString, "-o", "json", path.toString)).flatMap { r =>
      ZIO.fromEither(RiftTools.parseLint(r)).mapError(new RuntimeException(_))
    }

  /** `rift-verify --admin-url <url> --skip-dynamic --verify-dynamic -o json` against a running
    * engine — since rift#347 stdout carries only the JSON summary (banner/progress → stderr).
    */
  def riftVerify(adminUrl: String): IO[Throwable, VerifyReport] =
    runCapture(
      Command(
        verify.toString,
        "--admin-url",
        adminUrl,
        "--skip-dynamic",
        "--verify-dynamic",
        // Per-test detail on stderr; the JSON summary on stdout is unaffected (rift#347). Without
        // it a red gate can only report a count (#126).
        "--verbose",
        "-o",
        "json"
      )
    ).flatMap { r =>
      ZIO.fromEither(RiftTools.parseVerify(r)).mapError(new RuntimeException(_))
    }

  /** Run to completion, capturing stdout and stderr concurrently (draining both avoids a
    * pipe-buffer stall) WITHOUT failing on a non-zero exit — the tools signal findings via exit
    * code and we want the report either way.
    */
  private def runCapture(command: Command): IO[Throwable, RiftTools.RawRun] =
    for
      proc <- command.run
      outs <- proc.stdout.string <&> proc.stderr.string
      code <- proc.exitCode
    yield RiftTools.RawRun(outs._1, outs._2, code.code)

object RiftTools:
  /** Build the tools from the directory that scripts/build-rift.sh populated. */
  def fromBuildDir(buildDir: Path): RiftTools =
    RiftTools(
      buildDir.resolve("rift-lint"),
      buildDir.resolve("rift-verify"),
      buildDir.resolve("rift")
    )

  /** Captured process output; kept whole so a failing gate can quote exactly what the tool said. */
  final case class RawRun(stdout: String, stderr: String, exitCode: Int)

  /** Pure parse of `rift-lint -o json` output — exposed for fast, process-free tests. stderr is
    * folded into the failure message so a tool crash (a panic goes to stderr) is never lost.
    */
  private[rift] def parseLint(r: RawRun): Either[String, LintReport] =
    r.stdout
      .fromJson[LintJson]
      .left
      .map(msg => s"unparseable rift-lint JSON: $msg${diagnostics(r)}")
      .map(j => LintReport(j.errors, j.warnings, j.files_checked, j.issues, r.exitCode, r.stdout))

  /** Pure parse of the `rift-verify -o json` summary (rift#347: JSON on stdout) — exposed for fast,
    * process-free tests.
    */
  private[rift] def parseVerify(r: RawRun): Either[String, VerifyReport] =
    r.stdout
      .fromJson[VerifyJson]
      .left
      .map(msg => s"could not parse rift-verify JSON: $msg${diagnostics(r)}")
      .map(j =>
        VerifyReport(
          j.passed,
          j.failed,
          j.skipped,
          r.exitCode,
          r.stdout,
          failLines(r.stderr),
          r.stderr
        )
      )

  /** The per-test `FAIL` lines plus the indented reason that follows each one.
    *
    * Matched on the tool's own ` FAIL ` prefix rather than the word anywhere, so `PASS` rows and
    * the `Failed: N` summary line never masquerade as detail.
    */
  private def failLines(stderr: String): List[String] =
    val lines = stderr.linesIterator.toList
    // A continuation is an indented line that is not itself a verdict or summary row. Checking only
    // "not FAIL" would swallow the NEXT stub's PASS row into this stub's reason, which is precisely
    // what the first run of this instrumentation did.
    def isVerdictRow(l: String): Boolean =
      val t = l.trim
      Verdicts.exists(t.startsWith) || t.startsWith("Imposter:")
    lines.zipWithIndex.collect {
      case (l, i) if l.trim.startsWith("FAIL ") =>
        val reason =
          lines.drop(i + 1).takeWhile(n => n.startsWith(" ") && !isVerdictRow(n)).map(_.trim)
        (l.trim :: reason).mkString(" — ")
    }

  private val Verdicts = List("FAIL ", "PASS ", "SKIP ", "Failed:", "Passed:", "Skipped:")

  /** The error-severity findings, one per line as `<file> <code>[@location]` — this is the "names
    * the offending file" contract the consistency gate relies on. Pure, so it is unit-tested.
    */
  def errorOffenders(report: LintReport): List[String] =
    report.issues
      .filter(_.severity == "error")
      .map { i =>
        val file = i.file.substring(i.file.lastIndexOf('/') + 1)
        s"$file ${i.code}${i.location.fold("")(l => s"@$l")}"
      }
      .distinct

  private def diagnostics(r: RawRun): String =
    s" (exit ${r.exitCode})\nstdout:\n${r.stdout}\nstderr:\n${r.stderr}"

  private final case class LintJson(
      issues: List[LintIssue],
      files_checked: Int,
      errors: Int,
      warnings: Int
  )
  private object LintJson:
    given JsonDecoder[LintJson] = DeriveJsonDecoder.gen[LintJson]

  // `rift-verify -o json` emits more fields (imposters, stubs, tests); we only need the tallies,
  // and zio-json ignores the extras.
  private final case class VerifyJson(passed: Int, failed: Int, skipped: Int)
  private object VerifyJson:
    given JsonDecoder[VerifyJson] = DeriveJsonDecoder.gen[VerifyJson]
