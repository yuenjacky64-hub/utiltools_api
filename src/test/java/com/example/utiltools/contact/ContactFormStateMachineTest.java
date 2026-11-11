package com.example.utiltools.contact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the browser contact-form state machine declared in {@code static/script.js}.
 *
 * <p>The behavioural half of this suite executes the <em>shipped</em> {@code script.js}
 * inside a headless JavaScript engine (Node.js) against a hand-written DOM and Web Storage
 * stub. Nothing is re-implemented in Java: the transitions, the audit records, the rollback
 * and the failover UI are produced by the production file, so a drift between the script and
 * this suite fails the build. Node is a test-only driver; when it is absent the behavioural
 * tests are skipped (not silently passed) and the structural assertions still run.</p>
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

    private static final String GOOD_NAME = "Ada Lovelace";
    private static final String GOOD_EMAIL = "ada@example.com";
    private static final String GOOD_MESSAGE = "Saturday in June - is the venue step-free?";

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

    /** Lazily executed driver output, shared by every test method in the class. */
    private static volatile JsonNode scenarios;

    // ------------------------------------------------------------------ DOD-04

    @Test
    @DisplayName("script.js walks idle -> validating -> submitted -> success on valid input and idle -> validating -> error on invalid input")
    void machineReachesSuccessAndErrorPaths() throws IOException {
        JsonNode out = scenarios();

        JsonNode valid = out.get("valid");
        // The button is disabled for the whole in-flight window (validating, then submitted).
        assertThat(valid.get("during").get("state").asText()).isIn(VALIDATING, SUBMITTED);
        assertThat(valid.get("during").get("disabled").asBoolean()).isTrue();
        assertThat(valid.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(valid.get("disabled").asBoolean()).isFalse();
        assertThat(valid.get("successHidden").asBoolean()).isFalse();
        assertThat(chain(valid.get("audit")))
                .describedAs("newest-first audit chain of the happy path")
                .containsExactly(List.of(SUBMITTED, SUCCESS), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));

        JsonNode invalid = out.get("invalid");
        assertThat(invalid.get("state").asText()).isEqualTo(ERROR);
        assertThat(invalid.get("disabled").asBoolean()).isFalse();
        assertThat(invalid.get("errors").get("email").asText()).isNotEmpty();
        assertThat(chain(invalid.get("audit")))
                .containsExactly(List.of(VALIDATING, ERROR), List.of(IDLE, VALIDATING));

        // Blank input is the same error path, with one message per field.
        JsonNode blank = out.get("blank");
        assertThat(blank.get("state").asText()).isEqualTo(ERROR);
        assertThat(blank.get("errors").get("name").asText()).isNotEmpty();
        assertThat(blank.get("errors").get("email").asText()).isNotEmpty();
        assertThat(blank.get("errors").get("message").asText()).isNotEmpty();
        assertThat(chain(blank.get("audit")))
                .containsExactly(List.of(VALIDATING, ERROR), List.of(IDLE, VALIDATING));

        // A rejected dispatch is an error too, with the summary region carrying the failure.
        JsonNode rejected = out.get("dispatchRejected");
        assertThat(rejected.get("state").asText()).isEqualTo(ERROR);
        assertThat(rejected.get("errors").get("summary").asText()).isNotEmpty();
        assertThat(chain(rejected.get("audit")))
                .containsExactly(List.of(SUBMITTED, ERROR), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));
    }

    // ------------------------------------------------------------------ DOD-02

    @Test
    @DisplayName("script.js persists one localStorage audit record per transition with timestamp, fromState, toState and formData")
    void auditRecordDeclaresRequiredFields() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);

        assertThat(declaredAuditFields(script)).containsExactly("timestamp", "fromState", "toState", "formData");
        assertThat(declaredAuditKey(script)).isEqualTo("utiltools.contact.audit.v1");
        assertThat(declaredAuditMax(script)).isEqualTo(50);
        assertThat(script).contains("setItem(CONTACT_AUDIT_KEY");
        assertThat(script).contains("getItem(CONTACT_AUDIT_KEY");
        assertThat(script).contains("removeItem(CONTACT_AUDIT_KEY");
        // Degraded-storage flag: a failed write must not let the stale stored value win.
        assertThat(script).contains("memoryOnly");

        JsonNode valid = scenarios().get("valid");
        JsonNode audit = valid.get("audit");
        assertThat(audit).hasSize(3);
        // Validation runs synchronously inside the submit handler, so by the time
        // onSubmit returns the trail already holds idle->validating and validating->submitted.
        assertThat(chain(valid.get("auditDuring")))
                .containsExactly(List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));
        assertThat(valid.get("storageWrites").asInt()).isEqualTo(3);
        assertThat(valid.get("stored")).hasSize(3);

        String newerFrom = null;
        for (JsonNode record : audit) {
            List<String> keys = new ArrayList<>();
            record.get("keys").forEach(node -> keys.add(node.asText()));
            assertThat(keys).containsExactly("formData", "fromState", "timestamp", "toState");
            List<String> fields = new ArrayList<>();
            record.get("formData").forEach(node -> fields.add(node.asText()));
            assertThat(fields).containsExactly("email", "message", "name");
            Instant.parse(record.get("timestamp").asText());
            assertThat(record.get("message").asText()).isEqualTo(GOOD_MESSAGE);
            if (newerFrom != null) {
                // newest-first: this record's target is the next (newer) record's source
                assertThat(record.get("toState").asText()).isEqualTo(newerFrom);
            }
            newerFrom = record.get("fromState").asText();
        }

        // Quota exhausted on the very first write while storage stays readable: the in-memory
        // tail must outrank the stale stored array instead of being discarded by readAll().
        JsonNode quota = scenarios().get("quotaFromFirstWrite");
        assertThat(quota.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(chain(quota.get("audit"))).containsExactly(
                List.of(SUBMITTED, SUCCESS), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING),
                List.of(IDLE, VALIDATING));
        assertThat(quota.get("stored")).hasSize(1).describedAs("the refused write leaves the stale value alone");

        // Quota exhausted mid-chain: every transition is still reported.
        JsonNode mid = scenarios().get("quotaMidChain");
        assertThat(mid.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(chain(mid.get("audit"))).hasSize(3);
        assertThat(mid.get("secondRead")).hasSize(3);
    }

    // ------------------------------------------------------------------ DOD-03

    @Test
    @DisplayName("script.js rolls back to the pre-submission snapshot and preserves user input on error")
    void rollbackRestoresSnapshotAndPreservesFields() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);
        assertThat(script).contains("function rollback()");
        assertThat(script).contains("writeFormData(copyFormData(pendingSnapshot))");
        assertThat(script).contains("enableSubmit()");

        JsonNode invalid = scenarios().get("invalid");
        assertThat(text(invalid.get("fields"), "name")).isEqualTo(GOOD_NAME);
        assertThat(text(invalid.get("fields"), "email")).isEqualTo("not-an-email");
        assertThat(text(invalid.get("fields"), "message")).isEqualTo(GOOD_MESSAGE);
        assertThat(invalid.get("pendingSnapshot").get("email").asText()).isEqualTo("not-an-email");
        // rollback() is idempotent: a second call must not clear or duplicate anything.
        assertThat(text(invalid.get("afterDoubleRollback"), "name")).isEqualTo(GOOD_NAME);
        assertThat(text(invalid.get("afterDoubleRollback"), "email")).isEqualTo("not-an-email");
        assertThat(text(invalid.get("afterDoubleRollback"), "message")).isEqualTo(GOOD_MESSAGE);

        // A server-side rejection does not eat the message either.
        JsonNode rejected = scenarios().get("dispatchRejected");
        assertThat(text(rejected.get("fields"), "name")).isEqualTo(GOOD_NAME);
        assertThat(text(rejected.get("fields"), "email")).isEqualTo(GOOD_EMAIL);
        assertThat(text(rejected.get("fields"), "message")).isEqualTo(GOOD_MESSAGE);

        // Success is the only state that clears the inputs.
        assertThat(text(scenarios().get("valid").get("fields"), "name")).isEmpty();
    }

    // ------------------------------------------------------------------ DOD-05

    @Test
    @DisplayName("no event sequence leaves an undefined state and every accepted transition is audited exactly once")
    void invariantsAlwaysHold() throws IOException {
        String script = readClasspath(SCRIPT_RESOURCE);
        List<String> states = declaredStates(script);
        Map<String, List<String>> transitions = declaredTransitions(script);

        assertThat(states).containsExactly(IDLE, VALIDATING, SUBMITTED, SUCCESS, ERROR);
        for (String state : states) {
            assertThat(transitions.get(state)).isNotNull();
            for (String target : transitions.get(state)) {
                assertThat(states).contains(target);
            }
        }
        for (Map.Entry<String, Map<String, String>> row : EVENT_TARGETS.entrySet()) {
            for (Map.Entry<String, String> cell : row.getValue().entrySet()) {
                assertThat(transitions.get(cell.getKey()))
                        .describedAs("%s from %s", row.getKey(), cell.getKey())
                        .contains(cell.getValue());
            }
        }

        JsonNode sequences = scenarios().get("sequences");
        assertThat(sequences.get("violations")).isEmpty();
        assertThat(sequences.get("count").asInt()).isEqualTo(259);

        // Poisoned storage never wedges the machine: the corrupt value is dropped and the
        // submit still completes with a full, JSON-array audit trail.
        for (JsonNode poisoned : scenarios().get("poisoned")) {
            assertThat(poisoned.get("state").asText()).isEqualTo(SUCCESS);
            assertThat(poisoned.get("audit")).hasSize(3);
            assertThat(poisoned.get("storedIsArray").asBoolean()).isTrue();
        }

        // Re-entrancy: a second click during the in-flight window is refused, not duplicated.
        JsonNode reentrancy = scenarios().get("reentrancy");
        assertThat(reentrancy.get("secondReturn").asBoolean()).isFalse();
        assertThat(reentrancy.get("afterSecond").get("audit").asInt()).isEqualTo(2);
        assertThat(reentrancy.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(chain(reentrancy.get("audit"))).hasSize(3);
    }

    // ------------------------------------------------------------------ DOD-01

    @Test
    @DisplayName("the shipped bootstrap audits every transition and a post-success submit is a real transition, not a no-op")
    void submitHandlerAuditsEveryTransition() throws IOException {
        JsonNode bootstrap = scenarios().get("bootstrap");
        assertThat(bootstrap.get("listeners").toString()).isEqualTo("[\"submit\"]");
        assertThat(bootstrap.get("prevented").asBoolean()).isTrue();
        assertThat(bootstrap.get("hasMachine").asBoolean()).isTrue();
        assertThat(bootstrap.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(bootstrap.get("stored")).hasSize(3);

        JsonNode resubmit = scenarios().get("resubmit");
        assertThat(resubmit.get("afterFirst").get("state").asText()).isEqualTo(SUCCESS);
        assertThat(resubmit.get("afterFirst").get("audit").asInt()).isEqualTo(3);
        // Second submission after success leaves the terminal state and re-enters the machine.
        assertThat(resubmit.get("duringSecond").get("state").asText()).isIn(VALIDATING, SUBMITTED);
        assertThat(resubmit.get("duringSecond").get("disabled").asBoolean()).isTrue();
        assertThat(resubmit.get("duringSecond").get("audit").asInt()).isEqualTo(6);
        assertThat(resubmit.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(chain(resubmit.get("audit"))).hasSize(7);
        JsonNode resetEdge = resubmit.get("audit").get(3);
        assertThat(resetEdge.get("fromState").asText()).isEqualTo(SUCCESS);
        assertThat(resetEdge.get("toState").asText()).isEqualTo(IDLE);

        String script = readClasspath(SCRIPT_RESOURCE);
        assertThat(script).contains("go('reset')");
        assertThat(script).contains("appendAudit(from, target, data)");
    }

    // ------------------------------------------------------------------ DOD-01 (failover UI)

    @Test
    @DisplayName("contact.html declares the failover regions script.js writes to")
    void pageDeclaresFailoverUiWiring() throws IOException {
        String page = readClasspath(PAGE_RESOURCE);
        String script = readClasspath(SCRIPT_RESOURCE);

        for (String id : List.of("contact-form", "contact-submit", "error-summary", "contact-success",
                "error-name", "error-email", "error-message", "name", "email", "message")) {
            assertThat(page).describedAs("page declares id=%s", id).contains("id=\"" + id + "\"");
            assertThat(script).describedAs("script reads id=%s", id).contains("'" + id + "'");
        }
        assertThat(page).contains("novalidate");
        assertThat(page).contains("id=\"contact-success\"");
        assertThat(page).contains("hidden>");
        assertThat(script).contains("disabled = true");
        assertThat(script).contains("textContent");
        assertThat(script).doesNotContain("innerHTML");
    }

    // ------------------------------------------------------------------ DOD-03

    @Test
    @DisplayName("the shipped default transport asserts the wire contract and the bounded retry matrix")
    void wireContractAndRetryMatrixIsAsserted() throws IOException {
        String page = readClasspath(PAGE_RESOURCE);
        String script = readClasspath(SCRIPT_RESOURCE);

        // The page ships the hidden live status region; the script manages aria-busy on it.
        assertThat(page).describedAs("page declares the #contact-status region").contains("id=\"contact-status\"");
        assertThat(page).contains("aria-live=\"polite\"");
        assertThat(script).contains("aria-busy");

        JsonNode out = scenarios();

        // One real POST with the exact path, method, headers and body; a 202 success completes.
        JsonNode wire = out.get("wireSuccess");
        assertThat(wire.get("attempts").asInt()).isEqualTo(1);
        assertThat(wire.get("pathOk").asBoolean()).isTrue();
        assertThat(wire.get("method").asText()).isEqualTo("POST");
        assertThat(wire.get("contentType").asText()).isEqualTo("application/json");
        assertThat(wire.get("accept").asText()).isEqualTo("application/json");
        assertThat(wire.get("body").get("name").asText()).isEqualTo(GOOD_NAME);
        assertThat(wire.get("body").get("email").asText()).isEqualTo(GOOD_EMAIL);
        assertThat(wire.get("body").get("message").asText()).isEqualTo(GOOD_MESSAGE);
        assertThat(wire.get("keyWithinLimit").asBoolean()).isTrue();
        assertThat(wire.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(wire.get("successHidden").asBoolean()).isFalse();
        assertThat(chain(wire.get("audit")))
                .containsExactly(List.of(SUBMITTED, SUCCESS), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));
        assertThat(wire.get("delays")).isEmpty();
        assertThat(wire.get("statusBusy").asText()).isEqualTo("false");

        // One key per submission: stable across its transient retries, kept after the
        // attempts exhaust, reused on an unchanged resubmission, rotated on a payload change.
        JsonNode keys = out.get("keyLifecycle");
        assertThat(keys.get("totalKeys").asInt()).isEqualTo(9);
        assertThat(keys.get("stableAcrossRetries").asBoolean()).isTrue();
        assertThat(keys.get("keptAfterExhaust").asBoolean()).isTrue();
        assertThat(keys.get("reusedOnResubmit").asBoolean()).isTrue();
        assertThat(keys.get("rotatedOnPayloadChange").asBoolean()).isTrue();
        assertThat(keys.get("keyLengthsOk").asBoolean()).isTrue();

        // A terminal 409 fails fast: a single attempt, rollback restores the typed values,
        // the key rotates, and exactly one audit row is appended for submitted -> error.
        JsonNode conflict = out.get("http409");
        assertThat(conflict.get("attempts").asInt()).isEqualTo(1);
        assertThat(conflict.get("state").asText()).isEqualTo(ERROR);
        assertThat(conflict.get("fields").get("name").asText()).isEqualTo(GOOD_NAME);
        assertThat(conflict.get("fields").get("email").asText()).isEqualTo(GOOD_EMAIL);
        assertThat(conflict.get("fields").get("message").asText()).isEqualTo(GOOD_MESSAGE);
        assertThat(conflict.get("disabled").asBoolean()).isFalse();
        assertThat(conflict.get("summary").asText()).isNotEmpty();
        assertThat(conflict.get("storedKeyRotated").asBoolean()).isTrue();
        assertThat(chain(conflict.get("audit")))
                .containsExactly(List.of(SUBMITTED, ERROR), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));

        // A 429 with a numeric Retry-After sleeps the bounded seconds then retries and succeeds.
        JsonNode retry = out.get("http429RetryAfter");
        assertThat(retry.get("attempts").asInt()).isEqualTo(2);
        assertThat(retry.get("delays").toString()).isEqualTo("[2000]");
        assertThat(retry.get("state").asText()).isEqualTo(SUCCESS);
        assertThat(retry.get("successHidden").asBoolean()).isFalse();

        // Exhausted network failure: three attempts, capped exponential delays, rollback
        // keeps the typed values, aria-busy is released and retries never append audit rows.
        JsonNode exhausted = out.get("networkExhausted");
        assertThat(exhausted.get("attempts").asInt()).isEqualTo(3);
        assertThat(exhausted.get("delays").toString()).isEqualTo("[500,1000]");
        assertThat(exhausted.get("state").asText()).isEqualTo(ERROR);
        assertThat(exhausted.get("fields").get("name").asText()).isEqualTo(GOOD_NAME);
        assertThat(exhausted.get("fields").get("email").asText()).isEqualTo(GOOD_EMAIL);
        assertThat(exhausted.get("fields").get("message").asText()).isEqualTo(GOOD_MESSAGE);
        assertThat(exhausted.get("disabled").asBoolean()).isFalse();
        assertThat(exhausted.get("summary").asText()).isNotEmpty();
        assertThat(exhausted.get("statusBusy").asText()).isEqualTo("false");
        assertThat(exhausted.get("formBusy").asText()).isEqualTo("false");
        assertThat(exhausted.get("keyStillKept").asBoolean()).isTrue();
        assertThat(chain(exhausted.get("audit")))
                .containsExactly(List.of(SUBMITTED, ERROR), List.of(VALIDATING, SUBMITTED), List.of(IDLE, VALIDATING));
    }

    // ------------------------------------------------------------------ harness

    /**
     * Runs {@code static/script.js} in Node against the embedded DOM/storage driver and
     * caches the JSON report. A missing Node runtime fails the suite rather than skipping it:
     * the behaviour of the shipped file is the proof, so it must not be able to vanish silently.
     */
    private static JsonNode scenarios() throws IOException {
        JsonNode cached = scenarios;
        if (cached != null) {
            return cached;
        }
        assertThat(nodeAvailable())
                .describedAs("node is required to execute the shipped static/script.js headlessly")
                .isTrue();
        synchronized (ContactFormStateMachineTest.class) {
            if (scenarios == null) {
                scenarios = runDriver();
            }
            return scenarios;
        }
    }

    private static JsonNode runDriver() throws IOException {
        Path script = Files.createTempFile("contact-script", ".js");
        Path driver = Files.createTempFile("contact-driver", ".js");
        try {
            try (InputStream in = new ClassPathResource(SCRIPT_RESOURCE).getInputStream()) {
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(driver, DRIVER_JS, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("node", driver.toString(), script.toString());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String stdout;
            String stderr;
            try (InputStream out = process.getInputStream(); InputStream err = process.getErrorStream()) {
                boolean finished = process.waitFor(180, TimeUnit.SECONDS);
                stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("node driver timed out after 180s");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while running the node driver", interrupted);
            }
            if (process.exitValue() != 0) {
                throw new IOException("node driver failed (" + process.exitValue() + "): " + stderr);
            }
            return MAPPER.readTree(stdout);
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(driver);
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Newest-first audit records as [fromState, toState] pairs. */
    private static List<List<String>> chain(JsonNode audit) {
        List<List<String>> chain = new ArrayList<>();
        for (JsonNode record : audit) {
            chain.add(List.of(record.get("fromState").asText(), record.get("toState").asText()));
        }
        return chain;
    }

    private static String text(JsonNode node, String field) {
        return node.get(field).asText();
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> declaredStates(String script) {
        String block = stringArrayBlock(script, "CONTACT_STATES");
        List<String> states = new ArrayList<>();
        Matcher matcher = Pattern.compile("[\"']([^\"']+)[\"']").matcher(block);
        while (matcher.find()) {
            states.add(matcher.group(1));
        }
        return states;
    }

    private static Map<String, List<String>> declaredTransitions(String script) {
        int start = script.indexOf("const CONTACT_TRANSITIONS");
        assertThat(start).describedAs("CONTACT_TRANSITIONS table").isGreaterThan(-1);
        int end = script.indexOf("};", script.indexOf('{', start));
        String block = script.substring(script.indexOf('{', start), end);
        Map<String, List<String>> transitions = new LinkedHashMap<>();
        Matcher row = Pattern.compile("[\"']?([A-Za-z]+)[\"']?\\s*:\\s*\\[([^\\]]*)\\]").matcher(block);
        while (row.find()) {
            List<String> targets = new ArrayList<>();
            Matcher target = Pattern.compile("[\"']([^\"']+)[\"']").matcher(row.group(2));
            while (target.find()) {
                targets.add(target.group(1));
            }
            transitions.put(row.group(1), targets);
        }
        return transitions;
    }

    private static String stringArrayBlock(String script, String name) {
        int start = script.indexOf("const " + name);
        assertThat(start).describedAs("%s declaration", name).isGreaterThan(-1);
        int end = script.indexOf("]", start);
        return script.substring(start, end + 1);
    }

    private static List<String> declaredAuditFields(String script) {
        List<String> fields = new ArrayList<>();
        Matcher matcher = Pattern.compile("[\"']([a-zA-Z]+)[\"']").matcher(stringArrayBlock(script, "CONTACT_AUDIT_FIELDS"));
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static String declaredAuditKey(String script) {
        Matcher matcher = Pattern.compile("const CONTACT_AUDIT_KEY\\s*=\\s*[\"']([^\"']+)[\"']").matcher(script);
        assertThat(matcher.find()).describedAs("CONTACT_AUDIT_KEY").isTrue();
        return matcher.group(1);
    }

    private static int declaredAuditMax(String script) {
        Matcher matcher = Pattern.compile("const CONTACT_AUDIT_MAX\\s*=\\s*(\\d+)").matcher(script);
        assertThat(matcher.find()).describedAs("CONTACT_AUDIT_MAX").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Headless DOM/Web Storage driver executed by Node. It boots the shipped script in a
     * fresh vm context and prints one JSON report consumed by the assertions above.
     */
    private static final String DRIVER_JS = """
'use strict';
/*
 * Headless driver for static/script.js.
 *
 * Loads the shipped script into a fresh vm context with a hand-written DOM and
 * Web Storage stub, then drives the production machine through every DOD
 * scenario and prints one JSON object on stdout. No third-party module is used.
 */
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync(process.argv[2], 'utf8');
const KEY = 'utiltools.contact.audit.v1';
const IKEY = 'utiltools.contact.idempotency.v1';

function element(id) {
    const attrs = {};
    return {
        id: id,
        value: '',
        disabled: false,
        hidden: false,
        textContent: '',
        listeners: [],
        addEventListener: function (type, fn) {
            this.listeners.push({type: type, fn: fn});
        },
        setAttribute: function (name, value) {
            attrs[name] = String(value);
        },
        getAttribute: function (name) {
            return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
        }
    };
}

function makeDocument() {
    const ids = ['contact-form', 'contact-submit', 'error-summary', 'contact-success',
        'error-name', 'error-email', 'error-message', 'name', 'email', 'message',
        'contact-status'];
    const nodes = {};
    ids.forEach(function (id) {
        nodes[id] = element(id);
    });
    nodes['contact-success'].hidden = true;
    return {
        nodes: nodes,
        getElementById: function (id) {
            return Object.prototype.hasOwnProperty.call(nodes, id) ? nodes[id] : null;
        }
    };
}

function makeStorage(options) {
    const opts = options || {};
    const data = {};
    if (typeof opts.initial === 'string') {
        data[KEY] = opts.initial;
    }
    let writes = 0;
    const writeCounts = {};
    return {
        data: data,
        writes: function () {
            return writes;
        },
        auditWrites: function () {
            return writeCounts[KEY] || 0;
        },
        getItem: function (key) {
            return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null;
        },
        setItem: function (key, value) {
            writes += 1;
            writeCounts[key] = (writeCounts[key] || 0) + 1;
            if (typeof opts.failAfter === 'number' && writes > opts.failAfter) {
                const error = new Error('quota');
                error.name = 'QuotaExceededError';
                throw error;
            }
            data[key] = String(value);
        },
        removeItem: function (key) {
            delete data[key];
        }
    };
}

function clock() {
    let tick = 0;
    return function () {
        const date = new Date(Date.UTC(2026, 0, 1, 0, 0, 0, tick));
        tick += 1;
        return date.toISOString();
    };
}

function loadContext(doc, storage) {
    const sandbox = {document: doc, localStorage: storage, console: console};
    // The shipped default transport uses the global fetch when available (and
    // injects a test transport when the machine is constructed with one).
    // Provide a fake fetch so the existing bootstrap scenario still passes.
    sandbox.fetch = function (url, init) {
        return Promise.resolve({
            ok: true,
            status: 202,
            headers: {get: function () { return null; }},
            text: function () {
                return Promise.resolve(JSON.stringify({
                    success: true,
                    data: {message: 'Dispatched', taskId: 'fake-task'},
                    timestamp: new Date().toISOString()
                }));
            },
            json: function () {
                return Promise.resolve({
                    success: true,
                    data: {id: 'sub-fake', status: 'DISPATCHING', deduplicated: false},
                    timestamp: new Date().toISOString()
                });
            }
        });
    };
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, {filename: 'script.js'});
    return sandbox;
}

function machineFor(doc, storage, transmit, now) {
    const sandbox = loadContext(doc, storage);
    return sandbox.ContactFormStateMachine.createContactFormMachine({
        document: doc,
        storage: storage,
        transmit: transmit,
        now: now || clock()
    });
}

function fill(doc, name, email, message) {
    doc.nodes['name'].value = name;
    doc.nodes['email'].value = email;
    doc.nodes['message'].value = message;
}

function values(doc) {
    return {
        name: doc.nodes['name'].value,
        email: doc.nodes['email'].value,
        message: doc.nodes['message'].value
    };
}

function errorTexts(doc) {
    return {
        summary: doc.nodes['error-summary'].textContent,
        name: doc.nodes['error-name'].textContent,
        email: doc.nodes['error-email'].textContent,
        message: doc.nodes['error-message'].textContent
    };
}

function summary(record) {
    return {
        keys: Object.keys(record).sort(),
        timestamp: record.timestamp,
        fromState: record.fromState,
        toState: record.toState,
        formData: record.formData ? Object.keys(record.formData).sort() : null,
        message: record.formData ? record.formData.message : null
    };
}

function event() {
    return {preventDefault: function () {}};
}

function settle() {
    return new Promise(function (resolve) {
        setImmediate(resolve);
    });
}

const EVENTS = ['submit', 'validation-pass', 'validation-fail', 'dispatch-resolved', 'dispatch-rejected', 'reset'];
const GOOD = ['Ada Lovelace', 'ada@example.com', 'Saturday in June - is the venue step-free?'];
const BAD = ['Ada Lovelace', 'not-an-email', 'Saturday in June - is the venue step-free?'];

async function main() {
    const out = {};

    /* S1: valid input walks idle -> validating -> submitted -> success. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        let release = null;
        const m = machineFor(doc, storage, function () {
            return new Promise(function (resolve) {
                release = resolve;
            });
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        out.valid = {
            during: {state: m.getState(), disabled: doc.nodes['contact-submit'].disabled},
            auditDuring: m.auditRecords().map(summary)
        };
        release({ok: true});
        await settle();
        out.valid.state = m.getState();
        out.valid.disabled = doc.nodes['contact-submit'].disabled;
        out.valid.successHidden = doc.nodes['contact-success'].hidden;
        out.valid.fields = values(doc);
        out.valid.errors = errorTexts(doc);
        out.valid.audit = m.auditRecords().map(summary);
        out.valid.stored = JSON.parse(storage.data[KEY] || '[]').map(summary);
        out.valid.storageWrites = storage.auditWrites();
    }

    /* S2: invalid input walks idle -> validating -> error with input preserved. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, BAD[0], BAD[1], BAD[2]);
        const returned = m.onSubmit(event());
        out.invalid = {
            returned: returned,
            state: m.getState(),
            fields: values(doc),
            errors: errorTexts(doc),
            disabled: doc.nodes['contact-submit'].disabled,
            audit: m.auditRecords().map(summary),
            stored: JSON.parse(storage.data[KEY] || '[]').map(summary),
            pendingSnapshot: m.pendingSnapshot(),
            afterDoubleRollback: (function () {
                m.rollback();
                m.rollback();
                return values(doc);
            })()
        };
    }

    /* S2b: blank input fails every field but still keeps the (empty) values
     * the user typed, and the error region names each field. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, '', '  ', '');
        m.onSubmit(event());
        out.blank = {
            state: m.getState(),
            errors: errorTexts(doc),
            disabled: doc.nodes['contact-submit'].disabled,
            audit: m.auditRecords().map(summary)
        };
    }

    /* S3: a rejected dispatch lands on error with a visible summary. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const m = machineFor(doc, storage, function () {
            return Promise.reject(new Error('network down'));
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.dispatchRejected = {
            state: m.getState(),
            fields: values(doc),
            errors: errorTexts(doc),
            disabled: doc.nodes['contact-submit'].disabled,
            audit: m.auditRecords().map(summary)
        };
    }

    /* S4: BLOCK-1 - a submit after success is a real transition, not a no-op. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        const afterFirst = {state: m.getState(), audit: m.auditRecords().length};
        fill(doc, 'Grace Hopper', 'grace@example.com', 'Second message');
        m.onSubmit(event());
        const duringSecond = {
            state: m.getState(),
            disabled: doc.nodes['contact-submit'].disabled,
            audit: m.auditRecords().length
        };
        await settle();
        out.resubmit = {
            afterFirst: afterFirst,
            duringSecond: duringSecond,
            state: m.getState(),
            fields: values(doc),
            audit: m.auditRecords().map(summary)
        };
    }

    /* S5a: BLOCK-2 - quota failure from the very first write, storage readable. */
    {
        const stale = [{
            timestamp: '2026-01-01T00:00:00.000Z',
            fromState: 'idle',
            toState: 'validating',
            formData: {name: 'old', email: 'old@example.com', message: 'old'}
        }];
        const storage = makeStorage({initial: JSON.stringify(stale), failAfter: 0});
        const doc = makeDocument();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.quotaFromFirstWrite = {
            state: m.getState(),
            writes: storage.writes(),
            stored: JSON.parse(storage.data[KEY] || '[]').map(summary),
            audit: m.auditRecords().map(summary)
        };
    }

    /* S5b: BLOCK-2 - quota failure in the middle of the transition chain. */
    {
        const storage = makeStorage({failAfter: 1});
        const doc = makeDocument();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.quotaMidChain = {
            state: m.getState(),
            writes: storage.writes(),
            audit: m.auditRecords().map(summary),
            secondRead: m.auditRecords().map(summary)
        };
    }

    /* S6: hostile / corrupt stored values are dropped, never rethrown. */
    out.poisoned = [];
    for (const initial of ['{"a":1}', 'not json', '[1,2,3]']) {
        const storage = makeStorage({initial: initial});
        const doc = makeDocument();
        const m = machineFor(doc, storage, function () {
            return Promise.resolve({ok: true});
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.poisoned.push({
            initial: initial,
            state: m.getState(),
            audit: m.auditRecords().map(summary),
            storedIsArray: Array.isArray(JSON.parse(storage.data[KEY] || 'null'))
        });
    }

    /* S7: re-entrancy - one click cannot produce two transitions. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        let release = null;
        const m = machineFor(doc, storage, function () {
            return new Promise(function (resolve) {
                release = resolve;
            });
        });
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        const during = {state: m.getState(), disabled: doc.nodes['contact-submit'].disabled};
        const secondReturn = m.submit();
        const afterSecond = {state: m.getState(), audit: m.auditRecords().length};
        release({ok: true});
        await settle();
        out.reentrancy = {
            during: during,
            secondReturn: secondReturn,
            afterSecond: afterSecond,
            state: m.getState(),
            audit: m.auditRecords().map(summary)
        };
    }

    /* S8: every event sequence up to length 3 keeps the state inside CONTACT_STATES
     * and appends exactly one audit record per accepted transition. */
    {
        const states = loadContext(makeDocument(), makeStorage()).ContactFormStateMachine.CONTACT_STATES;
        const violations = [];
        let sequences = 0;
        function walk(prefix) {
            sequences += 1;
            const storage = makeStorage();
            const doc = makeDocument();
            const m = machineFor(doc, storage, function () {
                return Promise.resolve({ok: true});
            });
            fill(doc, GOOD[0], GOOD[1], GOOD[2]);
            let accepted = 0;
            prefix.forEach(function (name) {
                const before = m.getState();
                const moved = m.go(name);
                if (moved) {
                    accepted += 1;
                } else if (m.getState() !== before) {
                    violations.push({sequence: prefix, reason: 'state moved on rejected event'});
                }
                if (states.indexOf(m.getState()) === -1) {
                    violations.push({sequence: prefix, reason: 'undefined state ' + m.getState()});
                }
            });
            const audited = m.auditRecords().length;
            if (audited !== Math.min(accepted, 50)) {
                violations.push({sequence: prefix, reason: 'audit ' + audited + ' != accepted ' + accepted});
            }
            if (prefix.length < 3) {
                EVENTS.forEach(function (name) {
                    walk(prefix.concat([name]));
                });
            }
        }
        walk([]);
        out.sequences = {count: sequences, violations: violations};
    }

    /* S9: the shipped bootstrap wires the single submit listener itself. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const sandbox = loadContext(doc, storage);
        const listeners = doc.nodes['contact-form'].listeners;
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        let prevented = false;
        if (listeners.length > 0) {
            listeners[0].fn({preventDefault: function () {
                prevented = true;
            }});
        }
        await settle();
        out.bootstrap = {
            listeners: listeners.map(function (item) {
                return item.type;
            }),
            prevented: prevented,
            hasMachine: !!sandbox.contactFormMachine,
            state: sandbox.contactFormMachine ? sandbox.contactFormMachine.getState() : null,
            audit: sandbox.contactFormMachine ? sandbox.contactFormMachine.auditRecords().map(summary) : [],
            stored: JSON.parse(storage.data[KEY] || '[]').map(summary)
        };
    }

    /* S10: the shipped default transport (createContactTransport with a fake fetch)
     * posts the exact envelope to the submissions endpoint and completes on 202. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const requests = [];
        const delays = [];
        const sandbox = loadContext(doc, storage);
        const factory = sandbox.ContactFormStateMachine;
        const transport = factory.createContactTransport({
            fetch: function (path, init) {
                requests.push({path: path, init: init});
                return Promise.resolve({
                    ok: true,
                    status: 202,
                    headers: {get: function () { return null; }},
                    json: function () {
                        return Promise.resolve({
                            success: true,
                            data: {id: 's10', status: 'DISPATCHING', deduplicated: false},
                            timestamp: '2026-01-01T00:00:00.000Z'
                        });
                    }
                });
            },
            sleep: function (ms) {
                delays.push(ms);
                return Promise.resolve();
            }
        });
        const m = factory.createContactFormMachine({document: doc, storage: storage, transmit: transport, now: clock()});
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        const req = requests[0];
        const body = JSON.parse(req.init.body);
        out.wireSuccess = {
            attempts: requests.length,
            pathOk: req.path === factory.CT_SUBMISSIONS_PATH && req.path === '/api/v1/utils/contact/submissions',
            method: req.init.method,
            contentType: req.init.headers['Content-Type'],
            accept: req.init.headers['Accept'],
            body: {
                name: body.name,
                email: body.email,
                message: body.message,
                keyLen: body.idempotencyKey.length
            },
            keyWithinLimit: body.idempotencyKey.length > 0 && body.idempotencyKey.length <= 128,
            state: m.getState(),
            disabled: doc.nodes['contact-submit'].disabled,
            successHidden: doc.nodes['contact-success'].hidden,
            fields: values(doc),
            statusBusy: doc.nodes['contact-status'].getAttribute('aria-busy'),
            audit: m.auditRecords().map(summary),
            delays: delays
        };
    }

    /* S11: one submission keeps one idempotency key across its transient retries,
     * keeps it after the attempts exhaust, reuses it on resubmission of an unchanged
     * payload and rotates it once the payload changes. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const keys = [];
        const sandbox = loadContext(doc, storage);
        const factory = sandbox.ContactFormStateMachine;
        const transport = factory.createContactTransport({
            fetch: function (path, init) {
                keys.push(JSON.parse(init.body).idempotencyKey);
                return Promise.resolve({ok: false, status: 503, headers: {get: function () { return null; }}});
            },
            sleep: function () {
                return Promise.resolve();
            }
        });
        const m = factory.createContactFormMachine({document: doc, storage: storage, transmit: transport, now: clock()});
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        const storedAfterExhaust = storage.data[IKEY] ? JSON.parse(storage.data[IKEY]) : null;
        m.onSubmit(event());
        await settle();
        doc.nodes['message'].value = 'Edited before the resubmission';
        m.onSubmit(event());
        await settle();
        out.keyLifecycle = {
            totalKeys: keys.length,
            stableAcrossRetries: keys[0] === keys[1] && keys[1] === keys[2],
            keptAfterExhaust: storedAfterExhaust !== null && storedAfterExhaust.key === keys[0],
            reusedOnResubmit: keys[3] === keys[0],
            rotatedOnPayloadChange: keys[6] !== keys[3],
            keyLengthsOk: keys.every(function (k) {
                return k && k.length > 0 && k.length <= 128;
            })
        };
    }

    /* S12: a terminal 409 fails fast - no retry, rollback restores the typed values
     * and the idempotency key is rotated for the next submission. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const keys = [];
        const sandbox = loadContext(doc, storage);
        const factory = sandbox.ContactFormStateMachine;
        const transport = factory.createContactTransport({
            fetch: function (path, init) {
                keys.push(JSON.parse(init.body).idempotencyKey);
                return Promise.resolve({ok: false, status: 409, headers: {get: function () { return null; }}});
            },
            sleep: function () {
                return Promise.resolve();
            }
        });
        const m = factory.createContactFormMachine({document: doc, storage: storage, transmit: transport, now: clock()});
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        const stored = storage.data[IKEY] ? JSON.parse(storage.data[IKEY]) : null;
        out.http409 = {
            attempts: keys.length,
            state: m.getState(),
            fields: values(doc),
            disabled: doc.nodes['contact-submit'].disabled,
            summary: doc.nodes['error-summary'].textContent,
            storedKeyRotated: stored !== null && stored.key !== keys[0],
            audit: m.auditRecords().map(summary)
        };
    }

    /* S13: a 429 with a bounded numeric Retry-After is honoured before the next
     * attempt, then the retry succeeds. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const calls = [];
        const delays = [];
        const sandbox = loadContext(doc, storage);
        const factory = sandbox.ContactFormStateMachine;
        const transport = factory.createContactTransport({
            fetch: function (path, init) {
                calls.push(init.body);
                if (calls.length === 1) {
                    return Promise.resolve({
                        ok: false,
                        status: 429,
                        headers: {get: function (name) { return name === 'Retry-After' ? '2' : null; }}
                    });
                }
                return Promise.resolve({
                    ok: true,
                    status: 202,
                    headers: {get: function () { return null; }},
                    json: function () {
                        return Promise.resolve({
                            success: true,
                            data: {id: 's13', status: 'DISPATCHING', deduplicated: false},
                            timestamp: '2026-01-01T00:00:00.000Z'
                        });
                    }
                });
            },
            sleep: function (ms) {
                delays.push(ms);
                return Promise.resolve();
            }
        });
        const m = factory.createContactFormMachine({document: doc, storage: storage, transmit: transport, now: clock()});
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.http429RetryAfter = {
            attempts: calls.length,
            delays: delays,
            state: m.getState(),
            successHidden: doc.nodes['contact-success'].hidden
        };
    }

    /* S14: repeated network rejections stop after three attempts with capped
     * exponential delays, roll back to the pending snapshot and release aria-busy. */
    {
        const doc = makeDocument();
        const storage = makeStorage();
        const calls = [];
        const delays = [];
        const sandbox = loadContext(doc, storage);
        const factory = sandbox.ContactFormStateMachine;
        const transport = factory.createContactTransport({
            fetch: function (path, init) {
                calls.push(init.body);
                return Promise.reject(new Error('network'));
            },
            sleep: function (ms) {
                delays.push(ms);
                return Promise.resolve();
            }
        });
        const m = factory.createContactFormMachine({document: doc, storage: storage, transmit: transport, now: clock()});
        fill(doc, GOOD[0], GOOD[1], GOOD[2]);
        m.onSubmit(event());
        await settle();
        out.networkExhausted = {
            attempts: calls.length,
            delays: delays,
            state: m.getState(),
            fields: values(doc),
            disabled: doc.nodes['contact-submit'].disabled,
            summary: doc.nodes['error-summary'].textContent,
            statusBusy: doc.nodes['contact-status'].getAttribute('aria-busy'),
            formBusy: doc.nodes['contact-form'].getAttribute('aria-busy'),
            keyStillKept: storage.data[IKEY] !== undefined,
            audit: m.auditRecords().map(summary)
        };
    }

    process.stdout.write(JSON.stringify(out));
}

main().catch(function (error) {
    process.stderr.write(String(error && error.stack ? error.stack : error));
    process.exit(2);
});
""";
}
