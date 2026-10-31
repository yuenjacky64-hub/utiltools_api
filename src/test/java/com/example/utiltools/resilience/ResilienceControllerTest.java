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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResilienceController.class)
@Import({ResilienceConfig.class, GlobalExceptionHandler.class, JsonService.class})
class ResilienceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        tripBreaker();
    }

    @Test
    @DisplayName("GET /api/v1/utils/resilience returns the live state with the audit trail")
    void shouldReturnCircuitStateWithAuditTrail() throws Exception {
        mockMvc.perform(get("/api/v1/utils/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("json-query"))
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.consecutiveFailures").isNumber())
                .andExpect(jsonPath("$.data.consecutiveSuccesses").value(0))
                .andExpect(jsonPath("$.data.resetAt").exists())
                .andExpect(jsonPath("$.data.auditCapacity").value(1024))
                .andExpect(jsonPath("$.data.audit").isArray())
                .andExpect(jsonPath("$.data.audit[0].trigger").exists())
                .andExpect(jsonPath("$.data.audit[0].breaker").value("json-query"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/resilience/probe drives the failed transition back to OPEN")
    void shouldDriveProbeAndReturnNewState() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\": 1}",
                    "jsonPath": "invalid$$path["
                }
                """;

        mockMvc.perform(post("/api/v1/utils/resilience/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.probeSucceeded").value(false))
                .andExpect(jsonPath("$.data.error").exists())
                .andExpect(jsonPath("$.data.resetAt").exists());

        mockMvc.perform(get("/api/v1/utils/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.audit[0].trigger").value("PROBE_FAILED"));
    }

    private void tripBreaker() {
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
