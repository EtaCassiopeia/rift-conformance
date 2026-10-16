.PHONY: build-rift engine gate run-all fmt test

# Fetch the released rift binaries and print the resulting RIFT_BUILD_DIR. Run with
# `eval "$$(make build-rift)"` if you want RIFT_BUILD_DIR exported into your shell.
build-rift:
	./scripts/build-rift.sh

# Plane A: samples.rift.* suites against a real rift (issue #15).
engine:
	./scripts/conformance-engine.sh

# The Plane A consistency gate only (feature 01).
gate:
	sbt engineGate

# Full layered summary: Plane A external + embedded (SKIP below JDK 22) + container (SKIP unless
# RIFT_IT=1, gated on Docker, #12).
run-all:
	./scripts/run-all.sh

fmt:
	sbt scalafmtAll scalafmtSbt

test:
	sbt test
