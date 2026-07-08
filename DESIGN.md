# Rift conformance redesign — one BDD harness, two planes

**Status:** approved design, ready to implement
**Home repo:** `rift-conformance` (this repo) — the dedicated, purpose-built conformance suite. All new work lands here.
**Source repos (migrated in, then retired/trimmed):** `rift-imposter-samples` (canonical JSON corpus + shell suite → corpus migrates here, then the repo is **retired**), `zio-bdd-samples` (the `MockControl` mock corpus / Plane B → migrated here; its framework-general samples stay).
**No changes required:** `zio-bdd` library (MVP uses the existing `Rift.connect`; optional items in §12), `rift` engine (source of truth).
**Supersedes:** the shell conformance legs of `rift-imposter-samples` (`conformance.sh`, `conformance-ext.sh`, `conformance-reload.sh`, `consistency-gate.sh`, `run-all.sh` orchestration).
**Context:** EtaCassiopeia/rift#254 (standing conformance runbook).

---

## 0. Repo topology & end state

```
rift-conformance/                    ← THIS repo. The single conformance home.
  DESIGN.md                          ← this contract
  src/test/scala/samples/rift/…      ← Plane A: engine conformance (new)
  src/test/scala/samples/mock/…      ← Plane B: MockControl corpus (migrated from zio-bdd-samples)
  src/test/resources/features/rift/… ← Plane A features
  src/test/resources/features/mock/… ← Plane B features (migrated)
  corpus/imposters/*.json            ← canonical JSON corpus (migrated from rift-imposter-samples at the end)
  scripts/ · COVERAGE.md · FINDINGS.md

zio-bdd-samples/                     ← STAYS. Loses the mock corpus (moves here); keeps
                                       calculator/auth/shopping/core framework samples.
rift-imposter-samples/               ← RETIRED once corpus migrates here (owner does the retire).
                                       Until then: read-only corpus source via RIFT_CORPUS_ROOT.
rift/                                ← engine, source of truth. Untouched.
```

Two consumers of the same engine, one repo, two attribution planes:
- **Plane A** tests **Rift** (engine + `rift-lint`/`rift-verify`) — talks raw admin API, no `MockControl`.
- **Plane B** tests **your zio-bdd stack** (framework + mock SPI + rift adapter) — drives Rift *through* the library.
Both share one `RiftHarness` (engine lifecycle) and one engine build, so running them together yields
differential (engine-direct vs engine-via-adapter) coverage for free. **This is what "test both libraries
in one scalable suite" means** — not one merged test, but one home + one harness for two planes.

**Migration ordering:** Plane A is built first (it's the bash suite you're replacing). Plane B is
*migrated in* from `zio-bdd-samples` once Plane A + the shared harness are proven. The JSON corpus is
*migrated in* from `rift-imposter-samples` last; only then is that repo retired. Every migration is gated
by a same-build parity checkpoint (§11).

## 1. Goal and rationale

Today Rift is conformance-tested twice, in two disconnected harnesses:

1. **`rift-imposter-samples`** — ~670 lines of bash (`chk`/`soft`/`gap` helpers, curl, jq)
   asserting the engine + the `rift-lint`/`rift-verify` tools against a static JSON corpus.
   Dependency-free and authoritative, but every new engine feature means more untyped bash.
2. **`zio-bdd-samples`** — 23 feature files / 76 scenarios driving the portable `MockControl`
   SPI against WireMock, a Rift container, and embedded Rift (FFI). Scalable and readable,
   but it never runs lint/verify, and a failure can't distinguish "engine bug" from
   "adapter bug" because the library sits between the test and the engine.

This design consolidates conformance into **one zio-bdd harness with two cleanly separated
planes**, so that:

- every scenario added is **typed, English-readable Gherkin**, not bash;
- the harness battle-tests **both libraries** (zio-bdd core runs Plane A; zio-bdd-mock/-rift
  are the subject of Plane B) **and** the engine, from a single corpus and a single build;
- **attribution is architectural**: which plane went red tells you which layer broke;
- Mountebank/WireMock *parity* is explicitly a non-goal (per project owner). Plane B keeps
  its WireMock leg because it tests the SPI's portability, not because we care about
  Mountebank compatibility.

## 2. The central idea: two planes, one harness, one build

```
                       ┌─────────────────────────────────────────────┐
                       │  scripts/build-rift.sh                      │
                       │  fetch rift v$RIFT_VERSION release binaries │
                       │  rift · rift-lint · rift-verify (tarball)   │
                       │  sha256-verified → BUILD_INFO (tag, vers.)  │
                       └────────────────────┬────────────────────────┘
                                            │ one build, all artifacts
                     ┌──────────────────────┴──────────────────────┐
                     ▼                                             ▼
        ┌────────────────────────────┐              ┌────────────────────────────┐
        │  PLANE A                   │              │  PLANE B                   │
        │  Engine conformance        │              │  Portable SPI corpus       │
        │  features/rift/*.feature   │              │  features/mock/*.feature   │
        │                            │              │  (exists today, unchanged  │
        │  SUT: Rift engine + tools  │              │   in role)                 │
        │  Steps talk STRAIGHT to    │              │                            │
        │  the admin API (zio-http)  │              │  SUT: zio-bdd, zio-bdd-    │
        │  + shell out to rift-lint  │              │  mock, zio-bdd-rift(-ffi)  │
        │  and rift-verify.          │              │  Steps go through          │
        │  NO MockControl anywhere.  │              │  MockControl.              │
        │                            │              │  Backends: wiremock /      │
        │  Independent oracle:       │              │  rift / rift-external /    │
        │  red here = engine or tool │              │  embedded                  │
        │  finding, never a library  │              │                            │
        │  bug.                      │              │  Red here (Plane A green)  │
        │                            │              │  = library finding.        │
        └────────────┬───────────────┘              └────────────┬───────────────┘
                     │                                           │
                     └───────────────┬───────────────────────────┘
                                     ▼
                          RiftHarness (one service)
                 owns the engine process/container lifecycle,
                 exposes adminUrl · hostFor · tools · version
```

**Attribution rule (the payoff):**

| Plane A | Plane B (rift backend) | Conclusion |
|:-------:|:----------------------:|------------|
| 🟢 | 🔴 | zio-bdd library bug (adapter / SPI / framework) → file on `zio-bdd` |
| 🔴 | any | engine or tool bug → confirm with curl, file on `rift` (ref #254) |
| 🟢 | 🟢 | ship it |

Plane A also *incidentally* battle-tests zio-bdd core (Gherkin parsing, step matching,
`Stage`, hooks, reporters run a real, non-toy suite) — so both libraries are exercised even
before Plane B runs.

## 3. RiftHarness — engine provisioning (the one shared service)

A samples-side ZIO service (in `samples/rift/support/`), NOT a library addition. It owns
the engine lifecycle and is the single source of "where is the engine and which build is it".

```scala
trait RiftHarness:
  /** Admin API base, e.g. http://localhost:2525 */
  def adminUrl: String
  /** SUT-reachable base URI for an imposter port (identity in external mode). */
  def hostFor(imposterPort: Int): String
  /** Paths to rift-lint / rift-verify from the SAME build as the engine. */
  def tools: RiftTools
  /** BUILD_INFO contents (commit SHA) + the engine's self-reported version. */
  def buildInfo: UIO[RiftBuildInfo]
  /** Restart the engine with a --configfile (reload-feature tests only).
      Scoped: reverts to the plain engine when the scope closes. */
  def withConfigFile(json: String): ZIO[Scope, Throwable, Unit]

final case class RiftTools(lint: Path, verify: Path)
```

### 3.1 Modes (selected by `RIFT_MODE`, orthogonal to Plane B's `MOCK_BACKEND`)

| `RIFT_MODE` | What it does | Docker | Surface | Use |
|---|---|:---:|---|---|
| `external` *(default)* | Spawns `rift --port <admin> --allow-injection` (the released engine binary) from `RIFT_BUILD_DIR`, kills it on scope close | no | **full** (TCP faults, HTTPS, proxy, admin, metrics) | release testing, dev inner loop, CI |
| `embedded` | Runs the engine **in-process** over Panama FFM (`librift_ffi`): `rift_start` + `rift_serve_admin` starts the **real** admin API on a loopback ephemeral port (C-ABI v2), so every Plane A step drives it over HTTP exactly as `external`. **Zero process spawn, no Docker.** Needs JDK 22+. Reload (feature 11) is the real admin API's `POST /admin/reload` reconciling toward a seeded `configFile`. | no | **near-full** (admin, transport faults via real sockets, scripting, spaces/FSM, reload; **no** separate metrics port / proxy-record — 09/10 stay `external`/`container`) | fastest master-testing inner loop, CI job 1 |
| `container` | `GenericContainer` on a **published** `RIFT_IMAGE` (default `zainalpour/rift-proxy:v0.11.3`; `build-rift.sh --image` was retired by #38). Ports published **1:1** so Plane A keeps identity addressing; a process-wide singleton (zio-bdd rebuilds the env layer) reaped via shutdown hook + Ryuk. `hostFor` = identity. | yes | **transport-surface gate**: feature 01 (gate, minus `@full-corpus`) + 03 (faults) — the published images are built without the `lua` feature, so the full-corpus serve + `rift-verify` gate stays external/embedded (#12) | Docker-run image gate, CI job 2 (RIFT_IT-gated) |

Implementation notes for `external`:
- Spawn via `zio.process` (or `ProcessBuilder` + `ZIO.acquireRelease`); wait for
  `GET /imposters` readiness with a bounded retry; register a JVM shutdown hook *in
  addition to* the scope finalizer so an aborted sbt run can't leak the child process.
- **Working directory = the corpus repo root** (`RIFT_CORPUS_ROOT`, default
  `../rift-imposter-samples`). The corpus's `lookup` behavior references
  `data/products.csv` relatively; the engine must resolve it (this is why the old
  `start.sh` ran from the project root).
- Admin port: default 2525 but **allocate from a free port** when 2525 is busy, so Plane A
  and a developer's manually-running engine can coexist. `adminUrl` reflects reality.

Implementation notes for `container`:
- Mirror `Rift.managed`'s ~30 lines (image, exposed admin + imposter port pool,
  `Wait.forHttp("/imposters")`), but samples-side so `adminUrl` is exposed —
  the library's `Rift.managed` deliberately hides it, and Plane A needs it for
  rift-verify and direct admin steps.
- `hostFor` = testcontainers host-mapped port (non-identity).

### 3.2 Build script and version coherence (`scripts/build-rift.sh`)

Standing rule (project owner, non-negotiable): **conformance always builds from a clean
clone of `master`** — never the developer's working checkout at `~/Projects/rift`, which may
carry unfinished branch work.

```
scripts/build-rift.sh   (self-contained since #38 — no clone, no cargo, no sibling repos)
  1. detect the platform triple (aarch64/x86_64 × apple-darwin/unknown-linux-gnu).
  2. download rift-v$RIFT_VERSION-<triple>.tar.gz (+ .sha256) from the EtaCassiopeia/rift
     GitHub *release* into $RIFT_BUILD_CACHE (default ~/.cache/rift-conformance/rift-v<ver>-<triple>/);
     reuse if already extracted (idempotent). RIFT_VERSION defaults to the pinned release.
  3. sha256-verify, extract → bin/{rift, rift-lint, rift-verify, rift-tui}.
  4. write BUILD_INFO: {commit: <tag SHA>, branch: "v<ver>", builtAt, binaries:{…sha256…},
     versions:{…"<bin> <ver>"…}} — the released binaries carry none, the gate (#5) requires it.
  (zio-bdd is likewise a public Maven Central release, 1.3.x; no ../zio-bdd publishLocal.)
  6. print `export RIFT_BUILD_DIR=…` for the caller.
```

**Version-coherence preflight** (first scenario of Plane A, tagged `@preflight`): assert the
running engine's reported version/commit matches `BUILD_INFO`, and that `rift-lint
--version` / `rift-verify --version` come from the same dir. This kills the
false-diff failure mode where the image, the FFI lib, and the binaries drift to different
commits. (Implementer: check what the engine exposes — `rift --version` output at minimum
is recorded in BUILD_INFO at build time; if the admin API exposes no version endpoint,
preflight compares binary paths + BUILD_INFO instead and the scenario documents that.)

Escape hatch: `RIFT_BUILD_DIR=/path/to/any/build` skips the clone entirely (for testing a
branch deliberately). The default path fetches the pinned rift release.

## 4. Corpus — canonical location and the consistency gate

### 4.1 The corpus stays in `rift-imposter-samples/imposters/*.json`

It is the dependency-free, engine-adjacent artifact referenced by rift#254, readable by
anyone with curl. zio-bdd-samples **consumes** it:

- `RIFT_CORPUS_ROOT` (default `../rift-imposter-samples`) → corpus at
  `$RIFT_CORPUS_ROOT/imposters`, fixtures at `$RIFT_CORPUS_ROOT/fixtures`,
  CSV data at `$RIFT_CORPUS_ROOT/data`.
- CI checks out both repos side by side.
- Corpus conventions are unchanged: static, numbered, add-never-renumber, one
  feature-area per file, `_verify` annotations for verify-driveable dynamics.

### 4.2 The consistency gate bypasses the adapter — by design

Verified fact: the Rift adapter's `serveImposter` **rewrites every imposter's port** to a
pool port. The consistency gate's whole point is byte-fidelity: the engine must serve the
*exact* corpus documents (fixed 45xx ports, `_verify` blocks intact) and lint/verify must
agree on *those same documents*. Therefore the gate's steps POST corpus files **directly to
the admin API** with a zio-http client — no `MockControl` in the loop. This is what
preserves Plane A as an independent oracle. (Adapter-mediated raw provisioning is itself
covered — that's Plane B samples 09/17.)

### 4.3 Gate feature (replaces `consistency-gate.sh` + the old lint leg)

```gherkin
# features/rift/01-consistency-gate.feature
@rift @gate
Feature: Cross-tool consistency — engine, rift-lint, and rift-verify agree on the corpus

  Scenario: The corpus is present and complete
    Given the corpus directory is loaded
    Then the corpus contains at least 15 imposter files    # guard: no silent empty-dir pass

  Scenario: rift-lint accepts every corpus file
    Then rift-lint reports 0 errors across the corpus      # per-file detail in the failure message

  Scenario: The engine serves every corpus imposter on its declared port
    Given every corpus imposter is posted to the engine
    Then every corpus imposter answers on its declared port

  Scenario: rift-verify drives the loaded corpus with no failures
    Given every corpus imposter is posted to the engine
    Then rift-verify reports 0 failures                    # --skip-dynamic --verify-dynamic
```

Per-file results are aggregated inside the steps (a `chk`-style collector on `Stage`); a
failure message lists every disagreeing file — same information the shell gate printed,
now in one place. Scenario parallelism for this feature is **1** (fixed corpus ports).

## 5. Plane A feature map — porting the shell asserts

Every `chk`/`gap`/`soft`/timing assert in the shell scripts maps to a named scenario. The
implementing model must do a **1:1 port audit** (each shell assert → scenario name, or a
documented, justified drop) recorded in `COVERAGE.md` (§10).

| Feature file (`features/rift/`) | Replaces | Content (all asserted today in shell) |
|---|---|---|
| `01-consistency-gate.feature` | `consistency-gate.sh`, `lint.sh` | §4.3 above |
| `02-core-serving.feature` | `conformance.sh` | predicates (`not`+startsWith, matches, jsonpath, xpath); repeat:N cycling; `copy` path-group; FSM two-step transition; multi-value `Set-Cookie`; date template; `defaultForward`; cycling wrap |
| `03-faults-transport.feature` | `conformance.sh` + ext fault section | tcp reset → transport error; `RANDOM_DATA_THEN_CLOSE`; latency ≥ floor timing; error fault status/body/headers; **precedence tcp>error>latency**; `probability: 0` no-op; top-level `fault` resets at the transport level (rift#309, fixed in 0.9.0) |
| `04-scripting-bounds.feature` | ext scripting + #308 section | rhai flow_store counter increments across requests; lua result; js inject result; **#308**: runaway rhai under `timeoutMs: 300` → 500 + `x-rift-script-error` in ~300ms; engine + sibling imposter stay responsive with 3 concurrent runaways in flight |
| `05-templating.feature` | ext templating | `${request.method/path/query.*/headers.*}` vars; `{{NOW}}`; `{{DAYS+N}}`; `{{DAYS-N}}`; `{{MONTHS±N}}` |
| `06-behaviors.feature` | ext behaviors | `copy` from header / via jsonpath; `lookup` CSV column (needs corpus `data/`); JS-function `wait` ≥ floor; `shellTransform` on a static `is`; Set-Cookie multi-value survives a copy behavior (rift#272) |
| `07-spaces-and-fsm.feature` | conformance correlated + ext spaces + #311 | per-space vs global stubs; spaces admin CRUD; teardown isolation (alpha down, beta+global intact); space×FSM no-bleed; **#311**: sequential A→B one-shot; concurrent burst on a fresh flow always settles monotonically to the terminal state (the racy first-state count is deliberately NOT asserted) |
| `08-admin-surface.feature` | ext admin | stub by-id GET; enable/disable → 503/200; `?match=header:…` filter AND-semantics; targeted `savedRequests?match=` delete keeps non-matching; `X-Rift-Debug` introspection; flow-state KV lifecycle + 404 (#190); `PUT /imposters` atomic validate-then-swap |
| `09-gateway-https-metrics.feature` | conformance gateway/https + ext metrics | `/__rift/:port/<path>` gateway; HTTPS self-signed (TLS client, no cert verify); `_rift.metrics` counter increments on the dedicated metrics port; binary `_mode` decode |
| `10-proxy-record.feature` | conformance proxy + ext proxy | proxyOnce records→replays (upstream torn down mid-test); recorded stub prepended only with `predicateGenerators`; multi-level proxyAlways chain; proxyTransparent records nothing; predicateGenerators capture query/headers |
| `11-reload.feature` | `conformance-reload.sh` | **#316**: engine restarted `withConfigFile`; recorded requests pre-reload; `POST /admin/reload` returns `{created,replaced,stubPatched,deleted}`; identical config → all-zero report; unchanged imposter preserves recorded state; both imposters still serve |
| `12-intercept.feature` | intercept/TLS-MITM (rift 0.10.0/0.11.0, #48) | harness starts an `--intercept-port` listener (`RiftHarness.interceptEndpoint`); `/intercept/rules` add/list/clear; CA + PKCS#12 truststore export (`x-truststore-password`); a JDK `HttpClient` CONNECT-tunnels an HTTPS request **through** the listener, trusting the exported CA, and asserts the decrypted request is matched — served inline or forwarded to an imposter. Runs under **external** and **container** (#51 — the container publishes an `--intercept-port` 1:1, so the suite reuses unchanged); embedded (FFI `startIntercept`/`InterceptConfig`) + a Plane B intercept corpus + the caller-provided-CA scenario are follow-ons |

Conventions carried over from the corpus repo:
- **Fresh imposter per scenario** (the dirty-state trap): stateful/cyclic scenarios provision
  their own imposter on a pooled 48xx port and tear it down `afterScenario` — exactly the
  `mk`/`cleanup` pattern, now typed.
- **`@gap` replaces `gap`/`soft`**: an expected-divergence scenario asserts the *current*
  (wrong) behavior, carries the issue link in a comment, and flips to the correct assertion
  when the fix lands. Runs stay green; divergence stays visible in the feature file and
  FINDINGS.md. (Rationale: zio-bdd has no non-fatal assert; pinning current behavior also
  catches *unannounced* behavior changes.)
- **Transport scenarios** (`03`, HTTPS in `09`, proxy in `10`) are tagged `@transport`;
  they require `RIFT_MODE=external|container` (which Plane A always has — the tag is
  documentation + a guard step).

## 6. Plane A step architecture

One shared trait, mirroring the proven `MockSupport` shape:

```scala
// samples/rift/support/RiftConformanceSupport.scala
trait RiftConformanceSupport[S] { self: ZIOSteps[RiftHarness, S] =>

  // ── plumbing ──
  // POST an imposter (JSON string) to the admin API; track its port on Stage;
  // afterScenario deletes every tracked port. Direct zio-http — no MockControl.
  protected def postImposter(json: String): ZIO[RiftHarness, Throwable, Int]
  // Raw HTTP against an imposter port (plain, TLS-insecure, or expect-transport-error).
  protected def http(method, port, path, headers, body): ZIO[RiftHarness, Throwable, RawResponse]
  protected def httpExpectTransportError(...): ZIO[RiftHarness, Throwable, Unit]
  // Shell-outs (zio.process): exit code + captured output become assertable values.
  protected def riftLint(file: Path): ZIO[RiftHarness, Throwable, LintReport]
  protected def riftVerify(adminUrl: String): ZIO[RiftHarness, Throwable, VerifyReport]

  // ── steps (selection) ──
  Given("a fresh imposter from fixture " / string)          // loads src/test/resources/rift-fixtures/<name>.json
  Given("every corpus imposter is posted to the engine")
  When("the client requests " / string / " " / string)      // GET /path against the scenario's imposter
  When(int / " concurrent requests are sent to " / string)
  Then("the response status is " / int)                      // + body/header/timing asserts
  Then("the request fails at the transport level")
  Then("the engine remains responsive")                      // probes admin + a sibling imposter under load
  Then("rift-lint reports " / int / " errors across the corpus")
  Then("rift-verify reports " / int / " failures")
  // + admin-op steps: disable/enable, stub by-id, savedRequests?match, spaces CRUD,
  //   scenario reset, flow-state KV, POST /admin/reload → change-report asserts
}
```

Design decisions:

1. **Imposter JSON lives in fixture files** (`src/test/resources/rift-fixtures/<feature>/<name>.json`),
   referenced by name from Gherkin — *not* inlined in steps. Rationale: multi-line JSON in
   quoted step args is unreadable and fought the extractor once already (FINDINGS F2);
   fixtures diff cleanly and can be curl-replayed verbatim during triage. If zio-bdd grows
   docstring support (§12), short configs may inline later — not a blocker.
2. **Every HTTP step records a curl-equivalent line on `Stage`**; assertion failures append
   it. Triage of a Plane A red starts with a paste-ready repro — this replaces the shell
   suite's "the test *is* the curl command" property, which is the one thing bash did better.
3. **Suites**: one object per feature file (`Rift01GateSuite` … `Rift11ReloadSuite`),
   `environment = RiftHarnessLive.layer` (shared across suites via the same
   `provideSomeShared`-style mechanism the mock corpus uses; the engine starts once per
   sbt run, not per suite). `scenarioParallelism = 1` for `01` (fixed ports), `04` (#308
   saturates workers deliberately), `11` (restarts the engine); others may parallelize —
   fresh pooled imposters are share-nothing.
4. **Port discipline**: corpus files own 45xx (gate only); Plane A fresh probes draw from
   48xx via an in-harness pool; Plane B (rift-external) draws 46xx. Disjoint by
   construction, same engine instance is safe if ever shared — but the default is separate
   engine instances per plane (simpler, and `Test/parallelExecution := false` already
   serializes suites).

## 7. Plane B — migrated in from zio-bdd-samples, plus one backend addition

Plane B *is* the existing `MockControl` mock corpus (23 features / 76 scenarios + `samples/mock/**`,
`Backends`, and the `zio-bdd-mock`/`-rift` test deps). It **moves** from `zio-bdd-samples` into this
repo (see §11 Phase 3 for the mechanical move). The framework-general samples (calculator/auth/
shopping/core) stay in `zio-bdd-samples`. After the move, this repo carries both planes and their
shared `RiftHarness`, so the differential is in-process rather than cross-repo.

Once migrated, add a fourth backend to `Backends`:

```scala
case "rift-external" =>
  // Same engine build as Plane A, no Docker: connect the adapter to the
  // RiftHarness-spawned process. Identity port mapping.
  (Client.default ++ provisioning ++ riftHarness) >>> layerFromHarness
  // internally: Rift.connect(harness.adminUrl, pool)(harness.hostFor)
```

Why keep all four Plane B backends:
- `wiremock` — SPI portability witness (the *library* claim, not a Mountebank-parity claim).
- `rift` (container, `Rift.managed`) — battle-tests the library's own managed-container path.
- `rift-external` — **same-build differential** with Plane A and the no-Docker full run.
- `embedded` — FFI witness; also the fast dev loop.

`MOCK_BACKEND=rift-external` becomes the default rift leg in CI job 1 (no Docker);
`rift` (container) stays in the Docker-gated job.

## 8. Triage protocol (replaces #254 §H for this harness)

```
Scenario red
├─ Plane A?
│   ├─ Re-run the emitted curl repro against the harness adminUrl.
│   │   ├─ Engine misbehaves  → ENGINE finding → file on rift, ref #254.
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

Ledger: `FINDINGS.md` keeps its format; new rows note the plane in the Backend(s) column
(`plane-A/engine`, `plane-B/rift-external`, …). Engine findings are *also* mirrored to the
rift repo issue tracker as today (one finding → one issue, ref #254).

## 9. Scripts, Makefile, CI

```
scripts/build-rift.sh            # §3.2 — fetch rift release binaries + BUILD_INFO (RIFT_VERSION)
scripts/conformance-engine.sh    # Plane A: RIFT_MODE=external → engineConformance; RIFT_MODE=embedded → engineEmbedded (#18, JDK22+ or SKIP) + zero-spawn ps assert
scripts/conformance.sh           # (exists) Plane B wiremock
scripts/conformance-embedded.sh  # (exists) Plane B embedded — RIFT_FFI_LIB from build-rift --ffi
scripts/conformance-rift.sh      # (exists) Plane B container — now also accepts RIFT_IMAGE=rift-proxy:local
scripts/run-all.sh               # layer list:
                                 #   engine(A) → engine-embedded(A,#18) → wiremock(B) → embedded(B) → rift-external(B) → [rift-container(B)]
```

sbt aliases: `engineConformance` (`testOnly samples.rift.*`), `engineEmbedded`
(the embedded-supported subset — 01–08 + 11, minus 09/10, #18), `engineGate`
(`testOnly samples.rift.ConsistencyGateSuite`) for the cheap inner loop.

CI shape:
- **Job 1 (no Docker, always):** `build-rift.sh --ffi` → Plane A external → Plane B
  wiremock + embedded + rift-external. Full engine surface + full library surface,
  zero Docker.
- **Job 2 (Docker, gated as today by `RIFT_IT`):** Plane B rift-container; optionally
  Plane A in container mode against the release-candidate image as the pre-release gate.

The `/rift-conformance` command (in `rift-imposter-samples/.claude/commands/`) is rewritten
to: `build-rift.sh` → `run-all.sh` here → triage per §8 → update COVERAGE/FINDINGS + corpus
README. Its scope grammar maps to tags/suites (`gate` → `engineGate`, `ext` → suites 03–10,
`verify #NNN` → the named `@gap` flip workflow).

## 10. Coverage accounting

`COVERAGE.md` gains a **Plane A** section:

| Feature | Scenarios | Replaces (shell) | Status |
|---|---|---|---|
| 01-consistency-gate | 4 | consistency-gate.sh (15 AGREE), lint.sh | ⏳ |
| 02-core-serving | ~10 | conformance.sh (22 chk) | ⏳ |
| … | | | |

plus the port-audit appendix: every shell assert (22 conformance + 47 ext + 7 reload +
15 gate + 1 gap) → scenario name. Baseline parity target: **≥ 92 Plane A assertions**, the
shell suite's count at retirement, minus the migration leg (archived, §11) and any assert
whose drop is explicitly justified in the audit.

## 11. Migration & retirement plan (phased, each phase independently shippable)

**Parity checkpoint rule:** a shell leg is deleted only after its Plane A replacement has
run green **on the same build** in the same session, with the port audit row filled in.

- **Phase 0 — harness.** `build-rift.sh`, `RiftHarness` (external mode), `RiftTools`,
  `RiftConformanceSupport` plumbing, feature `01` (gate) + `@preflight`. Checkpoint: old
  `consistency-gate.sh` and new `01` both green on one build. *Shell suite untouched.*
- **Phase 1 — behavioral core.** Features `02`–`06`. Retire `conformance.sh`,
  `conformance-ext.sh` (and the lint leg — `01` covers it).
- **Phase 2 — admin/topology/proxy/reload.** Features `07`–`11`, `withConfigFile` in the
  harness, container mode. Retire `conformance-reload.sh`, `consistency-gate.sh`, trim
  `run-all.sh` in the corpus repo to a pointer at this repo.
- **Phase 3 — decommission & docs.** In `rift-imposter-samples`: move
  `conformance-migration.sh` to `attic/` (Mountebank-parity de-scoped; #304/#305 remain
  tracked as rift issues; **corpus file `20-mimeo-solo-compat.json` stays** — it is
  engine-served shapes and the gate still exercises it). Keep: `imposters/` (canonical
  corpus), `smoke.sh`, `start.sh`/`load.sh`/`tui.sh` (dev utilities), SETUP/README
  (rewritten to point conformance here). Rewrite `/rift-conformance`. Add Plane B
  `rift-external` backend. Update rift#254 with the new runbook.

Rollback: phases are additive until the retire step; retiring is a `git rm` that can be
reverted. The corpus is never moved, so nothing external breaks.

## 12. Known risks, open questions, and library follow-ups

| # | Item | Disposition |
|---|---|---|
| R1 | **Docstring support in zio-bdd** unverified — fixtures-by-file (§6.1) is the design; docstrings are an optional later ergonomic. Implementer verifies and notes in COVERAGE. | fallback designed |
| R2 | **Engine version endpoint** — unknown whether the admin API reports version/commit. Preflight falls back to BUILD_INFO + binary `--version` (§3.2). Embedded additionally reads `rift_build_info().commit` over the FFI (recorded as the engine version), a direct engine identity the external admin API still lacks. | fallback designed; embedded direct (#18) |
| R3 | **Child-process leak** on hard-killed sbt: scope finalizer + JVM shutdown hook + `pkill`-style guard in `conformance-engine.sh` (belt and braces, as the shell suite did). | mitigated |
| R4 | **#308 saturation test vs shared engine**: 3 concurrent runaways deliberately occupy blocking threads; feature `04` runs with `scenarioParallelism = 1` and a fresh imposter, matching the proven shell probe. | designed |
| R5 | **relative-path corpus data** (`data/products.csv`): `external` pins the engine cwd to `RIFT_CORPUS_ROOT` (§3.1); `embedded` shares the JVM cwd (must not chdir), so it absolutizes `data/...` paths in fixtures at load time (`RiftHarness.rewriteFixture`, #18). | designed; embedded done (#18) |
| R6 | **rift-ffi surface too thin** for Plane A. Widen the C-ABI (in-process admin API, apply_config, build-info) so embedded becomes a full Plane A oracle with zero process spawn. **DONE (#18):** `RIFT_MODE=embedded` drives the real in-process admin API via `rift_serve_admin` (C-ABI v2, zio-bdd 1.4.1) — Plane A features 01–08 + 11 green, zero process spawn. 09/10 (metrics/proxy-record) stay `external`/`container` by design. | done (#18) |
| R7 | **zio-bdd additions** — none required. Optional niceties: docstring args; a public `Rift.managed` variant exposing adminUrl (would let Plane A container mode reuse the library instead of mirroring 30 lines). File as zio-bdd enhancements when Phase 2 lands. | future (zio-bdd repo) |

## 13. Implementation checklist (ordered, for the implementing model)

Each item names its acceptance criterion. Do them in order; stop at any red.

1. **`scripts/build-rift.sh`** — fetch pinned rift release tarball → BUILD_INFO (self-contained, #38)
   flags, SHA-keyed cache. ✔ two consecutive runs: second is a cache hit; binaries exist;
   BUILD_INFO commit matches `git -C <cache> rev-parse HEAD`.
2. **`RiftHarness` external mode** (`samples/rift/support/`) — spawn/readiness/teardown,
   cwd = `RIFT_CORPUS_ROOT`, free-port admin, shutdown hook. ✔ a throwaway spec starts the
   engine, `GET /imposters` returns 200, scope close kills the process (no orphan in `ps`).
3. **`RiftTools`** — zio.process shell-outs for lint/verify with typed
   `LintReport`/`VerifyReport` (parse the summary lines; strip ANSI). ✔ lint of corpus
   returns errors=0; verify against the harness engine parses pass/fail/skip counts.
4. **`RiftConformanceSupport`** — postImposter/http/transport-error/curl-repro plumbing +
   the §6 step catalog skeleton. ✔ compiles; a smoke feature (fresh imposter → GET → 200)
   is green.
5. **Feature `01-consistency-gate`** + `@preflight`. ✔ green on a fresh build; deliberately
   corrupting one corpus file (locally, unstaged) turns it red with that file named.
6. **Parity checkpoint 0** — same build: old shell gate green AND `01` green. Record in
   COVERAGE.md.
7. **Features `02`–`06`** with fixtures; port audit rows as you go. ✔ each feature green;
   audit complete for conformance.sh + conformance-ext.sh (behavioral sections).
8. **Retire** `conformance.sh`, `conformance-ext.sh` in the corpus repo (commit there,
   local only, per repo rules).
9. **Features `07`–`10`**. ✔ green; audit rows done. (Proxy `10` needs the harness to also
   stand up a trivial upstream — reuse the pattern rift-verify's `--verify-dynamic` uses;
   a zio-http ephemeral server in the suite is fine.)
10. **`withConfigFile` + feature `11-reload`**. ✔ change-report fields + state-preservation
    asserts green (mirror `conformance-reload.sh`'s 7 asserts).
11. **Retire** `conformance-reload.sh`, `consistency-gate.sh`; trim corpus-repo `run-all.sh`.
12. **Container mode** (`RIFT_MODE=container`, `RIFT_IMAGE`), `build-rift.sh --image`.
    ✔ feature `01`+`03` green in container mode (transport surface confirmed through
    port mapping).
13. **Migrate Plane B mock corpus** from `zio-bdd-samples` → this repo (features/mock,
    samples/mock, Backends, mock test deps, conformance{,-embedded,-rift}.sh). ✔ `sbt
    "testOnly samples.mock.*"` green here on `MOCK_BACKEND=wiremock` + `embedded`;
    `zio-bdd-samples` still green on its remaining framework samples.
14. **Plane B `rift-external` backend** in the migrated `Backends`. ✔ mock corpus green with
    `MOCK_BACKEND=rift-external` (compare against `rift` container leg — same results).
15. **Harness wiring** — `conformance-engine.sh`, extended `run-all.sh`, sbt aliases,
    Makefile targets, CI YAML (two jobs). ✔ `run-all.sh` prints the layered summary with
    Plane A included; exit codes propagate.
16. **Docs, corpus migration & decommission** — copy the JSON corpus into `./corpus` and
    flip `RIFT_CORPUS_ROOT`; COVERAGE.md Plane A section + full port audit; FINDINGS.md
    plane annotations; bring `/rift-conformance` here and rewrite it; note the move in the
    `rift-imposter-samples` README (owner retires that repo); comment on rift#254.
    ✔ everything runs from this repo's README alone; nothing depends on `rift-imposter-samples`.

> Issue mapping: checklist item N = `rift-conformance` issue #N (1–16). Tracking umbrella: #17.
> The corpus stays static/numbered and add-never-renumber throughout; `20-mimeo-solo-compat.json`
> ships with it (the Mountebank *shell leg* is not ported — parity de-scoped).
