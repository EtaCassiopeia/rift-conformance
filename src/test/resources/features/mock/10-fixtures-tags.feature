@mock @fixtures @portable
Feature: Scenario-tier mock fixtures — @mock(name) deploys catalog entries

  @mock(greeting)
  Scenario: The greeting fixture is deployed for the tagged scenario
    Then the fixture provisioned 1 spaces
    When the client sends "GET" "/hello"
    Then the response body is "hi"

  @mock(greeting, farewell)
  Scenario: Two named fixtures are deployed together
    Then the fixture provisioned 2 spaces
