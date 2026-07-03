# Coverage & parity checkpoints

This file is the audit trail for the shell-suite → two-plane-harness migration (DESIGN §11).

## The parity-checkpoint rule

A shell leg is **deleted only after** its Plane A replacement has run **green on the same engine
build, in the same session**, with the port-audit row filled in. Each checkpoint below records the
build SHA and both gates' results on that SHA. No shell script is removed in the checkpoint issue
itself — retirement happens in a later, dedicated issue (e.g. #8, #11) that cites the checkpoint.

## Plane A checkpoints

| # | Date | Engine build (master) | Shell gate | Plane A gate | Verdict |
|---|------|-----------------------|------------|--------------|---------|
| 0 | 2026-07-03 | `4bc567dd06691a8c980b67d67994b696cc8f81d5` (built 2026-07-03T15:40:15Z) | `consistency-gate.sh` → **15 AGREE, 0 DISAGREE** (exit 0) | `sbt engineGate` (feature 01) → **5/5 scenarios PASSED** | ✅ **PARITY** |

### Checkpoint 0 — consistency gate (`consistency-gate.sh` ↔ `features/rift/01-consistency-gate`)

Same clean-master build (`build-rift.sh`, commit `4bc567d`) fed to both gates in one session:

- **Old shell gate** — `RIFT=$RIFT_BUILD_DIR/rift-http-proxy RIFT_LINT=… RIFT_VERIFY=…
  ~/Projects/rift-imposter-samples/scripts/consistency-gate.sh`: every corpus config AGREES across
  engine + `rift-lint` + `rift-verify` — **15/15 AGREE, 0 DISAGREE**, "all configs agree across
  engine + rift-lint + rift-verify".
- **New Plane A gate** — `RIFT_MODE=external RIFT_BUILD_DIR=… RIFT_CORPUS_ROOT=… sbt engineGate`:
  all five scenarios green — @preflight version coherence (live `--version` == BUILD_INFO),
  corpus present (≥15 files), `rift-lint` 0 errors, engine serves every corpus imposter on its
  declared port, `rift-verify` 0 failures.

Both gates assert the same property (engine ↔ lint ↔ verify agreement over the corpus) and reach the
same verdict on the same SHA. The Plane A gate additionally proves version coherence and names
offending files on failure (its RED path is verified: a corrupted corpus file turns it red naming
that file).

#### Port audit

| Range | Owner | This checkpoint |
|-------|-------|-----------------|
| 45xx  | Corpus imposters (posted verbatim, byte-fidelity) | 4501–4520 (15 files) |
| 46xx  | Plane B mock corpus | not used here (Plane B lands in #13) |
| 48xx  | Plane A in-harness probes (fresh-imposter pool) | not used by the gate feature (corpus-only) |
| 2525  | Admin API (harness external mode) | Plane A engine; shell gate ran its own engine on a separate admin port |

#### Retirement status

`consistency-gate.sh` is **NOT deleted** here — this checkpoint only establishes parity. Its
retirement (with `conformance-reload.sh`) is **#11**, which will cite this row.

## Plane A behavioral port audit (§5 · 1:1 shell-assert → scenario)

The behavioral core of the shell suite (`conformance.sh`, `conformance-ext.sh`) is ported one
assertion at a time. Each row: shell assert → Plane A scenario. This section fills in as features
02–06 land (issue #7).

### `02-core-serving.feature` ← conformance.sh + conformance-ext.sh (core) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| `not`+`startsWith` path predicate | `predicate not+startsWith selects the stub` (Outline) | `core/predicates` |
| `matches` regex path predicate | `predicate matches selects the stub` (Outline) | `core/predicates` |
| `jsonpath` body-selector predicate | `predicate jsonpath selects the stub` (Outline) | `core/predicates` |
| `xpath` body-selector predicate | `predicate xpath selects the stub` (Outline) | `core/predicates` |
| `repeat:N` response cycling + wrap-around | `response cycling with repeat:N wraps around` | `core/cycling` |
| `copy` path regex group into body | `copy path regex group into the response body` | `core/copy-path` |
| FSM two-step state transition | `FSM two-step state transition` | `core/fsm` |
| multi-value `Set-Cookie` preserved | `multi-value Set-Cookie headers are preserved` | `core/cookies` |
| date template (`{{DAYS+N}}`) renders | `date template renders to an ISO-8601 date` | `core/date-template` |
| `defaultForward` proxies unmatched | `defaultForward proxies unmatched requests to another imposter` | `core/forward-target`, `core/forwarder` |
| single-port gateway `/__rift/:port/…` | `single-port gateway routes to an imposter by port` | `core/gateway` |

Remaining behavioral features (this issue's scope, still open): `03-faults-transport`,
`04-scripting-bounds`, `05-templating`, `06-behaviors`. #7 closes when 06 lands.

### `03-faults-transport.feature` ← conformance.sh + conformance-ext.sh (faults) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| tcp `CONNECTION_RESET_BY_PEER` → transport error | `tcp reset aborts the connection at the transport level` (Outline) | `faults/faults` |
| `RANDOM_DATA_THEN_CLOSE` → transport error | `random-data-then-close aborts the connection…` (Outline) | `faults/faults` |
| latency fault ≥ floor (timing) | `latency fault delays the response past its floor` | `faults/faults` |
| error fault status/body/headers | `error fault sets the status, body and headers` | `faults/faults` |
| precedence tcp>error>latency (rift#277) | `fault precedence — tcp wins over error, after the latency delay` | `faults/faults` |
| `probability: 0` no-op | `probability 0 disables a fault` | `faults/faults` |
| top-level `fault` → 502 today (**@gap rift#309**) | `top-level fault currently returns 502 (rift#309)` | `faults/faults` |

Remaining: `04-scripting-bounds`, `05-templating`, `06-behaviors`. #7 closes when 06 lands.

### `04-scripting-bounds.feature` ← conformance-ext.sh (scripting + #308) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| rhai flow_store counter increments across requests | `rhai flow_store counter increments across requests` | `scripting/scripts` |
| lua script injects a result | `a lua script injects its response` (Outline) | `scripting/scripts` |
| js inject result | `a js script injects its response` (Outline) | `scripting/scripts` |
| rift#308 runaway rhai `timeoutMs:300` → 500 + `x-rift-script-error` ~300ms | `a runaway rhai script is bounded by its timeout` (≥250ms & ≤2000ms) | `scripting/runaway` |
| rift#308 engine + sibling stay responsive with 3 concurrent runaways **in flight** | `a sibling imposter stays responsive while concurrent runaways are in flight` (burst forked; sibling+admin probed while in flight; all 500 on join) | `scripting/sibling`, `scripting/runaway` |

Remaining: `05-templating`, `06-behaviors`. #7 closes when 06 lands.

> Harness note: a `sbt test` that recompiles a suite *during* the test task can miss that just-recompiled
> suite (discovery snapshots before incremental compile finishes); a clean re-run picks it up. Watch for
> this when wiring CI (#15) — run `compile` before `test`, or `sbt clean test`.

### `05-templating.feature` ← conformance-ext.sh (templating) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| `${request.method}` + `${request.path}` render | `request method and path variables render` | `templating/templates` |
| `${request.query.*}` renders | `request query variable renders` | `templating/templates` |
| `${request.headers.*}` renders | `request header variable renders` | `templating/templates` |
| `{{NOW}}` renders today | `date token NOW …` (Outline: offset 0 days) | `templating/templates` |
| `{{DAYS+N}}` renders today+N | `date token DAYS+5 …` (Outline: +5 days) | `templating/templates` |
| `{{DAYS-N}}` renders today-N | `date token DAYS-5 …` (Outline: -5 days) | `templating/templates` |
| `{{MONTHS+N}}` renders today+N months | `date token MONTHS+1 …` (Outline: +1 months) | `templating/templates` |
| `{{MONTHS-N}}` renders today-N months | `date token MONTHS-1 …` (Outline: -1 months) | `templating/templates` |

Date tokens verify the **actual computed offset** (expected date derived from wall-clock at
assertion time), not merely "renders some ISO date". Remaining: `06-behaviors`. #7 closes when 06 lands.
