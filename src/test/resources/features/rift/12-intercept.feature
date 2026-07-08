@rift @intercept
Feature: Intercept / TLS-MITM forward-proxy (rift#413) — CONNECT interception with rule matching

  # rift 0.10.0+ sits in the request path as an opt-in HTTPS forward proxy: the client CONNECTs,
  # rift TLS-terminates with an intercept CA (minting a per-SNI leaf), matches the decrypted request
  # against intercept rules, and serves an inline stub or forwards to an imposter. Driven here with a
  # JDK HttpClient pointed at the intercept listener as its proxy, trusting the exported truststore.
  #
  # @intercept-http marks scenarios that use the HTTP-only intercept admin routes (ca.pem, rules
  # list/clear) — embedded drives intercept over the C-ABI, which (as of zio-bdd 1.4.1) binds
  # start/add-rules/export-truststore but not those, so RIFT_MODE=embedded excludes them. The core
  # MITM matching scenarios (serve inline, forward) run under every intercept-capable mode.

  @intercept-http
  Scenario: the intercept CA and truststore are exported for the SUT to trust
    Then the intercept CA PEM is exported
    And the intercept truststore is exported with password "changeit"

  # @intercept-caller-ca: external mode seeds a committed test CA via `--intercept-ca-cert/--key`
  # (rift 0.11.3), so the exported CA is deterministic. Embedded/container don't seed a caller CA
  # (they let rift mint an ephemeral one), so this scenario is excluded from their aliases.
  @intercept-caller-ca
  Scenario: the intercept CA is the caller-provided persistent CA (rift 0.11.3), not ephemeral
    Then the exported intercept CA is the caller-provided test CA

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

  @intercept-http
  Scenario: intercept rules can be listed and cleared
    Given an intercept rule serves 200 "hi" for host "a.example.com" path "/"
    Then the intercept rules count is 1
    When the intercept rules are cleared
    Then the intercept rules count is 0
