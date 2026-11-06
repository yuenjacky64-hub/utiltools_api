package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contracts of the background journal reaper: one scheduled pass that rolls back expired replay
 * claims, fails stale PENDING entries and reclaims non-replayable terminal entries, leaving one
 * bounded {@code REAPED} tombstone per reaped entry and never falsifying a valid entry.
 */
class AuditJournalReaperTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 30L;
    private static final long STALE_SECONDS = 300L;
    private static final int MAX_ENTRIES = 5;
    private static final int MAX_REPLAY_BYTES = 65536;

    private MutableAuditClock clock;
    private AuditJournalService journal;

    @BeforeEach
    void setUp() {
        clock = new MutableAuditClock(START);
        journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES, MAX_REPLAY_BYTES,
                STALE_SECONDS, LEASE_SECONDS, clock);
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", "super-secret-key");
    }

    private static HashResponse hashResponse() {
        return new HashResponse("SHA-256", "abc123", false);
    }

    private AuditEntry succeeded(String requestId) {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), requestId);
        return journal.succeed(pending.id(), hashResponse());
    }

    @Test
    @DisplayName("reapJournal() is the single scheduled cleanup entry point")
    void scheduledReaperIsTheSingleEntryPoint() throws Exception {
        Scheduled scheduled = AuditJournalService.class.getMethod("reapJournal").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${utiltools.audit.cleanup-interval-ms:60000}");
        assertThat(AuditJournalService.class.getMethod("reapStalePending").getAnnotation(Scheduled.class))
                .isNull();

        assertThatCode(() -> journal.reapJournal()).doesNotThrowAnyException();

        succeeded("req-00000001");
        assertThatCode(() -> journal.reapJournal()).doesNotThrowAnyException();

        AuditJournalService empty = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_SECONDS, LEASE_SECONDS, clock);
        assertThatCode(empty::reapJournal).doesNotThrowAnyException();
        assertThat(empty.reap()).isZero();
        assertThat(empty.size()).isZero();
    }

    @Test
    @DisplayName("an expired IN_PROGRESS replay claim is rolled back and reaped")
    void reapsExpiredReplayClaim() {
        AuditEntry succeeded = succeeded("req-00000001");
        ReplayClaim staleOwner = journal.claimReplay(succeeded.id());
        clock.advanceSeconds(LEASE_SECONDS);

        assertThat(journal.reap()).isEqualTo(1);

        AuditEntry after = journal.find(succeeded.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.completedAt()).isEqualTo(succeeded.completedAt());
        assertThat(after.replayable()).isTrue();
        assertThat(after.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(after.replay().leaseExpiresAt()).isNull();
        assertThat(after.replay().detail()).contains("replay claim lease expired");

        List<ReapRecord> log = journal.reapLog(10);
        assertThat(log).hasSize(1);
        ReapRecord tombstone = log.get(0);
        assertThat(tombstone.status()).isEqualTo("REAPED");
        assertThat(tombstone.entryId()).isEqualTo(succeeded.id());
        assertThat(tombstone.operation()).isEqualTo(OperationType.CRYPTO_HASH);
        assertThat(tombstone.reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
        assertThat(tombstone.detail()).contains("replay claim lease expired");
        assertThat(tombstone.reapedAt()).isEqualTo(START.plusSeconds(LEASE_SECONDS));

        assertThat(journal.completeReplay(succeeded.id(), staleOwner.ownerToken(),
                ReplayState.VERIFIED, "late owner")).isFalse();
    }

    @Test
    @DisplayName("a non-replayable terminal entry from byte overflow is reclaimed")
    void reapsOversizeNonReplayableEntry() {
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES, 20,
                STALE_SECONDS, LEASE_SECONDS, clock);
        AuditEntry pending = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        assertThat(pending.replayable()).isFalse();
        AuditEntry terminal = bounded.succeed(pending.id(), hashResponse());
        assertThat(terminal.replayable()).isFalse();

        assertThat(bounded.reap()).isEqualTo(1);

        assertThat(bounded.find(pending.id())).isEmpty();
        assertThat(bounded.size()).isZero();

        ReapRecord tombstone = bounded.reapLog(1).get(0);
        assertThat(tombstone.status()).isEqualTo("REAPED");
        assertThat(tombstone.entryId()).isEqualTo(pending.id());
        assertThat(tombstone.reason()).isEqualTo(ReapReason.OVERSIZE_PAYLOAD);
        assertThat(tombstone.detail())
                .contains("request payload exceeds utiltools.audit.max-replay-bytes");
        assertThat(tombstone.detail()).hasSizeLessThanOrEqualTo(ReapRecord.MAX_DETAIL_LENGTH);
        assertThat(tombstone.toString()).doesNotContain("Hello World").doesNotContain("super-secret-key");

        assertThat(bounded.reap()).isZero();
    }

    @Test
    @DisplayName("an oversize RESULT with an in-bounds request keeps its audit record")
    void keepsOversizeResultEntryWithInBoundsRequest() {
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES, 64,
                STALE_SECONDS, LEASE_SECONDS, clock);
        AuditEntry pending = bounded.begin(OperationType.CRYPTO_HASH,
                Map.of("input", "Hello World"), "req-00000001");
        assertThat(pending.replayable()).isTrue();

        AuditEntry terminal = bounded.succeed(pending.id(), "x".repeat(200));
        assertThat(terminal.replayable()).isFalse();
        assertThat(terminal.state()).isEqualTo(JournalState.SUCCEEDED);

        assertThat(bounded.reap()).isZero();
        assertThat(bounded.reapLog(10)).isEmpty();

        AuditEntry after = bounded.find(pending.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.completedAt()).isEqualTo(terminal.completedAt());
        assertThat(after.result()).isEqualTo(terminal.result());
        assertThat(after.result().toString()).doesNotContain("x".repeat(10));
        assertThat(after.request()).isEqualTo(pending.request());
        assertThat(after.replayable()).isFalse();
        assertThat(bounded.size()).isEqualTo(1);

        assertThat(bounded.reap()).isZero();
        assertThat(bounded.find(pending.id())).isPresent();
    }

    @Test
    @DisplayName("the stale-pending-seconds threshold decides which PENDING entries are reaped")
    void respectsStalePendingSecondsThreshold() {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        clock.advanceSeconds(STALE_SECONDS + 1);
        AuditEntry fresh = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");

        assertThat(journal.reap()).isEqualTo(1);

        AuditEntry reaped = journal.find(stale.id()).orElseThrow();
        assertThat(reaped.state()).isEqualTo(JournalState.FAILED);
        assertThat(reaped.errorCode()).isEqualTo("STALE_PENDING_REAPED");
        assertThat(reaped.completedAt()).isEqualTo(START.plusSeconds(STALE_SECONDS + 1));
        assertThat(reaped.result()).isNull();
        assertThat(reaped.request()).isEqualTo(stale.request());
        assertThat(reaped.createdAt()).isEqualTo(stale.createdAt());
        assertThat(reaped.replayable()).isEqualTo(stale.replayable());

        AuditEntry untouched = journal.find(fresh.id()).orElseThrow();
        assertThat(untouched.state()).isEqualTo(JournalState.PENDING);
        assertThat(untouched.completedAt()).isNull();
        assertThat(untouched.errorCode()).isNull();
        assertThat(journal.succeed(fresh.id(), hashResponse()).state()).isEqualTo(JournalState.SUCCEEDED);

        List<ReapRecord> log = journal.reapLog(10);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).reason()).isEqualTo(ReapReason.STALE_PENDING);
    }

    @Test
    @DisplayName("each reap leaves one bounded REAPED audit-trail entry with a reason")
    void recordsBoundedReapedAuditTrail() {
        AuditEntry claimed = succeeded("req-00000001");
        journal.claimReplay(claimed.id());
        clock.advanceSeconds(LEASE_SECONDS);
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        clock.advanceSeconds(STALE_SECONDS + 1);

        assertThat(journal.reap()).isEqualTo(2);

        List<ReapRecord> log = journal.reapLog(10);
        assertThat(log).hasSize(2);
        assertThat(log).extracting(ReapRecord::entryId)
                .containsExactlyInAnyOrder(claimed.id(), stale.id())
                .doesNotHaveDuplicates();
        for (ReapRecord record : log) {
            assertThat(record.status()).isEqualTo("REAPED");
            assertThat(record.operation()).isEqualTo(OperationType.CRYPTO_HASH);
            assertThat(record.reason()).isNotNull();
            assertThat(record.detail()).isNotBlank().hasSizeLessThanOrEqualTo(ReapRecord.MAX_DETAIL_LENGTH);
            assertThat(record.reapedAt()).isNotNull();
        }

        assertThat(journal.reap()).isZero();
        assertThat(journal.reapLog(10)).hasSize(2);
    }

    @Test
    @DisplayName("reaping an expired claim does not mutate the entry and leaves it claimable")
    void reapRollsBackClaimWithoutMutatingEntry() {
        AuditEntry succeeded = succeeded("req-00000001");
        JsonNode resultBefore = succeeded.result();
        journal.claimReplay(succeeded.id());
        clock.advanceSeconds(LEASE_SECONDS + 1);

        assertThat(journal.reap()).isEqualTo(1);

        AuditEntry after = journal.find(succeeded.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.result()).isEqualTo(resultBefore);
        assertThat(after.completedAt()).isEqualTo(succeeded.completedAt());
        assertThat(after.replayable()).isEqualTo(succeeded.replayable());

        ReplayClaim fresh = journal.claimReplay(succeeded.id());
        assertThat(fresh.ownerToken()).isNotBlank();
        assertThat(journal.completeReplay(succeeded.id(), fresh.ownerToken(),
                ReplayState.VERIFIED, "fresh owner")).isTrue();
    }

    @Test
    @DisplayName("a valid replayable SUCCEEDED entry survives repeated passes untouched")
    void leavesValidReplayableEntryIntact() {
        AuditEntry succeeded = succeeded("req-00000001");
        assertThat(succeeded.replayable()).isTrue();

        assertThat(journal.reap()).isZero();
        assertThat(journal.reap()).isZero();

        AuditEntry after = journal.find(succeeded.id()).orElseThrow();
        assertThat(after).isEqualTo(succeeded);
        assertThat(after.replay()).isNull();
        assertThat(after.replayable()).isTrue();
        assertThat(journal.reapLog(10)).isEmpty();
        assertThat(journal.size()).isEqualTo(1);
        assertThatCode(() -> journal.claimReplay(succeeded.id())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a pass keeps size() <= maxEntries and never resurrects an evicted entry")
    void keepsSizeBoundAndEvictsOldestFirst() {
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), 3, 20,
                STALE_SECONDS, LEASE_SECONDS, clock);
        AuditEntry oldest = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        AuditEntry second = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        AuditEntry third = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000003");
        AuditEntry newest = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000004");

        assertThat(bounded.size()).isEqualTo(3);
        assertThat(bounded.find(oldest.id())).isEmpty();

        bounded.succeed(second.id(), hashResponse());
        assertThat(bounded.reap()).isEqualTo(1);

        assertThat(bounded.size()).isLessThanOrEqualTo(3);
        assertThat(bounded.find(second.id())).isEmpty();
        assertThat(bounded.find(third.id())).isPresent();
        assertThat(bounded.find(newest.id())).isPresent();
        assertThat(bounded.reapLog(10)).extracting(ReapRecord::entryId).containsExactly(second.id());
    }

    @Test
    @DisplayName("the tombstone log stays bounded at maxEntries, newest first")
    void boundsTheReapTombstoneLog() {
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), 3, 20,
                STALE_SECONDS, LEASE_SECONDS, clock);
        List<String> reapedIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AuditEntry entry = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(),
                    String.format("req-0000000%d", i + 1));
            bounded.succeed(entry.id(), hashResponse());
            assertThat(bounded.reap()).isEqualTo(1);
            reapedIds.add(entry.id());
        }

        List<ReapRecord> log = bounded.reapLog(Integer.MAX_VALUE);
        assertThat(log).hasSize(3);
        assertThat(log).extracting(ReapRecord::entryId)
                .containsExactly(reapedIds.get(4), reapedIds.get(3), reapedIds.get(2));
        assertThat(bounded.reapLog(1)).hasSize(1);
        assertThat(bounded.reapLog(0)).hasSize(1);
    }

    @Test
    @DisplayName("the pre-existing stale-PENDING sweep contract keeps working")
    void keepsStalePendingSweepContract() {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");

        assertThat(journal.reapStalePending(stale.createdAt().plusNanos(1))).isEqualTo(1);
        assertThat(journal.reapStalePending(stale.createdAt().plusNanos(1))).isZero();
        AuditEntry reaped = journal.find(stale.id()).orElseThrow();
        assertThat(reaped.state()).isEqualTo(JournalState.FAILED);
        assertThat(reaped.errorCode()).isEqualTo("STALE_PENDING_REAPED");

        List<ReapRecord> log = journal.reapLog(10);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).status()).isEqualTo("REAPED");
        assertThat(log.get(0).entryId()).isEqualTo(stale.id());
        assertThat(log.get(0).reason()).isEqualTo(ReapReason.STALE_PENDING);
        assertThat(log.get(0).detail()).contains("stale-pending-seconds");
    }
}
