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

/** Parsed rift-verify "Verification Summary". `raw`/`exitCode` preserved for failure messages. */
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

  /** `rift-lint -o json <path>`. rift-lint prints a coloured banner before the JSON body, so the
    * output is sliced from its first `{`; counts come from the tool's own summary, not a re-count.
    */
  def riftLint(path: Path): IO[Throwable, LintReport] =
    runCapture(Command(lint.toString, "-o", "json", path.toString)).flatMap { r =>
      ZIO.fromEither(RiftTools.parseLint(r)).mapError(new RuntimeException(_))
    }

  /** `rift-verify --admin-url <url> --skip-dynamic --verify-dynamic` against a running engine. */
  def riftVerify(adminUrl: String): IO[Throwable, VerifyReport] =
    runCapture(
      Command(verify.toString, "--admin-url", adminUrl, "--skip-dynamic", "--verify-dynamic")
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
    val brace = r.stdout.indexOf('{')
    if brace < 0 then Left(s"rift-lint emitted no JSON${diagnostics(r)}")
    else
      r.stdout
        .substring(brace)
        .fromJson[LintJson]
        .left
        .map(msg => s"unparseable rift-lint JSON: $msg${diagnostics(r)}")
        .map(j => LintReport(j.errors, j.warnings, j.files_checked, j.issues, r.exitCode, r.stdout))

  /** Pure parse of the rift-verify "Verification Summary" — exposed for fast, process-free tests.
    */
  private[rift] def parseVerify(r: RawRun): Either[String, VerifyReport] =
    val clean = stripAnsi(r.stdout)
    (count("Passed", clean), count("Failed", clean), count("Skipped", clean)) match
      case (Some(p), Some(f), Some(s)) => Right(VerifyReport(p, f, s, r.exitCode, r.stdout))
      case _ => Left(s"could not parse rift-verify summary${diagnostics(r)}")

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

  // A real SGR sequence is ESC '[' … 'm'. ESC is written as the regex hex escape \x1b so the
  // source stays plain ASCII — an embedded raw ESC byte is invisible in editors and diffs.
  private val ansi = "\\x1b\\[[0-9;]*m".r
  private def stripAnsi(s: String): String = ansi.replaceAllIn(s, "")
  private def count(label: String, text: String): Option[Int] =
    s"(?m)^\\s*$label:\\s*(\\d+)".r.findFirstMatchIn(text).map(_.group(1).toInt)
