@rift @behaviors
Feature: Response behaviors — copy, lookup, wait, shellTransform, decorate

  Scenario: copy from a request header into the response
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the request header "X-Request-Id" is "abc123"
    And the client sends "GET" "/ch"
    Then the response status is 200
    And the response body is "rid=abc123"

  Scenario: copy via jsonpath from the request body
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the request body is "{\"user\":{\"name\":\"alice\"}}"
    And the client sends "POST" "/cj"
    Then the response status is 200
    And the response body is "who=alice"

  # `lookup` reads data/products.csv relative to the engine's cwd, which RiftHarness sets to
  # $RIFT_CORPUS_ROOT — row id=456 has name=Gadget.
  Scenario: lookup a CSV column by key
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the client sends "GET" "/cat/456"
    Then the response status is 200
    And the response body is "name=Gadget"

  Scenario: the wait behavior delays the response past its floor
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the client sends "GET" "/wait"
    Then the response status is 200
    And the response body is "waited"
    And the response took at least 500 ms

  Scenario: shellTransform rewrites a static response body
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the client sends "GET" "/sh"
    Then the response status is 200
    And the response body is "transformed"

  # rift#272: a multi-value Set-Cookie must survive a copy behavior as two distinct headers (not
  # comma-folded). This build serves both, so the historical @gap is resolved — we assert the
  # correct behavior, which now guards against a regression. https://github.com/EtaCassiopeia/rift/issues/272
  Scenario: multi-value Set-Cookie survives a copy behavior (rift#272)
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the client sends "GET" "/cc?q=hi"
    Then the response status is 200
    And the response body is "x=hi"
    And the response has 2 "Set-Cookie" headers

  Scenario: a JS decorate behavior rewrites the response
    Given a fresh imposter from fixture "behaviors/behaviors"
    When the client sends "GET" "/dec"
    Then the response status is 200
    And the response body is "decorated"

  # Mountebank v2 inject parity (rift#438): the `inject` function is config-first — its single
  # argument IS the request, exposing method/path/body directly (mirroring Mountebank's convention).
  Scenario: a config-first inject reads the request method and path
    Given a fresh imposter from fixture "behaviors/mb-inject"
    When the client sends "GET" "/cfg"
    Then the response status is 200
    And the response body is "GET /cfg"

  Scenario: a config-first inject reads the request body
    Given a fresh imposter from fixture "behaviors/mb-inject"
    When the request body is payload-123
    And the client sends "POST" "/body"
    Then the response status is 200
    And the response body is "got:payload-123"

  # The inject function has a native `logger` (config.logger) — using it does not fail the response.
  Scenario: an inject function can use the native logger
    Given a fresh imposter from fixture "behaviors/mb-inject"
    When the client sends "GET" "/log"
    Then the response status is 200
    And the response body is "logged"
