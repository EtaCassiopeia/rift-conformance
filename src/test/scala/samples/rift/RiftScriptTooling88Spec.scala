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
  *     printed).
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
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
