@rift @transport
Feature: Faults and transport errors

  # Transport-level faults abort the connection — the client sees a failure, not an HTTP response.
  Scenario Outline: <kind> aborts the connection at the transport level
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "<path>" expecting a transport error
    Then the request fails at the transport level

    Examples:
      | kind                   | path   |
      | tcp reset              | /reset |
      | random-data-then-close | /rand  |

  Scenario: latency fault delays the response past its floor
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "/slow"
    Then the response status is 200
    And the response took at least 600 ms

  Scenario: error fault sets the status, body and headers
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "/err"
    Then the response status is 503
    And the response body contains "DOWN"
    And the response header "Retry-After" is "30"

  # Precedence tcp > error > latency (rift#277): the latency delay applies, then the tcp reset wins
  # so the error fault never surfaces — the client sees a transport failure after the delay.
  Scenario: fault precedence — tcp wins over error, after the latency delay
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "/all" expecting a transport error
    Then the request fails at the transport level
    And the response took at least 600 ms

  Scenario: probability 0 disables a fault
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "/p0"
    Then the response status is 200
    And the response body is "ok"

  # rift#309: a top-level `fault` response should abort the connection (Mountebank parity), not
  # return HTTP 502. This build (0.11.3) resets at the transport level, so the historical @gap is
  # resolved — we assert the correct behavior, which now guards against a regression.
  # https://github.com/EtaCassiopeia/rift/issues/309
  Scenario: top-level fault resets the connection at the transport level (rift#309)
    Given a fresh imposter from fixture "faults/faults"
    When the client sends "GET" "/topfault" expecting a transport error
    Then the request fails at the transport level
