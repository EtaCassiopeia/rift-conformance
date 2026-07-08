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

  @rift
  Scenario: A Dir source provisions one space per file
    Given a directory source "src/test/resources/mocksources/rift-dir"
    Then the directory produced 2 spaces
    And each directory space serves its own path
