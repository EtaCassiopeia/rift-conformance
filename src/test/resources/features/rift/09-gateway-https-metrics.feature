@rift @transport
Feature: Single-port gateway, binary-mode responses, self-signed HTTPS, and Prometheus metrics

  Scenario: the single-port gateway routes to the addressed imposter
    Given a fresh imposter from fixture "gateway/services"
    When the client sends "GET" "/g" via the gateway
    Then the response status is 200
    And the response body is "gwbody"

  Scenario: a binary-mode response body is base64-decoded on the wire
    Given a fresh imposter from fixture "gateway/services"
    When the client sends "GET" "/bin"
    Then the response status is 200
    And the response body is "hello"

  Scenario: an HTTPS imposter auto-generates a self-signed cert and serves over TLS
    Given a fresh verbatim imposter from fixture "gateway/secure"
    When the client sends "GET" "/sec" over HTTPS to port 4812
    Then the response status is 200
    And the response body is "secure"
    # …and the cert really is self-signed: a client that verifies the chain must reject it.
    And a cert-verifying HTTPS request to port 4812 at "/sec" is rejected

  Scenario: the metrics endpoint counts data-plane requests
    Given a fresh imposter from fixture "gateway/services"
    When the client records the request counter
    And the client sends "GET" "/ping"
    And the client sends "GET" "/ping"
    And the client sends "GET" "/ping"
    Then the request counter increased by at least 3
    And the metrics endpoint reports at least 1 requests
