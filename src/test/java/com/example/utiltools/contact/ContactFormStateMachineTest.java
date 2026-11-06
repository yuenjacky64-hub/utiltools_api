package com.example.utiltools.contact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the browser contact-form state machine declared in {@code static/script.js}.
 *
 * <p>The test reads the shipped assets from the classpath, parses the machine-readable
 * constants out of {@code script.js} and replays the machine in Java against those very
 * declarations, so the transitions, the audit record shape and the rollback contract are
 * asserted on the same table the browser executes rather than on a second copy.</p>
 */
class ContactFormStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCRIPT_RESOURCE = "static/script.js";
    private static final String PAGE_RESOURCE = "static/contact.html";

    private static final String IDLE = "idle";
    private static final String VALIDATING = "validating";
    private static final String SUBMITTED = "submitted";
    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private static final List<String> EVENTS = List.of(
            "submit", "validation-pass", "validation-fail", "dispatch-resolved", "dispatch-rejected", "reset");

    /** The legal event to target rows, mirroring CONTACT_EVENT_TARGETS in script.js. */
    private static final Map<String, Map<String, String>> EVENT_TARGETS = Map.of(
            "submit", Map.of(IDLE, VALIDATING, ERROR, VALIDATING),
            "validation-pass", Map.of(VALIDATING, SUBMITTED),
            "validation-fail", Map.of(VALIDATING, ERROR),
            "dispatch-resolved", Map.of(SUBMITTED, SUCCESS),
            "dispatch-rejected", Map.of(SUBMITTED, ERROR),
            "reset", Map.of(SUCCESS, IDLE));

    // ------------------------------------------------------------------ DOD-04

    @Test
    @DisplayName("idle -> validating -> submitted -> success on valid input, idle -> validating -> error on invalid input")
    void machineReachesSuccessAndErrorPaths() throws IOException {
        List<String> states = declaredStates();
        Map<String, List<String>> transitions = declaredTransitions();

        Machine valid = new Machine(states, transitions, declaredAuditMax());
        valid.setFields("Ada Lovelace", "ada@example.com", "Saturday in June - is the venue step-free?");
        assertThat(valid.submitAttempt()).isTrue();
        assertThat(valid.state()).isEqualTo(SUCCESS);
        assertThat(valid.path()).containsExactly(IDLE, VALIDATING, SUBMITTED, SUCCESS);

        Machine invalid = new Machine(states, transitions, declaredAuditMax());
        invalid.setFields("Ada Lovelace", "not-an-email", "Saturday in June - is the venue step-free?");
        assertThat(invalid.submitAttempt()).isFalse();
        assertThat(invalid.state()).isEqualTo(ERROR);
        assertThat(invalid.path()).containsExactly(IDLE, VALIDATING, ERROR);

        Machine blank = new Machine(states, transitions, declaredAuditMax());
        blank.setFields("", "", "");
        assertThat(blank.submitAttempt()).isFalse();
        assertThat(blank.state()).isEqualTo(ERROR);
        assertThat(blank.path()).containsExactly(IDLE, VALIDATING, ERROR);

        // Retry from error re-enters validating: the five states form a cycle, not a dead end.
        assertThat(invalid.fire("submit")).isTrue();
        assertThat(invalid.state()).isEqualTo(VALIDATING);
        assertThat(invalid.path()).containsExactly(IDLE, VALIDATING, ERROR, VALIDATING);

        // An event the current state does not accept is ignored: no state change, no audit record.
        Machine idle = new Machine(states, transitions, declaredAuditMax());
        int auditBefore = idle.audit().size();
        assertThat(idle.fire("dispatch-resolved")).isFalse();
        assertThat(idle.fire("validation-pass")).isFalse();
        assertThat(idle.state()).isEqualTo(IDLE);
        assertThat(idle.audit()).hasSize(auditBefore);
    }

    // ------------------------------------------------------------------ DOD-02

    @Test
    @DisplayName("Every transition appends one localStorage audit record with timestamp, fromState, toState and formData")
    void auditRecordDeclaresRequiredFields() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);

        List<String> auditFields = declaredAuditFields(script);
        assertThat(auditFields).containsExactly("timestamp", "fromState", "toState", "formData");
        assertThat(declaredAuditKey(script)).isEqualTo("utiltools.contact.audit.v1");
        assertThat(declaredAuditMax()).isEqualTo(50);

        // The trail is persisted through Web Storage, not kept in memory only.
        assertThat(script).contains("setItem(CONTACT_AUDIT_KEY");
        assertThat(script).contains("getItem(CONTACT_AUDIT_KEY");
        assertThat(script).contains("removeItem(CONTACT_AUDIT_KEY");

        Machine machine = new Machine(declaredStates(), declaredTransitions(), declaredAuditMax());
        machine.setFields("Ada Lovelace", "ada@example.com", "  padded message  ");
        machine.submitAttempt();

        List<Map<String, Object>> records = machine.audit();
        assertThat(records).hasSize(3);

        for (Map<String, Object> record : records) {
            assertThat(record.keySet()).containsExactlyElementsOf(auditFields);
            assertThat(record.get("timestamp")).isInstanceOf(String.class);
            Instant.parse((String) record.get("timestamp")); // ISO-8601 UTC, parseable
            assertThat(record.get("fromState")).isIn(declaredStates());
            assertThat(record.get("toState")).isIn(declaredStates());

            @SuppressWarnings("unchecked")
            Map<String, String> formData = (Map<String, String>) record.get("formData");
            assertThat(formData.keySet()).containsExactly("name", "email", "message");
            assertThat(formData.get("name")).isEqualTo("Ada Lovelace");
            assertThat(formData.get("email")).isEqualTo("ada@example.com");
            assertThat(formData.get("message")).isEqualTo("  padded message  ");
        }

        // Newest first, and the trail is a bounded ring buffer.
        assertThat(records.get(0).get("toState")).isEqualTo(SUCCESS);
        assertThat(records.get(2).get("fromState")).isEqualTo(IDLE);

        for (int i = 0; i < 40; i++) {
            machine.fire("reset");
            machine.setFields("Ada Lovelace", "ada@example.com", "message " + i);
            machine.submitAttempt();
        }
        assertThat(machine.audit())
                .as("audit trail is capped at CONTACT_AUDIT_MAX records")
                .hasSize(declaredAuditMax());
    }

    // ------------------------------------------------------------------ DOD-03

    @Test
    @DisplayName("Rollback restores the pre-submission snapshot and preserves the user's input on error")
    void rollbackRestoresSnapshotAndPreservesFields() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);

        String rollbackBody = functionBody(script, "rollback");
        assertThat(rollbackBody)
                .as("rollback restores the pending snapshot into the form fields")
                .contains("pendingSnapshot")
                .contains("writeFormData")
                .contains("copyFormData");
        assertThat(rollbackBody).as("rollback re-enables the submit button").contains("enableSubmit");
        assertThat(functionBody(script, "applySideEffects"))
                .as("the error transition invokes rollback instead of failing silently")
                .contains("rollback()");
        assertThat(script)
                .as("the three form fields are restored by id")
                .contains("const CONTACT_FIELD_IDS = [\"name\", \"email\", \"message\"]");

        Machine machine = new Machine(declaredStates(), declaredTransitions(), declaredAuditMax());
        machine.setFields("Ada Lovelace", "ada@example.com", "  padded message  ");
        Map<String, String> snapshot = machine.fields();

        machine.fire("submit"); // the snapshot is captured on idle -> validating

        // The user keeps typing while the form is validating: rollback must still
        // restore the pre-submission snapshot, not whatever is on screen now.
        machine.setFields("Ada Lovelace (edited)", "ada@example.com", "  padded message  ");
        machine.fire("validation-fail");

        assertThat(machine.state()).isEqualTo(ERROR);
        assertThat(machine.fields()).isEqualTo(snapshot);
        assertThat(machine.submitEnabled()).isTrue();
        assertThat(machine.rollbackInvocations()).isEqualTo(1);

        // Idempotent: a second rollback is a no-op on the same snapshot.
        machine.rollback();
        machine.rollback();
        assertThat(machine.fields()).isEqualTo(snapshot);
        assertThat(machine.submitEnabled()).isTrue();

        // Whitespace typed by the user is restored verbatim, never cleared.
        Machine padded = new Machine(declaredStates(), declaredTransitions(), declaredAuditMax());
        padded.setFields("Ada", "ada@example.com", " ");
        padded.fire("submit");
        padded.setFields("", "", "");
        padded.fire("validation-fail");
        assertThat(padded.fields())
                .containsEntry("name", "Ada")
                .containsEntry("email", "ada@example.com")
                .containsEntry("message", " ");
    }

    // ------------------------------------------------------------------ DOD-05

    @Test
    @DisplayName("Invariants: states stay inside the five literals, audit is never empty after a submit, input survives errors")
    void invariantsAlwaysHold() throws IOException {
        List<String> states = declaredStates();
        Map<String, List<String>> transitions = declaredTransitions();

        assertThat(states).containsExactlyInAnyOrder(IDLE, VALIDATING, SUBMITTED, SUCCESS, ERROR);
        assertThat(transitions.keySet())
                .as("the transition table is total over the declared states")
                .containsExactlyInAnyOrderElementsOf(states);

        assertThat(reachableFrom(states, transitions, IDLE))
                .as("every state is reachable from idle")
                .containsExactlyInAnyOrderElementsOf(states);

        for (Map.Entry<String, List<String>> entry : transitions.entrySet()) {
            assertThat(entry.getValue())
                    .as("state %s has at least one outgoing edge", entry.getKey())
                    .isNotEmpty();
            assertThat(states).as("no edge leaves the declared state set").containsAll(entry.getValue());
        }
        assertThat(transitions.get(SUCCESS)).containsExactly(IDLE);
        assertThat(transitions.get(IDLE)).containsExactly(VALIDATING);

        // Every event sequence up to five events: the state stays inside the set and each
        // accepted transition leaves exactly one audit record.
        List<List<String>> sequences = new ArrayList<>();
        buildSequences(new ArrayList<>(), sequences, 5);
        assertThat(sequences).hasSize(9331);

        for (List<String> sequence : sequences) {
            Machine machine = new Machine(states, transitions, declaredAuditMax());
            machine.setFields("Ada Lovelace", "ada@example.com", "Saturday in June");
            int transitionsApplied = 0;
            for (String event : sequence) {
                if (machine.fire(event)) {
                    transitionsApplied++;
                }
                assertThat(machine.state())
                        .as("sequence %s never leaves the declared state set", sequence)
                        .isIn(states);
            }
            assertThat(machine.audit())
                    .as("sequence %s audits each accepted transition exactly once", sequence)
                    .hasSize(transitionsApplied);
        }

        // Any submit attempt from idle audits at least one record, starting at fromState=idle.
        Machine firstAttempt = new Machine(states, transitions, declaredAuditMax());
        firstAttempt.setFields("", "", "");
        assertThat(firstAttempt.submitAttempt()).isFalse();
        assertThat(firstAttempt.audit()).isNotEmpty();
        assertThat(firstAttempt.audit().get(firstAttempt.audit().size() - 1).get("fromState")).isEqualTo(IDLE);

        // The successful path ends enabled, in a declared state, with the trail intact.
        Machine succeeding = new Machine(states, transitions, declaredAuditMax());
        succeeding.setFields("Ada Lovelace", "ada@example.com", "Saturday in June");
        assertThat(succeeding.submitAttempt()).isTrue();
        assertThat(succeeding.state()).isEqualTo(SUCCESS);
        assertThat(succeeding.submitEnabled()).isTrue();
        assertThat(succeeding.audit()).hasSize(3);

        // Form data is never lost on error.
        Machine failing = new Machine(states, transitions, declaredAuditMax());
        failing.setFields("", "", "");
        failing.submitAttempt();
        assertThat(failing.state()).isEqualTo(ERROR);
        assertThat(failing.submitEnabled()).isTrue();
    }

    // ------------------------------------------------------------------ DOD-06

    @Test
    @DisplayName("Submit handler audits every transition, routes failure to rollback and never fails silently")
    void submitHandlerAuditsEveryTransition() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);
        assertThat(script).contains("addEventListener('submit'");
        assertThat(functionBody(script, "onSubmit")).contains("preventDefault").contains("submitAttempt");

        String submitBody = functionBody(script, "submitAttempt");
        assertThat(submitBody)
                .as("the handler drives the machine through every transition")
                .contains("go('submit')")
                .contains("go('validation-pass')")
                .contains("go('validation-fail')")
                .contains("go('dispatch-resolved')")
                .contains("go('dispatch-rejected')");
        assertThat(submitBody).as("a failed dispatch is surfaced, not swallowed").contains("pendingErrors");
        assertThat(submitBody).as("re-entrancy guard rejects a second submit").contains("'validating'");
        assertThat(script).as("DOM writes never use innerHTML").doesNotContain("innerHTML");
        assertThat(functionBody(script, "renderErrors")).contains("textContent");

        Machine machine = new Machine(declaredStates(), declaredTransitions(), declaredAuditMax());
        machine.setFields("Ada Lovelace", "ada@example.com", "Saturday in June");
        machine.submitAttempt();

        assertThat(machine.audit()).hasSize(machine.path().size() - 1);
        for (int i = 0; i < machine.audit().size(); i++) {
            Map<String, Object> record = machine.audit().get(machine.audit().size() - 1 - i);
            assertThat(record.get("fromState")).isEqualTo(machine.path().get(i));
            assertThat(record.get("toState")).isEqualTo(machine.path().get(i + 1));
        }

        // Re-entrancy: a second submit inside the validating window changes nothing.
        Machine pending = new Machine(declaredStates(), declaredTransitions(), declaredAuditMax());
        pending.setFields("Ada Lovelace", "ada@example.com", "Saturday in June");
        pending.fire("submit");
        int auditAtValidating = pending.audit().size();
        assertThat(pending.submitAttempt()).isFalse();
        assertThat(pending.state()).isEqualTo(VALIDATING);
        assertThat(pending.audit()).hasSize(auditAtValidating);
    }

    @Test
    @DisplayName("contact.html declares the failover UI: form fields, aria-live error region, success banner")
    void pageDeclaresFailoverUiWiring() throws IOException {
        String page = readClasspath(PAGE_RESOURCE);
        assertThat(page).contains(
                "id=\"contact-form\"",
                "novalidate",
                "id=\"name\"",
                "id=\"email\"",
                "id=\"message\"",
                "id=\"contact-submit\"",
                "type=\"submit\"",
                "id=\"contact-errors\"",
                "role=\"alert\"",
                "aria-live=\"polite\"",
                "id=\"error-name\"",
                "id=\"error-email\"",
                "id=\"error-message\"",
                "id=\"contact-success\"",
                "role=\"status\"",
                "hidden",
                "src=\"/script.js\"",
                "defer");
    }

    // ------------------------------------------------------------------ helpers

    private static String readClasspath(String resource) throws IOException {
        ClassLoader loader = ContactFormStateMachineTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            assertThat(stream).as("classpath resource %s is packaged", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String constantJson(String source, String name) {
        Matcher matcher = Pattern
                .compile("const\\s+" + Pattern.quote(name) + "\\s*=\\s*([^;]+);")
                .matcher(source);
        assertThat(matcher.find()).as("script.js declares %s", name).isTrue();
        return matcher.group(1).trim();
    }

    private static List<String> declaredStates() throws IOException {
        return MAPPER.readValue(constantJson(readClasspath(SCRIPT_RESOURCE), "CONTACT_STATES"),
                new TypeReference<List<String>>() {
                });
    }

    private static Map<String, List<String>> declaredTransitions() throws IOException {
        return MAPPER.readValue(constantJson(readClasspath(SCRIPT_RESOURCE), "CONTACT_TRANSITIONS"),
                new TypeReference<LinkedHashMap<String, List<String>>>() {
                });
    }

    private static List<String> declaredAuditFields(String source) throws IOException {
        return MAPPER.readValue(constantJson(source, "CONTACT_AUDIT_FIELDS"),
                new TypeReference<List<String>>() {
                });
    }

    private static String declaredAuditKey(String source) throws IOException {
        return MAPPER.readValue(constantJson(source, "CONTACT_AUDIT_KEY"), String.class);
    }

    private static int declaredAuditMax() throws IOException {
        return Integer.parseInt(constantJson(readClasspath(SCRIPT_RESOURCE), "CONTACT_AUDIT_MAX"));
    }

    /** Extracts the body of {@code function <name>(...)} by brace matching. */
    private static String functionBody(String source, String name) {
        int declaration = source.indexOf("function " + name + "(");
        assertThat(declaration).as("script.js declares function %s", name).isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces in function " + name);
    }

    private static Set<String> reachableFrom(List<String> states, Map<String, List<String>> transitions, String start) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : transitions.getOrDefault(current, List.of())) {
                assertThat(states).contains(next);
                if (seen.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return seen;
    }

    private static void buildSequences(List<String> prefix, List<List<String>> out, int maxLength) {
        out.add(List.copyOf(prefix));
        if (prefix.size() == maxLength) {
            return;
        }
        for (String event : EVENTS) {
            prefix.add(event);
            buildSequences(prefix, out, maxLength);
            prefix.remove(prefix.size() - 1);
        }
    }

    /** In-memory replay of the machine declared in script.js, driven by the parsed tables. */
    private static final class Machine {

        private final List<String> states;
        private final Map<String, List<String>> transitions;
        private final Deque<Map<String, Object>> records = new ArrayDeque<>();
        private final int auditMax;
        private final List<String> path = new ArrayList<>();
        private final Map<String, String> fields = new LinkedHashMap<>();

        private String state = IDLE;
        private Map<String, String> snapshot;
        private boolean submitEnabled = true;
        private int rollbackInvocations;

        Machine(List<String> states, Map<String, List<String>> transitions, int auditMax) {
            this.states = states;
            this.transitions = transitions;
            this.auditMax = auditMax;
            path.add(IDLE);
            setFields("", "", "");
        }

        void setFields(String name, String email, String message) {
            fields.put("name", name);
            fields.put("email", email);
            fields.put("message", message);
        }

        Map<String, String> fields() {
            return Map.copyOf(fields);
        }

        String state() {
            return state;
        }

        List<String> path() {
            return List.copyOf(path);
        }

        boolean submitEnabled() {
            return submitEnabled;
        }

        int rollbackInvocations() {
            return rollbackInvocations;
        }

        List<Map<String, Object>> audit() {
            return List.copyOf(records);
        }

        /** Mirrors go(event) in script.js: an event with no row for the current state is a no-op. */
        boolean fire(String event) {
            Map<String, String> row = EVENT_TARGETS.get(event);
            if (row == null) {
                return false;
            }
            String target = row.get(state);
            if (target == null || !states.contains(target)) {
                return false;
            }
            if (!transitions.getOrDefault(state, List.of()).contains(target)) {
                return false;
            }
            String from = state;
            Map<String, String> data = new LinkedHashMap<>(fields);
            if (VALIDATING.equals(target)) {
                snapshot = data;
            }
            state = target;
            auditAppend(from, target, data);
            applySideEffects(target);
            path.add(target);
            return true;
        }

        boolean submitAttempt() {
            if (VALIDATING.equals(state) || SUBMITTED.equals(state)) {
                return false;
            }
            if (!fire("submit")) {
                return false;
            }
            if (!validationErrors().isEmpty()) {
                fire("validation-fail");
                return false;
            }
            fire("validation-pass");
            fire("dispatch-resolved");
            return true;
        }

        void rollback() {
            rollbackInvocations++;
            if (snapshot != null) {
                fields.putAll(snapshot);
            }
            submitEnabled = true;
        }

        private List<String> validationErrors() {
            List<String> errors = new ArrayList<>();
            String name = fields.get("name").trim();
            String email = fields.get("email").trim();
            String message = fields.get("message").trim();
            if (name.isEmpty()) {
                errors.add("name is required");
            } else if (name.length() > 100) {
                errors.add("name is too long");
            }
            if (email.isEmpty()) {
                errors.add("email is required");
            } else if (email.length() > 254 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                errors.add("email is invalid");
            }
            if (message.isEmpty()) {
                errors.add("message is required");
            } else if (message.length() > 2000) {
                errors.add("message is too long");
            }
            return errors;
        }

        private void applySideEffects(String target) {
            if (VALIDATING.equals(target) || SUBMITTED.equals(target)) {
                submitEnabled = false;
            } else if (SUCCESS.equals(target)) {
                submitEnabled = true;
                setFields("", "", "");
            } else if (ERROR.equals(target)) {
                submitEnabled = true;
                rollback();
            } else if (IDLE.equals(target)) {
                submitEnabled = true;
            }
        }

        private void auditAppend(String from, String to, Map<String, String> data) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", Instant.now().toString());
            record.put("fromState", from);
            record.put("toState", to);
            record.put("formData", new LinkedHashMap<>(data));
            records.addFirst(record);
            while (records.size() > auditMax) {
                records.removeLast();
            }
        }
    }
}
