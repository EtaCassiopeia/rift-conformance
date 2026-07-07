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

/** Parsed `rift-verify -o json` summary. `raw`/`exitCode` preserved for failure messages. */
final case class VerifyReport(
    passed: Int,
    failed: Int,
    skipped: Int,
    exitCode: Int,
    raw: String
)

/** Typed shell-outs to rift-lint and rift-verify — the SAME binaries built alongside the engine, so
  * lint/verify judgements always come from the commit under test (version coherence, DESIGN §6).
  */
final case class RiftTools(lint: Path, verify: Path):

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
    RiftTools(buildDir.resolve("rift-lint"), buildDir.resolve("rift-verify"))

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
      .map(j => VerifyReport(j.passed, j.failed, j.skipped, r.exitCode, r.stdout))

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
