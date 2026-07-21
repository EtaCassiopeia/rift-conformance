#!/usr/bin/env python3
"""Prove the corpus `_verify` transcripts are load-bearing (rift-conformance issue #117).

A `_verify` block that rift-verify silently ignores looks exactly like one that passes: the gate
stays green either way. That is the same failure mode the transcripts exist to catch (a fixture
that loads and answers, but no longer *behaves*), so "the gate is green" is not evidence on its
own.

This script supplies the evidence. For every corpus file carrying `_verify`, it corrupts one
expectation in a throwaway copy of the corpus, replays it through the real `rift-verify`, and
requires the run to FAIL. A transcript whose mutation still passes is decorative — rift-verify never
evaluated it.

What a KILLED verdict does and does not prove: the mutation is always `expect.status -> 599`, so
KILLED proves rift-verify **evaluated** that transcript, not that the transcript's own expectations
are strong. A step asserting only `status: 200` on a door that answers 200 when its behavior is
dropped will report KILLED while still being unable to catch its own regression. Strength of the
expectations is a review question; this script only rules out silent no-ops.

Not wired into CI: it needs a live engine and re-runs it once per file. Run it after editing
`_verify` blocks, and on an engine-pin bump.

Usage:
  scripts/verify-mutation-check.py [--corpus corpus] [--build-dir $RIFT_BUILD_DIR] [--port 7910]
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


def wait_ready(admin: str, timeout: float = 15.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"{admin}/imposters", timeout=1).read()
            return True
        except (urllib.error.URLError, OSError):
            time.sleep(0.2)
    return False


def run_verify(corpus: Path, build_dir: Path, port: int) -> dict:
    """Start an engine on `corpus`, POST every imposter, and return rift-verify's JSON summary."""
    admin = f"http://127.0.0.1:{port}"
    engine = subprocess.Popen(
        [str(build_dir / "rift"), "--port", str(port), "--allow-injection", "--nologfile"],
        cwd=str(corpus),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        if not wait_ready(admin):
            raise RuntimeError(f"engine on {port} never became ready")
        for f in sorted((corpus / "imposters").glob("*.json")):
            req = urllib.request.Request(
                f"{admin}/imposters",
                data=f.read_bytes(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                urllib.request.urlopen(req, timeout=10).read()
            except urllib.error.HTTPError as e:
                raise RuntimeError(f"POST {f.name} -> {e.code}: {e.read()[:300]!r}") from e
        out = subprocess.run(
            [
                str(build_dir / "rift-verify"),
                "--admin-url", admin,
                "--skip-dynamic",
                "--verify-dynamic",
                "-o", "json",
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
        try:
            return json.loads(out.stdout)
        except json.JSONDecodeError as e:
            # Without stderr a rift-verify crash surfaces as a bare JSONDecodeError on empty stdout.
            raise RuntimeError(
                f"rift-verify produced no JSON summary (exit {out.returncode}): {out.stderr[-600:]}"
            ) from e
    finally:
        engine.terminate()
        try:
            engine.wait(timeout=10)
        except subprocess.TimeoutExpired:
            engine.kill()


def verify_stubs(doc: dict) -> list[int]:
    """Indices of stubs carrying a non-empty `_verify.sequence`."""
    return [
        i
        for i, s in enumerate(doc.get("stubs", []))
        if s.get("_verify", {}).get("sequence")
    ]


def mutate(doc: dict, stub_index: int) -> tuple[dict, str]:
    """Corrupt the first expectation of stub `stub_index`'s `_verify`. Returns (doc, description).

    Status is bumped to an impossible value rather than the body being edited, because a status
    mismatch is unambiguous: no legitimate stub answers 599.
    """
    out = copy.deepcopy(doc)
    stub = out["stubs"][stub_index]
    expect = stub["_verify"]["sequence"][0].setdefault("expect", {})
    original = expect.get("status")
    expect["status"] = 599
    return out, f"stub #{stub_index} '{stub.get('name', '?')[:36]}' status {original} -> 599"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=os.environ.get("RIFT_CORPUS_ROOT", "corpus"))
    ap.add_argument("--build-dir", default=os.environ.get("RIFT_BUILD_DIR"))
    ap.add_argument("--port", type=int, default=7910)
    ap.add_argument("--all", action="store_true",
                    help="mutate EVERY _verify block, not just the first per file")
    args = ap.parse_args()

    if not args.build_dir:
        print("RIFT_BUILD_DIR is unset (run scripts/build-rift.sh)", file=sys.stderr)
        return 2
    corpus, build_dir = Path(args.corpus).resolve(), Path(args.build_dir).resolve()

    baseline = run_verify(corpus, build_dir, args.port)
    baseline_failed = baseline.get("failed", 1)
    print(f"baseline: {baseline}")
    if baseline_failed != 0:
        print("baseline is not green — fix the corpus before mutation-testing", file=sys.stderr)
        return 1

    # One mutation per file proves each FILE's transcripts are wired up; --all mutates every
    # `_verify` block, which is what proves each individual transcript is load-bearing.
    targets: list[tuple[Path, int]] = []
    for path in sorted((corpus / "imposters").glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        idxs = verify_stubs(doc)
        targets.extend((path, i) for i in (idxs if args.all else idxs[:1]))
    print(f"mutating {len(targets)} transcript(s) across "
          f"{len({p for p, _ in targets})} files\n")

    survivors = []
    for n, (path, stub_index) in enumerate(targets):
        doc = json.loads(path.read_text(encoding="utf-8"))
        mutated, what = mutate(doc, stub_index)
        # A kill is exactly ONE new failure. `failed > 0` would accept any red — a timeout, a port
        # clash, an unrelated flake — as proof the mutation was caught, which is precisely the
        # circumstantial reasoning this script exists to replace.
        #
        # The corpus has an intermittent failure of its own (the `wait` stubs in 03-behaviors, ~1 run
        # in 6-12 under full-corpus load — reproducible on an UNMODIFIED corpus, so not a transcript
        # defect). A count that is neither baseline nor baseline+1 is therefore insufficient
        # evidence rather than a verdict: re-run that one mutation. A transcript rift-verify never
        # evaluates sits at exactly baseline every time, so retrying cannot mask a real survivor.
        failed = None
        for attempt in range(2):
            with tempfile.TemporaryDirectory() as tmp:
                work = Path(tmp) / "corpus"
                shutil.copytree(corpus, work)
                (work / "imposters" / path.name).write_text(
                    json.dumps(mutated, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
                )
                report = run_verify(work, build_dir, args.port + 1 + (n % 200) + attempt * 300)
            failed = report.get("failed", 0)
            if failed in (baseline_failed, baseline_failed + 1):
                break
            print(f"  retrying {path.name}#{stub_index}: failed={failed} "
                  f"(neither {baseline_failed} nor {baseline_failed + 1} — unrelated red)")
        killed = failed == baseline_failed + 1
        verdict = "KILLED  " if killed else ("SURVIVED" if failed == baseline_failed else "AMBIGUOUS")
        print(f"{verdict} {path.name}: {what} -> failed={failed} (baseline {baseline_failed})")
        if not killed:
            survivors.append(f"{path.name}#{stub_index} (failed={failed})")

    print()
    if survivors:
        print(f"FAIL — {len(survivors)} mutation(s) survived: {', '.join(survivors)}")
        print("Those _verify blocks are decorative: rift-verify is not asserting them.")
        return 1
    print(f"OK — all {len(targets)} mutations killed; rift-verify evaluates every transcript checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
