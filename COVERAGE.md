# Coverage & parity checkpoints

This file is the audit trail for the shell-suite → two-plane-harness migration (DESIGN §11).

**Corpus location (#16):** the canonical JSON corpus, CSV lookup data, and injection fixtures now
live in-repo under `./corpus` (`corpus/imposters/*.json`, `corpus/data/products.csv`,
`corpus/fixtures/injection/*`), vendored from `rift-imposter-samples`. `RIFT_CORPUS_ROOT` defaults
to `corpus` (`RiftHarnessConfig.DefaultCorpusRoot`) — no sibling checkout is required to run Plane
A. The historical checkpoint records below predate the vendoring and cite the old sibling-repo
path (`~/Projects/rift-imposter-samples/...`) as it existed at the time; they are left as-is for
audit-trail accuracy.

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

- **Embedded Plane A (#18)** — `RIFT_MODE=embedded` runs the same suites against the engine
  **in-process** over Panama FFM (`rift_serve_admin`, zero process spawn, JDK 22+): features 01–08 +
  11-reload all green (`sbt engineEmbedded`), the corpus lint/verify gate included. 09/10
  (metrics/proxy-record) stay `external`/`container` (see DESIGN §3.1). Verified via
  `RiftEmbeddedHarnessSpec` (real admin URL + zero `--allow-injection` child) and the runner's `ps`
  sweep.

- **Intercept / TLS-MITM (#48)** — feature `12-intercept` is **new-capability** coverage (rift 0.10.0
  intercept admin API + 0.11.0 FFI), with no shell antecedent to port. External mode starts an
  `--intercept-port` listener (readiness-probed); the suite drives `/intercept/rules` (add/list/clear)
  + CA/PKCS#12 truststore export, then CONNECT-tunnels a real HTTPS request through the listener with
  a JDK `HttpClient` trusting the exported CA, asserting the decrypted request is served inline or
  forwarded to an imposter. Runs under **external**, **container** (#51 — publishes an
  `--intercept-port` 1:1), and **embedded** (#53 — via the FFI `startIntercept`/`interceptAddRules`/
  `interceptExportTruststore` behind a `RiftHarness` intercept-admin seam; the HTTP-only ca.pem +
  rules-list/clear scenarios are tagged `@intercept-http` and skipped for embedded). **Plane B**
  intercept corpus is `24-intercept.feature` (#54) — the portable `MockControl.Intercept` capability
  (`respondWith` inline + `redirectTo` a mock space), driven through the FFM backend's TLS-MITM proxy
  with a CA-trusting JDK client; tagged `@intercept` so it runs on `MOCK_BACKEND=embedded` and is
  excluded from wiremock + rift-external (which don't advertise the capability). Finally, external
  mode seeds a **committed throwaway test CA** (`src/test/resources/rift-fixtures/intercept/ca-*.pem`)
  via rift 0.11.3's `--intercept-ca-cert/--key` and asserts the exported `/intercept/ca.pem`
  byte-equals it (feature-12 `@intercept-caller-ca`, external-only, #55) — proving rift loaded the
  caller CA rather than minting an ephemeral one. All #51 intercept follow-ons are now landed. The
  **embedded** `EmbeddedRift.InterceptConfig` — non-loopback bind host, pinned fixed port, and a
  persistent caller CA (`caCert`/`caKey`, rift ≥ 0.11.3) — gets its own Plane B check in
  `EmbeddedInterceptConfigSpec` (#62): a self-gating zio-test spec (`MOCK_BACKEND=embedded` + FFM
  available) that builds `EmbeddedRift.layer(InterceptConfig(bindHost="0.0.0.0", port=Some(N),
  caCert=…, caKey=…))` and asserts `Intercept.proxyEndpoint` reports the configured host/port and the
  exported truststore's CA byte-equals the supplied one — the embedded mirror of `@intercept-caller-ca`
  (a distinct subsystem from Plane A's `--intercept-ca-cert/--key`). The full container-reachability
  leg stays out of scope (macOS Docker host-networking limit, #45).

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
| top-level `fault` resets at the transport level (rift#309, fixed in rift 0.9.0) | `top-level fault resets the connection at the transport level (rift#309)` | `faults/faults` |

Remaining: `04-scripting-bounds`, `05-templating`, `06-behaviors`. #7 closes when feature 06 lands — now shipped; see the 06 section below.

### `04-scripting-bounds.feature` ← conformance-ext.sh (scripting + #308) — DONE

| Shell assert | Plane A scenario | Fixture |
|--------------|------------------|---------|
| rhai ctx.state counter increments across requests | `rhai ctx.state counter increments across requests` | `scripting/scripts` |
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

**Note on the former `@gap` probes:** the design tagged `shellTransform`-on-static-`is`,
Set-Cookie-survives-`copy` (rift#272), and top-level-`fault`-→-502 (rift#309) as expected-divergence.
On the pinned engine build all three now behave correctly (rift#272 and rift#309 are **closed**
upstream — rift#309 fixed in 0.9.0), so these scenarios assert the *correct* behavior with no `@gap`
tag — the gap has "flipped on fix" and the scenarios now guard against a regression. No `@gap` remains
open in this suite.

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

#9 closed with feature 10; `11-reload` (issue #10) below completes Phase-2.

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

### `11-reload.feature` ← conformance-reload.sh (rift#316) — DONE (issue #10)

| Shell assert (conformance-reload.sh) | Plane A step |
|---|---|
| 2-imposter configfile (one recordRequests:true) loaded | `Given the engine is started with the reload config file` (via `RiftHarness.startReloadConfig`) |
| recorded 2 requests pre-reload | `Then imposter 4870 has recorded 2 requests` |
| reload created nothing (identical config) | `Then the reload report created 0 imposters` |
| reload deleted nothing | `And the reload report deleted 0 imposters` |
| report exposes {created,replaced,stubPatched,deleted} | `And the reload report exposes all four change fields` |
| recorded state preserved across reload (rift#316) | `And imposter 4870 has recorded 2 requests` (still 2, post-reload) |
| both imposters still serve post-reload | `GET /a`→"A", `GET /b`→"B" |

> Harness: `RiftHarness` is now engine-restartable in place — `withConfigFile` / `startReloadConfig`
> swap in a `--configfile` engine on the same admin port and revert to a plain engine via an
> `afterScenario` hook (mandatory even on a failed assertion). A single `engineRef`-based layer
> finalizer reaps whichever engine is current at run end. `conformance-reload.sh` retirement is
> deferred to #11.

## Phase 2 admin/topology/proxy/reload surface — COMPLETE ✅ (features 07–11 all green)

## Plane B — MockControl mock corpus (issue #13)

Migrated from `zio-bdd-samples` (`src/test/scala/samples/mock/`,
`src/test/resources/features/mock/`). Feature area → sample → per-backend status. Legend: ✅ pass ·
❌ fail (→ FINDINGS) · ➖ n/a by design (capability not advertised) · ⏳ gated/not-run · 🟡 partial.

### Samples

Backends: **WireMock** (in-process, Correlated), **Rift** (Docker container, all
capabilities), **Embedded** (in-process Rift via Panama FFI — PerInstance, core
port + Faults/Scripting/ProxyRecord/Templating; Stateful/StateInspection `➖` by
design, see F4/zio-bdd#185).

| # | Sample | Area / capability | Tags | WireMock | Rift | Embedded |
|---|--------|-------------------|------|:--------:|:----:|:--------:|
| 01 | basic-matching | method + exact path, status, Text/Json/Empty body | `@portable` | ✅ | ✅ | ✅ |
| 02 | path-matching | PathMatch Exact/Regex/Template/Any | `@portable` | ✅ | ✅ | ✅ |
| 03 | value-matching | header/query Equals/Contains/Matches | `@portable` | ✅ | ✅ | ✅ |
| 04 | body-matching | BodyMatch Equals/Contains/JsonPath | `@portable` | ✅ | ✅ | ✅ |
| 05 | response-fidelity | base64 body, multi-value headers (#162), latency | `@portable` | ✅ | ✅ | ✅ |
| 06 | rule-precedence | Overlay/Base, add/remove/replaceRules | `@portable` | ✅ | ✅ | ✅ |
| 07 | lifecycle-isolation | per-space destroy, space-local received() | `@portable` | ✅ | ✅ | ✅ |
| 08 | received-verification | count, method/path, body | `@portable` | ✅ | ✅ | ✅ |
| 09 | mock-sources | Dsl (portable); raw Resource/File/Dir on Rift; `received()` on a raw Resource-provisioned space (#63); multi-stub raw imposter doc (#65) | `@sources` | ✅ (Dsl) | ✅ | ✅ (Dsl) |
| 10 | fixtures-tags | MockFixtures `@mock(...)` + catalog (mixed Dsl + Resource entries, #64), auto-teardown | `@fixtures @portable` | ✅ | ✅ | ✅ |
| 11 | faults | FaultKind ConnReset/Empty/MalformedChunk/RandomThenClose/LatencySpike | `@faults` | ✅ | ✅ | ✅ |
| 12 | stateful-fsm | StatefulScenarios define/reset | `@stateful` | ✅ | ✅ | ➖ |
| 13 | state-inspection | currentState/setState | `@stateinspection` | ✅ | ✅ | ➖ |
| 14 | scripting | Script (Rhai) | `@scripting` | ➖ | ✅ | ✅ |
| 15 | proxy-record | ProxyRecord (capability surface; round-trip = library conformance) | `@proxyrecord` | ➖ | ✅ | ✅ |
| 16 | templating | ResponseTemplate path + body captures | `@templating` | ➖ | ✅ | ✅ |
| 17 | native-escape-hatch | provisionNative WireMock / Rift | `@native` | ✅ | ✅ | ➖ |
| 18 | error-paths | RuleNotFound/SpaceNotFound/InvalidDefinition | `@errors` | ✅ | ✅ | ✅ |
| 19 | capability-negotiation | require ok/fail; capabilities honest | `@negotiation` | ✅ | ✅ | ➖ |
| 20 | inflight-reconfiguration | live replaceRules/addRule/removeRule, no restart | `@portable @reconfig` | ✅ | ✅ | ✅ |
| 21 | stress-edge-cases | unicode, 100k body, special chars, root path, multi-query, space churn | `@portable @stress` | ✅ | ✅ | ✅ |
| 22 | overlapping-steps | most-specific step tie-break (#186 verification), no backend | `@portable` | ✅ | ✅ | ✅ |
| 23 | extractors-advanced | word/rest, bigDecimal precision, uuid, long bound, regex, oneOf, optional | `@portable` | ✅ | ✅ | ✅ |

### Capability roll-up

| Capability | WireMock | Rift (container) | Embedded (FFI) | Sample |
|------------|:--------:|:----:|:----:|--------|
| Faults | ✅ | ✅ | ✅ | 11 |
| StatefulScenarios | ✅ | ✅ | ➖ | 12 |
| StateInspection | ✅ | ✅ | ➖ | 13 |
| Scripting | ➖ | ✅ | ✅ | 14 |
| ProxyRecord | ➖ | ✅ | ✅ | 15 |
| Templating | ➖ | ✅ | ✅ | 16 |

Since [zio-bdd#185](https://github.com/EtaCassiopeia/zio-bdd/issues/185) landed (F4, now closed),
the **embedded FFI** adapter advertises **and executes** Faults/Scripting/ProxyRecord/Templating —
the stub-based caps (`_rift.fault`, `_rift.script`, a Mountebank `proxy`, an `is`+`_behaviors.copy`
template) wire to the same Rift engine over the C-ABI, verified green on the embedded leg (samples
11/14/15/16). **StatefulScenarios/StateInspection stay `➖`** on embedded by design — there are no
scenario-state endpoints over the C-ABI. Embedded also runs the full **portable** surface (01–10,
18, 20, 21, 22).

### Notes

- **09** raw sources (Json/Resource/File/Dir) are Rift-only via `provision`; WireMock requires
  `provisionNative` for raw docs (sample 17), so 09 tests only the Dsl source on WireMock — an
  intentional adapter asymmetry, not a gap.
- `mockConformance` (the default CI leg) excludes `scripting`, `templating`, `proxyrecord`, `rift` —
  the three capability tags are `➖` on WireMock by design (see the roll-up above), and `@rift`
  scenarios assert raw-Rift-only behavior a WireMock backend cannot satisfy — so they auto-skip
  rather than fail there; they run on the `rift`/`embedded` legs instead.
- Findings and their fixes are tracked in [FINDINGS.md](FINDINGS.md).

## Plane B — `rift-external` backend (issue #14)

`MOCK_BACKEND=rift-external` connects the MockControl adapter (`zio.bdd.mock.rift.Rift.connect`,
from `zio-bdd-rift` — already published, no library change) to a **real rift engine spawned by the
Plane A `RiftHarness`** (`samples.rift.support.RiftHarness.layer`) — no Docker, no FFM/JDK-22
floor, same engine build Plane A uses. `Backends.scala` composes `RiftHarness.layer ++
Client.default ++ Provisioning` into `Rift.connect(harness.adminUrl, ports)(hostFor)`, where `ports`
is a private 4600–4639 imposter pool (DESIGN §6.4 — disjoint from the corpus's 45xx and Plane A's
in-harness probe pool 48xx).

One integration wrinkle: `Rift.connect`'s `hostFor` contract wants the **full** `http://host:port`
string for a given imposter port (see zio-bdd's own `ProxyRecordConformanceSpec`, which maps a
testcontainers-published port: `hostFor = p => s"http://${host}:${mappedPort(p)}"`), whereas
`RiftHarness.hostFor` returns a **bare hostname** (Plane A's own convention is to prepend the
scheme and append the port itself, unmapped/identity — see `RiftConformanceSupport`'s
`s"http://$host:$port$path"`). `Backends.scala` wraps it: `p => s"http://${harness.hostFor(p)}:$p"`,
rather than changing `RiftHarness`'s Plane-A-only convention.

**Exclusion set: `--exclude-tags wiremock`** (`mockRiftExternal` in `build.sbt`) — that's it. Since
`rift-external` is a full rift engine (the same binary/build Plane A drives), it runs the entire
capability surface `rift` (the Docker-container leg) does: Faults, StatefulScenarios,
StateInspection, Scripting, ProxyRecord, Templating, native escape hatch, capability negotiation,
and the `@rift`-tagged raw-imposter scenarios (samples 09/17/19) — the only tag excluded is
`wiremock` (the two scenarios, in samples 17 and 19, that assert WireMock-specific behavior — e.g.
"WireMock does not advertise Scripting" — which a rift engine can't and shouldn't satisfy; they
`IGNORED`-skip rather than fail under the tag exclusion, same mechanism as every other backend leg).

**Result:** all 23 `samples.mock.*` suites green under `MOCK_BACKEND=rift-external` with that
exclusion — no capability gaps and no behavioral divergences found against the pinned rift 0.11.3
build. The capability roll-up above (WireMock / Rift-container / Embedded) now has a fourth column
in practice: `rift-external` matches the `Rift (container)` column exactly (✅ everywhere except the
two `@wiremock`-only scenarios, which are `➖` by design on every non-WireMock backend).

Harness: `scripts/conformance-rift-external.sh` fetches the engine (`build-rift.sh`, reusing
`RIFT_BUILD_DIR` if already set) and runs `MOCK_BACKEND=rift-external sbt -batch mockRiftExternal`
— no Docker, no `RIFT_IT` gate. `scripts/run-all.sh`'s `rift-external (Plane B)` layer now runs this
script instead of its former `exit 77` SKIP stub.

## v0.12.0 new-capability coverage gaps (backlog) — tracked by [#72](https://github.com/EtaCassiopeia/rift-conformance/issues/72)

Rift 0.12.0 shipped the scripting DX redesign. Existing scripting coverage was **migrated** to the
new contract (Script API v2 `respond(ctx)` / `ctx.state`; Lua dropped) so the gate stays green — but
the release added capabilities this harness does not yet exercise. Tracked here as coverage gaps
(umbrella issue **#72**), not divergences:

| Capability | rift | Gap | Sketch |
|------------|------|-----|--------|
| Script API v2 result constructors beyond `http()` | #442 | `delay(ms)`, `reset()`, `pass()` are not exercised | a stub whose `respond(ctx)` returns `delay(N)` (latency then normal), `reset()` (transport reset), `pass()` (no injection) |
| `ctx.state` atomics | #443 | only `incr_by`/`get_or`/`delete` are driven; `cas`/`ttl`/`exists` are not | a CAS-guarded state transition + a TTL-expiry assertion |
| Declarative response templating | #444 | `{{ … }}` body/header template functions behind the opt-in `templated` flag — no coverage | `templated: true` stub rendering a request field into the body |
| File-based script authoring | #441 | `file:`/`ref:` script references + named library via `--scripts-dir`/`RIFT_SCRIPTS_DIR` — no coverage | post an imposter whose script is a `ref:` into a `--scripts-dir` library |
| `rift script` tooling | #445 | `rift script check` / `rift script run` subcommands + debug-mode script trace — no coverage | a `RiftTools` seam driving `rift script check` over a fixture, asserting pass/fail |
| Mountebank v2 fidelity bundle | #438 | v2 `config`-first inject convention, native `logger`, `--allowInjection`, wait fns, EJS stringify — partially exercised via JS inject only | targeted MB-parity scenarios |
| `request.pathParams` | #435 | route-pattern path-parameter extraction as a stub field (predicates + templating) — no coverage | a stub with a `routePattern` asserting `pathParams` in predicate/response |
| `ffi-manifest.json` release asset | #459 | `{version, abi, artifacts[]}` map — the embedded (Panama FFI) leg still resolves natives without consulting it | assert the manifest resolves the platform cdylib the embedded leg loads |
| `rift_stub_warnings` FFI accessor | #423 | stub-overlap warnings over the C-ABI — not asserted on the embedded leg | drive `rift_stub_warnings` and compare with the HTTP admin plane's overlap analysis |

Container-mode `@full-corpus` **serve** was re-enabled by the Lua removal ([#71](https://github.com/EtaCassiopeia/rift-conformance/issues/71),
supersedes closed #45) — verified green on the v0.13.1 image via `RIFT_IT`. The `rift-verify
--verify-dynamic` scenario (`@full-corpus-verify`) stays external/embedded-only: `--verify-dynamic`
allocates ephemeral imposters on dynamic high ports that bind inside the container's network
namespace, unreachable from the host tool over the 1:1 published-port topology — a standing
container constraint, not a lua/image gap. See the container row in DESIGN §3.1.

## v0.13.0 new-capability coverage gaps (backlog) — tracked by [#72](https://github.com/EtaCassiopeia/rift-conformance/issues/72)

The engine pin moved to **rift 0.13.1** with **zio-bdd 1.4.2**. 0.13.1 is a packaging-only patch (the
`rift-core` → `rift-mock-core` crate rename, [rift#528](https://github.com/EtaCassiopeia/rift/pull/528);
binary/FFI/Docker/npm are byte-identical) — **all runtime changes landed in 0.13.0**, a substantial
release. One behavior change is **already conformed**: the script-timeout contract now returns
`504 Gateway Timeout` + `x-rift-script-timeout: true` ([rift#499/#515](https://github.com/EtaCassiopeia/rift/pull/515)),
asserted by `04-scripting-bounds` (the `_rift.script` path). The remaining new surface is untested:

| Capability | rift | Gap | Sketch |
|------------|------|-----|--------|
| Server-side `verify` endpoint | #494/#512 | **core covered (#74)** — `POST /imposters/{port}/verify` `{matched,total}` count + `includeClosest` `failedPredicates` diff, in `08-admin-surface` (external + embedded). Remaining: `flowId` scoping (flow-partition semantics differ from the `flowState` header source — needs investigation) + the `rift_verify` FFI symbol (embedded bridge does not yet export it) — tracked by [#94](https://github.com/EtaCassiopeia/rift-conformance/issues/94) | covered by `08-admin-surface`; #94 drives `flowId` + FFI parity |
| C-ABI admin long-tail (12 symbols) | #491/#513 | `rift_list_imposters`/`rift_get_imposter`/`rift_add_stub`/`rift_get_stub`/`rift_update_stub`/`rift_delete_stub`/`rift_clear_recorded`/`rift_clear_proxy_recordings`/`rift_set_imposter_enabled`/`rift_scenarios`/`rift_set_scenario_state`/`rift_reset_scenarios` — the embedded leg doesn't drive them | for each, call over FFI and diff the returned domain JSON against the equivalent admin HTTP route |
| Runtime intercept lifecycle over admin API | #493/#521 | **covered (#76)** — `POST /intercept` (201 + `{interceptPort,interceptUrl}`), `GET /intercept` (404 + "intercept listener not running" when off / 200 when running), double-`POST` → 409, `DELETE` → 204 then `GET` 404, repeat `DELETE` idempotent 204, on a server started **without** `--intercept-port`, in `12-intercept` (`@intercept-runtime`, external-only — the external harness spawns a dedicated no-intercept engine for it). The `rift_stop_intercept` FFI symbol is unbound in the embedded binding ([zio-bdd#323](https://github.com/EtaCassiopeia/zio-bdd/issues/323)), so the lifecycle is exercised over the HTTP admin plane here | covered by `12-intercept` (`@intercept-runtime`) |
| `allowInjection` in `rift_serve_admin` | #492/#511 | the embedded admin plane's `allowInjection` options flag (default false) mirroring the `--allowInjection` CLI gate — no coverage | start `rift_serve_admin` with `allowInjection:false`, POST an inject imposter → rejected; restart with `true` → accepted |
| `x-rift-generator-error` proxy header | #498/#507 | **covered (#77)** — a proxyOnce proxy whose `predicateGenerators.inject` throws tags the proxied response with `x-rift-generator-error: script-error` and records **no** shadowing match-all stub (the proxy still forwards the upstream 200); asserted in `10-proxy-record` (external-only) via the `proxy/generror-*` fixtures | covered by `10-proxy-record` — header value + stub count stays at the single proxy stub after recording settles |
| Script-timeout contract — remaining inject sites | #499/#515 | **covered (#78)** — response-inject → 504 "injection timeout", predicate-inject → 504 "predicate injection timeout", decorate → undecorated 200 (non-strict) / 504 (top-level `strictBehaviors`), all carrying `x-rift-script-timeout: true` (+ `x-rift-inject-error`/`x-rift-decorate-error`), in `04-scripting-bounds` via the `scripting/inject-*-timeout` fixtures (a `Date.now()` spin under a tiny `timeoutMs`) | covered by `04-scripting-bounds` — 4 scenarios, one per inject site plus the decorate strictBehaviors variant |
| Script error-response contracts (non-timeout) | #500/#509 | response-inject failure → 400 "invalid injection"; decorate failure → 200 + `x-rift-decorate-error` (or 500 under `strictBehaviors`); predicate-inject failure → 400 "invalid predicate injection" — pin these | a broken script per inject site asserting the documented status + marker header |
| Bare `scenarioName` provisions a real flow store | #514/#520 | a stub with `scenarioName` but no `requiredScenarioState`/`newScenarioState` now persists scenario state (was a silent no-op store) — no coverage | drive `PUT /scenarios/{name}/state` on a bare-`scenarioName` stub, read it back, assert the write persists |
| ASCII-only case folding for `contains`/`startsWith`/`endsWith` | #480/#508 | case-insensitive string predicates fold ASCII only now (was Unicode) — no non-ASCII scenario pins the narrowed contract | a predicate where `É`/`é` no longer match case-insensitively but ASCII still does |
| SDK conformance corpus release artifact | #460/#516/#524 | `sdk-conformance-<version>.tar.gz` (imposter fixtures + `_verify` transcripts + `manifest.json`; 12/15 fixtures carry `_verify`) — could be replayed with `rift-verify` as an upstream-authored oracle | download the pinned tarball, replay each fixture, assert `_verify.sequence` holds + `manifest.json` matches disk |

Robustness/perf changes in 0.13.0 that don't need new gap rows but are worth noting: per-imposter
`ctx.state` updates are now atomic under same-imposter concurrency ([rift#477/#486](https://github.com/EtaCassiopeia/rift/pull/486))
— exercised by `04-scripting-bounds`' concurrent-runaway + per-flow-id isolation scenario; every FFI
entry point is panic-guarded across the C ABI ([rift#484/#487](https://github.com/EtaCassiopeia/rift/pull/487))
— hardens the embedded (Panama FFM) leg; proxy HTTP connection pooling was re-enabled
([rift#482/#496](https://github.com/EtaCassiopeia/rift/pull/496)); and the function-form `wait`
fallback now clamps/caps like the Boa path ([rift#490/#504](https://github.com/EtaCassiopeia/rift/pull/504),
`--no-default-features` only).
