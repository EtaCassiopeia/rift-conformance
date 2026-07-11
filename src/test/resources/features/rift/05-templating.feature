@rift @templating
Feature: Response templating — request variables and date tokens

  Scenario: request method and path variables render
    Given a fresh imposter from fixture "templating/templates"
    When the client sends "GET" "/rmp"
    Then the response status is 200
    And the response body is "m=GET p=/rmp"

  Scenario: request query variable renders
    Given a fresh imposter from fixture "templating/templates"
    When the client sends "GET" "/rq?name=bob"
    Then the response status is 200
    And the response body is "q=bob"

  Scenario: request header variable renders
    Given a fresh imposter from fixture "templating/templates"
    When the request header "X-Test" is "val"
    And the client sends "GET" "/rh"
    Then the response status is 200
    And the response body is "h=val"

  # Each date token must render an ISO-8601 date with the CORRECT offset — not just "some date" and
  # not the literal `{{...}}`. The expected date is computed at assertion time, so a bug that ignored
  # the offset/sign (e.g. always returned NOW) fails, while the test never goes stale.
  Scenario Outline: date token <token> renders a date <offset> <unit> from today
    Given a fresh imposter from fixture "templating/templates"
    When the client sends "GET" "<path>"
    Then the response status is 200
    And the response body is a date offset by <offset> <unit> from today

    Examples:
      | token    | path | offset | unit   |
      | NOW      | /now | 0      | days   |
      | DAYS+5   | /d5  | 5      | days   |
      | DAYS-5   | /dm5 | -5     | days   |
      | MONTHS+1 | /m1  | 1      | months |
      | MONTHS-1 | /mm1 | -1     | months |

  # rift 0.13.0 (rift#444): the richer `{{ ... }}` template FUNCTIONS (handlebars-style args) are opt-in
  # behind the `_rift.templated: true` response flag — distinct from the always-on `${...}` vars and the
  # `{{NOW}}`/`{{DAYS±N}}` date tokens above. `{{ request.header 'X-Name' }}` renders the request header.
  Scenario: a templated response renders a {{ }} request-field function into the body
    Given a fresh imposter from fixture "templating/templated"
    When the request header "X-Name" is "world"
    And the client sends "GET" "/hdr"
    Then the response status is 200
    And the response body is "hi world"

  # The flag is opt-in: WITHOUT `_rift.templated`, the same `{{ ... }}` is served verbatim (not rendered).
  Scenario: without the templated flag the {{ }} function is left literal
    Given a fresh imposter from fixture "templating/templated"
    When the request header "X-Name" is "world"
    And the client sends "GET" "/plain"
    Then the response status is 200
    And the response body is "hi {{ request.header 'X-Name' }}"

  # A second {{ }} function — randomInt with equal bounds renders deterministically.
  Scenario: a templated response renders the randomInt {{ }} function
    Given a fresh imposter from fixture "templating/templated"
    When the client sends "GET" "/rand"
    Then the response status is 200
    And the response body is "n=7"

  # rift 0.13.0 (rift#435): a stub's `routePattern` extracts path parameters into
  # `request.pathParams.<name>`, usable from a response template. (By engine design pathParams feeds
  # templates and scripts — not predicate matching — so ordinary predicates route to the stub.)
  Scenario Outline: routePattern extracts a path parameter into a response template
    Given a fresh imposter from fixture "templating/path-params"
    When the client sends "GET" "<path>"
    Then the response status is 200
    And the response body is "<body>"

    Examples:
      | path         | body       |
      | /users/42    | user=42    |
      | /users/alice | user=alice |

  # The same extraction is visible to a script via `ctx.request.pathParams`.
  Scenario: routePattern extracts a path parameter visible to a script
    Given a fresh imposter from fixture "templating/path-params"
    When the client sends "GET" "/s/77"
    Then the response status is 200
    And the response body is "script=77"

  # routePattern is NOT a predicate: a path that satisfies the stub's predicate (startsWith /users/)
  # but does not fit the pattern shape (/users/:id) still serves the stub — pathParams is simply empty.
  Scenario: a routePattern that the path does not fit still serves the stub with empty pathParams
    Given a fresh imposter from fixture "templating/path-params"
    When the client sends "GET" "/users/42/extra"
    Then the response status is 200
    And the response body is "user="
