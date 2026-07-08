package samples.mock

import zio.*
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps
import zio.bdd.mock.*
import zio.bdd.mock.dsl.*
import samples.mock.support.{Backends, MockState, MockSupport}

/** 14 — scripting (capability Scripting): a backend script computes the response from the request.
  * Rift only (WireMock advertises no scripting engine).
  */
@Suite(
  featureDirs = Array("src/test/resources/features/mock/14-scripting.feature"),
  reporters = Array("pretty"),
  scenarioParallelism = 1
)
object Scripting14Suite extends ZIOSteps[MockControl, MockState] with MockSupport[MockState]:

  // Rhai script that echoes the request method into the body — a static stub
  // couldn't do this, so a passing round-trip proves the engine ran the script.
  private val rhaiEchoMethod =
    "fn should_inject(request, flow_store) { #{inject: true, fault: \"error\", status: 200, " +
      "body: `scripted-${request.method}`, headers: #{\"Content-Type\": \"text/plain\"}} }"

  Given("the backend supports scripting") {
    requiring(Capability.Scripting)
  }

  Given("a " / string / " script echoing the method for GET " / string) {
    (engine: String, path: String) =>
      val eng = engine match
        case "Rhai" => ScriptEngine.Rhai
        case "Lua" => ScriptEngine.Lua
        case "JavaScript" => ScriptEngine.JavaScript
        case other => throw new IllegalArgumentException(s"unknown engine '$other'")
      for
        space <- activeSpace
        sc <- ZIO
          .serviceWithZIO[MockControl](_.scripting)
          .mapError(u => new RuntimeException(u.message))
        _ <- sc
          .inject(space, get(path), Script(eng, rhaiEchoMethod))
          .mapError(e => new RuntimeException(s"$e"))
      yield ()
  }

  override def environment: ZLayer[Any, Throwable, MockControl] = Backends.mockControl
