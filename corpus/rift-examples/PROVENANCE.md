# rift shipped examples — vendored copy

Source: [`achird-labs/rift`](https://github.com/achird-labs/rift) `examples/` at tag **v0.18.1**
(the engine pin). Replayed by `samples.rift.ShippedExamples120Spec` (#120).

These are **verbatim copies** — do not hand-edit. Refresh them with the engine-pin bump:

```sh
scripts/sync-rift-examples.sh          # re-fetch at $RIFT_VERSION
scripts/sync-rift-examples.sh --check  # verify the vendored copy matches the tag
```

## Why vendored rather than fetched at test time

The release tarball ships only `bin/`, `CHANGELOG.md`, `LICENSE` and `README.md` — **no `examples/`**
— so the oracle's inputs can only come from the repo tree at the tag. Vendoring keeps the spec
hermetic (no network or GitHub rate limits in CI). The cost is that a stale copy could let the
oracle keep passing against examples the engine no longer ships; `--check` is what makes that
detectable, which is why it is part of the pin-bump procedure rather than an optional extra.

## Contents at v0.18.1

git blob SHAs (`sha1("blob <len>\0" + content)`) — what `--check` compares against:

| file | port | git blob SHA |
|------|------|--------------|
| `authentication-api.json`  | 4547 | `4b68876a4f29d497305d56a9108c0cdb0ab7c4ce` |
| `basic-api.json`           | 4545 | `125fd12f8ce3f51f9f0396b553b26c878ca468b3` |
| `error-testing.json`       | 4548 | `a1bb5e32fdd84ffd9a57943b19afef36fda03cd0` |
| `feature-flags-api.json`   | 4546 | `cf61b6b296d7dae96febe7557057637eb951b665` |
| `latency-testing.json`     | 4549 | `4c91030c6ffa41bf609e5212c77757a778778da5` |
| `task-management-api.json` | 4550 | `ac8d3ec294e5707a158f574949c61d550ce836a1` |

Every example declares its own port (rift#1217, re-ported in v0.18.1 by rift#1218; up to v0.18.0
four of them shared **4545**), so the whole folder can be co-loaded and `rift-lint` lints it in one
run. The spec still loads each document *verbatim* (its declared port included) and `DELETE`s it
before the next, rather than rewriting ports — replaying the example as shipped is the whole point
of the oracle.
