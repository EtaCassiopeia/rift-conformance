# rift-conformance

The dedicated conformance suite for [Rift](https://github.com/achird-labs/rift) — one
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) harness for the engine, fully self-contained:
no sibling checkout of `rift` or `rift-imposter-samples` is required. This README alone is enough
to fetch the engine and run everything.

> This repository is being wound down (#237, achird-labs/rift#1338): the Plane A specification and
> its runner are moving into the engine repo, and the repo is archived once nothing runs here.

## Plane A — engine conformance

`src/test/scala/samples/rift/`, `samples.rift.*`. Drives Rift's admin API directly (raw HTTP) and
shells out to the engine's own `rift-lint` / `rift-verify` tools. No `MockControl` in the path, so
a red here is an **engine or tool** finding, never a library finding. This is the typed-Gherkin
successor to the retired `rift-imposter-samples` bash suite — see COVERAGE.md for the full 1:1 port
audit. A red is confirmed with curl and filed on `rift` (ref this repo — see
[CONFORMANCE.md](CONFORMANCE.md)).

**Plane B** — the portable `MockControl` SPI corpus that drives Rift *through* the zio-bdd adapter
(WireMock / rift-external / embedded backends) — moved to
[EtaCassiopeia/zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) in zio-bdd#362 and runs there.

**[CONFORMANCE.md](CONFORMANCE.md)** is the standing-conformance charter — the runbook pointer,
ledger pointers, findings-filing convention, and the dated pass log (it replaces the retired
tracking issue `rift#254`). See **[DESIGN.md](DESIGN.md)** for the full architecture, risk register,
and the ordered implementation checklist. Coverage bookkeeping lives in **[COVERAGE.md](COVERAGE.md)**;
the defect ledger in **[FINDINGS.md](FINDINGS.md)**. The triage decision tree (DESIGN.md §8) is
reproduced at the top of FINDINGS.md.

## Repo layout

```
CONFORMANCE.md                         standing-conformance charter + pass log
DESIGN.md                              the implementation contract
COVERAGE.md · FINDINGS.md              coverage matrix + defect ledger
scripts/build-rift.sh                  fetches the released engine binaries (single source of artifacts)
scripts/run-all.sh                     one-command layered summary, every Plane A mode
scripts/sync-rift-examples.sh          refreshes corpus/rift-examples/ from rift's examples/ at the pin (`--check` verifies)
src/test/scala/samples/rift/           Plane A — engine conformance suites + RiftHarness
src/test/plane-a/                      Plane A spec tree (features/rift, rift-fixtures, rift-lint-fixtures, rift-scripts); RIFT_FEATURES_DIR replaces it
corpus/imposters/*.json                canonical JSON corpus (vendored in-repo, #16) — static, numbered, add-never-renumber
corpus/data/products.csv               CSV lookup data for feature 06 (`lookup` behavior)
corpus/fixtures/injection/*.cjs,*.js   JS injection modules for the scripting/decorate scenarios
corpus/rift-examples/*.json            rift's own shipped examples, vendored at the pin (#120) — see its PROVENANCE.md
```

## Plane A specification tree (`RIFT_FEATURES_DIR`)

The Plane A suites load their features and fixtures from the **test classpath**, from one root:
the in-repo `src/test/plane-a/` by default, or the directory `RIFT_FEATURES_DIR` names. The engine
repo takes ownership of that tree (achird-labs/rift#1338): it ships it as
`rift-conformance-<version>.tar.gz`, and pointing `RIFT_FEATURES_DIR` at it runs this runner
against the engine's own specification. The tree's layout is the contract:

```
features/rift/*.feature     one file per Plane A suite (each suite names its file: classpath:features/rift/NN-x.feature)
rift-fixtures/**            imposter JSON and other fixtures, loaded as /rift-fixtures/<name>.json
rift-lint-fixtures/**       rift-lint inputs (RiftToolsCli204Spec)
rift-scripts/**             the --scripts-dir library (RiftScriptTooling88Spec, FileScriptAuthoring87Spec)
```

Exactly one tree is on the classpath, never both: zio-bdd resolves a `classpath:` feature with
`getResources`, so two trees would run every feature twice. `PlaneATreeSpec`, part of
`sbt lintFeatures`, fails when a suite's feature is missing from the active tree or found twice; a
missing `classpath:` feature would otherwise run zero scenarios and pass.

```bash
RIFT_FEATURES_DIR=/path/to/rift/conformance sbt -batch lintFeatures
RIFT_FEATURES_DIR=/path/to/rift/conformance sbt -batch engineConformance
```

Run the two as separate invocations: `lintFeatures` sets dry-run for the rest of its sbt session,
so an `engineConformance` chained after it would never start an engine. `RIFT_FEATURES_DIR` is read
when sbt loads the build, so `reload` after changing it in a session.

### Features ported to the engine repo's Rust runner

The engine repo is moving these features to a Rust runner (`conformance/runner/`,
achird-labs/rift#1346…#1350), one batch at a time. [`conformance/ported.txt`](conformance/ported.txt)
names the feature files it owns. A suite whose features are all listed leaves `engineConformance`,
`engineEmbedded` and `lintFeatures` here, so the two runners never both own a feature in a mode both
run. It stays in `engineContainer` until the Rust runner has a container mode (achird-labs/rift#1467).
Each porting batch adds its file names here in the same release.

## Quickstart (cold start, nothing else checked out)

```bash
# 1. Fetch the released rift engine binaries (rift, rift-lint, rift-verify) — cached under
#    ~/.cache/rift-conformance, sha256-verified, BUILD_INFO written for version-coherence checks.
eval "$(scripts/build-rift.sh)"     # exports RIFT_BUILD_DIR into your shell

# 2. Run everything — every Plane A mode, layered summary, non-fatal SKIPs for gated legs.
scripts/run-all.sh
```

`scripts/run-all.sh` (equivalently `./scripts/run-all.sh --build` to fetch the engine first in
one step) runs, in order:

| Layer | Suites | Status |
|---|---|---|
| engine (Plane A) | `samples.rift.*` against a real rift child | runs |
| engine embedded (Plane A) | `engineEmbedded` against in-process Rift (Panama FFM) | SKIP below JDK 22 |
| engine container (Plane A) | `engineContainer` against a Rift Docker image | SKIP unless `RIFT_IT=1` (#12) |

Exit code is 0 iff the Plane A layer passed and no layer FAILed (SKIPs never fail the run).

### Running one leg at a time

```bash
sbt engineGate                                  # cheapest: just the consistency gate (feature 01)
sbt engineConformance                           # full Plane A: every samples.rift.* suite
RIFT_MODE=embedded scripts/conformance-engine.sh   # Plane A in-process (needs JDK 22+)
```

or the shell wrapper `scripts/conformance-engine.sh`, or the `Makefile`
targets (`make engine`, `make gate`, `make run-all`, `make fmt`, `make test`).

## Environment knobs

| Var | Default | Meaning |
|---|---|---|
| `RIFT_BUILD_DIR` | (fetched by `build-rift.sh`) | dir holding `rift`/`rift-lint`/`rift-verify` from a released rift build |
| `RIFT_VERSION` | `0.19.0` | released rift version `build-rift.sh` fetches |
| `RIFT_BUILD_CACHE` | `~/.cache/rift-conformance` | cache root for `build-rift.sh` downloads (SHA-keyed, idempotent) |
| `RIFT_MODE` | `external` | `external` (spawn the binary, no Docker) or `container` (image) — Plane A |
| `RIFT_CORPUS_ROOT` | `./corpus` | where the vendored JSON corpus / CSV data / fixtures live (imposters, data, fixtures subdirs) |
| `RIFT_IT` | unset | set to `1` to attempt the Docker-gated Plane A container leg (#12) |
| `RIFT_FFI_LIB` | (bundled) | `librift_ffi` path for the embedded Plane A leg |

`RIFT_CORPUS_ROOT` defaults to `./corpus` (relative to the sbt working directory) — the corpus
is vendored in this repo, so no other env var needs to be set to run Plane A. The engine is
spawned with cwd = `RIFT_CORPUS_ROOT`, so `corpus/data/products.csv` and `corpus/imposters/*.json`
both resolve correctly without extra configuration.

## sbt aliases

| Alias | What it runs |
|---|---|
| `engineGate` | `testOnly samples.rift.ConsistencyGateSuite` — feature 01 only, the cheap inner loop |
| `engineConformance` | `testOnly samples.rift.*` — every Plane A suite |
| `engineEmbedded` | the Plane A suites and specs the in-process (FFM) engine can run |
| `engineContainer` | the Plane A suites the container harness can drive |
| `lintFeatures` | `testOnly samples.rift.*Suite -- --dry-run` — dry-run step-matching validation |

## CI

`.github/workflows/conformance.yml` runs on every `push`/`pull_request`, on a **daily schedule**,
and on manual `workflow_dispatch`:

- **compile-and-format** — `Test/compile`, `scalafmtCheckAll`, `scalafmtSbtCheck`.
- **plane-a-engine** — fetches the released rift via `scripts/build-rift.sh`, then
  `sbt engineConformance` with `RIFT_MODE=external` and `RIFT_CORPUS_ROOT` pointed at the
  in-repo `./corpus`.
- **plane-a-embedded** — the same Plane A oracle in-process over Panama FFM, on JDK 25
  (`RIFT_MODE=embedded`).
- **plane-a-container** — the same Plane A oracle against the published image (`RIFT_IT=1`):
  every feature the container harness can drive (COVERAGE.md's feature × mode matrix).

The daily schedule exists because the subject of this suite is an **external** engine: triggering
only on harness commits meant a quiet repo showed green while rift moved six releases ahead. The
schedule also catches environment rot — a withdrawn release asset, a runner image change — that no
commit would have surfaced.

### Engine-drift watch

`.github/workflows/rift-release-watch.yml` runs daily and compares the `RIFT_VERSION` pin in
`scripts/build-rift.sh` against the newest published `achird-labs/rift` release:

- No newer release → nothing to do (and any open drift issue is closed automatically).
- Newer release → the **same** conformance pipeline is re-run against the candidate version via
  `workflow_call`, and a single `engine-drift`-labelled tracking issue is created or **updated in
  place** with the verdict. One issue, edited each run — not one per release — so it stays legible
  and closes itself when the pin catches up.

When the pin does move, run `scripts/sync-rift-examples.sh` as part of the bump: the shipped-examples
oracle (#120) replays a vendored copy of rift's `examples/`, and a stale copy would let it keep
passing against documents the engine no longer ships. `--check` reports drift without writing.

A red candidate turns the *release-watch* run red and nothing gates on that workflow, so it never
blocks the repo. (It is not a `continue-on-error` flag — GitHub does not allow one on a job that
calls a reusable workflow.)

The candidate verdict covers the **external** Plane A leg plus compile/format. The **container** leg
(pins a published image tag) and the **embedded** leg (runs zio-bdd's bundled `librift_ffi` native)
are both skipped for candidate runs, because `RIFT_VERSION` moves neither engine — including them
would report a green that says nothing about the candidate, or a red caused by pairing candidate
tools with an old native.

One caveat worth knowing: GitHub disables `schedule:` triggers after 60 days of repository
inactivity — the very quiet-repo case these exist for. GitHub emails before it does; re-enable from
the Actions tab, or push anything.

`scripts/rift-release-watch.py` holds the comparison logic and self-tests it
(`scripts/rift-release-watch.py --self-test`) — versions are compared numerically per component,
because lexicographically `"0.9.1" > "0.14.0"`, which would hide every 0.14.x release. Run it
locally any time to see where the pin stands.

## The `/rift-conformance` command

`.claude/commands/rift-conformance.md` drives the standing conformance pass against this
harness (fetch → run Plane A → triage per DESIGN.md §8 → update COVERAGE.md/FINDINGS.md).
See the command file for its scope grammar.
