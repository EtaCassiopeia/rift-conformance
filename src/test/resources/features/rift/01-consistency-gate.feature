@rift @gate
Feature: Cross-tool consistency — engine, rift-lint, and rift-verify agree on the corpus

  # Rift's admin API exposes no version endpoint, so version coherence is asserted from BUILD_INFO
  # (written by scripts/build-rift.sh) plus co-location of the tool binaries with the engine build.
  @preflight
  Scenario: Version coherence — the engine, rift-lint and rift-verify are one released build
    Then BUILD_INFO records a released build and all three binaries
    And the engine, rift-lint and rift-verify binaries match BUILD_INFO

  Scenario: The corpus is present and complete
    Given the corpus directory is loaded
    Then the corpus contains at least 15 imposter files

  Scenario: rift-lint accepts every corpus file
    Then rift-lint reports 0 errors across the corpus

  # @full-corpus: posts/serves the ENTIRE corpus, including the scripting imposter
  # (05-scripting-engines.json). RIFT_MODE=container still excludes these, but the original reason —
  # the published images lacking the `lua` cargo feature the corpus used — is void: rift 0.12.0
  # removed Lua (#451) and 05-scripting is now rhai + JS (both built-in). Re-enabling container
  # full-corpus + rift-verify is pending a green RIFT_IT run on the v0.12.0 image (follow-up #45).
  @full-corpus
  Scenario: The engine serves every corpus imposter on its declared port
    Given every corpus imposter is posted to the engine
    Then every corpus imposter answers on its declared port

  @full-corpus
  Scenario: rift-verify drives the loaded corpus with no failures
    Given every corpus imposter is posted to the engine
    Then rift-verify reports 0 failures
