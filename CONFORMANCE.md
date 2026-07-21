# Rift Conformance — Standing Charter & Pass Log

This repository **is** the standing conformance suite for Rift. It supersedes the former tracking
issue [`achird-labs/rift#254`](https://github.com/achird-labs/rift/issues/254) (a
GitHub-issue-based runbook that pointed at the retired `rift-imposter-samples` + `conformance.sh`
shell flow) — the runbook, the coverage/finding ledgers, and the pass history now all live in-repo,
so there is no open issue to "keep doing."

The **engine is the source of truth.** Both planes exercise it as a downstream consumer:

- **Plane A** (`samples.rift.*`) drives the admin API directly and shells out to
  `rift-lint`/`rift-verify` — no `MockControl` in the path, so a red here is an **engine or tool**
  finding.
- **Plane B** (`samples.mock.*`) drives Rift *through* the zio-bdd `MockControl` adapter on four
  backends (`wiremock` / `rift` / `rift-external` / `embedded`) — a red here with Plane A green is a
  **library** finding.

## Runbook

Run the standing pass with the **`/rift-conformance [scope]`** command
(`.claude/commands/rift-conformance.md`). Scopes: `full` (default) · `gate` · `ext` · `mock` ·
`verify #NNN`. The engine is fetched as a published release by `scripts/build-rift.sh` (pinned by
`RIFT_VERSION`, default `0.14.0`); the corpus is vendored in-repo under `corpus/`. No sibling
checkout is needed.

## Ledgers

- **[COVERAGE.md](COVERAGE.md)** — feature → scenario coverage matrix + checkpoint audit trail.
- **[FINDINGS.md](FINDINGS.md)** — one row per finding (plane-annotated backend, tripping
  sample/scenario, filed issue link, status).
- **[DESIGN.md](DESIGN.md)** — architecture, the triage protocol (§8), and the risk register (§12).

## Filing findings

One finding → one issue, confirmed against real behavior with `curl` before filing:

- **Engine / tool** findings → file on **`achird-labs/rift`**.
- **Library** findings → file on **`zio-bdd`**.

Reference **this repository** (`EtaCassiopeia/rift-conformance`) — ideally the specific
`FINDINGS.md` row or failing scenario — for context, so a maintainer can trace the finding back to
the pass that caught it. (This replaces the old "reference rift#254" convention.)

## Pass log

Append one row per standing pass — newest last. This is the audit trail that the closed issue #254
used to carry in prose.

| Date | Scope | Engine | Result | Findings |
|------|-------|--------|--------|----------|
| 2026-07-08 | `full` | 0.11.3 | ✅ all green — Plane A: external, embedded (JDK 25), container (`RIFT_IT=1`); Plane B: wiremock, rift-external, embedded (JDK 25) | none |
| 2026-07-10 | `scripting+gate` | 0.12.0 | ✅ green (0.12.0 bump + Script API v2 migration) — `Test/compile`; rift-lint 0 errors/15 files; ScriptingBounds04Suite 4/4 + ConsistencyGateSuite 5/5 (incl. `@full-corpus` + rift-verify) external; Scripting14Suite 1/1 rift-external. Full external/embedded/container + Plane-B sweep not re-run this pass | none in scope; new-capability coverage gaps + container `@full-corpus` re-enable logged in COVERAGE.md |
| 2026-07-11 | `full (ext + embedded + container CI)` | 0.13.1 | ✅ green (0.13.1 + zio-bdd 1.4.2 pin bump) — `Test/compile` + `scalafmtCheckAll` clean on 1.4.2; rift-lint 0 errors/15 files. **external:** ScriptingBounds04 4/4 + Gate 5/5 (incl. `@full-corpus` + rift-verify) + Scripting14 1/1 rift-external. **embedded (FFM, JDK 25):** full `engineEmbedded` green — 01–08/11/12 + zio-test specs. **container:** green in CI. Direct smoke confirms the new timeout contract | **(1) Conformed behavior change:** rift 0.13.0 refined the script-timeout contract — status `500 → 504 Gateway Timeout` + new `x-rift-script-timeout: true` header (keeps `x-rift-script-error: true`, rift#499/#515); `04-scripting-bounds` updated. **(2) Embedded interaction:** rift 0.13.0 gated `serve_admin` injection behind an `allowInjection` option defaulting to `false` (rift#492/#511) — the embedded bridge (`EmbeddedAdmin.serve`) now opts in, matching external mode's `--allowInjection`. Both intended refinements, not defects. New-capability gaps for 0.13.0 tracked in COVERAGE.md / issue #72 |
| 2026-07-16 | `gate+scripting (ext)` | 0.14.0 | ✅ green (**0.13.1 → 0.14.0** pin bump; zio-bdd stays 1.4.2) — `scalafmtCheckAll` (14) + `Test/compile` (62) clean; 0.14.0 release sha256-verified. **external:** ConsistencyGateSuite **5/5** — version coherence (BUILD_INFO↔binaries all 0.14.0), rift-lint **0 errors**, `@full-corpus` serve, rift-verify **0 failures** (no tool drift at 0.14.0); ScriptingBounds04Suite **16/16** (the error-door `body contains "…"` substring asserts survive the envelope change). Full external sweep (02/05–12), embedded, container, Plane B **not re-run this pass** — CI covers them | **No conforming code change needed.** 0.14.0's four black-box behavior changes are non-breaking here: the error-body envelope moved `{"error":…}` → `{"errors":[{code,message}]}` + added `Content-Type: application/json` (rift#611 sweep), but scenarios assert marker headers + body *substrings* (still in `message`); `POST /intercept/rules` now gates `inject` (rift#657/#659) but feature 12 uses `serve`/`forward` rules; query-param-name decoding (#619) and `DELETE` teardown-await (#597) aren't asserted yet. **Engine-version split:** binary legs → 0.14.0; embedded natives stay 0.13.1 (zio-bdd 1.4.2, ABI-safe — no 0.14.0 FFI bump). All 0.13.2–0.14.0 behavior changes + new capabilities logged as coverage opportunities in COVERAGE.md / #72; #113 (declarative intercept block) now **unblocked** by rift#655/#658 shipping in 0.14.0 |
