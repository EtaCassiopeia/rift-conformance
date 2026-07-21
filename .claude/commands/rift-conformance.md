---
description: Standing Rift conformance pass — fetch the engine, run both attribution planes of this harness, triage any red per DESIGN.md §8, and update COVERAGE.md/FINDINGS.md for gaps.
argument-hint: "[scope: full | gate | ext | mock | verify #NNN]"
---

# Rift standing conformance pass

You are running the repeatable, self-driving conformance loop for **Rift** using **this repo's**
two-plane zio-bdd harness — not the retired `rift-imposter-samples` shell suite. The **engine is
the source of truth**; both planes exercise it as a downstream consumer:

- **Plane A** (`samples.rift.*`, `src/test/resources/features/rift/`) drives the admin API
  directly and shells out to `rift-lint`/`rift-verify`. No `MockControl` in the path — a red here
  is an **engine or tool** finding.
- **Plane B** (`samples.mock.*`, `src/test/resources/features/mock/`) drives Rift *through* the
  zio-bdd `MockControl` adapter on four backends (`wiremock` / `rift` / `rift-external` /
  `embedded`). A red here with Plane A green is a **library** finding.

This repo is self-contained: the corpus (`corpus/imposters/*.json`, `corpus/data/products.csv`,
`corpus/fixtures/injection/*`) is vendored in-repo, and the engine is fetched as a published
release by `scripts/build-rift.sh` — no sibling checkout of `rift`, `rift-imposter-samples`, or
`zio-bdd-samples` is needed. See **README.md** for the environment-knob matrix and **DESIGN.md**
for the architecture and risk register.

**Scope for this run:** `$ARGUMENTS` (empty ⇒ `full`). Interpret it as one of:

| Scope | Meaning |
|---|---|
| `full` (default) | both planes, every leg `scripts/run-all.sh` runs |
| `gate` | just `sbt engineGate` (feature 01, the consistency gate — cheapest inner loop) |
| `ext` | Plane A suites 03–10 (`samples.rift.*` beyond the gate and core serving — faults, scripting, templating, behaviors, spaces/FSM, admin, gateway/HTTPS/metrics, proxy/record) |
| `mock` | the Plane B legs (`mockConformance` wiremock, `mockEmbedded`, `mockRiftExternal`, and the Docker-gated `rift` container leg if `RIFT_IT=1`) |
| `verify #NNN` | the `@gap` flip workflow — validate a specific fix/finding end-to-end and flip its pinned-divergence scenario if the fix landed |

Guiding principles:
- **The corpus is the gate.** A green Plane A run (especially feature 01) is evidence; a red one
  is a finding.
- **Engine vs a tool ⇒ the tool is the finding.** `rift-lint`/`rift-verify` disagreeing with what
  the engine actually serves is TOOL-DRIFT, not an engine bug — feature 01 exists to catch this.
- **`@gap` scenarios assert current (possibly wrong) behavior on purpose.** A `@gap` scenario
  turning red means the pinned divergent behavior *changed* — that's the trigger for `verify #NNN`.
- **Corpus stays static & numbered.** Add new `corpus/imposters/NN-*.json` files, never renumber;
  `20-mimeo-solo-compat.json` stays (engine-served shapes, still gated).
- **One finding → one issue**, filed on `achird-labs/rift` (engine/tool findings) or `zio-bdd`
  (library findings), referencing **this repo** (`EtaCassiopeia/rift-conformance`; see
  `CONFORMANCE.md`). Confirm against real behavior with `curl` before filing — no speculative issues.

---

## Step 1 — Fetch the engine

```bash
eval "$(scripts/build-rift.sh)"     # exports RIFT_BUILD_DIR; cache hit if already fetched
```

`RIFT_VERSION` (default `0.13.1`) pins the release; `RIFT_BUILD_CACHE` controls the cache root.
Never trust a stale `RIFT_BUILD_DIR` from a previous session when validating a fix — re-run this
if in doubt, or unset `RIFT_BUILD_DIR` first to force a fresh cache check.

## Step 2 — Run the scope

```bash
# full
scripts/run-all.sh                                    # both planes, layered summary

# gate
sbt engineGate                                        # feature 01 only

# ext (Plane A suites 03-10, beyond gate + core serving)
sbt "testOnly samples.rift.FaultsTransport03Suite samples.rift.ScriptingBounds04Suite \
  samples.rift.Templating05Suite samples.rift.Behaviors06Suite samples.rift.SpacesFsm07Suite \
  samples.rift.AdminSurface08Suite samples.rift.GatewayHttpsMetrics09Suite \
  samples.rift.ProxyRecord10Suite"

# mock (Plane B, both no-Docker legs + wiremock; add the container leg if RIFT_IT=1)
sbt mockConformance
MOCK_BACKEND=rift-external sbt mockRiftExternal
MOCK_BACKEND=embedded sbt mockEmbedded                # needs JDK 22+ (FFM); skip if unavailable
[ "${RIFT_IT:-0}" = 1 ] && scripts/conformance-rift.sh
```

No `RIFT_CORPUS_ROOT` needs to be set — it defaults to `./corpus`, vendored in this repo.

### `verify #NNN`

1. Find the scenario(s) tagged `@gap` (or the finding row in FINDINGS.md) referencing `#NNN`.
2. Re-run just that suite (or `sbt engineConformance -- --include-tags gap` style selection if
   the runner supports tag filters; otherwise the smallest `testOnly` that covers it).
3. If the fix landed: flip the scenario from asserting the wrong behavior to asserting the
   correct one, remove the `@gap` tag, and update COVERAGE.md's note + FINDINGS.md's status to
   "fixed & verified".
4. If it's still open, leave the scenario as-is (pinning current behavior) and note the
   verification attempt/date in FINDINGS.md.

## Step 3 — Triage any red (DESIGN.md §8)

```
Scenario red
├─ Plane A?
│   ├─ Re-run the emitted curl repro (every Plane A HTTP step logs one on `Stage`) against the
│   │   harness adminUrl.
│   │   ├─ Engine misbehaves                          → ENGINE finding → file on rift, ref this repo.
│   │   ├─ Engine fine, rift-lint/rift-verify disagreed → TOOL-DRIFT finding → file on rift.
│   │   └─ Engine fine, curl matches expectation       → corpus/step bug → fix here, no issue.
│   └─ A `@gap` scenario started failing → the pinned divergent behavior CHANGED: either the fix
│       landed (see `verify #NNN` above) or it's an unannounced behavior change — investigate,
│       possibly file.
└─ Plane B (wiremock / rift / rift-external / embedded)?
    ├─ Same behavior green in Plane A  → LIBRARY finding → file on zio-bdd.
    ├─ Also red in Plane A             → engine finding (Plane A owns it, not a separate filing).
    └─ Red on one backend only         → localize: wiremock-only → SPI/portability gap;
                                          embedded-only → FFI adapter gap; container-only →
                                          managed-container path gap.
```

For each real finding:
1. Reproduce minimally — the smallest imposter/config + `curl` that trips it.
2. Classify per the tree above.
3. File one issue per finding with the tripping config/sample, the `curl` repro, observed vs
   expected, and which leg/tool caught it. `gh issue create --repo achird-labs/rift` (engine/tool)
   or the `zio-bdd` repo (library), referencing this repo (`EtaCassiopeia/rift-conformance`).
4. If the fix is yours to make upstream, hand off to that repo's own `/fix-issue <N>`, then
   re-fetch (Step 1, bump `RIFT_VERSION` once a new release ships) and re-run the affected leg.

## Step 4 — Update the ledgers

- **COVERAGE.md**: if you added a scenario, add its row to the relevant feature's port-audit
  table (shell assert → scenario name, or "new" if there's no shell precedent); if a checkpoint
  changed status, update the checkpoint table.
- **FINDINGS.md**: add one row per new finding (plane-annotated backend, tripping
  sample/scenario, filed issue link, status), or flip an existing row's status on `verify #NNN`.
- Both files' F-series/checkpoint history stays as a static audit trail — only append rows or
  flip statuses, never rewrite history.

## Step 5 — Report

Summarize: legs run + pass/fail/gap counts per plane, scenarios added (feature + names), findings
filed (issue numbers + classification + which repo), ledger updates (COVERAGE.md/FINDINGS.md rows
touched), and the next-highest-value gap left (check DESIGN.md §12's risk register and any open
`@gap` scenarios).

---

## Hard constraints

- **Do not push and do not open PRs** unless explicitly asked. Filing GitHub **issues** (on
  `rift` or `zio-bdd`) for findings is expected and in-scope.
- **Keep feature 01 (the consistency gate) green** — any new static `corpus/imposters/NN-*.json`
  must be served by the engine, lint with 0 errors, and draw no `rift-verify` FAIL.
- **Corpus stays static & numbered** — add new samples, never renumber existing ones.
- **One finding → one issue.** Don't bundle unrelated gaps. Reference this repo
  (`EtaCassiopeia/rift-conformance`) on engine/tool issues.
- Confirm a finding against real behavior with `curl` before filing — no speculative issues.
