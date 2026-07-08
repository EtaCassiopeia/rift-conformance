# rift-conformance

The dedicated conformance suite for [Rift](https://github.com/EtaCassiopeia/rift) — one
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) harness hosting **two attribution planes**:

- **Plane A — engine conformance.** Drives Rift's admin API directly (raw HTTP) and runs the
  engine's own `rift-lint` / `rift-verify` tools. No `MockControl` in the path, so a red here is an
  **engine or tool** finding. This is the typed-Gherkin successor to the `rift-imposter-samples`
  shell suite.
- **Plane B — portable SPI corpus.** Drives Rift *through* the zio-bdd `MockControl` adapter (and
  WireMock / embedded-FFI backends). A red here with Plane A green is a **library** finding. Migrated
  in from `zio-bdd-samples`.

The Plane B mock corpus (issue #13) now lives in this repo under `src/test/scala/samples/mock/`,
driven by `MOCK_BACKEND` (default `wiremock`, in-process, no Docker required).

Both planes share one `RiftHarness` (engine lifecycle) and one engine build, so running them together
gives differential (engine-direct vs engine-via-adapter) coverage. **This is how one suite tests both
libraries** — the Rift engine *and* the zio-bdd mock stack.

See **[DESIGN.md](DESIGN.md)** for the full architecture, risk register, and the ordered
implementation checklist. Work is tracked in this repo's issues (start at the pinned tracking issue).

## Status

🚧 Under construction — being migrated from two sources:

| Source | What moves here | Then |
|---|---|---|
| `rift-imposter-samples` | the canonical JSON corpus + the shell-suite coverage (→ Plane A) | repo **retired** |
| `zio-bdd-samples` | the `MockControl` mock corpus (→ Plane B) | keeps its framework-general samples |

## Repo layout (target)

```
DESIGN.md                              the implementation contract
scripts/build-rift.sh                  fetches the released engine binaries (single source of artifacts)
src/test/scala/samples/rift/           Plane A — engine conformance suites + RiftHarness
src/test/scala/samples/mock/           Plane B — MockControl corpus (migrated)
src/test/resources/features/rift/      Plane A features
src/test/resources/features/mock/      Plane B features (migrated)
corpus/imposters/*.json                canonical JSON corpus (migrated last)
COVERAGE.md · FINDINGS.md              coverage matrix + defect ledger
```

## Environment knobs

This repo is self-contained: **no sibling checkout of rift or zio-bdd is required.** zio-bdd is
consumed from Maven Central; rift is fetched as a published GitHub release by
`scripts/build-rift.sh`.

| Var | Default | Meaning |
|---|---|---|
| `RIFT_BUILD_DIR` | (fetched by `build-rift.sh`) | dir holding `rift`/`rift-lint`/`rift-verify` from a released rift build |
| `RIFT_VERSION` | `0.9.1` | released rift version `build-rift.sh` fetches |
| `RIFT_MODE` | `external` | `external` (spawn the binary, no Docker) or `container` (image) — Plane A |
| `RIFT_CORPUS_ROOT` | `../rift-imposter-samples` | where the JSON corpus lives (until it migrates here) |
| `MOCK_BACKEND` | `wiremock` | Plane B backend: `wiremock` / `rift` / `rift-external` / `embedded` |
| `RIFT_FFI_LIB` | (bundled) | `librift_ffi` path for the embedded Plane B leg |
