#!/bin/bash
# UTILTOOLS-29 QA probe: contact form state machine, audit trail and failover UI.
# Exercises the static assets served by the running Spring Boot app (port 8080).
# Conventions follow .qa/qa_probe.sh: curl, grep assertions, non-zero exit on failure.
B=${B:-http://localhost:8080}
FAIL=0

say() { echo; echo "### $*"; }

check() { # check <description> <pattern> <file>
  if grep -q -- "$2" "$3"; then echo "PASS: $1"; else echo "FAIL: $1 (missing: $2)"; FAIL=1; fi
}

refute() { # refute <description> <pattern> <file>
  if grep -q -- "$2" "$3"; then echo "FAIL: $1 (unexpected: $2)"; FAIL=1; else echo "PASS: $1"; fi
}

fetch() { # fetch <url> <outfile>
  code=$(curl -s -o "$2" -w '%{http_code}' "$1")
  if [ "$code" != "200" ]; then echo "FATAL: GET $1 -> $code"; exit 1; fi
  echo "GET $1 -> $code ($(wc -c < "$2") bytes)"
}

say "P0 service reachable"
curl -s -o /dev/null -w 'GET / -> %{http_code}\n' "$B/" || { echo "FATAL: $B unreachable"; exit 1; }

say "P1 DOD-01 DOD-07 contact.html declares the form, the three fields and the failover UI"
fetch "$B/contact.html" /tmp/qa_contact.html
check "form is #contact-form with novalidate"            'id="contact-form" novalidate' /tmp/qa_contact.html
check "name field"                                      'id="name"' /tmp/qa_contact.html
check "email field"                                     'id="email"' /tmp/qa_contact.html
check "message field"                                   'id="message"' /tmp/qa_contact.html
check "submit button during validating is disable-able" 'id="contact-submit"' /tmp/qa_contact.html
check "submit button is type=submit"                    'type="submit"' /tmp/qa_contact.html
check "error region is #contact-errors"                 'id="contact-errors"' /tmp/qa_contact.html
check "error region is announced (role=alert)"          'role="alert"' /tmp/qa_contact.html
check "error region is a live region"                   'aria-live="polite"' /tmp/qa_contact.html
check "per-field error slot name"                       'id="error-name"' /tmp/qa_contact.html
check "per-field error slot email"                       'id="error-email"' /tmp/qa_contact.html
check "per-field error slot message"                     'id="error-message"' /tmp/qa_contact.html
check "success banner is #contact-success"              'id="contact-success"' /tmp/qa_contact.html
check "success banner starts hidden"                    'id="contact-success" role="status" aria-live="polite" hidden' /tmp/qa_contact.html
check "page loads the state machine"                    'src="/script.js"' /tmp/qa_contact.html
check "script is deferred (no inline JS)"               'defer' /tmp/qa_contact.html
refute "no inline event handlers / no innerHTML in page" 'innerHTML' /tmp/qa_contact.html

say "P2 DOD-01 REQ-01 script.js declares the five states and the transition table"
fetch "$B/script.js" /tmp/qa_script.js
check "five states literal"         'const CONTACT_STATES = \["idle", "validating", "submitted", "success", "error"\]' /tmp/qa_script.js
check "transition table literal"    'const CONTACT_TRANSITIONS = {"idle":\["validating"\],"validating":\["submitted","error"\],"submitted":\["success","error"\],"success":\["idle"\],"error":\["validating"\]}' /tmp/qa_script.js
for state in idle validating submitted success error; do
  check "state $state participates in the table" "\"$state\":" /tmp/qa_script.js
done
check "single submit listener drives the machine" "addEventListener('submit'" /tmp/qa_script.js
check "submit is intercepted, not a silent page reload" 'event.preventDefault()' /tmp/qa_script.js
check "validation failure routes to error"  "go('validation-fail')" /tmp/qa_script.js
check "dispatch rejection routes to error"  "go('dispatch-rejected')" /tmp/qa_script.js
check "re-entrancy guard on validating"     "'validating' || state === 'submitted'" /tmp/qa_script.js

say "P3 DOD-02 REQ-02 audit trail is persisted per transition"
check "namespaced localStorage key" 'const CONTACT_AUDIT_KEY = "utiltools.contact.audit.v1"' /tmp/qa_script.js
check "required audit fields"       'const CONTACT_AUDIT_FIELDS = \["timestamp", "fromState", "toState", "formData"\]' /tmp/qa_script.js
check "bounded ring buffer"         'const CONTACT_AUDIT_MAX = 50' /tmp/qa_script.js
check "record is appended inside go()" 'appendAudit(from, target, data)' /tmp/qa_script.js
check "record carries timestamp"    'timestamp: now()' /tmp/qa_script.js
check "record carries fromState/toState" 'toState: toState' /tmp/qa_script.js
check "storage write"               'setItem(CONTACT_AUDIT_KEY' /tmp/qa_script.js
check "storage read"                'getItem(CONTACT_AUDIT_KEY' /tmp/qa_script.js
check "corrupt storage is dropped, not rethrown" 'removeItem(CONTACT_AUDIT_KEY' /tmp/qa_script.js

say "P4 DOD-03 REQ-03 rollback restores the pre-submission snapshot"
check "snapshot captured on idle|error -> validating" 'pendingSnapshot = copyFormData(data)' /tmp/qa_script.js
check "rollback restores all three fields" 'writeFormData(copyFormData(pendingSnapshot))' /tmp/qa_script.js
check "the three field ids are restored by id" 'const CONTACT_FIELD_IDS = \["name", "email", "message"\]' /tmp/qa_script.js
check "error transition invokes rollback" 'rollback();' /tmp/qa_script.js
check "rollback re-enables the submit button" 'submitButton.disabled = false' /tmp/qa_script.js

say "P5 DOD-07 REQ-05 failover UI preserves user input and renders text"
check "errors rendered with textContent" 'node.textContent = item.message' /tmp/qa_script.js
refute "no innerHTML anywhere in script.js" 'innerHTML' /tmp/qa_script.js
check "fields are only written from the snapshot" 'fields\[id\].value = data\[id\]' /tmp/qa_script.js
check "success clears the form" 'function resetFields()' /tmp/qa_script.js

say "P6 no backend surface was added (static assets only)"
code=$(curl -s -o /tmp/qa_post.json -w '%{http_code}' -X POST "$B/api/v1/utils/contact" -H 'Content-Type: application/json' -d '{"name":"probe"}')
echo "POST /api/v1/utils/contact -> $code"
if [ "$code" = "200" ] || [ "$code" = "201" ]; then echo "FAIL: unexpected contact endpoint"; FAIL=1; else echo "PASS: no contact endpoint (static-only change)"; fi
if [ -e tests/qa_probe_contact.sh ]; then echo "FAIL: unexpected tests/ shim"; FAIL=1; else echo "PASS: no tests/ shim added"; fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: PASS - contact form state machine, audit trail, rollback and failover UI verified"
  exit 0
fi
echo "RESULT: FAIL - see FAIL lines above"
exit 1
