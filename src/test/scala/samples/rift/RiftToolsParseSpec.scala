package samples.rift

import samples.rift.support.*
import zio.test.*

/** Fast, process-free tests for the RiftTools JSON parsers — they exercise the failure branches
  * (malformed / empty output, panic-on-stderr) that the real-tool integration test (RiftToolsSpec)
  * cannot easily force.
  */
object RiftToolsParseSpec extends ZIOSpecDefault:

  def spec = suite("RiftTools parsing")(
    test("parseLint reads counts + a populated issue from the JSON stdout") {
      val out =
        """{"issues":[{"severity":"warning","code":"W004","message":"bad","file":"f.json","location":"stubs[0]","suggestion":"fix it"}],"files_checked":15,"errors":0,"warnings":1}"""
      val r = RiftTools.parseLint(RiftTools.RawRun(out, "", 0))
      assertTrue(r.isRight) &&
      assertTrue(r.toOption.exists(_.errors == 0)) &&
      assertTrue(r.toOption.exists(_.warnings == 1)) &&
      assertTrue(r.toOption.exists(_.filesChecked == 15)) &&
      assertTrue(r.toOption.exists(_.issues.head.code == "W004")) &&
      assertTrue(r.toOption.exists(_.issues.head.suggestion.contains("fix it")))
    },
    test("parseLint fails loudly and surfaces stderr when the tool emits no JSON") {
      val r = RiftTools.parseLint(RiftTools.RawRun("", "thread 'main' panicked at lint.rs", 101))
      assertTrue(r.isLeft) &&
      assertTrue(r.swap.exists(_.contains("panicked"))) &&
      assertTrue(r.swap.exists(_.contains("exit 101")))
    },
    test("parseVerify decodes the JSON summary (passed/failed/skipped; extra fields ignored)") {
      val out =
        """{"failed":0,"imposters":3,"passed":5,"skipped":2,"stubs":9,"tests":7}"""
      val r = RiftTools.parseVerify(RiftTools.RawRun(out, "", 0))
      assertTrue(r.isRight) &&
      assertTrue(r.toOption.exists(_.passed == 5)) &&
      assertTrue(r.toOption.exists(_.failed == 0)) &&
      assertTrue(r.toOption.exists(_.skipped == 2))
    },
    test("parseVerify fails when the output is not the JSON summary") {
      val r = RiftTools.parseVerify(RiftTools.RawRun("some unrelated output", "boom", 1))
      assertTrue(r.isLeft) && assertTrue(r.swap.exists(_.contains("could not parse")))
    },
    // Locks in the consistency gate's "names the offending file" RED-path contract.
    test("errorOffenders names error-severity files (with code/location) and drops non-errors") {
      val report = LintReport(
        errors = 1,
        warnings = 1,
        filesChecked = 2,
        issues = List(
          LintIssue(
            "error",
            "E010",
            "bad",
            "/corpus/imposters/99-broken.json",
            Some("stubs[0]"),
            None
          ),
          LintIssue("warning", "W004", "meh", "/corpus/imposters/20-ok.json", None, None),
          LintIssue("info", "I003", "fyi", "/corpus/imposters/04-x.json", None, None)
        ),
        exitCode = 1,
        raw = ""
      )
      val out = RiftTools.errorOffenders(report)
      assertTrue(out == List("99-broken.json E010@stubs[0]")) &&
      assertTrue(!out.exists(_.contains("20-ok.json"))) &&
      assertTrue(!out.exists(_.contains("04-x.json")))
    }
  )
