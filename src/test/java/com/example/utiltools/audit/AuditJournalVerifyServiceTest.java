package com.example.utiltools.audit;

import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.dto.JournalVerifyResult;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * Focused tests for the bounded journal integrity sweep
 * ({@link AuditReplayService#verify(int)}): per-entry failure isolation, the shared attempt helper
 * it has in common with {@link AuditReplayService#replay(String)} and the journal invariants the
 * sweep must never break. Plain unit tests — no Spring context, no HTTP.
 */
class AuditJournalVerifyServiceTest {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_REPLAY_BYTES = 65536;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CryptoService cryptoService = new CryptoService();

    private AuditJournalService journal;
    private AuditedOperations auditedOperations;
    private AuditReplayService replayService;

    @BeforeEach
    void setUp() {
        journal = newJournal(MAX_ENTRIES, MAX_REPLAY_BYTES);
        auditedOperations = spy(newAuditedOperations(journal));
        replayService = new AuditReplayService(journal, auditedOperations, objectMapper);
    }

    @Test
    @DisplayName("verify - VERIFIED, MISMATCH and FAILED in one sweep, later candidates still processed")
    void mixedOutcomeSweepProcessesEveryCandidate() {
        String intact = succeededHashEntry("hello world", "req-verify-01");
        String tampered = tamperedHashEntry("hello world", "req-verify-02");
        String stats = succeededTextStatsEntry("req-verify-03");
        doThrow(new IllegalStateException("stats backend exploded"))
                .when(auditedOperations).replayInvoke(eq(OperationType.TEXT_STATS), any());

        JournalVerifyResponse response = replayService.verify(10);

        assertThat(response.scanned()).isEqualTo(3);
        assertThat(response.results()).hasSize(3);
        assertThat(row(response, intact).outcome()).isEqualTo(ReplayState.VERIFIED);
        assertThat(row(response, intact).skipReason()).isNull();
        assertThat(row(response, tampered).outcome()).isEqualTo(ReplayState.MISMATCH);
        assertThat(row(response, tampered).detail()).contains("first mismatch at");
        assertThat(row(response, stats).outcome()).isEqualTo(ReplayState.FAILED);
        assertThat(row(response, stats).detail()).contains("stats backend exploded");

        assertThat(response.verified()).isEqualTo(1);
        assertThat(response.mismatch()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.skipped()).isZero();
        assertThat(response.verified() + response.mismatch() + response.failed() + response.skipped())
                .isEqualTo(response.scanned());

        // The throwing invocation is still recorded as a FAILED replay trace on an intact entry.
        AuditEntry afterStats = journal.find(stats).orElseThrow();
        assertThat(afterStats.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(afterStats.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(afterStats.result()).isNotNull();
    }

    @Test
    @DisplayName("verify - originals keep state, result, completedAt, request and replayable flag")
    void sweepNeverRewritesOriginalEntryFields() {
        String intact = succeededHashEntry("hello world", "req-verify-11");
        String tampered = tamperedHashEntry("hello world", "req-verify-12");
        String stats = succeededTextStatsEntry("req-verify-13");
        doThrow(new IllegalStateException("stats backend exploded"))
                .when(auditedOperations).replayInvoke(eq(OperationType.TEXT_STATS), any());

        Map<String, AuditEntry> before = new LinkedHashMap<>();
        for (String id : List.of(intact, tampered, stats)) {
            before.put(id, journal.find(id).orElseThrow());
        }
        int sizeBefore = journal.list(MAX_ENTRIES, JournalState.SUCCEEDED).size();
        Instant firstCompletedAt = before.get(intact).completedAt();

        replayService.verify(10);

        for (Map.Entry<String, AuditEntry> snapshot : before.entrySet()) {
            AuditEntry after = journal.find(snapshot.getKey()).orElseThrow();
            AuditEntry original = snapshot.getValue();
            assertThat(after.state()).isEqualTo(original.state());
            assertThat(after.result()).isEqualTo(original.result());
            assertThat(after.completedAt()).isEqualTo(original.completedAt());
            assertThat(after.operation()).isEqualTo(original.operation());
            assertThat(after.request()).isEqualTo(original.request());
            assertThat(after.replayable()).isEqualTo(original.replayable());
            assertThat(after.requestId()).isEqualTo(original.requestId());
        }
        assertThat(journal.find(intact).orElseThrow().completedAt()).isEqualTo(firstCompletedAt);
        assertThat(journal.list(MAX_ENTRIES, JournalState.SUCCEEDED)).hasSize(sizeBefore);
    }

    @Test
    @DisplayName("verify - a live claim is SKIPPED/LIVE_CLAIM and the owner can still complete it")
    void liveClaimIsSkippedAndOwnerStillCompletes() {
        String id = succeededHashEntry("live claim", "req-verify-21");
        ReplayClaim claim = journal.claimReplay(id);
        Instant leaseExpiresAt = claim.leaseExpiresAt();

        JournalVerifyResponse response = replayService.verify(10);

        JournalVerifyResult row = row(response, id);
        assertThat(row.skipReason()).isEqualTo("LIVE_CLAIM");
        assertThat(row.outcome()).isNull();
        assertThat(row.detail()).isNotNull().hasSizeLessThanOrEqualTo(200);
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.verified() + response.mismatch() + response.failed()).isZero();

        AuditEntry claimed = journal.find(id).orElseThrow();
        assertThat(claimed.replay().state()).isEqualTo(ReplayState.IN_PROGRESS);
        assertThat(claimed.replay().leaseExpiresAt()).isEqualTo(leaseExpiresAt);
        assertThat(claimed.replay().leaseExpiresAt().toEpochMilli())
                .isEqualTo(leaseExpiresAt.toEpochMilli());

        assertThat(journal.completeReplay(id, claim.ownerToken(), ReplayState.VERIFIED, "results match"))
                .isTrue();
        assertThat(journal.find(id).orElseThrow().replay().state()).isEqualTo(ReplayState.VERIFIED);
    }

    @Test
    @DisplayName("verify - ineligible entries are skipped with explicit reasons")
    void ineligibleEntriesAreSkippedWithReasons() {
        AuditJournalService bounded = newJournal(MAX_ENTRIES, 64);
        AuditReplayService boundedReplay = new AuditReplayService(bounded,
                newAuditedOperations(bounded), objectMapper);
        String oversize = bounded.begin(OperationType.CRYPTO_HASH,
                hashRequest("x".repeat(500)), "req-verify-31").id();
        bounded.succeed(oversize, Map.of("algorithm", "SHA-256", "digest", "big", "isHmac", false));
        String nonDeterministic = bounded.begin(OperationType.CRYPTO_UUID,
                Map.of("version", "V4"), "req-verify-32").id();
        bounded.succeed(nonDeterministic, Map.of("uuid", "00000000-0000-0000-0000-000000000000"));
        String failed = bounded.begin(OperationType.TEXT_STATS,
                Map.of("text", "boom"), "req-verify-33").id();
        bounded.fail(failed, "BAD_REQUEST");

        JournalVerifyResponse response = boundedReplay.verify(10);

        assertThat(row(response, oversize).skipReason()).isEqualTo("NOT_REPLAYABLE");
        assertThat(row(response, nonDeterministic).skipReason()).isEqualTo("NON_DETERMINISTIC");
        assertThat(row(response, oversize).outcome()).isNull();
        assertThat(row(response, nonDeterministic).outcome()).isNull();
        assertThat(response.skipped()).isEqualTo(2);
        // Only SUCCEEDED entries are candidates, so a FAILED entry never reaches the sweep; the
        // NOT_SUCCEEDED classification stays a defensive fallback for a concurrent transition.
        assertThat(response.results()).extracting(JournalVerifyResult::entryId).doesNotContain(failed);
        assertThat(response.results()).extracting(JournalVerifyResult::skipReason)
                .doesNotContain("NOT_SUCCEEDED");
        assertThat(bounded.find(failed).orElseThrow().state()).isEqualTo(JournalState.FAILED);
    }

    @Test
    @DisplayName("verify - an over-max limit is clamped to the configured journal bound")
    void overMaxLimitIsClampedToConfiguredBound() {
        AuditJournalService bounded = newJournal(2, MAX_REPLAY_BYTES);
        AuditReplayService boundedReplay = new AuditReplayService(bounded,
                newAuditedOperations(bounded), objectMapper);
        String first = succeedHash(bounded, "one", "req-verify-41");
        String second = succeedHash(bounded, "two", "req-verify-42");
        String third = succeedHash(bounded, "three", "req-verify-43");

        assertThat(bounded.maxEntries()).isEqualTo(2);

        JournalVerifyResponse response = boundedReplay.verify(999_999);

        assertThat(response.limit()).isEqualTo(2);
        assertThat(response.scanned()).isEqualTo(2);
        assertThat(response.results()).hasSize(2);
        assertThat(response.verified()).isEqualTo(2);
        assertThat(response.results()).extracting(JournalVerifyResult::entryId)
                .containsExactlyInAnyOrder(second, third);
        assertThat(bounded.find(first)).isEmpty();
    }

    @Test
    @DisplayName("verify - single replay and sweep share one attempt outcome for the same entry")
    void singleReplayAndSweepShareOneAttemptOutcome() {
        String tampered = tamperedHashEntry("share one helper", "req-verify-51");
        String claimed = succeededHashEntry("live claim", "req-verify-52");
        journal.claimReplay(claimed);

        // replay(id) keeps its existing responses: 200 on a completed attempt, 404 on unknown ids,
        // 409 while another caller holds a live claim.
        AuditEntry replayed = replayService.replay(tampered);
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.MISMATCH);
        assertThatThrownBy(() -> replayService.replay("missing-entry-id"))
                .isInstanceOf(AuditEntryNotFoundException.class);
        assertThatThrownBy(() -> replayService.replay(claimed))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("active replay claim");

        JournalVerifyResponse response = replayService.verify(10);

        assertThat(row(response, tampered).outcome()).isEqualTo(ReplayState.MISMATCH);
        assertThat(row(response, tampered).skipReason()).isNull();
        assertThat(row(response, claimed).skipReason()).isEqualTo("LIVE_CLAIM");
        assertThat(response.mismatch()).isEqualTo(1);
        assertThat(response.skipped()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private AuditJournalService newJournal(int maxEntries, int maxReplayBytes) {
        return new AuditJournalService(objectMapper, maxEntries, maxReplayBytes, 300L);
    }

    private AuditedOperations newAuditedOperations(AuditJournalService target) {
        return new AuditedOperations(new DateTimeService(), cryptoService, new JsonService(objectMapper),
                new TextService(), target);
    }

    private HashRequest hashRequest(String content) {
        return new HashRequest("SHA-256", content, null);
    }

    private HashResponse hashResponse(String content) {
        return cryptoService.hash(hashRequest(content));
    }

    /** SUCCEEDED CRYPTO_HASH entry whose recorded result is the real one, so a replay matches. */
    private String succeededHashEntry(String content, String requestId) {
        return succeedHash(journal, content, requestId);
    }

    private String succeedHash(AuditJournalService target, String content, String requestId) {
        String id = target.begin(OperationType.CRYPTO_HASH, hashRequest(content), requestId).id();
        target.succeed(id, hashResponse(content));
        return id;
    }

    /** SUCCEEDED CRYPTO_HASH entry whose recorded result was altered, so a replay mismatches. */
    private String tamperedHashEntry(String content, String requestId) {
        String id = journal.begin(OperationType.CRYPTO_HASH, hashRequest(content), requestId).id();
        journal.succeed(id, Map.of("algorithm", "SHA-256", "digest", "tampered", "isHmac", false));
        return id;
    }

    private String succeededTextStatsEntry(String requestId) {
        String id = journal.begin(OperationType.TEXT_STATS, Map.of("text", "hello world"), requestId).id();
        journal.succeed(id, Map.of("words", 2));
        return id;
    }

    private JournalVerifyResult row(JournalVerifyResponse response, String entryId) {
        List<JournalVerifyResult> matches = response.results().stream()
                .filter(result -> entryId.equals(result.entryId()))
                .toList();
        assertThat(matches).as("exactly one row for entry %s", entryId).hasSize(1);
        return matches.get(0);
    }
}
