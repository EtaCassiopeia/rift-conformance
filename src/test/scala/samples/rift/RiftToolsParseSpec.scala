package samples.rift

import samples.rift.support.*
import zio.test.*

/** Fast, process-free tests for the RiftTools output parsers — they exercise the failure branches
  * and the ANSI stripping that the real-tool integration test (RiftToolsSpec) cannot easily force.
  */
object RiftToolsParseSpec extends ZIOSpecDefault:

  private val esc = 27.toChar.toString // ESC, without an invisible byte in the source

  def spec = suite("RiftTools parsing")(
    test("parseLint reads counts + a populated issue from banner-prefixed JSON") {
      val out =
        s"${esc}[36mRift Imposter Linter${esc}[0m\nScanning: /x\n" +
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
    test("parseVerify strips ANSI around the label and reads Passed/Failed/Skipped") {
      val out =
        s"Verification Summary\n  ${esc}[32mPassed:${esc}[0m  5\n  Failed:  0\n  Skipped: 2\n"
      val r = RiftTools.parseVerify(RiftTools.RawRun(out, "", 0))
      assertTrue(r.isRight) &&
      assertTrue(r.toOption.exists(_.passed == 5)) &&
      assertTrue(r.toOption.exists(_.failed == 0)) &&
      assertTrue(r.toOption.exists(_.skipped == 2))
    },
    test("parseVerify fails when the summary is missing") {
      val r = RiftTools.parseVerify(RiftTools.RawRun("some unrelated output", "boom", 1))
      assertTrue(r.isLeft) && assertTrue(r.swap.exists(_.contains("could not parse")))
    }
  )
