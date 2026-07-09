@mock @sources
Feature: MockSource variants — Dsl (portable) and raw Resource/File/Dir (Rift)

  @portable
  Scenario: A Dsl source provisions a space
    Given a Dsl source serving GET "/dsl" text "from-dsl"
    When the client sends "GET" "/dsl"
    Then the response status is 200
    And the response body is "from-dsl"

  @rift
  Scenario: A classpath Resource source (raw Rift imposter) provisions a space
    Given a resource source "mocksources/rift/orders.json"
    When the client sends "GET" "/from-resource"
    Then the response status is 200
    And the response body is "resource-body"

  @rift
  Scenario: A File source (raw Rift imposter) provisions a space
    Given a file source "src/test/resources/mocksources/rift/orders.json"
    When the client sends "GET" "/from-resource"
    Then the response status is 200
    And the response body is "resource-body"

  # received() was only ever exercised against a DSL-provisioned space; this proves it works
  # end-to-end on a space provisioned from a RAW imposter document via MockSource.Resource (the path
  # a real consumer uses, authoring "recordRequests": true in the JSON). NB: the zio-bdd-rift adapter
  # force-sets recordRequests on all raw sources (RiftProtocol.imposterFromRaw), so recording here is
  # guaranteed by the adapter, not by the authored flag — see FINDINGS.md (#63).
  @rift
  Scenario: received() works on a raw imposter document provisioned via Resource
    Given a resource source "mocksources/rift/recorded.json"
    When the client sends "GET" "/recorded"
    Then the response status is 200
    And the response body is "recorded-body"
    And the mock recorded 1 requests
    And a "GET" to "/recorded" was recorded

  # A single raw imposter document declaring MULTIPLE stubs — a distinct parsing/plumbing path from
  # the well-covered DSL multi-rule case; every other raw fixture here has exactly one stub (#65).
  @rift
  Scenario: A raw imposter document with multiple stubs serves each distinctly
    Given a resource source "mocksources/rift/multi-stub.json"
    When the client sends "GET" "/alpha"
    Then the response body is "alpha-body"
    When the client sends "GET" "/beta"
    Then the response body is "beta-body"

  @rift
  Scenario: A Dir source provisions one space per file
    Given a directory source "src/test/resources/mocksources/rift-dir"
    Then the directory produced 2 spaces
    And each directory space serves its own path
