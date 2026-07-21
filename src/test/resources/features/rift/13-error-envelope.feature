@rift @errors
Feature: Error-response envelope — the standard {"errors":[{code,message}]} contract on every error door

  # rift 0.14.0 completed the rift#611 sweep: every engine error door now serves the Mountebank
  # envelope {"errors":[{"code","message"}]} with Content-Type: application/json (was a bare
  # {"error":"…"} with no content-type). The pre-sweep bug was concrete — a script error message
  # containing a `"` produced a body the client's JSON decoder rejected — and the suite could not
  # have caught it, because every scenario asserted marker headers plus body SUBSTRINGS, which
  # survive any envelope change. This feature pins the shape itself.
  #
  # `code` is deliberately asserted per door rather than assumed equal to the status: the four
  # `inject` doors carry a Mountebank slug ("invalid injection", "injection timeout", …) while every
  # other door carries the status as a string. Asserting a blanket code==status would be wrong.
  #
  # Two doors named in #118 are deliberately absent because probing 0.14.0 showed they are not error
  # doors at all: a failed TEMPLATE render serves 200 with the template left literal, and a
  # non-strict DECORATE failure serves the undecorated 200 (both already pinned as 200s — see
  # 04-scripting-bounds). Only their strictBehaviors forms are error doors, and those are covered.

  # The rift#611 regression case, pinned directly: the thrown message contains a double quote, so a
  # body that is not serde-escaped fails to parse at all. The message assert reads the decoded JSON
  # string, so it can only pass if the quote round-tripped through a well-formed envelope.
  Scenario: a script error whose message carries a double quote stays decodable JSON
    Given a fresh imposter from fixture "errors/script-error-quoted"
    When the client sends "GET" "/boom"
    Then the response status is 500
    And the response header "x-rift-script-error" is "true"
    And the response is a rift error envelope
    And the error envelope code is "500"
    And the error envelope message contains "expected \"ready\""

  # strictBehaviors turns a shellTransform failure and a binary-mode base64 decode failure into real
  # 500 doors (non-strict, both serve the untransformed 200 — see feature 06). Both keep their marker
  # header AND now carry the envelope.
  Scenario Outline: a strictBehaviors <behavior> failure serves the envelope with a 500
    Given a fresh imposter from fixture "errors/strict-behaviors"
    When the client sends "GET" "<path>"
    Then the response status is 500
    And the response header "<marker>" is "true"
    And the response is a rift error envelope
    And the error envelope code is "500"
    And the error envelope message contains "strictBehaviors"

    Examples:
      | behavior       | path    | marker                        |
      | shellTransform | /shell  | x-rift-shelltransform-error   |
      | binary decode  | /binary | x-rift-binary-error           |

  # The defaultForward 502: the upstream is a refused port, so the forward fails at connect time.
  Scenario: a defaultForward to a refused upstream serves a 502 envelope
    Given a fresh imposter from fixture "errors/default-forward"
    When the client sends "GET" "/anything"
    Then the response status is 502
    And the response header "x-rift-default-forward-error" is "true"
    And the response is a rift error envelope
    And the error envelope code is "502"
    And the error envelope message contains "defaultForward"

  # The over-size door is enforced before any stub matching, so it needs no special imposter — only
  # a body past the engine's 10 MiB request cap.
  Scenario: a request body past the size cap serves a 413 envelope
    Given a fresh imposter from fixture "errors/oversize"
    When the request body is 11 MiB of "a"
    And the client sends "POST" "/echo"
    Then the response status is 413
    And the response is a rift error envelope
    And the error envelope code is "413"
    And the error envelope message contains "exceeds maximum size"

  # The disabled-imposter door. Feature 08 already asserts the 503 status; this pins its body shape.
  Scenario: a disabled imposter serves a 503 envelope
    Given a fresh imposter from fixture "admin/base"
    When the client disables the imposter
    And the client sends "GET" "/hi"
    Then the response status is 503
    And the response header "x-rift-imposter-disabled" is "true"
    And the response is a rift error envelope
    And the error envelope code is "503"
    And the error envelope message contains "disabled"

  # The admin plane serves the same envelope on a rejected create — an unresolvable script reference
  # is caught at imposter-creation time, so it never reaches the data plane at all. Both reference
  # forms are covered: a `ref:` naming no `_rift.scripts` entry and a `file:` that resolves to
  # nothing. The message assert is the shared "Script resolution failed" prefix rather than either
  # tail, since the `file:` tail depends on whether a `--scripts-dir` is configured.
  Scenario Outline: the admin plane rejects an unresolvable script <kind> with a 400 envelope
    When the client posts an imposter whose script <kind> is unresolvable
    Then the response status is 400
    And the response is a rift error envelope
    And the error envelope code is "400"
    And the error envelope message contains "Script resolution failed"

    Examples:
      | kind |
      | ref  |
      | file |

  # The deliberate NON-envelope doors, pinned as negatives so a future over-sweep of rift#611 is
  # caught rather than silently accepted. An unrecognised fault name is answered with a plain-text
  # 500 carrying no content-type at all.
  Scenario: an unrecognised fault name keeps its plain-text 500 reply
    Given a fresh imposter from fixture "errors/unknown-fault"
    When the client sends "GET" "/unknown-fault"
    Then the response status is 500
    And the response body contains "Unknown fault"
    And the response is not a JSON error envelope
    And the response has no header "content-type"
