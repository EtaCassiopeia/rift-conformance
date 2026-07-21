#!/usr/bin/env python3
"""Detect engine-pin drift: is there a rift release newer than the one this harness pins?

Why this exists: the conformance suite's subject is an external, fast-moving engine, but CI only ran
on harness commits. The pin sat at 0.13.1 while rift shipped six releases through 0.14.0, and nothing
went red — a stale pin silently asserts a stale contract.

Two facts, one comparison:
  * pinned  — the `RIFT_VERSION` default in scripts/build-rift.sh, the single source of truth
  * latest  — the newest non-draft, non-prerelease `vX.Y.Z` release of the engine repo

Version comparison is numeric per component, NOT lexicographic: "0.9.1" > "0.14.0" as strings, which
would report drift backwards and hide every 0.14.x release. That trap is what `--self-test` pins.

Usage:
  scripts/rift-release-watch.py                 # query GitHub, print a report, set GITHUB_OUTPUT
  scripts/rift-release-watch.py --self-test     # offline unit checks of the pure logic
  scripts/rift-release-watch.py --latest 0.15.0 # override the query (for testing the report path)

Exit codes: 0 = no drift (or self-test passed), 3 = drift detected, 1 = error, 2 = bad usage.
Drift is deliberately NOT exit 1: it is a finding, not a failure of this script.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ENGINE_REPO = "achird-labs/rift"
PIN_FILE = Path("scripts/build-rift.sh")
# Matches: RIFT_VERSION="${RIFT_VERSION:-0.14.0}"
PIN_RE = re.compile(r'RIFT_VERSION="\$\{RIFT_VERSION:-([0-9]+(?:\.[0-9]+)*)\}"')
TAG_RE = re.compile(r"^v([0-9]+(?:\.[0-9]+)*)$")


def parse_version(text: str) -> tuple[int, ...]:
    """'v0.14.0' / '0.14.0' -> (0, 14, 0). Raises ValueError on anything else."""
    cleaned = text.strip().lstrip("v")
    if not re.fullmatch(r"[0-9]+(\.[0-9]+)*", cleaned):
        raise ValueError(f"not a plain numeric version: {text!r}")
    return tuple(int(p) for p in cleaned.split("."))


def is_newer(candidate: str, baseline: str) -> bool:
    """True when `candidate` is a strictly newer version than `baseline`, compared numerically."""
    return parse_version(candidate) > parse_version(baseline)


def read_pinned_version(pin_file: Path) -> str:
    """The RIFT_VERSION default from build-rift.sh — the version CI actually fetches."""
    try:
        text = pin_file.read_text(encoding="utf-8")
    except OSError as e:
        raise RuntimeError(f"cannot read the pin file {pin_file}: {e}") from e
    # Never guess a pin: a wrong baseline silently reports drift backwards, or not at all. That
    # includes an AMBIGUOUS pin — with two matches, `search` would silently take whichever came
    # first in the file.
    matches = PIN_RE.findall(text)
    if not matches:
        raise RuntimeError(
            f"no `RIFT_VERSION=\"${{RIFT_VERSION:-<version>}}\"` default found in {pin_file}; "
            "the pin moved or changed shape — update PIN_RE in this script"
        )
    if len(matches) > 1:
        raise RuntimeError(
            f"{pin_file} has {len(matches)} RIFT_VERSION defaults ({', '.join(matches)}); "
            "the baseline is ambiguous — collapse them to one"
        )
    return matches[0]


def latest_release(repo: str) -> str:
    """Newest published `vX.Y.Z` release tag, excluding drafts and prereleases.

    `gh release list` order is not a version order, so every tag is parsed and compared rather than
    trusting the first row.
    """
    try:
        out = subprocess.run(
            ["gh", "release", "list", "--repo", repo, "--limit", "50", "--json",
             "tagName,isDraft,isPrerelease"],
            capture_output=True, text=True, timeout=60, check=True,
        )
    except FileNotFoundError as e:
        raise RuntimeError("`gh` is not installed; it is required to query releases") from e
    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"gh release list failed ({e.returncode}): {e.stderr.strip()[:300]}") from e
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"gh release list timed out after {e.timeout}s") from e

    try:
        releases = json.loads(out.stdout)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"gh returned non-JSON: {out.stdout[:200]!r}") from e

    versions = []
    for rel in releases:
        if rel.get("isDraft") or rel.get("isPrerelease"):
            continue
        m = TAG_RE.match(rel.get("tagName", ""))
        if m:
            versions.append(m.group(1))
    if not versions:
        raise RuntimeError(f"no published vX.Y.Z releases found for {repo}")
    return max(versions, key=parse_version)


def self_test() -> int:
    """Offline checks of the pure logic. The ordering cases are the whole point."""
    cases_newer = [
        ("0.14.1", "0.14.0", True),
        ("0.15.0", "0.14.0", True),
        ("1.0.0", "0.14.0", True),
        ("0.14.0", "0.14.0", False),
        ("0.13.2", "0.14.0", False),
        # The lexicographic trap: as strings "0.9.1" > "0.14.0", which would hide every 0.14.x.
        ("0.9.1", "0.14.0", False),
        ("0.14.0", "0.9.1", True),
        # Uneven component counts.
        ("0.14", "0.14.0", False),
        ("0.14.0.1", "0.14.0", True),
        ("v0.14.1", "0.14.0", True),
    ]
    failures = []
    for candidate, baseline, want in cases_newer:
        got = is_newer(candidate, baseline)
        if got != want:
            failures.append(f"is_newer({candidate!r}, {baseline!r}) = {got}, want {want}")

    for bad in ["", "latest", "0.14.0-rc1", "v", "abc"]:
        try:
            parse_version(bad)
            failures.append(f"parse_version({bad!r}) should have raised")
        except ValueError:
            pass

    # The pin must be readable from the real file — a silently-unreadable pin is the failure mode
    # that would make this whole watcher a no-op.
    try:
        pinned = read_pinned_version(PIN_FILE)
        parse_version(pinned)
        print(f"self-test: pin file parses -> RIFT_VERSION={pinned}")
    except (RuntimeError, ValueError) as e:
        failures.append(f"read_pinned_version({PIN_FILE}): {e}")

    if failures:
        print("SELF-TEST FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print(f"self-test: OK ({len(cases_newer)} ordering cases + parse rejections + pin read)")
    return 0


def emit_output(**kv: str) -> None:
    """Expose results to later workflow steps; harmless no-op outside Actions."""
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        for k, v in kv.items():
            fh.write(f"{k}={v}\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="run offline logic checks and exit")
    ap.add_argument("--repo", default=ENGINE_REPO, help=f"engine repo (default {ENGINE_REPO})")
    ap.add_argument("--pin-file", type=Path, default=PIN_FILE)
    ap.add_argument("--latest", help="skip the GitHub query and use this version (testing)")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    try:
        pinned = read_pinned_version(args.pin_file)
        latest = args.latest.lstrip("v") if args.latest else latest_release(args.repo)
        drift = is_newer(latest, pinned)
    except (RuntimeError, ValueError) as e:
        print(f"rift-release-watch: {e}", file=sys.stderr)
        return 1

    emit_output(pinned=pinned, latest=latest, drift=str(drift).lower())
    if drift:
        print(f"DRIFT: pinned v{pinned}, latest v{latest} ({args.repo})")
        return 3
    print(f"up to date: pinned v{pinned}, latest v{latest} ({args.repo})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
