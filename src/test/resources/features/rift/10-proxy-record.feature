@rift @transport
Feature: Proxy record/replay — proxyOnce, proxyTransparent, proxyAlways chains, and predicateGenerators

  Scenario: a proxyOnce proxy with predicateGenerators records a replayable stub, then replays it
    Given a fresh verbatim imposter from fixture "proxy/once-upstream"
    And a fresh verbatim imposter from fixture "proxy/once-proxy"
    Then the imposter on port 4814 has 1 stubs
    When the client sends "GET" "/svc" to port 4814
    Then the response status is 200
    And the response body is "UP"
    And the imposter on port 4814 has 2 stubs
    And the imposter on port 4814 first recorded predicate includes "/svc"
    And the imposter on port 4814 first recorded predicate includes "GET"
    When the client tears down the imposter on port 4813
    And the client sends "GET" "/svc" to port 4814
    Then the response status is 200
    And the response body is "UP"

  Scenario: a proxyOnce proxy WITHOUT predicateGenerators does not record a replayable stub
    Given a fresh verbatim imposter from fixture "proxy/nopg-upstream"
    And a fresh verbatim imposter from fixture "proxy/nopg-proxy"
    Then the imposter on port 4816 has 1 stubs
    When the client sends "GET" "/svc" to port 4816
    Then the response status is 200
    And the response body is "UP2"
    And the imposter on port 4816 still has 1 stubs after recording settles

  Scenario: a proxyTransparent proxy forwards the response but records nothing
    Given a fresh verbatim imposter from fixture "proxy/transparent-upstream"
    And a fresh verbatim imposter from fixture "proxy/transparent-proxy"
    Then the imposter on port 4800 has 1 stubs
    When the client sends "GET" "/t" to port 4800
    Then the response status is 200
    And the response body is "TP"
    And the imposter on port 4800 still has 1 stubs after recording settles

  Scenario: a multi-level proxyAlways chain resolves through every hop
    Given a fresh verbatim imposter from fixture "proxy/chain-leaf"
    And a fresh verbatim imposter from fixture "proxy/chain-mid"
    And a fresh verbatim imposter from fixture "proxy/chain-top"
    When the client sends "GET" "/c" to port 4803
    Then the response status is 200
    And the response body is "leaf"

  Scenario: predicateGenerators can capture the query string
    Given a fresh verbatim imposter from fixture "proxy/query-upstream"
    And a fresh verbatim imposter from fixture "proxy/query-proxy"
    When the client sends "GET" "/q?x=1" to port 4806
    Then the response status is 200
    And the response body is "Q"
    And the imposter on port 4806 has 2 stubs
    And the imposter on port 4806 first recorded predicate includes "/q"
    # Prove the query VALUE was captured, not merely the "query" key.
    And the imposter on port 4806 first recorded predicate includes "{\"x\":\"1\"}"

  # predicateGenerators capture a named request header into the recorded predicate. NOTE the config
  # shape: unlike path/method/query (which take a boolean `true`), `headers` takes a per-header
  # OBJECT — `"headers": {"X-Trace": true}`. rift does NOT honour mountebank's blanket `"headers":
  # true` (capture-all) shorthand — that form is silently ignored — which is the one genuine
  # mountebank-compat divergence here (a config-shape gap, not a capture bug).
  Scenario: predicateGenerators capture a named request header into the recorded predicate
    Given a fresh verbatim imposter from fixture "proxy/headers-upstream"
    And a fresh verbatim imposter from fixture "proxy/headers-proxy"
    When the request header "X-Trace" is "abc123"
    And the client sends "GET" "/h" to port 4811
    Then the response status is 200
    And the response body is "H"
    And the imposter on port 4811 has 2 stubs
    And the imposter on port 4811 first recorded predicate includes "/h"
    And the imposter on port 4811 first recorded predicate includes "X-Trace"
    And the imposter on port 4811 first recorded predicate includes "abc123"
