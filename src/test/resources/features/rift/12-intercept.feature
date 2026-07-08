@rift @intercept
Feature: Intercept / TLS-MITM forward-proxy (rift#413) — CONNECT interception with rule matching

  # rift 0.10.0+ sits in the request path as an opt-in HTTPS forward proxy: the client CONNECTs,
  # rift TLS-terminates with an intercept CA (minting a per-SNI leaf), matches the decrypted request
  # against intercept rules, and serves an inline stub or forwards to an imposter. Driven here with a
  # JDK HttpClient pointed at the intercept listener as its proxy, trusting the exported truststore.

  Scenario: the intercept CA and truststore are exported for the SUT to trust
    Then the intercept CA PEM is exported
    And the intercept truststore is exported with password "changeit"

  Scenario: an intercept rule serves an inline stub over TLS-MITM
    Given an intercept rule serves 200 "{ok:1}" for host "cdn.example.com" path "/config.json"
    When an HTTPS GET to "cdn.example.com" "/config.json" through the intercept proxy
    Then the response status is 200
    And the response body is "{ok:1}"

  Scenario: an intercept rule forwards the decrypted request to an imposter
    Given a fresh imposter from fixture "intercept/upstream"
    And an intercept rule forwards host "api.example.com" to the current imposter
    When an HTTPS GET to "api.example.com" "/svc" through the intercept proxy
    Then the response status is 200
    And the response body is "upstream-ok"

  Scenario: intercept rules can be listed and cleared
    Given an intercept rule serves 200 "hi" for host "a.example.com" path "/"
    Then the intercept rules count is 1
    When the intercept rules are cleared
    Then the intercept rules count is 0
