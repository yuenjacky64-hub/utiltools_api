package com.example.utiltools.audit;

import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.example.utiltools.text.service.TextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contracts of {@code GET /api/v1/utils/audit/quarantine-status}: it reports the configured
 * replay-lease quarantine threshold plus the journal's {@code REPLAY_LEASE_QUARANTINED} tombstone
 * count and its aggregate replay-lease expiration count, and it is a pure read — polling it never
 * rolls back a claim, fails an entry, appends a tombstone or runs a reap pass.
 */
@WebMvcTest(AuditController.class)
@Import({AuditJournalService.class, AuditedOperations.class, AuditReplayService.class,
        CryptoService.class, DateTimeService.class, JsonService.class, TextService.class,
        ResilienceConfig.class, GlobalExceptionHandler.class,
        AuditQuarantineStatusTest.MutableClockConfig.class})
@TestPropertySource(properties = {
        "utiltools.audit.max-entries=50",
        "utiltools.audit.max-replay-bytes=65536",
        "utiltools.audit.stale-pending-seconds=300",
        "utiltools.audit.replay-lease-seconds=30",
        // Deliberately not the property file's 3: the handler must report the injected value.
        "utiltools.audit.replay-lease-quarantine-threshold=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuditQuarantineStatusTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final MutableAuditClock CLOCK = new MutableAuditClock(START);
    private static final long LEASE_SECONDS = 30L;
    private static final String PATH = "/api/v1/utils/audit/quarantine-status";

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        Clock auditClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditJournalService journal;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetClock() {
        CLOCK.advanceSeconds(0L);
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", null);
    }

    private static HashResponse hashResponse() {
        return new HashResponse("SHA-256", "abc123", false);
    }

    /** Claims the entry's replay, advances the lease past expiry and runs one reap pass. */
    private void expireOneClaim(String id) {
        ReplayClaim claim = journal.claimReplay(id);
        assertThat(claim).isNotNull();
        CLOCK.advanceSeconds(LEASE_SECONDS + 1L);
        journal.reap();
    }

    /** A SUCCEEDED entry whose replay state is {@code VERIFIED}. */
    private String verifiedEntry() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-verified01");
        journal.succeed(entry.id(), hashResponse());
        ReplayClaim claim = journal.claimReplay(entry.id());
        journal.completeReplay(entry.id(), claim.ownerToken(), ReplayState.VERIFIED, "ok");
        return entry.id();
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/quarantine-status - returns threshold, quarantine and expiration counts")
    void returnsThresholdQuarantinedAndExpirationCounts() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.data.threshold").value(2))
                .andExpect(jsonPath("$.data.quarantinedCount").value(0))
                .andExpect(jsonPath("$.data.totalExpirationCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/quarantine-status - counts QUARANTINED among mixed entry states")
    void countsMixedEntryStates() throws Exception {
        // PENDING: begun and left open.
        journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-pending0001");
        // FAILED: begun and failed.
        AuditEntry failed = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-failed0001");
        journal.fail(failed.id(), "BAD_REQUEST");
        // VERIFIED: SUCCEEDED entry whose replay state is VERIFIED — contributes 0 expirations.
        verifiedEntry();
        // QUARANTINED: threshold (2) expired claims on one entry; the second rolls back and fails it.
        AuditEntry doomed = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-doomed0001");
        journal.succeed(doomed.id(), hashResponse());
        expireOneClaim(doomed.id());
        expireOneClaim(doomed.id());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threshold").value(2))
                .andExpect(jsonPath("$.data.quarantinedCount").value(1))
                .andExpect(jsonPath("$.data.totalExpirationCount").value(2));

        // Quarantined entry is terminal FAILED with its single REPLAY_LEASE_QUARANTINED tombstone.
        // reapLog is newest-first: the quarantine tombstone precedes the earlier expiry one.
        List<ReapRecord> tombstones = journal.reapLog(50);
        assertThat(tombstones).hasSize(2);
        assertThat(tombstones.get(0).reason()).isEqualTo(ReapReason.REPLAY_LEASE_QUARANTINED);
        assertThat(tombstones.get(1).reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);

        // INV-QS-4 bounds hold: counters never exceed maxEntries / maxEntries*threshold.
        JsonNode data = data(mockMvc.perform(get(PATH)).andReturn());
        assertThat(data.get("quarantinedCount").asInt()).isBetween(0, 50);
        assertThat(data.get("totalExpirationCount").asInt()).isBetween(0, 50 * 2);
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/quarantine-status - repeated calls are side-effect free")
    void repeatedCallsReturnIdenticalCountsWithoutSideEffects() throws Exception {
        journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-pending0002");
        AuditEntry doomed = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-doomed0002");
        journal.succeed(doomed.id(), hashResponse());
        expireOneClaim(doomed.id());
        expireOneClaim(doomed.id());

        int sizeBefore = journal.size();
        int tombstonesBefore = journal.reapLog(50).size();

        JsonNode first = data(mockMvc.perform(get(PATH)).andReturn());
        JsonNode second = data(mockMvc.perform(get(PATH)).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(journal.size()).isEqualTo(sizeBefore);
        assertThat(journal.reapLog(50).size()).isEqualTo(tombstonesBefore);
        // A reap pass would have failed the open PENDING entry; it is still PENDING, so none ran.
        assertThat(journal.size()).isEqualTo(sizeBefore);
        assertThat(journal.list(50, JournalState.PENDING)).hasSize(1);
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/quarantine-status - reports the injected threshold")
    void injectsConfiguredQuarantineThreshold() throws Exception {
        assertThat(journal.replayLeaseQuarantineThreshold()).isEqualTo(2);
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threshold").value(2));
    }

    private JsonNode data(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }
}
