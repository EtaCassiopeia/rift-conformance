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

  # rift#1160 (0.18.0): POST /admin/reload swaps the config file's `routes` block into the running
  # front door; before, it validated the block and dropped it. @front-door is external-only: it needs
  # the engine started with `--front-door`, which only the spawned engine accepts.
  @front-door
  Scenario: reload re-applies an edited front-door routes block
    Given the engine is started with the front door and a route from host "a.test" to port 4872
    When the client sends "GET" "/x" through the front door with host "a.test"
    Then the response status is 200
    And the response body is "A"
    When the reload config file routes host "b.test" to port 4873
    And the engine reloads its configuration
    And the client sends "GET" "/x" through the front door with host "b.test"
    Then the response status is 200
    And the response body is "B"
    When the client sends "GET" "/x" through the front door with host "a.test"
    Then the response status is 404
    And the response header "x-rift-front-door" is "no-route"

  @front-door
  Scenario: an invalid route table refuses the reload and the old routes keep serving
    Given the engine is started with the front door and a route from host "a.test" to port 4872
    When the reload config file declares two routes with the same id
    And the engine reload is attempted
    Then the reload is refused naming "duplicate route id"
    When the client sends "GET" "/x" through the front door with host "a.test"
    Then the response status is 200
    And the response body is "A"

  @front-door
  Scenario: a routes block without --front-door reloads with a warning naming the flag
    Given the engine is started without the front door and a route from host "a.test" to port 4872
    When the engine reloads its configuration
    Then the reload report warns about "--front-door"
