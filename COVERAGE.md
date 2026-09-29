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
| Script API v2 result constructors beyond `http()` | #442 | **covered (#84)** — a `respond(ctx)` rhai script returning `delay(300)` (≈300 ms latency then a default 200), `reset()` (transport-level connection reset), and `pass()` (script ran but declined to inject → default empty 200 tagged `x-rift-script: rhai`), in `04-scripting-bounds` via `scripting/script-constructors` | covered by `04-scripting-bounds` — 3 scenarios, one per constructor |
| `ctx.state` atomics | #443 | **covered (#85)** — `exists()` (false before a write, true after), `cas()` (a compare-and-swap that applies only when the current value matches the expected — a `v1→v2` transition succeeds, a stale `v1→v9` on the now-`v2` key does not), and TTL expiry via the store-level `flowState.ttlSeconds` (a key is gone after a real-time wait past its TTL), in `04-scripting-bounds` via `scripting/state-atomics` | covered by `04-scripting-bounds` — one ordered scenario: exists false→true, cas applied→stale-rejected, ttl-expiry after a 3 s wait (key gone, exists false) |
| Declarative response templating | #444 | **covered (#86)** — the opt-in `_rift.templated: true` response flag enabling the richer `{{ … }}` template functions (handlebars-style args), distinct from the always-on `${…}` vars + `{{NOW}}`/`{{DAYS±N}}` date tokens: `{{ request.header 'X-Name' }}` renders the request header into the body, `{{ randomInt 7 7 }}` renders `7`, and WITHOUT the flag the `{{ … }}` is left literal (the gate), in `05-templating` via `templating/templated` | covered by `05-templating` — 3 scenarios: header render, opt-in-gate literal, randomInt function |
| File-based script authoring | #441 | **covered (#87)** — `FileScriptAuthoring87Spec` spawns an engine with `--scripts-dir` pointed at a committed library (`src/test/resources/rift-scripts`) and drives, over the admin API: `_rift.script.file: "greet.rhai"` resolves under the root and serves; `_rift.script.ref: "greet"` resolves a named entry in the imposter's own `_rift.scripts` map and serves; a path-traversal `file: "../../etc/passwd"` is rejected `400` (the resolved path may not escape the root). External-leg only, self-gating on `RIFT_BUILD_DIR` | covered by `FileScriptAuthoring87Spec` — file:/ref: serve + escape rejection |
| `rift script` tooling | #445 | **covered (#88)** — `RiftTools` gains `riftScriptCheck`/`riftScriptRun` seams (the released `rift` binary); `RiftScriptTooling88Spec` drives `rift script check` on a valid script → exit 0 "OK", on a broken script → non-zero "FAILED", and `rift script run` on a valid script → exit 0 with the `respond` decision trace. Offline (no engine), self-gating on `RIFT_BUILD_DIR` | covered by `RiftScriptTooling88Spec` — check pass/fail + run decision trace |
| Mountebank v2 fidelity bundle | #438 | **covered (#89)** — the previously-unpinned MB-parity elements: (a) the **config-first inject convention** (the `inject` function's single argument IS the request: `config.method`/`config.path`/`config.body` direct) and the **native logger** (`config.logger.info(…)` does not fail the response), in `06-behaviors` via `behaviors/mb-inject`; (b) **EJS `stringify`** — `--configfile` templates are EJS-preprocessed by default, and `<%- stringify('path') %>` inlines a file's contents into the surrounding string, pinned by `MbEjsStringify89Spec` (a seeded `config.ejs` whose body is `"<%- stringify('greeting.txt') %>"` serves the inlined file content). Already covered: `wait`/`decorate`/`copy`/`lookup`/`shellTransform` in `06-behaviors`; the embedded `serve_admin` `allowInjection` gate in `AllowInjectionGate82Spec` (#82). The **external** `--allowInjection` create-time gate is now covered by `AllowInjectionMatrix116Spec` (#116) — see the gate-parity row below. (#79's 400 "invalid injection" remains the distinct *runtime* script-error path, not the create gate.) | covered by `06-behaviors` (config-first inject + logger) + `MbEjsStringify89Spec` (EJS stringify) |
| `request.pathParams` | #435 | **covered (#90)** — a stub's `routePattern` (e.g. `/users/:id`) extracts path parameters into `request.pathParams.<name>`, asserted both in a **response template** (`${request.pathParams.id}` → `GET /users/42` renders `user=42`) and from a **script** (`ctx.request.pathParams.id` → `GET /s/77` renders `script=77`), in `05-templating` via `templating/path-params`. Note: per the engine design (`routePattern` populates `pathParams` for templates + scripts, not predicate matching — a non-matching pattern yields empty `pathParams`, the stub still serves) `pathParams` is **not** a predicate matcher, so ordinary path predicates route to the stub | covered by `05-templating` — routePattern → pathParams in template + script |
| `ffi-manifest.json` release asset | #459 | **covered (#91)** — `FfiManifest91Spec` fetches the release `ffi-manifest.json` and asserts it resolves the running platform's `librift_ffi` cdylib: for the JVM's `os.name`/`os.arch` (e.g. `darwin-aarch64`) the manifest lists an artifact whose `file` is `librift_ffi-<platform>.<dylib\|so\|dll>` — the same native the embedded (Panama FFM) leg loads — under `abi: "v2"` at `version: v<pin>`, with a 64-hex `sha256` and a download `url`. Self-gating (offline → no-op; public asset, no token) | covered by `FfiManifest91Spec` — manifest resolves the platform cdylib (name/abi/version/sha256/url) |
| `rift_stub_warnings` FFI accessor | #423 | stub-overlap warnings over the C-ABI — not asserted on the embedded leg | drive `rift_stub_warnings` and compare with the HTTP admin plane's overlap analysis |

Container-mode `@full-corpus` **serve** was re-enabled by the Lua removal ([#71](https://github.com/EtaCassiopeia/rift-conformance/issues/71),
supersedes closed #45) — verified green on the v0.13.1 image via `RIFT_IT`. The `rift-verify
--verify-dynamic` scenario (`@full-corpus-verify`) stays external/embedded-only: `--verify-dynamic`
allocates ephemeral imposters on dynamic high ports that bind inside the container's network
namespace, unreachable from the host tool over the 1:1 published-port topology — a standing
container constraint, not a lua/image gap. See the container row in DESIGN §3.1.

**Corpus wait magnitudes + a self-diagnosing gate ([#126](https://github.com/EtaCassiopeia/rift-conformance/issues/126)).**
`03-behaviors`' two `wait` stubs were the only ones ever seen to flake the `@full-corpus-verify`
gate (~1 run in 6–12, always the corpus's two longest-in-flight requests, and never reproducible in
isolation). The failure reason was never captured, so the mechanism stayed unknown — every
occurrence was on the superseded 0.14.0, and 30 runs on 0.15.0 (including a load-saturated box)
produced none. Rather than file an unactionable upstream report, the waits were tightened
(`1500` → `400` ms; `{200,1200}` → `{100,300}` ms) to shrink the in-flight window ~4× for *any*
candidate mechanism. Nothing consumes those magnitudes — every `took at least …` assertion in the
suite targets Plane A/B **fixtures**, never corpus port 4503; the stubs exist to demonstrate the two
`wait` spellings, which 400 ms does as well as 1500 ms. Both stubs also gained the `_verify`
transcripts they were missing (status+body; latency is still not expressible in that vocabulary), so
the corpus is now 40 transcripts across 15/15 files.

The durable half is instrumentation: `rift-verify` now runs `--verbose` and `VerifyReport.failDetail`
lifts the per-test `FAIL` lines off stderr into the gate's assertion message, so a red gate names the
stub and the mismatch instead of reporting a count. **Upstream filing criterion**: one recurrence on
≥ 0.15.0 *with that captured detail* → file at `achird-labs/rift`. Until then there is nothing
actionable to send.

**Corpus `_verify` backfill ([#117](https://github.com/EtaCassiopeia/rift-conformance/issues/117)).**
`@full-corpus` proves every imposter *answers*; only a `_verify` transcript proves it answers
*correctly*. 12 of 15 corpus files carried none, so rift-verify skipped their dynamic stubs outright
(*"no `_verify` annotation; cannot assert"*) — the same blind spot rift#608 hid in. All 12 are now
backfilled (33 new transcripts, 38 total), taking the `01-consistency-gate` rift-verify scenario from
**50 passed / 55 skipped** to **89 passed / 35 skipped**, still 0 failures, with no new suite.
Deliberately still bare: `04-fault-injection`'s probabilistic faults + TCP reset (only the
`probability: 1.0` latency stub is asserted) — a transcript over a coin flip is flakiness, not
coverage. `scripts/verify-mutation-check.py --all` rules out decorative blocks: it corrupts one
expectation per transcript and requires exactly one new rift-verify failure; all 40 are currently
killed. That proves rift-verify **evaluates** each transcript, not that each is strong — see DESIGN
§ for the distinction. Re-run it after editing a `_verify` block or bumping the engine pin.

**Still-open sub-gaps** (behavior named by a stub that the `_verify` vocabulary cannot assert —
`expect` supports only `status`/`bodyContains`/`bodyEquals`): the two-`Set-Cookie`-lines-on-the-wire
behavior of `11-headers-and-templates` (the transcript can only assert the body), and response
latency generally (`03`'s `wait`, `16`'s JS-function `wait`, `04`'s `probability: 1.0` latency) —
asserted on status+body only. Plane A's own feature files cover latency floors directly
(`03-faults-transport`, `06-behaviors`), so this is a corpus-oracle limitation, not a coverage hole.

## v0.13.0 new-capability coverage gaps (backlog) — tracked by [#72](https://github.com/EtaCassiopeia/rift-conformance/issues/72)

The engine pin moved to **rift 0.13.1** with **zio-bdd 1.4.2**. 0.13.1 is a packaging-only patch (the
`rift-core` → `rift-mock-core` crate rename, [rift#528](https://github.com/achird-labs/rift/pull/528);
binary/FFI/Docker/npm are byte-identical) — **all runtime changes landed in 0.13.0**, a substantial
release. One behavior change is **already conformed**: the script-timeout contract now returns
`504 Gateway Timeout` + `x-rift-script-timeout: true` ([rift#499/#515](https://github.com/achird-labs/rift/pull/515)),
asserted by `04-scripting-bounds` (the `_rift.script` path). The remaining new surface is untested:

| Capability | rift | Gap | Sketch |
|------------|------|-----|--------|
| Server-side `verify` endpoint | #494/#512 | **core covered (#74)** — `POST /imposters/{port}/verify` `{matched,total}` count + `includeClosest` `failedPredicates` diff, in `08-admin-surface` (external + embedded). Remaining: `flowId` scoping (flow-partition semantics differ from the `flowState` header source — needs investigation) + the `rift_verify` FFI symbol (embedded bridge does not yet export it) — tracked by [#94](https://github.com/EtaCassiopeia/rift-conformance/issues/94) | covered by `08-admin-surface`; #94 drives `flowId` + FFI parity |
| C-ABI admin long-tail (12 symbols) | #491/#513 | `rift_list_imposters`/`rift_get_imposter`/`rift_add_stub`/`rift_get_stub`/`rift_update_stub`/`rift_delete_stub`/`rift_clear_recorded`/`rift_clear_proxy_recordings`/`rift_set_imposter_enabled`/`rift_scenarios`/`rift_set_scenario_state`/`rift_reset_scenarios` — the embedded leg doesn't drive them | for each, call over FFI and diff the returned domain JSON against the equivalent admin HTTP route |
| Runtime intercept lifecycle over admin API | #493/#521 | **covered (#76)** — `POST /intercept` (201 + `{interceptPort,interceptUrl}`), `GET /intercept` (404 + "intercept listener not running" when off / 200 when running), double-`POST` → 409, `DELETE` → 204 then `GET` 404, repeat `DELETE` idempotent 204, on a server started **without** `--intercept-port`, in `12-intercept` (`@intercept-runtime`, external-only — the external harness spawns a dedicated no-intercept engine for it). The `rift_stop_intercept` FFI symbol is unbound in the embedded binding ([zio-bdd#323](https://github.com/EtaCassiopeia/zio-bdd/issues/323)), so the lifecycle is exercised over the HTTP admin plane here | covered by `12-intercept` (`@intercept-runtime`) |
| **`--allowInjection` door × payload parity** (external CLI gate) | #610/#612/#616/#657 | **covered (#116)** — `AllowInjectionMatrix116Spec` spawns engines **without** `--allow-injection` (which `RiftHarness` cannot) and asserts the rift#612 invariant *"the same document must get the same security answer through every door"*: `POST /imposters` refuses all 9 scripted payloads — inject response/predicate, **nested `not`/`or`** (rift#657), `predicateGenerators.inject`, `decorate`, `shellTransform`, non-numeric `wait`, `_rift.script`, and an **unparseable `_behaviors`** which must fail *closed* (rift#610) — while a clean imposter is accepted; `PUT /imposters`, `POST /imposters/{port}/stubs` and `POST /intercept/rules` (rift#657/#659) refuse likewise; `--configfile` **aborts startup** naming the file and port; `--datadir` **skips only** the scripted file and still serves the clean one (which is also the rift#612 restart-persistence case — a persisted scripted imposter is skipped, not executed); `POST /admin/reload` returns 400 **and leaves running imposters untouched**. Every door answers `400 "invalid injection"`. The admin doors each carry a gate-**on** twin, so the refusal is shown to come from the gate rather than from a schema rejection — a distinction that is not academic: the first cut of this spec sent a bare stub to the add-stub door and an `inject` *action* to the intercept door, both of which are schema errors refused whether the gate is open or shut, and both looked green while testing nothing. `--datadir` and `POST /admin/reload` discriminate on observable **state** instead (which ports end up served / stay served) | covered by `AllowInjectionMatrix116Spec` |
| `allowInjection` in `rift_serve_admin` | #492/#511 | **covered (#82)** — `AllowInjectionGate82Spec` serves the in-process admin plane twice: `allowInjection:false` → `POST /imposters` of an inject imposter is rejected `400 "invalid injection"`; `allowInjection:true` → the same imposter is accepted `201`. Embedded-only + self-gating (RIFT_MODE=embedded && FFM available), via `EmbeddedAdmin.serve(…, allowInjection)` | covered by `AllowInjectionGate82Spec` (embedded FFM leg) — false rejects, true accepts |
| `x-rift-generator-error` proxy header | #498/#507 | **covered (#77)** — a proxyOnce proxy whose `predicateGenerators.inject` throws tags the proxied response with `x-rift-generator-error: script-error` and records **no** shadowing match-all stub (the proxy still forwards the upstream 200); asserted in `10-proxy-record` (external-only) via the `proxy/generror-*` fixtures | covered by `10-proxy-record` — header value + stub count stays at the single proxy stub after recording settles |
| Script-timeout contract — remaining inject sites | #499/#515 | **covered (#78)** — response-inject → 504 "injection timeout", predicate-inject → 504 "predicate injection timeout", decorate → undecorated 200 (non-strict) / 504 (top-level `strictBehaviors`), all carrying `x-rift-script-timeout: true` (+ `x-rift-inject-error`/`x-rift-decorate-error`), in `04-scripting-bounds` via the `scripting/inject-*-timeout` fixtures (a `Date.now()` spin under a tiny `timeoutMs`) | covered by `04-scripting-bounds` — 4 scenarios, one per inject site plus the decorate strictBehaviors variant |
| Script error-response contracts (non-timeout) | #500/#509 | **covered (#79)** — a throwing (not slow) script per inject site: response-inject → 400 "invalid injection", predicate-inject → 400 "invalid predicate injection" (both `x-rift-inject-error`), decorate → undecorated 200 (non-strict) / 500 (top-level `strictBehaviors`) (`x-rift-decorate-error`), all WITHOUT `x-rift-script-timeout` (the marker that separates an error from the #78 timeout), in `04-scripting-bounds` via the `scripting/inject-*-error` fixtures | covered by `04-scripting-bounds` — 4 scenarios, one per inject site plus the decorate strictBehaviors variant |
| Bare `scenarioName` provisions a real flow store | #514/#520 | **covered (#80)** — a bare-`scenarioName` stub (no `requiredScenarioState`/`newScenarioState`) now lists at `"Started"` and a direct `PUT /scenarios/{name}/state` persists (read back), with `POST /scenarios/reset` clearing it back to `"Started"` — proving a real store, not the pre-0.13.0 silent no-op; in `07-spaces-and-fsm` via `spaces/bare-scenario` | covered by `07-spaces-and-fsm` — Started → PUT custom (persists) → reset → Started |
| ASCII-only case folding for `contains`/`startsWith`/`endsWith` | #480/#508 | **covered (#81)** — a `caseSensitive:false` predicate per kind (startsWith `café`, contains `naïve`, endsWith `élan`): the ASCII part folds (`CAF`→`caf`) but the accented letter no longer folds, so an accent-case-swapped body (`É`/`Ï`) falls through to the match-all fallback; in `02-core-serving` via `core/case-fold` (6-row Outline: ASCII-fold hit + non-ASCII miss per kind) | covered by `02-core-serving` — ASCII folds, `É`/`é` and `Ï`/`ï` don't |
| SDK conformance corpus release artifact | #460/#516/#524 | **covered (#83)** — `SdkCorpusReplay83Spec` downloads the pinned `sdk-conformance-v<version>.tar.gz` (`scripts/fetch-sdk-corpus.sh`, sha256-verified + cached), asserts `manifest.json` matches the fixtures on disk (schemaVersion, `engineVersion == v<pin>`, every `fixtures[].file` present, `hasVerify` flag == on-disk `_verify`, count), then loads the corpus into a dedicated `--allow-injection` engine (cwd = `corpus/`) and drives `rift-verify --skip-dynamic --verify-dynamic` → 0 failures. Self-gating (offline / no `RIFT_BUILD_DIR` → no-op). External-leg only (`engineConformance`; spawns a real engine + `rift-verify`, so excluded from the enumerated embedded/container aliases) | covered by `SdkCorpusReplay83Spec` — manifest-vs-disk + rift-verify replay |
| rift's shipped `examples/` load **and behave** | #608 | **covered (#120)** — `ShippedExamples120Spec` replays all 6 shipped examples: each loads verbatim → 201 and is registered, then 34 drives assert status **and** a distinguishing response body (a wrong-stub match fails; an unexpected `x-rift-no-match` fails, and one drive is a *negative control* asserting the header IS still emitted on a genuine miss, so the guard can't go inert), and `rift-lint` over the same directory → 0 errors, over exactly the files on disk. The hand-curated drive table is checked against the vendored directory, so a pin bump that syncs a **new** example fails rather than leaving it silently undriven. The rift#608 shape — `{"wait":{"inject":…}}` plus two numeric `wait`s — is asserted against elapsed-time floors; stripping every `_behaviors` block reproduces rift#608 exactly (load + lint stay green, the 3 latency floors go red), which is the evidence the behave gate discriminates rather than merely exists. Inputs vendored under `corpus/rift-examples/` because **the release tarball ships no `examples/`**; `scripts/sync-rift-examples.sh --check` compares git blob SHAs against the tag and is part of the pin bump — a stale vendored copy is the one way this gate could pass vacuously. Two drives pin an *example* defect, not an engine one: `?status=OPEN` and `/tasks/task-999` are unreachable behind more general stubs under first-match-wins ([achird-labs/rift#805](https://github.com/achird-labs/rift/issues/805), filed from here), asserted as they actually behave so an upstream reorder shows up. Self-gating on `RIFT_BUILD_DIR`. External-leg only | covered by `ShippedExamples120Spec` — 6 examples load + 34 behavior drives + lint |

Robustness/perf changes in 0.13.0 that don't need new gap rows but are worth noting: per-imposter
`ctx.state` updates are now atomic under same-imposter concurrency ([rift#477/#486](https://github.com/achird-labs/rift/pull/486))
— exercised by `04-scripting-bounds`' concurrent-runaway + per-flow-id isolation scenario; every FFI
entry point is panic-guarded across the C ABI ([rift#484/#487](https://github.com/achird-labs/rift/pull/487))
— hardens the embedded (Panama FFM) leg; proxy HTTP connection pooling was re-enabled
([rift#482/#496](https://github.com/achird-labs/rift/pull/496)); and the function-form `wait`
fallback now clamps/caps like the Boa path ([rift#490/#504](https://github.com/achird-labs/rift/pull/504),
`--no-default-features` only).

## Pin bump 0.15.0 → 0.18.0 + zio-bdd 1.4.4 → 1.5.0 (2026-09-28) — closes #146, #145, #140

The pin moved three releases (0.16.0, 0.17.0, 0.18.0). The first run against 0.18.0 was red: 7 Plane A
failures across 5 specs/suites. **None was an engine defect.** Each was either a documented 0.18.0
contract change the harness had not caught up with, or a harness bug the new release exposed. The
fixes below adapt the harness, and every Plane A leg is green again (external, embedded, container).

| Red on 0.18.0 | Cause | Resolution |
|---|---|---|
| `12-intercept` ×2 (`Broken pipe`), `09` metrics counter (`delta=0`), `11-reload` (`ConnectException`) | **Harness race.** zio-bdd builds a suite's env layer more than once. The second build's `choosePort(2525)` bind probe reported a live rift's admin port **free** on macOS, so it spawned an engine that could never bind 2525. 0.18.0 binds the admin listener **last** (after metrics and intercept), so that doomed engine passed every readiness probe: the admin probe was answered by the *other* engine, metrics and intercept by its own short-lived listeners. It then exited `Address already in use`, leaving the harness on dead ports. | `probeFree` now treats a live listener (a connect to `127.0.0.1:port`) as busy. Regression test `RiftHarnessSpec` "a second layer build while the first engine is alive gets its own live engine" builds the layer twice against a real engine. It fails without the fix (the second build shares the first's admin URL and has a dead metrics port) and passes with it. A JVM-side decoy socket does not reproduce the bug; only a real rift does |
| `AllowInjectionMatrix116Spec` `--datadir` case | rift#1128: a `--datadir` file must be named `<port>.json`, and any other name is skipped | Fixture files renamed `9500.json`/`9501.json`. The spec also asserts the skip reason names `allowInjection`, so it cannot pass for the naming reason |
| `AllowInjectionMatrix116Spec` unparseable `_behaviors` | 0.18.0 refuses a malformed behavior as a `400 bad data` load error on every door, before the injection classifier runs | Still refused, so the rift#610 fail-closed invariant holds, and the gate simply never sees it. `SchemaRefused` accepts `bad data` for that one payload only |
| `ShippedExamples120Spec` lint | rift#1094/#1156: a directory lint reports `E002` for ports shared *across* files, and four standalone examples share 4545 | The examples are linted one file per run, since each is loaded alone. **Filed [rift#1217](https://github.com/achird-labs/rift/issues/1217)** (TOOL-DRIFT): `examples/README.md` tells users to run `rift-lint examples/`, which now exits 1 on rift's own examples |

The vendored examples are synced to v0.18.0. `task-management-api.json` carries the rift#808 reorder, so
the two pinned-divergence drives flip to the advertised `count:1` and `404` (**closes #140**).

### Embedded leg: engine-version split closed, binding gap gone

zio-bdd 1.5.0 (zio-bdd#336) removed `zio-bdd-rift-embedded*`. The embedded engine is now rift-java's
`rift-java-embedded` + `rift-java-natives` **0.3.2**, which embeds rift **0.18.0**, the same release as the
binary legs. The split recorded in the sections below is **closed**. Neither zio-bdd's `EmbeddedRift`
nor rift-java's public `Rift.embedded` can pass `serve_admin` the `allowInjection` and `configFile`
options, or take raw intercept-rule JSON. So `EmbeddedAdmin` (now `samples.rift.support`) binds the
C-ABI through rift-java's package-private `RiftFfi` in a single JDK-22-only class
(`src/test/java-jdk22/.../ConformanceFfi.java`, compiled only when `ffmEnabled`).

`RiftFfi` binds the **whole** C-ABI: the 12 v2 admin symbols, `rift_verify`, `rift_stub_warnings`, and
intercept list/clear/`ca_pem`. The binding gap of **#96** is therefore gone, and the FFI legs of
**#75 / #92 / #94** plus the `@intercept-http` embedded exclusions are reachable. They are not wired
yet: `ConformanceFfi` wraps only the calls the harness uses today.

### Plane B on 1.5.0

- **wiremock (`mockConformance`) is green**, fixing the #143 red: zio-bdd#338 makes `--exclude-tags`
  drop a scenario that also carries a parameterized `@mock(...)` tag. The missing Plane B CI job in
  #143 still stands.
- `FixedPort25`: a pooled adapter (`Rift.connect`) now refuses an authored port outside its pool
  (zio-bdd#213/#303, documented). `Backends.pinnablePorts` exposes the rift-external pool, and the
  suite pins a free port from inside it.
- Two library regressions were filed and **not worked around** (FINDINGS F6/F7):
  [zio-bdd#349](https://github.com/EtaCassiopeia/zio-bdd/issues/349) (recorded text body reads back
  JSON-quoted; `08-received` red on rift-external + embedded) and
  [zio-bdd#350](https://github.com/EtaCassiopeia/zio-bdd/issues/350) (raw documents with `"port": 0`
  refused; every raw-document scenario red on rift-external).

### 0.16.0–0.18.0 new-capability gaps

These are already tracked by open issues filed as the releases shipped:

- intercept serve-rule non-string body: #148
- outbound `--upstream-ca-file` and imposter `mutualAuth`: #149
- intercept tunnel 413 and decoded chunked bodies: #150
- CONNECT tunnel reuse: #151
- intercept multi-value headers and HTTP/2 negotiation: #152
- reload re-applying front-door routes: #153
- behaviors on proxy/inject responses: #154
- repeated copy behaviors: #155
- the ordered behaviors program: #156
- templating substitution not re-expanded: #157

Not yet tracked: `rift_preface_failures_total{listener,kind}` (rift#1045, visible on `/metrics` in
0.18.0), graceful `SIGTERM` shutdown (#1155), and WebSocket upgrade through the intercept tunnel (#997).

## v0.15.0 new-capability coverage gaps (pin bump 0.14.0 → 0.15.0) — tracked by [#72](https://github.com/EtaCassiopeia/rift-conformance/issues/72)

Verified green on the bump: all 14 Plane A features pass against 0.15.0 with no conforming change.

| Capability / change | rift | Coverage status |
|---|---|---|
| **`type` slug on every error-envelope entry** — `{"code","type","message"}`; six slugs copied verbatim from Mountebank's `errors.js`, the rest naming doors Mountebank has no equivalent for | #797 | **covered (#132)** — `13-error-envelope` asserts `type` **and** `code` on all 14 doors, each slug probed against a live 0.15.0: `script error`, `script timeout`, the four inject slugs (where `type` == `code`), `behavior error` (shared by the three strictBehaviors doors, so `type` is *coarser* than `code` there), `upstream failure`, `request too large`, `imposter disabled`, `bad data`. Asserting both per door is what pins the "additive, not a rewrite" promise — a release that repurposed `code` goes red here. This capability exists **because of this harness**: #797 was filed from the #118 work reporting that `code` was unbranchable, and shipped in the next release |
| Field-level `equals`-on-body predicates indexed by a quamina automaton (default-on `quamina-matching` feature) | #767/#777/#779 | **behaviour-neutral** — a prefilter; anything it cannot express falls through to full evaluation, and matching results are unchanged. Worth an eventual differential check that a body-`equals` stub set matches identically with and without the feature, but nothing is unpinned today |
| Per-core runtime topology serving imposter traffic (`--runtime per-core[=N]`, `--runtime-affinity`) | #744/#745 | **gap — opt-in, experimental.** Default work-stealing topology is unchanged, and macOS falls back to work-stealing with a warning, so this is Linux-only to exercise. Delete/drain semantics and the `RIFT_MAX_CONNECTIONS` cap are claimed identical across topologies — that equivalence is the assertable property |
| `rift_accepted_connections_total{worker=…}` per-worker accept counters | #746 | **gap** — `09-gateway-https-metrics` already scrapes the metrics endpoint; add the counter's presence/labels under `--runtime per-core` |
| Opt-in `jemalloc` allocator feature for the server binary | #717 | **n/a** — a build-time allocator choice in the engine's own release process; no wire-visible surface for this harness to assert |

## v0.13.2 → v0.14.0 new-capability coverage gaps (pin bump 0.13.1 → 0.14.0) — tracked by [#72](https://github.com/EtaCassiopeia/rift-conformance/issues/72)

The engine pin moved **0.13.1 → 0.14.0** (2026-07-16). This jump folds in five intermediate releases
(0.13.2–0.13.6) that were never on the pin, plus 0.14.0 itself. Existing scenarios stay green: the
suite asserts status codes + `x-rift-*` marker headers + body **substrings**, none of which changed
under any of the behavior changes below. The changes are therefore logged here as **coverage
opportunities** (assert the *new* contract), not as conforming fixes.

### Engine-version split (embedded leg lags)

`RIFT_VERSION` governs the **binary** legs (external + container Plane A, rift-external Plane B) —
now **0.15.0**. The **embedded** leg (embedded Plane A + Plane B) loads `librift_ffi` from
`zio-bdd-rift-embedded-natives`, pinned by `zioBddVersion`. This gap **narrowed** on the zio-bdd
1.4.2 → 1.4.4 bump (2026-07-21): zio-bdd 1.4.3 moved its rift pin 0.13.1 → **0.14.0** for both the
container image and the natives ([zio-bdd#325](https://github.com/EtaCassiopeia/zio-bdd/pull/325) —
`riftNativesVersion = riftVersion`), so the embedded leg now exercises **0.14.0** engine code, not
0.13.1. It still lags the binary legs by one release: no zio-bdd release carries 0.15.0 natives yet.
The split is ABI-safe — neither 0.14.0 nor 0.15.0 ships a C-ABI/FFI/`ffi-manifest.json` bump (0.14.0's
only FFI touch is an additive optional `rules` field on `rift_start_intercept`) — so the 0.14.0
natives load and drive unchanged; the full `engineEmbedded` + `mockEmbedded` legs verified green on
them. **Upstream follow-up:** ask zio-bdd to bump its rift pin to 0.15.0 to close the remaining lag.
The `@preflight` gate is external-only and compares BUILD_INFO against the same-fetch binaries, so it
stays coherent at 0.15.0.

**Not narrowed by 1.4.4 — the FFI binding gap ([#96](https://github.com/EtaCassiopeia/rift-conformance/issues/96)).**
`librift_ffi` in the 1.4.4 natives exports all 12 v2 admin symbols plus `rift_verify` and
`rift_stub_warnings` (verified with `nm` on the published jar), but the Scala binding still wraps only
the same 15 as 1.4.2 — `EmbeddedEngine` has no methods for them and `RiftFfi` has no generic
`call(symbol, json)` dispatch. So this is a **binding** gap, not a natives gap, and the FFI legs of
[#75](https://github.com/EtaCassiopeia/rift-conformance/issues/75),
[#92](https://github.com/EtaCassiopeia/rift-conformance/issues/92) and
[#94](https://github.com/EtaCassiopeia/rift-conformance/issues/94) stay blocked on it.

### Behavior changes now live (non-breaking to existing scenarios)

| Change | rift | Release | Coverage status |
|--------|------|---------|-----------------|
| Error-body envelope unified: proxy/`defaultForward` 502 **and** every imposter error door → `{"errors":[{"code","message"}]}` (was `{"error":"…"}`), now valid JSON even when the message contains `"` | #611 sweep completion | 0.14.0 | **covered** (#118) — `13-error-envelope.feature` pins the shape on the strictBehaviors shellTransform/binary doors, `defaultForward` 502, the 413, the disabled 503 and the admin-plane 400, plus the quote round-trip regression **and** the script-timeout / four-`inject` / two strict-decorate doors. `Unknown fault`'s plain-text 500 is pinned as a **negative** so a later sweep can't absorb it. **Still external-only, but no longer by necessity:** the envelope postdated the embedded leg's then-pinned 0.13.1 natives, so `04-scripting-bounds` — which also runs embedded — asserts only what both engine versions agreed on (status + marker headers), and every envelope assert lives in the external-only feature 13. The zio-bdd 1.4.4 bump moved the embedded natives to **0.14.0**, so that constraint is **lifted** — folding the script/inject envelope asserts back into 04 (and enrolling feature 13 in `engineEmbedded`) is now a live follow-up, not a blocked one |
| `Content-Type: application/json` now on every imposter error door that serves the envelope | #611 | 0.14.0 | **covered** (#118) — asserted inside the `the response is a rift error envelope` step, so every door that claims the envelope also proves the content-type |
| **Declarative `intercept` block in `--configfile`** (`{host?,port?,ca?,rules[]}` beside `imposters`) | #655 | 0.14.0 | **covered (#113)** — `ConfigInterceptBlock113Spec` spawns engines from a config file with **no** `--intercept-*` flag (the shared harness always passes one, and block + flag is a hard startup error) and pins the declarative wiring: the listener is **bound** (asserted by connecting to it, not just by reading the admin JSON back) and both its rules — including a `forward` at an imposter declared in the **same** document — exist at boot with only READ calls made; a runtime `POST /intercept/rules` layers on top; `POST /admin/reload` returns `200` with a `warnings` entry stating the block was **NOT re-applied** and leaves the rules untouched (silence there would let an edited block look applied); and both startup-error doors fire — block + `--intercept-port` names both plus the `RIFT_INTERCEPT_*` env spelling, and a block outside the `{"imposters":[…]}` wrapper form names the fix rather than being silently ignored. The TLS-MITM **data** path stays covered imperatively by feature 12 |
| `POST /intercept/rules` (+ `rules` on `POST /intercept`, + the `--configfile intercept` block) enforce `--allowInjection`; an `inject` predicate → `400`, atomic, sees through `not`/`or`/`and` | #657/#659 | 0.14.0 | **partial** — the `POST /intercept/rules` door is covered by `AllowInjectionMatrix116Spec` (#116: gated-`inject` rejection with a gate-on twin; a rule's injectable surface is its `predicates`, `inject` is not an action variant), and the `--configfile intercept` block door by `ConfigInterceptBlock113Spec` (#113: a block rule with an `inject` predicate aborts startup gate-off, boots gate-on). The nested `not`/`or` case is covered at the imposter door. **Still open:** the `rules` array on `POST /intercept` (start-and-seed in one call), and **atomicity** — a multi-rule body with one poisoned rule must install none |
| Query-param **names** percent-decoded everywhere (`?first%20name` → `first name`) | #619 | 0.13.6 | **gap** — matching-semantics change; add a predicate keyed on a decoded query-param name |
| `DELETE /imposters[/{port}]` awaits full teardown before returning (port no longer serves after DELETE) | #597 | 0.13.5 | **gap** — assert connection-refused immediately after DELETE |
| `PUT /imposters` is atomic (no lossy delete-then-recreate on partial failure) | #582 | 0.13.3 | **gap** |
| Intercept rule store capped 10k → `429`; admin body capped 64 MiB → `413` | #579/#567 | 0.13.3 | **gap** — net-new status codes |
| `flowState.ttlSeconds` ≤ 0 rejected `400` at create (was accepted) | — | 0.13.2 | **gap** — pin the reject |
| Constant-time admin API-key comparison | #566 | 0.13.3 | not black-box assertable (timing) — no gap row |

### Cache-key correctness — regression-pin opportunities (both fixed in 0.14.0)

0.14.0 closed the decision-cache key-completeness family (cache on by default, 300 s TTL): the key now
covers the **query string** ([rift#660](https://github.com/achird-labs/rift/issues/660)) and the
**raw non-JSON body** ([rift#652](https://github.com/achird-labs/rift/issues/652)) — previously two
requests differing only in query (or in a non-JSON body, all of which hashed to `null`) shared one
cached fault decision. **Gap:** a script branching on `ctx.request.query` / `ctx.request.raw_body`,
two requests differing only in that dimension → assert independent decisions (the fix holds). Same
invariant, third instance (headers) landed earlier via #630.

### New capabilities

| Capability | rift | Release | Gap | Sketch |
|------------|------|---------|-----|--------|
| Declarative `intercept` block in `--configfile` (`{host?,port?,ca?,rules[]}`), rules seeded before the listener binds; block + `--intercept-*` flags is a startup error | #655/#658 | 0.14.0 | **[#113](https://github.com/EtaCassiopeia/rift-conformance/issues/113) now UNBLOCKED** — the release carrying #655 has shipped; land #113's 5 scenarios | feature 12, dedicated no-`--intercept-port` engine |
| `rules` array on `POST /intercept` + FFI `rift_start_intercept` — start-and-seed in one call | #655 | 0.14.0 | gap | POST with `rules[]`, assert listener + rules up atomically |
| `POST /admin/reload` emits a `warnings` entry (+ logs) when the reloaded file carries an `intercept` block (reload still applies imposters only) | #655 | 0.14.0 | gap | reload a config with a block → 200 + warnings, listener untouched |
| `rift healthcheck` CLI subcommand (probes `/health`, exits 0/1, reads `--host`/`--port`/`MB_*`) | 0.14.0 | 0.14.0 | gap | offline tool spec, like `RiftScriptTooling88Spec` |
| `-static` image flavor (`FROM scratch`, musl; no shell, no curl, cosign-signed + SBOM) | 0.14.0 | 0.14.0 | gap (container leg) | smoke that `:<ver>-static` serves the corpus; exec-form only |
| `GET /events` admin SSE stream (request tails + imposter events) | #518 | 0.13.6 | **covered (#122)** — `AdminEventsCursor122Spec` subscribes with `Accept: text/event-stream`, drives one request, and asserts `200` + `Content-Type: text/event-stream`, the opening `event: hello` frame (with `engineVersion`, so a subscriber knows it is connected before any traffic — the thing that removes the poll), and an `event: request` frame carrying the journal `index` plus an SSE `id:`. Note those are two different numbers — the `id:` is the stream's own sequence, not the journal index — so each is asserted for what it is. The read stops on two **complete** frames and closes, so a never-ending stream cannot hang CI | covered by `AdminEventsCursor122Spec` |
| `savedRequests?since=<index>` cursor (per-port monotonic journal indices) | #603/#607 | 0.13.6 | **covered (#122)** — record 3, then `?since=0` → 3, `?since=1` → the 2-item tail, `?since=99` (past the end) → an **empty array**, never an error or an object. Element counts are structural, not substring matches. This is also the endpoint rift#606 broke by serving `{}` where an array belongs — a shape bug a consumer only meets in its own decoder — so the array assertion guards that regression too | covered by `AdminEventsCursor122Spec` |
| `rift_abi_version()` FFI symbol (SDKs gate on ABI, not marketing version) | #592 | 0.13.4 | gap (embedded leg) | drive over FFI, assert the ABI contract version |
| Intercept CA: inline `caCertPem`/`caKeyPem` input + generate-and-return bootstrap mode | #594 | 0.13.4 | gap | POST /intercept with inline PEM; assert the returned CA |
| Per-key flow-state TTL `ctx.state.ttl(key, seconds)`; probabilistic TCP faults `{probability,type}` | #535/#533 | 0.13.2 | gap | extend `04-scripting-bounds` / `03-faults-transport` |

Each row is independently `/fix-issue`-able; split into a child of #72 when picked up. Priority order
(recurrence × black-box cheapness): (1) `POST /intercept/rules` allowInjection gate + the door-parity
matrix; (2) the error-envelope + content-type unification; (3) query-param-name decoding; (4) the
declarative intercept block (#113); (5) cache-key regression pins.
