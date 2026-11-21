package com.example.utiltools.scheduling;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.resilience.CircuitState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOD-05, DOD-06: the read-only {@code /maintenance/passes} route.
 *
 * <p>The controller runs on a standalone {@link MockMvc} so the assertions are exactly the JSON
 * contract: eight pass rows with the documented fields, the guard's audit bounds, and a bounded
 * {@code recentTransitions} list. A second scenario asserts the route is read-only: two GETs leave
 * every counter, state and {@code nextProbeAt} untouched, and the existing
 * {@code /maintenance/scheduler} pool snapshot still answers.</p>
 */
@DisplayName("Maintenance pass status route (MaintenancePassStatusTest)")
final class MaintenancePassStatusTest {

    private MaintenanceTaskScheduler taskScheduler;
    private MaintenancePassGuard guard;
    private ObjectMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MaintenanceSchedulingProperties properties = MaintenanceSchedulingProperties.defaults();
        taskScheduler = new MaintenanceTaskScheduler(properties);
        taskScheduler.initialize();
        guard = new MaintenancePassGuard(properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC));
        mockMvc = buildMockMvc(new SchedulerStatusController(taskScheduler, properties, guard));
    }

    @AfterEach
    void tearDown() {
        taskScheduler.shutdown();
    }

    @Test
    @DisplayName("GET /api/v1/utils/maintenance/passes returns one row per pass with counters and audit bounds")
    void shouldReturnOneRowPerMaintenancePass() throws Exception {
        mockMvc.perform(get("/api/v1/utils/maintenance/passes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.passes.length()").value(MaintenancePass.values().length))
                .andExpect(jsonPath("$.data.passes[0].pass").value(MaintenancePass.AUDIT_JOURNAL_REAP.name()))
                .andExpect(jsonPath("$.data.passes[0].state").value(CircuitState.CLOSED.name()))
                .andExpect(jsonPath("$.data.passes[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.data.passes[0].lastOutcome").value("NONE"))
                .andExpect(jsonPath("$.data.passes[0].lastFailureAt").doesNotExist())
                .andExpect(jsonPath("$.data.passes[0].lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$.data.passes[0].nextProbeAt").doesNotExist())
                .andExpect(jsonPath("$.data.passes[0].succeeded").value(0))
                .andExpect(jsonPath("$.data.passes[0].failed").value(0))
                .andExpect(jsonPath("$.data.passes[0].skipped").value(0))
                .andExpect(jsonPath("$.data.passes[7].pass")
                        .value(MaintenancePass.CONTACT_RETRY_SCHEDULED.name()))
                .andExpect(jsonPath("$.data.auditSize").value(0))
                .andExpect(jsonPath("$.data.auditCapacity")
                        .value(MaintenancePassGuard.AUDIT_CAPACITY))
                .andExpect(jsonPath("$.data.recentTransitions").isArray())
                .andExpect(jsonPath("$.data.recentTransitions.length()").value(0));
    }

    @Test
    @DisplayName("a quarantined pass is reported with its probe deadline and QUARANTINE transition")
    void shouldReportQuarantineAndRecentTransition() throws Exception {
        MaintenancePass pass = MaintenancePass.JOBS_EVICT_EXPIRED_JOBS;
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock advanceable = new MutableClock(start);

        MaintenanceSchedulingProperties properties = MaintenanceSchedulingProperties.defaults();
        MaintenancePassGuard clockedGuard = new MaintenancePassGuard(properties, advanceable);
        for (int tick = 0; tick < properties.passFailureThreshold(); tick++) {
            try {
                clockedGuard.run(pass, () -> {
                    throw new IllegalStateException("job retention pass failed");
                });
            } catch (IllegalStateException expected) {
                // the pass body's own failure is rethrown unchanged (INV-5)
            }
        }
        MockMvc clocked = buildMockMvc(
                new SchedulerStatusController(taskScheduler, properties, clockedGuard));

        int index = pass.ordinal();
        clocked.perform(get("/api/v1/utils/maintenance/passes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passes[" + index + "].state").value(CircuitState.OPEN.name()))
                .andExpect(jsonPath("$.data.passes[" + index + "].consecutiveFailures").value(3))
                .andExpect(jsonPath("$.data.passes[" + index + "].lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.data.passes[" + index + "].lastFailureAt")
                        .value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.passes[" + index + "].nextProbeAt")
                        .value("2026-01-01T00:01:00Z"))
                .andExpect(jsonPath("$.data.passes[" + index + "].failed").value(3))
                .andExpect(jsonPath("$.data.auditSize").value(2))
                .andExpect(jsonPath("$.data.recentTransitions[1].pass").value(pass.name()))
                .andExpect(jsonPath("$.data.recentTransitions[1].from").value(CircuitState.CLOSED.name()))
                .andExpect(jsonPath("$.data.recentTransitions[1].to").value(CircuitState.OPEN.name()))
                .andExpect(jsonPath("$.data.recentTransitions[0].trigger").value("QUARANTINE"))
                .andExpect(jsonPath("$.data.recentTransitions[1].at").value("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the routes are read-only and the scheduler snapshot is unchanged")
    void shouldKeepSchedulerSnapshotAndNotMutateState() throws Exception {
        mockMvc.perform(get("/api/v1/utils/maintenance/scheduler"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.poolSize").value(8))
                .andExpect(jsonPath("$.data.threadNamePrefix").value("maintenance-"))
                .andExpect(jsonPath("$.data.awaitTerminationSeconds").value(30))
                .andExpect(jsonPath("$.data.activeThreadCount").isNumber())
                .andExpect(jsonPath("$.data.completedTaskCount").isNumber())
                .andExpect(jsonPath("$.data.queueDepth").isNumber());

        MaintenancePass pass = MaintenancePass.CONTACT_REAP_SCHEDULED;
        for (int tick = 0; tick < 3; tick++) {
            try {
                guard.run(pass, () -> {
                    throw new IllegalStateException("contact reaper failed");
                });
            } catch (IllegalStateException expected) {
                // rethrown pass failure, counted by the guard
            }
        }

        MvcResult first = mockMvc.perform(get("/api/v1/utils/maintenance/passes"))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/utils/maintenance/passes"))
                .andExpect(status().isOk()).andReturn();

        // the ApiResponse envelope stamps its own timestamp, so compare the payload only
        JsonNode firstData = mapper.readTree(first.getResponse().getContentAsString()).get("data");
        JsonNode secondData = mapper.readTree(second.getResponse().getContentAsString()).get("data");
        assertThat(secondData).isEqualTo(firstData);
        assertThat(secondData.toString())
                .contains("\"state\":\"OPEN\"")
                .contains("\"skipped\":0")
                .contains("\"failed\":3");

        MaintenancePassGuard.PassSnapshot row = guard.snapshot().stream()
                .filter(snapshot -> snapshot.pass() == pass)
                .findFirst()
                .orElseThrow();
        assertThat(row.state()).isEqualTo(CircuitState.OPEN);
        assertThat(row.failed()).isEqualTo(3);
        assertThat(row.skipped()).isZero();
        assertThat(row.nextProbeAt()).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
    }

    /**
     * Standalone MVC with the application's date rendering (ISO-8601 strings, not epoch numbers),
     * so the JSON contract asserted here is the one the running app serves.
     */
    private MockMvc buildMockMvc(SchedulerStatusController controller) {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    /** Deterministic clock shared with the guard under test. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
