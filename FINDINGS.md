# Findings — Plane B MockControl mock corpus

Migrated from `zio-bdd-samples` (issue #13); the F-series ledger below predates the migration and
is preserved as-is. Defects and gaps surfaced by the sample corpus. Each links the tripping sample,
the backend, and the filed `zio-bdd` issue. The standing-conformance charter + pass log lives in
[CONFORMANCE.md](CONFORMANCE.md) (this repo supersedes the former umbrella issue rift#254, now
closed). Corpus stays static/numbered; only additions + status flips.

## Triage decision tree (DESIGN.md §8)

Every new finding is triaged with this tree before it gets a row below or a filed issue:

```
Scenario red
├─ Plane A?
│   ├─ Re-run the emitted curl repro against the harness adminUrl.
│   │   ├─ Engine misbehaves  → ENGINE finding → file on rift, ref this repo (CONFORMANCE.md).
│   │   ├─ Engine fine, rift-lint/rift-verify disagreed → TOOL-DRIFT finding → file on rift.
│   │   └─ Engine fine, curl matches expectation → corpus/step bug → fix in samples, no issue.
│   └─ (@gap scenario started failing = the pinned divergent behavior CHANGED:
│       either the fix landed (flip the scenario, promote, update FINDINGS) or an
│       unannounced behavior change (investigate, possibly file).)
└─ Plane B (rift/rift-external/embedded)?
    ├─ Same behavior green in Plane A          → LIBRARY finding → file on zio-bdd.
    ├─ Also red in Plane A                     → engine finding (Plane A owns it).
    └─ Red on one Plane B backend only         → localize: wiremock-only → SPI/portability;
                                                  embedded-only → FFI adapter; container-only
                                                  → managed-container path.
```

The Backend(s) column below is annotated with the plane each finding was surfaced on
(`plane-A/engine`, `plane-B/wiremock`, `plane-B/embedded`, `plane-B/rift-external`,
`plane-B/framework` for framework-level findings that affect every Plane B backend). All F-series
rows below predate this harness (they were found in `zio-bdd-samples`, pre-migration) and are all
Plane B findings — no `plane-A/engine` finding has been filed from this repo yet.

| # | Finding | Backend(s) | Tripping sample | zio-bdd issue | Status |
|---|---------|-----------|-----------------|---------------|--------|
| F1 | `SutClient`/`MockSteps` send over `HttpClient.newHttpClient()` (HTTP/2 default); against an HTTP/1.1 mock server the exchange fails with `EOFException` (`Http2Connection`) — reproducibly on **201** responses under the ZIOBDD runtime. Fix: pin `HttpClient.Version.HTTP_1_1`. | plane-B/wiremock (Correlated + PerInstance); recheck plane-B/rift | 01-basic-matching (201 regression scenario) | [zio-bdd#183](https://github.com/EtaCassiopeia/zio-bdd/issues/183) (CLOSED) | ✅ fixed & workaround reverted — `MockSupport.send` now drives the real `zio.bdd.mock.SutClient`; sample 01's 201-regression scenario is green on WireMock |
| F2 | The `string` typed extractor was greedy (`"\".*\""`) so a step with two quoted args had its first `{string}` span across both; it also never unescaped `\"`/`\\`, so an inline JSON body `"{\"a\":1}"` arrived with literal backslashes (invalid JSON → jsonpath/body matchers miss). Fix: escape-aware non-spanning quoted match + Cucumber-style unescaping. | plane-B/framework (all backends) | 04-body-matching (jsonpath), 03-value-matching | [zio-bdd#184](https://github.com/EtaCassiopeia/zio-bdd/issues/184) (CLOSED) | ✅ fixed (525 core tests green; 03/04 green) |
| F3 | With overlapping step prefixes, the old fewest-extractors rule + the `string` `.*` unquoted fallback let a shorter step (`order {string}`) full-match a longer step's line (`order … rush …`), silently mis-binding args. Fixed in the library by a `mostSpecificWinner` tie-break: most-literal-characters wins, extractor count only breaks a literal-length tie (`StepRegistry.specificity`). | plane-B/framework | **22-overlapping-steps** (new, direct assertion); 04/07/16/21 (defensive phrasing retained) | [zio-bdd#186](https://github.com/EtaCassiopeia/zio-bdd/issues/186) (CLOSED) | ✅ fixed & verified — sample 22 asserts the longer definition wins the greedy-fallback tie; the non-overlapping phrasings in 04/07/16/21 are kept as belt-and-suspenders, not because the sharp edge remains |
| F4 | Transport-parity gap: the **container** Rift adapter advertised all six capabilities; the **embedded** (FFI) adapter — the same Rift engine over Panama — advertised **none** (`capabilities = Set.empty`, all accessors `Unsupported`), so embedded users couldn't reach faults/scripting/etc. through the portable API. Fixed: embedded now advertises **and executes** Faults/Scripting/ProxyRecord/Templating via stub-based caps (`_rift.fault`/`_rift.script`/`proxy`/template) wired to the engine over the C-ABI (`EmbeddedRiftMockControl`). Stateful/StateInspection stay `Unsupported` on embedded by design (no scenario-state C-ABI endpoints). | plane-B/embedded (FFI) | 11/14/15/16 now RUN & green on embedded; 12/13 stay `➖` | [zio-bdd#185](https://github.com/EtaCassiopeia/zio-bdd/issues/185) (CLOSED) | ✅ fixed & verified — embedded conformance leg green with the four caps un-gated; parity confirmed |
| F5 | Raw imposter provisioning **silently forces `recordRequests: true`**: `RiftProtocol.imposterFromRaw` (the path for `provision(MockSource.Resource/File)` **and** `provisionNative(NativeSpec.Rift)`) `upsert`s `recordRequests -> true` unconditionally, so an authored `"recordRequests": false` in a raw doc is overridden — recording is always on, deviating from Mountebank semantics. Surfaced while adding raw-source `received()` coverage; flipping the fixture flag true↔false makes no difference (verified rift 0.11.3 / rift-external). Low severity (recording-on is the safe default for the portable `received()` capability), but the override is silent. | plane-B/rift-external (framework) | **09-mock-sources** (received() on a raw Resource-provisioned space, #63) | [zio-bdd#294](https://github.com/EtaCassiopeia/zio-bdd/issues/294) (CLOSED) | ✅ fixed & verified — zio-bdd#297 (in 1.4.2, the pinned release) makes `RiftProtocol.imposterFromRaw` inject the `recordRequests` default only when the field is **absent**, so an authored `false` is honored. Adapter-level fix, engine-agnostic. `09-mock-sources` gains a false-variant scenario (`recorded-off.json`) pinning `received()` empty; the pre-existing true-variant keeps the other side. Stale "adapter force-sets recording" comment removed |

## F1 — HTTP 201 + body → `EOF reached while reading`

**Minimal repro** (WireMock backend, in-process, no Docker), via the built-in
`zio.bdd.mock.steps.MockSteps` steps:

```gherkin
Scenario: plain 201 with body, no overlay
  Given a mock space returning 201 "created" at "/c"
  When a "GET" "/c" is sent through the space
  Then the space response status is 201     # ← fails: EOF reached while reading
Scenario: plain 204 no body                 # ← PASSES
Scenario: plain 500 with body               # ← PASSES
```

**Narrowing:**
- Deterministic. Reproduces on both `MOCK_BACKEND=wiremock` (Correlated) and
  `wiremock-pi` (PerInstance) — not isolation-related.
- Independent of overlays: a *plain* provision returning 201+body fails; the
  earlier "overlay EOF" was just the overlay's 201 response.
- `204` (empty body) and `500` (with body) both succeed → specific to **status 201
  carrying a body**.
- Both the core `MockSteps.sendThrough` and `zio.bdd.mock.SutClient.Live.send`
  use `java.net.http.HttpClient.newHttpClient()` (HTTP/2 default) per request, so
  the failure is in the SUT-client ↔ WireMock exchange for a 201-with-body
  response. The library's own conformance suite never asserts a 201+body response,
  so its `SutClient` round-trips never hit this — a coverage gap this corpus closes.

**Root cause (confirmed):** both `zio.bdd.mock.SutClient.Live.send` and
`zio.bdd.mock.steps.MockSteps.sendThrough` build `HttpClient.newHttpClient()`,
which defaults to `HTTP_2`. Against a cleartext HTTP/1.1 server (WireMock/Jetty)
the JDK client's HTTP/2 path throws:

```
java.io.EOFException: EOF reached while reading
  at jdk.internal.net.http.Http2Connection$Http2TubeSubscriber.onComplete(Http2Connection.java:1819)
```

Isolation of the variable, all on the same provisioned 201 space:
- Standalone `ZIOAppDefault`: raw `HttpClient` (HTTP/1.1 **and** HTTP/2) and
  `SutClient.send` all return `201 body='created'` fine.
- Inside the ZIOBDD test runtime: the same `SutClient.send` / `MockSteps` send
  throws the EOF above on a 201 (a 200 on the same path succeeds).
- A client pinned to `HttpClient.Version.HTTP_1_1` returns 201 correctly in every
  context.

So the runtime scheduling tips the JDK HTTP/2-vs-HTTP/1.1 handling into the EOF for
a 201 response. **Recommended library fix:** pin `HttpClient.Version.HTTP_1_1` in
`SutClient` (and `MockSteps.sendThrough`) — mock servers are HTTP/1.1 and there is
no reason to negotiate HTTP/2.

**Resolution:** #183 merged (`SutClient` pins `HttpClient.Version.HTTP_1_1`) and
the corpus workaround has been **reverted** — `MockSupport.send` now drives the
real `zio.bdd.mock.SutClient` (`SutClient.make(space).send(...)`), honouring
`space.inject`. Sample 01's 201-with-body regression scenario is green on WireMock
through the real client, so F1 is validated end-to-end. Re-check on the Rift
backend when the Docker leg runs.

## By-design / non-findings (do not file)

- **Gherkin i18n `# language:` (zio-bdd#192 §F#3 / D1)** — the parser silently
  strips a `# language:` header and treats every feature as English. This is
  **intentional; English-only by design, won't-fix** (confirmed by the maintainer,
  2026-07-01). Do not file it as a bug or enhancement, and don't re-investigate it
  in future passes.
- **Soft assertions / error accumulation (zio-bdd#192 §F#4 / D4)** — `Assertions`
  throws on the first failed assertion; there is no accumulation across a scenario.
  This **fail-fast behavior is by design, won't-fix** (confirmed by the maintainer,
  2026-07-01). Do not file it as an enhancement, and don't re-investigate it.
