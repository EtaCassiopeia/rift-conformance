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
`RIFT_VERSION`, default `0.18.1`); the corpus is vendored in-repo under `corpus/`. No sibling
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
| 2026-07-21 | `full Plane A (ext)` | 0.15.0 | ✅ green (**0.14.0 → 0.15.0** pin bump; zio-bdd stays 1.4.2) — `scalafmtCheckAll` + `scalafmtSbtCheck` clean; **all 14 Plane A features green** on the external leg, including `13-error-envelope` (the door-by-door `code` assertions from #118 pass unchanged). 0.15.0 release sha256-verified via `build-rift.sh`; the **container image also moves 0.13.1 → 0.15.0** (it had drifted behind the binary pin, and `build.sbt`'s comment claiming it tracked 0.14.0 was wrong — both corrected here). Embedded natives still lag at 0.13.1 (zio-bdd 1.4.2, ABI-safe — no 0.15.0 FFI bump) | **No conforming code change needed.** 0.15.0's headline change is *additive*: every error-envelope entry gains a `type` slug (rift#797 — filed from this harness during #118), and `code` is byte-identical on every door, which the suite verified empirically (`script error`/`invalid injection`/`imposter disabled` observed alongside unchanged `code` values). Body-`equals` quamina indexing is a prefilter with unchanged matching results; the per-core runtime and jemalloc are opt-in build/runtime flags. New capabilities logged as gaps in COVERAGE.md |
| 2026-07-21 | `full Plane A (ext + embedded) + Plane B embedded` | 0.15.0 binaries / 0.14.0 natives | ✅ green (**zio-bdd 1.4.2 → 1.4.4** bump; engine pin unchanged at 0.15.0) — `scalafmtCheckAll` + `scalafmtSbtCheck` + `Test/compile` (68 sources) clean on 1.4.4, no API breakage. **external:** full `engineConformance` — all 14 features + 47 zio-test tests, **0 failures**. **embedded (FFM, JDK 25):** full `engineEmbedded` — 91 scenarios across 01–08/11/12 + 9 zio-test tests, **0 failures**, now driving 0.14.0 natives. **Plane B embedded:** `mockEmbedded` 66 passed / 0 failed. Container + Plane B wiremock/rift-external not re-run — CI covers them | **No conforming code change needed.** The bump's substance is the **engine-version split narrowing**: zio-bdd 1.4.3 moved `riftNativesVersion` 0.13.1 → 0.14.0 (zio-bdd#325), so the embedded leg jumped a release and now exercises 0.14.0 engine code — one release behind the binaries instead of two. ABI-safe (no C-ABI bump in 0.14.0 or 0.15.0), confirmed by the green embedded legs. Knock-on: feature 13's external-only constraint is **lifted** (0.14.0 has the rift#611 envelope) — folding those asserts back into `04-scripting-bounds` is now a live follow-up. **NOT unblocked:** [#96](https://github.com/EtaCassiopeia/rift-conformance/issues/96) — `nm` on the 1.4.4 natives shows all 12 v2 admin symbols plus `rift_verify`/`rift_stub_warnings` exported, but `EmbeddedEngine` still wraps only the same 15 as 1.4.2 and `RiftFfi` has no generic `call(symbol, json)`; a **binding** gap, so #75/#92/#94's FFI legs stay blocked. Also corrected a stale `RIFT_VERSION` default in `FfiManifest91Spec` (`0.13.1` → `0.15.0`, now tracking `build-rift.sh`) |
| 2026-09-28 | `full` (ext + embedded + container Plane A; wiremock + rift-external + embedded Plane B) | 0.18.0 binaries **and** natives (rift-java 0.3.2) | ✅ Plane A green on all three legs (**0.15.0 → 0.18.0** pin bump + **zio-bdd 1.4.4 → 1.5.0** SDK re-base; ZIO 2.1.21). The first 0.18.0 run was red ×7, none an engine defect: a harness port-probe race that 0.18.0's admin-bound-last startup exposed (fixed, regression-tested), plus 0.18.0 contract changes (datadir `<port>.json`, malformed behaviors → `bad data`, cross-file lint E002). The embedded leg now runs the same 0.18.0 engine through rift-java `RiftFfi`. Plane B: wiremock ✅ (the #143 red is gone via zio-bdd#338), fixed-port ✅ on both Rift legs; rift-external 🔴 (raw-document scenarios + `08-received`) and embedded 🔴 (`08-received` only), both on filed library regressions. `scalafmtCheckAll`, `scalafmtSbtCheck` and `lintFeatures` clean on JDK 21 | [rift#1217](https://github.com/achird-labs/rift/issues/1217) (TOOL-DRIFT, examples vs lint), [zio-bdd#349](https://github.com/EtaCassiopeia/zio-bdd/issues/349), [zio-bdd#350](https://github.com/EtaCassiopeia/zio-bdd/issues/350) (LIBRARY) |
| 2026-09-28 | `mock` (rift-external + embedded Plane B) + embedded Plane A | 0.18.0 | ✅ green (**zio-bdd 1.5.0 → 1.5.1**) — `mockRiftExternal` 26/26, `mockEmbedded` 26/26, `engineEmbedded` green. The two 1.5.0 library regressions (F6/F7) are fixed upstream in zio-bdd#351 and verified here | [zio-bdd#349](https://github.com/EtaCassiopeia/zio-bdd/issues/349), [zio-bdd#350](https://github.com/EtaCassiopeia/zio-bdd/issues/350) closed |
| 2026-09-29 | `full` (ext + embedded + container Plane A; Redis flow-store; wiremock + rift-external + embedded Plane B) | 0.18.1 binaries / 0.18.0 natives (rift-java 0.3.2, ABI v2 unchanged) | ✅ green (**0.18.0 → 0.18.1** pin bump, fixes-only). `engineConformance` 90/90 + 75 scenarios; `engineContainer` (v0.18.1 image), `engineEmbedded`, `engineRedis` green; `mockConformance`, `mockRiftExternal`, `mockEmbedded` 26/26 each. Examples re-synced (4548/4549/4550) and linted as one directory run again (closes #160). `@gap` rift#1220/#1221 flipped to a 7-door `400 bad data` outline; rift#1219 asserted on the binary legs (`@native-lag`, excluded from embedded) — the embedded flip is tracked by [#182](https://github.com/EtaCassiopeia/rift-conformance/issues/182) | none |
