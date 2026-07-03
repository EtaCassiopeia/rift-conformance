@rift @scripting
Feature: Scripting engines and runaway bounds

  # Stateful: the flow_store counter persists across requests to the same imposter, so this is one
  # ordered scenario (a fresh imposter resets the counter to 0).
  Scenario: rhai flow_store counter increments across requests
    Given a fresh imposter from fixture "scripting/scripts"
    When the client sends "GET" "/rhai"
    Then the response status is 200
    And the response body is "1"
    When the client sends "GET" "/rhai"
    Then the response status is 200
    And the response body is "2"

  Scenario Outline: a <engine> script injects its response
    Given a fresh imposter from fixture "scripting/scripts"
    When the client sends "GET" "<path>"
    Then the response status is 200
    And the response body is "<body>"

    Examples:
      | engine | path | body   |
      | lua    | /lua | lua-ok |
      | js     | /js  | js-ok  |

  # rift#308: a runaway script is bounded by timeoutMs (300) — it returns 500 with x-rift-script-error
  # in about 300ms rather than hanging.
  Scenario: a runaway rhai script is bounded by its timeout
    Given a fresh imposter from fixture "scripting/runaway"
    When the client sends "GET" "/hang"
    Then the response status is 500
    And the response header "x-rift-script-error" is "true"
    And the response took at least 250 ms
    And the response took at most 2000 ms

  # scenarioParallelism=1 deliberately saturates the blocking-script threads. The burst is launched
  # in the background (fork), so the sibling + admin probes run WHILE the three runaways are still
  # in flight (the rift#308 property — the engine must not wedge during script execution). The burst
  # is joined last and every request must have been bounded (500).
  Scenario: a sibling imposter stays responsive while concurrent runaways are in flight
    Given a fresh verbatim imposter from fixture "scripting/sibling"
    And a fresh imposter from fixture "scripting/runaway"
    When the client fires 3 concurrent requests to "/hang"
    And the client sends "GET" "/alive" to port 4805
    Then the response status is 200
    And the response body is "alive"
    And the engine remains responsive
    And all concurrent responses have status 500
