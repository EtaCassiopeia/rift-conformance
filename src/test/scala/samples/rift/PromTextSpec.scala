package samples.rift

import samples.rift.support.PromText
import samples.rift.support.PromText.Series
import zio.test.*

/** Offline spec for the feature-17 exposition parser (#192): no engine needed. */
object PromTextSpec extends ZIOSpecDefault:

  private val text =
    """# HELP rift_requests_total Requests served
      |# TYPE rift_requests_total counter
      |rift_requests_total{method="GET",status="200"} 5
      |rift_requests_total_created{method="GET",status="200"} 1.7e9
      |rift_imposters_total 3
      |rift_latency_injected_ms_bucket{rule_id="4880",le="+Inf"} 2
      |""".stripMargin

  def spec = suite("PromText")(
    test("keys series by exact name and label set, never folding in a _created sibling") {
      val p = PromText.parse(text)
      assertTrue(
        p.map(_.get(Series("rift_requests_total", Map("method" -> "GET", "status" -> "200")))) ==
          Right(Some(5.0)),
        p.map(_.get(Series("rift_imposters_total", Map.empty))) == Right(Some(3.0)),
        p.map(_.series.size) == Right(4)
      )
    },
    test("a malformed sample line is an error, not a dropped series") {
      assertTrue(PromText.parse("rift_x{a=\"1\"} notanumber").isLeft)
    },
    test("a selector takes unquoted label values in any order") {
      assertTrue(
        PromText.selector("rift_faults_injected_total{type=tcp,rule_id=4882}") ==
          Right(Series("rift_faults_injected_total", Map("type" -> "tcp", "rule_id" -> "4882"))),
        PromText.selector("rift_imposters_total") == Right(
          Series("rift_imposters_total", Map.empty)
        ),
        PromText.selector("rift_x{a}").isLeft,
        PromText.selector("rift_x{a=1").isLeft
      )
    }
  )
