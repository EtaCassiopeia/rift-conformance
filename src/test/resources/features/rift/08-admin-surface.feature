@rift @admin
Feature: Admin surface — stub-by-id, enable/disable, savedRequests filtering, flow-state KV, atomic PUT /imposters

  Scenario: a stub is retrievable by its explicit id; an unknown id 404s
    Given a fresh imposter from fixture "admin/base"
    Then the stub with id "stub-hi" is retrievable
    And the stub with id "stub-hi" includes "/hi"
    And no stub has id "nope"

  Scenario: disabling an imposter 503s the data plane; enabling it restores 200
    Given a fresh imposter from fixture "admin/base"
    When the client disables the imposter
    And the client sends "GET" "/hi"
    Then the response status is 503
    When the client enables the imposter
    And the client sends "GET" "/hi"
    Then the response status is 200
    And the response body is "hi"

  Scenario: X-Rift-Debug returns diagnostics instead of the normal stub response
    Given a fresh imposter from fixture "admin/base"
    When the request header "X-Rift-Debug" is "true"
    And the client sends "GET" "/hi"
    Then the response status is 200
    And the response header "x-rift-debug-response" is "true"
    And the response body contains "/hi"

  Scenario: savedRequests ?match= filters by header, and stacked match= clauses AND together
    Given a fresh imposter from fixture "admin/base"
    When the request header "X-Tag" is "a"
    And the request header "X-Env" is "prod"
    And the client sends "GET" "/hi"
    When the request header "X-Tag" is "a"
    And the request header "X-Env" is "dev"
    And the client sends "GET" "/hi"
    When the request header "X-Tag" is "b"
    And the request header "X-Env" is "prod"
    And the client sends "GET" "/hi"
    Then recorded requests total 3
    And recorded requests matching header "X-Tag" value "a" total 2
    And recorded requests matching header "X-Tag" value "a" and header "X-Env" value "prod" total 1

  Scenario: a targeted savedRequests delete removes only the matching requests
    Given a fresh imposter from fixture "admin/base"
    When the request header "X-Tag" is "a"
    And the client sends "GET" "/hi"
    When the request header "X-Tag" is "b"
    And the client sends "GET" "/hi"
    Then recorded requests total 2
    When the client deletes recorded requests matching header "X-Tag" value "a"
    Then recorded requests total 1
    And recorded requests matching header "X-Tag" value "b" total 1

  Scenario: flow-state KV full lifecycle — set, get, delete, then 404
    Given a fresh imposter from fixture "admin/base"
    When the client sets flow-state "k1" in flow "f1" to "v1"
    Then flow-state "k1" in flow "f1" is "v1"
    And flow-state "k1" in flow "f2" is absent
    When the client deletes flow-state "k1" in flow "f1"
    Then flow-state "k1" in flow "f1" is absent

  Scenario: a valid PUT /imposters atomically swaps the whole imposter set
    Given a fresh verbatim imposter from fixture "admin/pre-existing"
    When the client sends "GET" "/old"
    Then the response status is 200
    And the response body is "old"
    When the client replaces all imposters with fixture "admin/replace-batch"
    Then the imposter on port 4808 no longer serves
    When the client sends "GET" "/swapped" to port 4809
    Then the response status is 200
    And the response body is "swapped"

  Scenario: an invalid PUT /imposters batch is rejected and the previous set is untouched
    Given a fresh verbatim imposter from fixture "admin/pre-existing"
    When the client sends "GET" "/old"
    Then the response status is 200
    And the response body is "old"
    When the client replaces all imposters with an invalid batch
    # validate-then-swap: the rejected batch's port must never have been bound (no partial apply)…
    Then the imposter on port 4810 no longer serves
    # …and the previous set is untouched.
    When the client sends "GET" "/old"
    Then the response status is 200
    And the response body is "old"
