#!/usr/bin/env bash
#
# Gate for scripts/build-rift.sh (issue #1).
#
# A real conformance build clones the rift engine from master and does a multi-crate
# `cargo build --release`, which is far too slow for a fast, hermetic gate. So this test
# stands up a *fake* rift source repo — a tiny zero-dependency cargo workspace whose crates
# and bin targets carry the exact names the real build command selects (rift-http-proxy /
# rift-verify / rift-lint / rift-ffi) — and points build-rift.sh at it via RIFT_SRC_REMOTE.
# The script's control logic (clean-master clone, SHA-keyed cache reuse, BUILD_INFO shape,
# RIFT_BUILD_DIR export, escape hatch) is exercised end-to-end; only the compilation payload
# is swapped for something that builds in seconds.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/build-rift.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
fail() { echo "  NOT OK: $*" >&2; FAILED=1; }
ok()   { echo "  ok: $*" >&2; }
build_dir_of() { echo "$1" | sed -n 's/^export RIFT_BUILD_DIR=//p' | tr -d '"'; }
get_mtime()    { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1"; }

# ---- build a fake rift source repo with fast-compiling stub crates ---------------------
FAKE_SRC="$WORK/fake-rift"
mk_crate_bin() { # <dir> <pkg> ; writes a stub bin crate (main prints a version for --version)
  local dir="$1" pkg="$2"
  mkdir -p "$dir/src"
  cat >"$dir/Cargo.toml" <<EOF
[package]
name = "$pkg"
version = "0.1.0"
edition = "2021"
EOF
  cat >"$dir/src/main.rs" <<'EOF'
fn main() { println!("stub 0.1.0 (fake)"); }
EOF
}

setup_fake_src() {
  rm -rf "$FAKE_SRC"
  mkdir -p "$FAKE_SRC/crates"
  cat >"$FAKE_SRC/Cargo.toml" <<'EOF'
[workspace]
resolver = "2"
members = ["crates/rift-http-proxy", "crates/rift-lint", "crates/rift-ffi"]
EOF
  # rift-http-proxy: two bins — rift-http-proxy (main.rs) and rift-verify (src/bin/verify.rs)
  mk_crate_bin "$FAKE_SRC/crates/rift-http-proxy" "rift-http-proxy"
  mkdir -p "$FAKE_SRC/crates/rift-http-proxy/src/bin"
  cat >"$FAKE_SRC/crates/rift-http-proxy/Cargo.toml" <<'EOF'
[package]
name = "rift-http-proxy"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "rift-http-proxy"
path = "src/main.rs"

[[bin]]
name = "rift-verify"
path = "src/bin/verify.rs"
EOF
  cat >"$FAKE_SRC/crates/rift-http-proxy/src/bin/verify.rs" <<'EOF'
fn main() { println!("stub 0.1.0 (fake)"); }
EOF
  # rift-lint: one bin
  mk_crate_bin "$FAKE_SRC/crates/rift-lint" "rift-lint"
  # rift-ffi: cdylib
  mkdir -p "$FAKE_SRC/crates/rift-ffi/src"
  cat >"$FAKE_SRC/crates/rift-ffi/Cargo.toml" <<'EOF'
[package]
name = "rift-ffi"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]
EOF
  echo '#[no_mangle] pub extern "C" fn rift_ffi_probe() -> i32 { 0 }' \
    >"$FAKE_SRC/crates/rift-ffi/src/lib.rs"
  # a Dockerfile so --image has something to build (not exercised here)
  echo 'FROM scratch' >"$FAKE_SRC/Dockerfile"

  git -C "$FAKE_SRC" init -q -b master
  git -C "$FAKE_SRC" -c user.email=t@t -c user.name=t add -A
  git -C "$FAKE_SRC" -c user.email=t@t -c user.name=t commit -q -m "fake rift"
}

echo "== setup fake rift source ==" >&2
setup_fake_src
EXPECTED_SHA="$(git -C "$FAKE_SRC" rev-parse HEAD)"

CACHE="$WORK/cache"
run_build() { # args... ; echoes the captured stdout (export line), logs go to stderr
  RIFT_SRC_REMOTE="$FAKE_SRC" RIFT_BUILD_CACHE="$CACHE" bash "$SCRIPT" "$@"
}

# ---- 1: first run builds all three binaries + a valid BUILD_INFO ----------------------
echo "== run 1 (cold build) ==" >&2
OUT1="$(run_build 2>"$WORK/err1")" || { cat "$WORK/err1" >&2; fail "run 1 exited non-zero"; }
echo "$OUT1" | grep -q '^export RIFT_BUILD_DIR=' || fail "run 1 stdout missing 'export RIFT_BUILD_DIR='"
BUILD_DIR="$(build_dir_of "$OUT1")"
[ -n "$BUILD_DIR" ] && [ -d "$BUILD_DIR" ] || fail "RIFT_BUILD_DIR not a dir: '$BUILD_DIR'"

for b in rift-http-proxy rift-verify rift-lint; do
  [ -x "$BUILD_DIR/$b" ] && ok "binary $b present" || fail "missing binary $b in $BUILD_DIR"
done

INFO="$BUILD_DIR/BUILD_INFO"
[ -f "$INFO" ] && ok "BUILD_INFO written" || fail "BUILD_INFO missing at $INFO"
if [ -f "$INFO" ]; then
  jq -e . "$INFO" >/dev/null 2>&1 || fail "BUILD_INFO is not valid JSON"
  [ "$(jq -r .commit "$INFO")" = "$EXPECTED_SHA" ] \
    && ok "BUILD_INFO.commit matches clone HEAD" \
    || fail "BUILD_INFO.commit != clone HEAD ($(jq -r .commit "$INFO") vs $EXPECTED_SHA)"
  [ "$(jq -r .branch "$INFO")" = "master" ] && ok "branch=master" || fail "branch != master"
  jq -e '.builtAt | test("^[0-9]{4}-")' "$INFO" >/dev/null && ok "builtAt present" || fail "builtAt missing/mal"
  for b in rift-http-proxy rift-verify rift-lint; do
    jq -e --arg b "$b" '.binaries[$b] | test("^[0-9a-f]{64}$")' "$INFO" >/dev/null \
      && ok "sha256 for $b" || fail "BUILD_INFO.binaries.$b not a sha256"
    jq -e --arg b "$b" '.versions[$b] | test("stub 0.1.0")' "$INFO" >/dev/null \
      && ok "version captured for $b" || fail "BUILD_INFO.versions.$b missing --version output"
  done
fi

# ---- 2: second run is a cache hit — no rebuild (binary mtime unchanged) ----------------
echo "== run 2 (cache hit) ==" >&2
MTIME_BEFORE="$(get_mtime "$BUILD_DIR/rift-http-proxy")"
OUT2="$(run_build 2>"$WORK/err2")" || { cat "$WORK/err2" >&2; fail "run 2 exited non-zero"; }
grep -qiE 'cache hit|reus|cached' "$WORK/err2" && ok "cache-hit reported" || fail "run 2 did not report a cache hit"
BUILD_DIR2="$(build_dir_of "$OUT2")"
[ "$BUILD_DIR2" = "$BUILD_DIR" ] && ok "same build dir reused" || fail "run 2 build dir differs"
MTIME_AFTER="$(get_mtime "$BUILD_DIR/rift-http-proxy")"
[ "$MTIME_BEFORE" = "$MTIME_AFTER" ] && ok "no rebuild on cache hit" || fail "binary rebuilt on cache hit"

# ---- 3: --ffi also builds the cdylib and records it -----------------------------------
echo "== run 3 (--ffi) ==" >&2
case "$(uname -s)" in Darwin) EXT=dylib ;; *) EXT=so ;; esac
OUT3="$(run_build --ffi 2>"$WORK/err3")" || { cat "$WORK/err3" >&2; fail "run 3 (--ffi) exited non-zero"; }
BUILD_DIR3="$(build_dir_of "$OUT3")"
[ -f "$BUILD_DIR3/librift_ffi.$EXT" ] && ok "librift_ffi.$EXT built" || fail "ffi lib librift_ffi.$EXT missing"
jq -e --arg k "librift_ffi.$EXT" '.binaries[$k] | test("^[0-9a-f]{64}$")' "$BUILD_DIR3/BUILD_INFO" >/dev/null \
  && ok "ffi lib in BUILD_INFO" || fail "ffi lib not recorded in BUILD_INFO"

# ---- 4: RIFT_BUILD_DIR escape hatch skips the clone entirely ----------------------------
echo "== run 4 (escape hatch) ==" >&2
# Point the remote at a bogus path: if the script tries to clone, it fails; the hatch must skip it.
OUT4="$(RIFT_SRC_REMOTE="/nonexistent/should-not-clone" RIFT_BUILD_DIR="$BUILD_DIR" \
        RIFT_BUILD_CACHE="$CACHE" bash "$SCRIPT" 2>"$WORK/err4")" \
  || { cat "$WORK/err4" >&2; fail "escape-hatch run exited non-zero"; }
echo "$OUT4" | grep -q "^export RIFT_BUILD_DIR=\"\?$BUILD_DIR\"\?$" \
  && ok "escape hatch echoes provided build dir" || fail "escape hatch did not echo provided dir"

# ---- 5: a new master commit yields a new SHA-keyed dir and a fresh build ----------------
echo "== run 5 (new commit → new cache dir, rebuild) ==" >&2
git -C "$FAKE_SRC" -c user.email=t@t -c user.name=t commit -q --allow-empty -m "advance master"
NEW_SHA="$(git -C "$FAKE_SRC" rev-parse HEAD)"
OUT5="$(run_build 2>"$WORK/err5")" || { cat "$WORK/err5" >&2; fail "run 5 exited non-zero"; }
BUILD_DIR5="$(build_dir_of "$OUT5")"
[ "$BUILD_DIR5" != "$BUILD_DIR" ] && ok "new SHA → new build dir" || fail "run 5 reused old dir despite new commit"
if grep -qiE 'cache hit' "$WORK/err5"; then fail "run 5 wrongly reported a cache hit for a new sha"; else ok "run 5 did a fresh build"; fi
[ -f "$BUILD_DIR5/BUILD_INFO" ] && [ "$(jq -r .commit "$BUILD_DIR5/BUILD_INFO")" = "$NEW_SHA" ] \
  && ok "run 5 BUILD_INFO.commit == new sha" || fail "run 5 BUILD_INFO.commit != new sha"

# ---- 6: a partial cache dir (missing a binary) is not a false cache hit -----------------
echo "== run 6 (partial dir rebuilds, no false hit) ==" >&2
rm -f "$BUILD_DIR5/rift-verify"
OUT6="$(run_build 2>"$WORK/err6")" || { cat "$WORK/err6" >&2; fail "run 6 exited non-zero"; }
[ -x "$BUILD_DIR5/rift-verify" ] && ok "missing binary rebuilt" || fail "run 6 did not restore rift-verify"
if grep -qiE 'cache hit' "$WORK/err6"; then fail "run 6 treated a partial dir as a cache hit"; else ok "run 6 correctly rebuilt partial dir"; fi

if [ "$FAILED" -eq 0 ]; then echo "ALL PASS" >&2; else echo "FAILURES ABOVE" >&2; fi
exit "$FAILED"
