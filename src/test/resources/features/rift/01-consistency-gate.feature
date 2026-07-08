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

  # @full-corpus: posts/serves the ENTIRE corpus, including the lua-scripting imposter
  # (05-scripting-engines.json). RIFT_MODE=container excludes these because the published
  # rift-proxy images are built without the `lua` cargo feature (unlike the released binary); the
  # full-corpus + rift-verify oracle stays external/embedded. Tracked by the agent-filed follow-up.
  @full-corpus
  Scenario: The engine serves every corpus imposter on its declared port
    Given every corpus imposter is posted to the engine
    Then every corpus imposter answers on its declared port

  @full-corpus
  Scenario: rift-verify drives the loaded corpus with no failures
    Given every corpus imposter is posted to the engine
    Then rift-verify reports 0 failures
