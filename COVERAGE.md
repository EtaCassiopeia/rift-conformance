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
`04-scripting-bounds`, `05-templating`, `06-behaviors`. #7 closes when feature 06 lands — now shipped; see the 06 section below.

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

Remaining: `04-scripting-bounds`, `05-templating`, `06-behaviors`. #7 closes when feature 06 lands — now shipped; see the 06 section below.

### `04-scripting-bounds.feature` ← conformance-ext.sh (scripting + #308) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| rhai flow_store counter increments across requests | `rhai flow_store counter increments across requests` | `scripting/scripts` |
| lua script injects a result | `a lua script injects its response` (Outline) | `scripting/scripts` |
| js inject result | `a js script injects its response` (Outline) | `scripting/scripts` |
| rift#308 runaway rhai `timeoutMs:300` → 500 + `x-rift-script-error` ~300ms | `a runaway rhai script is bounded by its timeout` (≥250ms & ≤2000ms) | `scripting/runaway` |
| rift#308 engine + sibling stay responsive with 3 concurrent runaways **in flight** | `a sibling imposter stays responsive while concurrent runaways are in flight` (burst forked; sibling+admin probed while in flight; all 500 on join) | `scripting/sibling`, `scripting/runaway` |

Remaining: `05-templating`, `06-behaviors`. #7 closes when feature 06 lands — now shipped; see the 06 section below.

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
assertion time), not merely "renders some ISO date". Remaining: `06-behaviors`. #7 closes when feature 06 lands — now shipped; see the 06 section below.

### `06-behaviors.feature` ← conformance.sh + conformance-ext.sh (behaviors) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| `copy` from a request header | `copy from a request header into the response` | `behaviors/behaviors` |
| `copy` from body via jsonpath | `copy via jsonpath from the request body` | `behaviors/behaviors` |
| `lookup` CSV column by key (needs corpus `data/`, engine cwd = `$RIFT_CORPUS_ROOT`) | `lookup a CSV column by key` | `behaviors/behaviors` |
| `wait` behavior delays past floor | `the wait behavior delays the response past its floor` (≥500ms) | `behaviors/behaviors` |
| `shellTransform` rewrites a static `is` | `shellTransform rewrites a static response body` | `behaviors/behaviors` |
| Set-Cookie multi-value survives a `copy` (rift#272) | `multi-value Set-Cookie survives a copy behavior (rift#272)` (2 distinct headers) | `behaviors/behaviors` |
| JS `decorate` rewrites the response | `a JS decorate behavior rewrites the response` | `behaviors/behaviors` |

**Note on the two former `@gap` probes:** the design tagged `shellTransform`-on-static-`is` and
Set-Cookie-survives-`copy` (rift#272) as expected-divergence. On the pinned engine build both now
behave correctly (rift#272 is **closed** upstream), so these scenarios assert the *correct* behavior
with no `@gap` tag — the gap has "flipped on fix" and the scenarios now guard against a regression.
The one still-open `@gap` in this suite is rift#309 (top-level `fault` → 502) in `03-faults-transport`.

---

## Behavioral core port — COMPLETE ✅

Features **02–06** are all green under `RIFT_MODE=external`. The 1:1 port audit covers every
`chk`/`gap`/timing assert in **`conformance.sh`** and the behavioral sections of
**`conformance-ext.sh`** — each maps to a named scenario above (or a documented drop). Issue **#7**
is discharged by this feature.

---

## Phase 2 — admin/topology/proxy surface (issue #9)

### `07-spaces-and-fsm.feature` ← ext spaces + rift#311 FSM/CAS — DONE

| Spec item (issue #9) | Plane A scenario | Fixture |
|----------------------|------------------|---------|
| per-space vs global stubs (flowIdSource header:X-Mock-Space) | `requests partition by space via the flow-id header, with a global fallback` | `spaces/spaces` |
| space stub outranks global on same path; unknown space → global | `a space-scoped stub takes precedence over a global stub on the same path` | `spaces/spaces` |
| spaces admin CRUD (POST/GET /imposters/:port/spaces/:flowId/stubs) | `spaces CRUD — register a stub into a new space, then serve and count it` | `spaces/spaces` |
| teardown isolation (alpha down, beta+global intact) | `tearing down a space empties its stubs and stops serving, without affecting siblings` (pre-teardown liveness check + admin `0 stubs` + data-plane no-match) | `spaces/spaces` |
| space×FSM no-bleed | `a scenario stub advances only the flow-id it is addressed with (no cross-space bleed)` | `spaces/spaces` |
| rift#311 FSM sequential A→B→terminal | `FSM sequential transitions from Started through to the terminal state` | `spaces/fsm` |
| rift#311 concurrent burst settles to terminal (NOT asserting racy first-state count) | `FSM concurrent settle (rift#311) — a burst always settles to the terminal state` (20-way burst → terminal `two`) | `spaces/fsm` |

Remaining Phase-2 feature: `11-reload` (issue #10). #9 closes with feature 10.

### `08-admin-surface.feature` ← ext admin surface + rift#190 flow-state — DONE

| Spec item (issue #9) | Plane A scenario | Fixture |
|----------------------|------------------|---------|
| stub by-id GET (+ 404 for unknown id, + content check) | `a stub is retrievable by its explicit id; an unknown id 404s` | `admin/base` |
| enable/disable → 503/200 | `disabling an imposter 503s the data plane; enabling it restores 200` | `admin/base` |
| X-Rift-Debug introspection | `X-Rift-Debug returns diagnostics instead of the normal stub response` | `admin/base` |
| ?match= header filter + stacked AND-semantics | `savedRequests ?match= filters by header, and stacked match= clauses AND together` | `admin/base` |
| targeted savedRequests delete keeps non-matching | `a targeted savedRequests delete removes only the matching requests` | `admin/base` |
| flow-state KV lifecycle + 404 + cross-flow isolation (rift#190) | `flow-state KV full lifecycle — set, get, delete, then 404` (+ f2 isolation) | `admin/base` |
| PUT /imposters atomic swap (valid) | `a valid PUT /imposters atomically swaps the whole imposter set` | `admin/pre-existing`, `admin/replace-batch` |
| PUT /imposters validate-then-swap (invalid → 400, old kept, rejected port never bound) | `an invalid PUT /imposters batch is rejected and the previous set is untouched` | `admin/pre-existing` |

### `09-gateway-https-metrics.feature` ← conformance gateway/https + ext metrics — DONE

| Spec item (issue #9) | Plane A scenario | Fixture |
|----------------------|------------------|---------|
| `/__rift/:port/<path>` single-port gateway | `the single-port gateway routes to the addressed imposter` | `gateway/services` |
| binary `_mode` (base64-decoded on the wire) | `a binary-mode response body is base64-decoded on the wire` | `gateway/services` |
| HTTPS self-signed (TLS client no-verify) + verifying client rejected | `an HTTPS imposter auto-generates a self-signed cert and serves over TLS` | `gateway/secure` |
| `_rift.metrics` counter on the DEDICATED metrics port (not admin) | `the metrics endpoint counts data-plane requests` (delta: +3 after 3 requests) | `gateway/services` |

Harness: `RiftHarness` now spawns the engine with a fresh `--metrics-port` and exposes `metricsUrl`
(the metrics listener is a separate port from the admin API, per the old `conformance-ext.sh`
`METRICS_URL` note); a trust-all `HttpClient` (confined to the HTTPS send) drives the self-signed leg.

### `10-proxy-record.feature` ← conformance proxy + ext proxy — DONE (closes #9)

| Spec item (issue #9) | Plane A scenario | Fixtures |
|----------------------|------------------|----------|
| proxyOnce records → replays (upstream torn down mid-scenario) | `a proxyOnce proxy with predicateGenerators records a replayable stub, then replays it` | `proxy/once-{upstream,proxy}` |
| recorded stub prepended ONLY with predicateGenerators | `a proxyOnce proxy WITHOUT predicateGenerators does not record a replayable stub` (settle-then-assert) | `proxy/nopg-{upstream,proxy}` |
| proxyTransparent records nothing | `a proxyTransparent proxy forwards the response but records nothing` (settle-then-assert) | `proxy/transparent-{upstream,proxy}` |
| multi-level proxyAlways chain | `a multi-level proxyAlways chain resolves through every hop` | `proxy/chain-{leaf,mid,top}` |
| predicateGenerators capture query (VALUE, not just key) | `predicateGenerators can capture the query string` | `proxy/query-{upstream,proxy}` |
| predicateGenerators capture a named request header | `predicateGenerators capture a named request header into the recorded predicate` | `proxy/headers-{upstream,proxy}` |

> Note on config shape: `predicateGenerators.matches` takes booleans for `path`/`method`/`query`
> but a per-header OBJECT for headers — `"headers": {"X-Trace": true}` — which rift captures
> correctly. rift does NOT honour mountebank's blanket `"headers": true` (capture-all) shorthand
> (silently ignored) — the one genuine mountebank-compat divergence, a config-shape gap rather than
> a capture bug.

## Phase 2 behavioral surface (issue #9) — COMPLETE ✅ (features 07–10 all green)
