@rift
Feature: Incremental reload (rift#316) — POST /admin/reload reconciles toward --configfile

  Scenario: reload against an identical configfile reports no changes and preserves runtime state
    Given the engine is started with the reload config file
    When the client sends "GET" "/a" to port 4870
    And the client sends "GET" "/a" to port 4870
    Then imposter 4870 has recorded 2 requests
    When the engine reloads its configuration
    Then the reload report created 0 imposters
    And the reload report deleted 0 imposters
    And the reload report exposes all four change fields
    And imposter 4870 has recorded 2 requests
    When the client sends "GET" "/a" to port 4870
    Then the response body is "A"
    When the client sends "GET" "/b" to port 4871
    Then the response body is "B"
