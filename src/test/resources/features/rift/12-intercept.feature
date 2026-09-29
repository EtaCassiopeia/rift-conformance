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

  # @intercept-runtime: rift 0.13.0 (rift#493) lets the TLS-MITM listener be started/stopped at
  # runtime over the admin API on a server that was started WITHOUT `--intercept-port`. External-only
  # (embedded drives intercept over the C-ABI, and container publishes a fixed `--intercept-port`
  # 1:1) — so this drives a dedicated no-intercept engine the external harness spawns for it, and the
  # embedded/container aliases exclude the tag.
  @intercept-runtime
  Scenario: the TLS-MITM listener starts and stops at runtime over the admin API (rift 0.13.0)
    Given a rift server started without an intercept listener
    When the intercept listener is queried over the admin API
    Then the response status is 404
    And the response body contains "intercept listener not running"
    When the intercept listener is started over the admin API
    Then the response status is 201
    And the response body contains "interceptPort"
    And the response body contains "interceptUrl"
    When the intercept listener is queried over the admin API
    Then the response status is 200
    When the intercept listener is started over the admin API
    Then the response status is 409
    When the intercept listener is stopped over the admin API
    Then the response status is 204
    When the intercept listener is queried over the admin API
    Then the response status is 404
    When the intercept listener is stopped over the admin API
    Then the response status is 204

  # @intercept-raw: request framing and connection handling inside the CONNECT tunnel (rift 0.18.0 —
  # rift#991 moved the tunnel onto hyper). A JDK HttpClient hides both, so these scenarios drive a
  # raw client: `CONNECT host:443`, TLS over the tunnel trusting the intercept CA, then hand-framed
  # HTTP/1.1. The 1 MiB request-body cap is the tunnel's; a body over it is refused before matching
  # or forwarding. (The 408 body-read timeout is not covered: it needs a multi-second stall, so it
  # stays pinned engine-side.)
  @intercept-raw
  Scenario: a tunnelled request body over the 1 MiB cap is refused 413 and never forwarded (rift#991)
    Given a fresh imposter from fixture "intercept/upstream"
    And an intercept rule forwards host "overcap.example.com" to the current imposter
    When a raw HTTPS POST of 1048577 body bytes to "overcap.example.com" "/svc" through the intercept tunnel
    Then the response status is 413
    And the current imposter has recorded 0 requests

  @intercept-raw
  Scenario: a tunnelled request body of exactly 1 MiB is still forwarded (rift#991)
    Given a fresh imposter from fixture "intercept/upstream"
    And an intercept rule forwards host "atcap.example.com" to the current imposter
    When a raw HTTPS POST of 1048576 body bytes to "atcap.example.com" "/svc" through the intercept tunnel
    Then the response status is 200
    And the response body is "upstream-ok"
    And the current imposter has recorded 1 requests

  @intercept-raw
  Scenario: a chunked tunnelled request body is decoded before rule matching (rift#991)
    Given an intercept rule serves 200 "chunked-matched" for host "chunked.example.com" when the body equals "hello chunked world"
    When a raw HTTPS POST of the chunked body "hello chunked world" to "chunked.example.com" "/upload" through the intercept tunnel
    Then the response status is 200
    And the response body is "chunked-matched"

  # rift 0.18.0 (rift#993) keeps a successful tunnel alive for more requests; the pre-TLS refusals
  # still close. (A 407 needs an engine started with --intercept-auth, which the shared harness is
  # not, so it is not covered here.)
  @intercept-raw
  Scenario: two requests on one CONNECT tunnel are both answered on the same connection (rift#993)
    Given an intercept rule serves 200 "first" for host "keepalive.example.com" path "/first"
    And an intercept rule serves 200 "second" for host "keepalive.example.com" path "/second"
    When a raw HTTPS GET to "keepalive.example.com" "/first" through the intercept tunnel
    Then the response status is 200
    And the response body is "first"
    When a raw HTTPS GET to "keepalive.example.com" "/second" through the intercept tunnel
    Then the response status is 200
    And the response body is "second"
    And the intercept tunnel answered 2 requests on one connection

  @intercept-raw
  Scenario: a non-CONNECT request to the intercept listener is refused 405 and the connection closed (rift#993)
    When a plain GET for "http://plain.example.com/" is sent to the intercept listener
    Then the response status is 405
    And the intercept listener then closes the connection

  # A 4 KiB tail past the cap: small enough that the engine may drain it and keep the tunnel.
  @intercept-raw
  Scenario: the unread tail of an over-cap body is never answered as a request (rift#993)
    Given an intercept rule serves 200 "smuggled" for host "tail.example.com" path "/smuggled"
    And an intercept rule serves 200 "after-413" for host "tail.example.com" path "/after"
    When a raw HTTPS POST of 1052672 body bytes of pipelined "/smuggled" requests to "tail.example.com" "/upload" through the intercept tunnel
    Then the response status is 413
    And the next raw HTTPS GET to "tail.example.com" "/after" on the same tunnel is answered "after-413" or the connection is closed

  # rift 0.18.0: the intercept listener advertises `h2, http/1.1` over ALPN (rift#996), and a request
  # header sent more than once keeps every value for matching and forwarding (rift#994).
  Scenario: an h2-capable client negotiates HTTP/2 through the intercept tunnel (rift#996)
    Given an intercept rule serves 200 "over-h2" for host "h2.example.com" path "/h2"
    When an HTTP/2 HTTPS GET to "h2.example.com" "/h2" through the intercept proxy
    Then the response status is 200
    And the response body is "over-h2"
    And the response was served over HTTP/2

  @intercept-raw
  Scenario: a repeated request header matches an intercept rule on either value (rift#994)
    Given an intercept rule serves 200 "saw-first" for host "multi-first.example.com" when header "x-multi" equals "first"
    And an intercept rule serves 200 "saw-second" for host "multi-second.example.com" when header "x-multi" equals "second"
    When a raw HTTPS GET to "multi-first.example.com" "/" with header "x-multi" sent as "first" then "second" through the intercept tunnel
    Then the response status is 200
    And the response body is "saw-first"
    When a raw HTTPS GET to "multi-second.example.com" "/" with header "x-multi" sent as "first" then "second" through the intercept tunnel
    Then the response status is 200
    And the response body is "saw-second"

  @intercept-raw
  Scenario: a repeated request header is forwarded with every value, in order (rift#994)
    Given a fresh imposter from fixture "intercept/upstream-recording"
    And an intercept rule forwards host "multi-forward.example.com" to the current imposter
    When a raw HTTPS GET to "multi-forward.example.com" "/svc" with header "x-multi" sent as "first" then "second" through the intercept tunnel
    Then the response status is 200
    And the response body is "upstream-ok"
    And the current imposter recorded header "x-multi" as "first" then "second"

  # rift 0.18.0 (rift#933): a serve rule's `body` may be any JSON value, not just a string, as with
  # `is.body` on an imposter. A non-string body is rendered to compact JSON once, when the rule is
  # added, and is listed back in the shape it was posted in.
  @intercept-raw
  Scenario: an intercept serve rule with a JSON object body serves it as compact JSON (rift#933)
    Given an intercept rule for host "object-body.example.com" path "/flags" serves the object body with "featureX" set to "ON"
    When a raw HTTPS GET to "object-body.example.com" "/flags" through the intercept tunnel
    Then the response status is 200
    And the response body is the compact JSON object with "featureX" set to "ON"
    And the response content-length matches its body

  @intercept-raw
  Scenario: the object body and its escaped-string form serve byte-identical bodies (rift#933)
    Given an intercept rule for host "object-form.example.com" path "/flags" serves the object body with "featureX" set to "ON"
    And an intercept rule for host "string-form.example.com" path "/flags" serves the escaped-string body with "featureX" set to "ON"
    When a raw HTTPS GET to "object-form.example.com" "/flags" through the intercept tunnel
    Then the response status is 200
    And the response body is byte-identical to a raw HTTPS GET to "string-form.example.com" "/flags" through the intercept tunnel

  @intercept-http
  Scenario: GET /intercept/rules lists an object body as an object, not its rendering (rift#933)
    Given an intercept rule for host "object-list.example.com" path "/flags" serves the object body with "featureX" set to "ON"
    Then the intercept rule for host "object-list.example.com" lists its serve body as a JSON object with "featureX" set to "ON"
