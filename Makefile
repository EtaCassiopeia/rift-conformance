.PHONY: build-rift engine gate run-all fmt test

# Build rift@master (clean clone) and print the resulting RIFT_BUILD_DIR. Run with
# `eval "$$(make build-rift)"` if you want RIFT_BUILD_DIR exported into your shell.
build-rift:
	./scripts/build-rift.sh

# Plane A: samples.rift.* suites against a real rift-http-proxy (issue #15).
engine:
	./scripts/conformance-engine.sh

# The Plane A consistency gate only (feature 01).
gate:
	sbt engineGate

# Full layered summary: engine (Plane A) + Plane B/container layers (SKIP until #12/#13/#14).
run-all:
	./scripts/run-all.sh

fmt:
	sbt scalafmtAll scalafmtSbt

test:
	sbt test
