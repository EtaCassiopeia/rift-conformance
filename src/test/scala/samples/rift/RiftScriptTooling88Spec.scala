package samples.rift

import samples.rift.support.RiftTools
import zio.*
import zio.test.*

import java.nio.file.{Path, Paths}

/** Acceptance gate for the `rift script` tooling (rift#445, #88): the `check` and `run` subcommands
  * validate/execute a script **without** a running server. `RiftTools` gains the `riftScriptCheck`
  * / `riftScriptRun` seams (the same released `rift` binary as the engine, so the judgement comes
  * from the commit under test). This drives:
  *   - `rift script check` on a valid script → exit 0, "OK";
  *   - `rift script check` on a syntactically broken script → non-zero, a "FAILED" error report;
  *   - `rift script run` on a valid script → the debug-mode trace (the `respond` decision,
  *     printed);
  *   - the `run` options `--request`, `--state`, `--flow-id` and `--engine` (#204).
  *
  * Self-gating: a no-op pass when `RIFT_BUILD_DIR` is unset (no binaries to drive), mirroring the
  * other tool gates. No engine is spawned — `script check`/`run` are offline.
  */
object RiftScriptTooling88Spec extends ZIOSpecDefault:

  private def tools: Option[RiftTools] =
    sys.env.get("RIFT_BUILD_DIR").map(d => RiftTools.fromBuildDir(Paths.get(d)))

  private val scriptsDir: Path = Paths.get("src/test/resources/rift-scripts")
  private def valid: Path = scriptsDir.resolve("greet.rhai")
  private def invalid: Path = scriptsDir.resolve("invalid.rhai")

  private def whenTools(f: RiftTools => Task[TestResult]): Task[TestResult] =
    tools match
      case None => ZIO.succeed(assertCompletes)
      case Some(t) => f(t)

  def spec = suite("rift script tooling (#88)")(
    test("rift script check accepts a valid script") {
      whenTools { t =>
        t.riftScriptCheck(valid).map { r =>
          assertTrue(r.exitCode == 0, (r.stdout + r.stderr).contains("OK"))
        }
      }
    },
    test("rift script check rejects a syntactically broken script") {
      whenTools { t =>
        t.riftScriptCheck(invalid).map { r =>
          assertTrue(r.exitCode != 0, (r.stdout + r.stderr).contains("FAILED"))
        }
      }
    },
    test("rift script run prints the respond decision (debug trace)") {
      whenTools { t =>
        t.riftScriptRun(valid).map { r =>
          val out = r.stdout + r.stderr
          // Tie to greet.rhai's actual decision (`http(200, "from-file-lib")`), not just any label,
          // so a regression to a boilerplate trace can't pass.
          assertTrue(r.exitCode == 0, out.contains("decision:"), out.contains("from-file-lib"))
        }
      }
    },
    // `rift script run` options (#204; rift docs/configuration/cli.md § script).
    test("rift script run --request feeds the script the given request") {
      whenTools { t =>
        t.riftScriptRun(
          scriptsDir.resolve("echo-method.rhai"),
          List("--request", scriptsDir.resolve("post-request.json").toString)
        ).map(r => assertTrue(r.exitCode == 0, r.stdout.contains("body=\"method=POST\"")))
      }
    },
    test("rift script run --state seeds JSON-parsed flow state") {
      whenTools { t =>
        t.riftScriptRun(scriptsDir.resolve("incr-state.rhai"), List("--state", "n=5"))
          .map(r =>
            assertTrue(
              r.exitCode == 0,
              r.stdout.contains("body=\"n=6\""),
              r.stdout.contains("n = 6")
            )
          )
      }
    },
    test("rift script run --flow-id sets ctx.flowId") {
      whenTools { t =>
        t.riftScriptRun(scriptsDir.resolve("flow-id.rhai"), List("--flow-id", "f9"))
          .map(r => assertTrue(r.exitCode == 0, r.stdout.contains("body=\"flow=f9\"")))
      }
    },
    test("rift script run --engine js runs a file whose extension names no engine") {
      whenTools { t =>
        val script = scriptsDir.resolve("js-body.txt")
        for
          inferred <- t.riftScriptRun(script)
          explicit <- t.riftScriptRun(script, List("--engine", "js"))
        yield assertTrue(
          inferred.exitCode != 0,
          (inferred.stdout + inferred.stderr).contains("cannot infer script engine"),
          explicit.exitCode == 0,
          explicit.stdout.contains("body=\"js-engine\"")
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
