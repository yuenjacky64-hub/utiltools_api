package com.example.utiltools.resilience;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.json.service.JsonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The resilience endpoint keeps its existing contract and adds the per-fingerprint breakdown next
 * to the global state.
 */
@WebMvcTest(ResilienceController.class)
@Import({ResilienceConfig.class, GlobalExceptionHandler.class, JsonService.class})
class FingerprintResilienceControllerTest {

    private static final String INVALID_PROBE_BODY =
            "{\"json\": \"{\\\"a\\\": 1}\", \"jsonPath\": \"invalid$$path[\"}";
    private static final String FINGERPRINT_A = "fp-a";
    private static final String FINGERPRINT_B = "fp-b";

    private static String filter(String fingerprint) {
        return "$.data.fingerprints[?(@.fingerprint=='" + fingerprint + "')]";
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetBreaker() {
        // WebMvcTest reuses one context per class: trip the default machine once before every test.
        trip();
    }

    @Test
    @DisplayName("GET /api/v1/utils/resilience exposes data.fingerprints[] alongside the global state")
    void shouldExposePerFingerprintStateAlongsideGlobalState() throws Exception {
        for (int i = 0; i < 10 && circuitBreaker.state(FINGERPRINT_A) != CircuitState.OPEN; i++) {
            try {
                circuitBreaker.execute(FINGERPRINT_A, () -> {
                    throw new IllegalStateException("forced failure");
                });
            } catch (RuntimeException expected) {
                // expected: the delegate's own exception is rethrown (or the breaker fast-fails)
            }
        }

        mockMvc.perform(get("/api/v1/utils/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("json-query"))
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.consecutiveSuccesses").value(0))
                .andExpect(jsonPath("$.data.auditCapacity").value(1024))
                .andExpect(jsonPath("$.data.audit").isArray())
                .andExpect(jsonPath("$.data.fingerprints").isArray())
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".state").value(contains("OPEN")))
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".quarantined").value(contains(true)))
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".consecutiveFailures").value(contains(org.hamcrest.Matchers.instanceOf(Integer.class))))
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".resetAt").exists())
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".probeInFlight").value(contains(false)))
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".probeLeaseExpiresAt").value(contains(nullValue())))
                .andExpect(jsonPath(filter(FINGERPRINT_A) + ".state").value(contains("OPEN")))
                .andExpect(jsonPath(filter(FINGERPRINT_A) + ".quarantined").value(contains(true)))
                .andExpect(jsonPath("$.data.audit[0].fingerprint").value(FINGERPRINT_A))
                .andExpect(jsonPath("$.data.audit[0].trigger").value("QUARANTINE"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/resilience/probe quarantines only the fingerprint named by the header")
    void shouldQuarantineOnlyFingerprintNamedByHeader() throws Exception {
        for (int i = 0; i < 10 && circuitBreaker.state(FINGERPRINT_B) != CircuitState.OPEN; i++) {
            try {
                circuitBreaker.execute(FINGERPRINT_B, () -> {
                    throw new IllegalStateException("forced failure");
                });
            } catch (RuntimeException expected) {
                // expected: the delegate's own exception is rethrown (or the breaker fast-fails)
            }
        }

        mockMvc.perform(post("/api/v1/utils/resilience/probe")
                        .header(Fingerprints.HEADER, FINGERPRINT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_PROBE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.fingerprint").value(FINGERPRINT_B))
                .andExpect(jsonPath("$.data.probeSucceeded").value(false))
                .andExpect(jsonPath("$.data.error").exists())
                .andExpect(jsonPath("$.data.resetAt").exists());

        mockMvc.perform(get("/api/v1/utils/resilience")
                        .header(Fingerprints.HEADER, FINGERPRINT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audit[0].trigger").value("QUARANTINE"))
                .andExpect(jsonPath("$.data.audit[0].fingerprint").value(FINGERPRINT_B))
                .andExpect(jsonPath("$.data.audit[1].trigger").value("PROBE_FAILED"))
                .andExpect(jsonPath(filter(FingerprintResilienceControllerTest.FINGERPRINT_B) + ".state").value(contains("OPEN")))
                .andExpect(jsonPath(filter(FingerprintResilienceControllerTest.FINGERPRINT_B) + ".quarantined").value(contains(true)));
    }

    @Test
    @DisplayName("A probe without the header still drives the default machine and records PROBE_FAILED")
    void shouldDriveDefaultMachineWhenHeaderIsAbsent() throws Exception {
        mockMvc.perform(post("/api/v1/utils/resilience/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_PROBE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fingerprint").value(Fingerprints.DEFAULT))
                .andExpect(jsonPath("$.data.probeSucceeded").value(false));

        mockMvc.perform(get("/api/v1/utils/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.audit[0].trigger").value("PROBE_FAILED"))
                .andExpect(jsonPath("$.data.audit[0].fingerprint").value(Fingerprints.DEFAULT))
                .andExpect(jsonPath(filter(Fingerprints.DEFAULT) + ".state").value(contains("OPEN")));
    }

    private void trip() {
        for (int i = 0; i < 10 && circuitBreaker.state() != CircuitState.OPEN; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new IllegalStateException("forced failure");
                });
            } catch (RuntimeException expected) {
                // expected: the delegate's own exception is rethrown (or the breaker fast-fails)
            }
        }
    }
}
