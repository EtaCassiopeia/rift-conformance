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
    // #126: a red gate must name the stub and the reason, not just a count. rift-verify's
    // human-readable detail goes to STDERR in `-o json` mode (rift#347), so the parse must lift the
    // per-test FAIL lines out of stderr into the report.
    test("parseVerify lifts the per-test FAIL detail out of stderr") {
      val out = """{"failed":2,"imposters":15,"passed":89,"skipped":35,"stubs":70,"tests":70}"""
      val err =
        """|Imposter: 03 · Behaviors (wait, repeat, decorate, copy, lookup) (port 4503)
           |   PASS Stub #2 - GET /retry-me -> 503 (4ms)
           |   FAIL Stub #1 - GET /slow-range
           |      expected status 200, got 000 (timeout after 10s)
           |   FAIL Stub #0 - GET /slow
           |  Failed:  2
           |""".stripMargin
      val r = RiftTools.parseVerify(RiftTools.RawRun(out, err, 1))
      assertTrue(r.isRight) &&
      assertTrue(r.toOption.exists(_.failed == 2)) &&
      // Both failing stubs are named...
      assertTrue(r.toOption.exists(_.failDetail.exists(_.contains("Stub #1 - GET /slow-range")))) &&
      assertTrue(r.toOption.exists(_.failDetail.exists(_.contains("Stub #0 - GET /slow")))) &&
      // ...and the PASS noise is not carried along.
      assertTrue(r.toOption.exists(!_.failDetail.exists(_.contains("PASS")))) &&
      assertTrue(r.toOption.exists(_.failDetail.size == 2)) &&
      // The indented reason attaches to the stub it belongs to...
      assertTrue(
        r.toOption.exists(_.failDetail.head.contains("expected status 200, got 000"))
      ) &&
      // ...and the next stub's entry neither inherits it nor absorbs the `Failed:` summary row —
      // the first cut of this instrumentation appended the following verdict row as the reason.
      assertTrue(r.toOption.exists(!_.failDetail.last.contains("expected status"))) &&
      assertTrue(r.toOption.exists(!_.failDetail.exists(_.contains("Failed:"))))
    },
    // rift-verify puts the reason on the FAIL line itself for a `_verify` mismatch, and on the
    // following indented line for an auto-derived test. Both shapes must survive, and neither may
    // absorb the next verdict row.
    test("parseVerify keeps a same-line reason and does not absorb the next verdict") {
      val out = """{"failed":1,"imposters":1,"passed":1,"skipped":0,"stubs":2,"tests":2}"""
      val err =
        """|   FAIL stub #0 _verify[0] GET /slow — body "x" does not contain "nope"
           |   PASS stub #1 _verify[0] GET /slow-range
           |""".stripMargin
      val r = RiftTools.parseVerify(RiftTools.RawRun(out, err, 1))
      assertTrue(r.toOption.exists(_.failDetail.size == 1)) &&
      assertTrue(r.toOption.exists(_.failDetail.head.contains("does not contain"))) &&
      assertTrue(r.toOption.exists(!_.failDetail.head.contains("PASS")))
    },
    test("parseVerify reports no fail detail on a green run") {
      val out = """{"failed":0,"imposters":1,"passed":3,"skipped":0,"stubs":3,"tests":3}"""
      val r = RiftTools.parseVerify(RiftTools.RawRun(out, "   PASS Stub #0 - GET /hi -> 200\n", 0))
      assertTrue(r.toOption.exists(_.failDetail.isEmpty))
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
