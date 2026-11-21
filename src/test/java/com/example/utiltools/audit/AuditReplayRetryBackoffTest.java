package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounded-retry contract of a lapsed replay claim (REQ-01..REQ-05): a lease expiry rolls the claim
 * back under the journal lock, increments the attempt counter, publishes a capped exponential
 * backoff deadline on {@code ReplayRecord.nextRetryAt} and — once
 * {@code utiltools.audit.replay-lease-quarantine-threshold} is reached — quarantines the entry for
 * good. Everything is journalled as a deterministic tombstone, so replaying the same clock sequence
 * on a fresh journal yields the same schedule and the same quarantine decision.
 */
class AuditReplayRetryBackoffTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 30L;
    private static final int MAX_ENTRIES = 50;
    private static final int MAX_REPLAY_BYTES = 65536;
    private static final long STALE_PENDING_SECONDS = 300L;

    private static HashRequest request() {
        return new HashRequest("SHA-256", "Hello World", null);
    }

    private static String succeeded(AuditJournalService journal, String requestId) {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, request(), requestId);
        journal.succeed(entry.id(), new HashResponse("SHA-256", "abc123", false));
        return entry.id();
    }

    @Test
    @DisplayName("lapsed replay claim is rolled back with a fresh attempt and a published retry deadline")
    void admittedRetryIncrementsAttemptAndStampsNextRetryAt() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_PENDING_SECONDS, LEASE_SECONDS, 3, 1000L, 60000L, clock);
        String id = succeeded(journal, "retry-admit01");
        AuditEntry before = journal.find(id).orElseThrow();
        String firstOwner = journal.claimReplay(id).ownerToken();

        clock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(journal.reap()).isEqualTo(1);

        AuditEntry after = journal.find(id).orElseThrow();
        // REQ-01: the entry's original state, result and completedAt are never rewritten.
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.result()).isEqualTo(before.result());
        assertThat(after.completedAt()).isEqualTo(before.completedAt());
        // REQ-01/REQ-02: claim rolled back, attempt counter incremented (readable as one
        // expiration in the quarantine snapshot), lease cleared, backoff deadline published.
        ReplayRecord rolledBack = after.replay();
        assertThat(rolledBack.state()).isEqualTo(ReplayState.FAILED);
        assertThat(rolledBack.leaseExpiresAt()).isNull();
        assertThat(rolledBack.detail()).contains("retry 1 of 3");
        assertThat(Duration.between(rolledBack.replayedAt(), rolledBack.nextRetryAt()))
                .isEqualTo(Duration.ofMillis(1000L));
        assertThat(journal.quarantineStatus().totalExpirationCount()).isEqualTo(1);
        assertThat(journal.reapLog(MAX_ENTRIES)).hasSize(1);
        assertThat(journal.reapLog(MAX_ENTRIES).get(0).reason())
                .isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);

        // The admission gate refuses the claim until the backoff seat has passed.
        assertThatThrownBy(() -> journal.claimReplay(id))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("replay backoff in effect")
                .hasMessageContaining("attempt 1 of 3");
        assertThat(journal.find(id).orElseThrow().replay().state()).isEqualTo(ReplayState.FAILED);

        // Once due, the retry is admitted with a brand-new owner token and a re-stamped lease.
        clock.advanceSeconds(1L);
        Instant due = Instant.now(clock);
        var claim = journal.claimReplay(id);
        assertThat(claim.ownerToken()).isNotEqualTo(firstOwner);
        assertThat(claim.leaseExpiresAt()).isEqualTo(due.plusSeconds(LEASE_SECONDS));
        assertThat(journal.find(id).orElseThrow().replay().state())
                .isEqualTo(ReplayState.IN_PROGRESS);
    }

    @Test
    @DisplayName("retry backoff doubles per attempt and saturates at the configured cap")
    void backoffDoublesAndSaturatesAtCap() {
        MutableAuditClock clock = new MutableAuditClock(START);
        // Threshold 5 leaves room for four rollbacks; base 1000ms, cap 3000ms so attempt 3 and 4
        // would be 4000ms/8000ms uncapped and must clamp to 3000ms.
        AuditJournalService journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_PENDING_SECONDS, LEASE_SECONDS, 5, 1000L, 3000L, clock);
        String id = succeeded(journal, "retry-backoff1");

        long[] expectedDelaysMs = {1000L, 2000L, 3000L, 3000L};
        for (int attempt = 1; attempt <= expectedDelaysMs.length; attempt++) {
            journal.claimReplay(id);
            clock.advanceSeconds(LEASE_SECONDS + 1L);
            assertThat(journal.reap()).isEqualTo(1);

            ReplayRecord rolledBack = journal.find(id).orElseThrow().replay();
            assertThat(rolledBack.detail()).contains("retry " + attempt + " of 5");
            assertThat(Duration.between(rolledBack.replayedAt(), rolledBack.nextRetryAt()).toMillis())
                    .isEqualTo(expectedDelaysMs[attempt - 1]);

            // The backoff gate holds until the published deadline, then admits the next attempt.
            assertThatThrownBy(() -> journal.claimReplay(id))
                    .isInstanceOf(ReplayConflictException.class);
            clock.advanceSeconds(expectedDelaysMs[attempt - 1] / 1000L);
            assertThat(journal.find(id).orElseThrow().state()).isEqualTo(JournalState.SUCCEEDED);
        }
    }

    @Test
    @DisplayName("reaching the quarantine threshold quarantines the entry with a tombstone")
    void entryIsQuarantinedOnceThresholdIsReached() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_PENDING_SECONDS, LEASE_SECONDS, 3, 1000L, 60000L, clock);
        String id = succeeded(journal, "retry-quarant1");
        AuditEntry before = journal.find(id).orElseThrow();

        // Attempts 1 and 2: bounded retries, entry stays SUCCEEDED.
        for (int attempt = 1; attempt <= 2; attempt++) {
            journal.claimReplay(id);
            clock.advanceSeconds(LEASE_SECONDS + 1L);
            assertThat(journal.reap()).isEqualTo(1);
            assertThat(journal.find(id).orElseThrow().state()).isEqualTo(JournalState.SUCCEEDED);
            clock.advanceSeconds(60L);
        }

        // Attempt 3 reaches the threshold: one final rollback plus a permanent quarantine.
        journal.claimReplay(id);
        clock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(journal.reap()).isEqualTo(1);

        AuditEntry quarantined = journal.find(id).orElseThrow();
        assertThat(quarantined.state()).isEqualTo(JournalState.FAILED);
        assertThat(quarantined.errorCode()).isEqualTo(ReapReason.REPLAY_LEASE_QUARANTINED.name());
        assertThat(quarantined.result()).isNull();
        assertThat(quarantined.completedAt()).isAfter(before.completedAt());
        assertThat(quarantined.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(quarantined.replay().nextRetryAt()).isNull();
        assertThat(quarantined.replay().leaseExpiresAt()).isNull();

        List<ReapRecord> tombstones = journal.reapLog(MAX_ENTRIES);
        assertThat(tombstones).hasSize(3);
        assertThat(tombstones.get(0).reason()).isEqualTo(ReapReason.REPLAY_LEASE_QUARANTINED);
        assertThat(tombstones.get(0).detail()).isEqualTo("replay lease expired 3 times; entry quarantined");
        assertThat(tombstones.get(1).reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
        assertThat(tombstones.get(2).reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
        assertThat(journal.quarantineStatus().quarantinedCount()).isEqualTo(1);
        assertThat(journal.quarantineStatus().totalExpirationCount()).isEqualTo(3);

        // REQ-03/REQ-04: quarantine is terminal — no later pass reclaims, no new claim is admitted.
        clock.advanceSeconds(600L);
        assertThat(journal.reap()).isZero();
        assertThat(journal.reapLog(MAX_ENTRIES)).hasSize(3);
        assertThatThrownBy(() -> journal.claimReplay(id))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("REPLAY_LEASE_QUARANTINED");
        assertThat(journal.find(id).orElseThrow())
                .usingRecursiveComparison().isEqualTo(quarantined);
    }

    @Test
    @DisplayName("only the claim owner may renew or complete; a stale token never mutates the entry")
    void staleOwnerTokenIsFencedOutOfRenewalAndCompletion() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_PENDING_SECONDS, LEASE_SECONDS, 2, 1000L, 60000L, clock);
        String id = succeeded(journal, "retry-fence01");
        String owner = journal.claimReplay(id).ownerToken();

        // Live claim: the owner renews, a stale token does not, and neither path mutates state.
        assertThat(journal.renewReplayLease(id, "not-the-owner")).isEmpty();
        assertThat(journal.renewReplayLease(id, owner)).isPresent();
        assertThat(journal.completeReplay(id, "not-the-owner", ReplayState.VERIFIED, "ok")).isFalse();
        assertThat(journal.find(id).orElseThrow().replay().state()).isEqualTo(ReplayState.IN_PROGRESS);

        // Lapsed claim: renewal and completion are refused for the former owner.
        clock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(journal.renewReplayLease(id, owner)).isEmpty();
        assertThat(journal.completeReplay(id, owner, ReplayState.VERIFIED, "late")).isFalse();
        AuditEntry rolledBack = journal.find(id).orElseThrow();
        assertThat(rolledBack.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(rolledBack.replay().state()).isEqualTo(ReplayState.IN_PROGRESS);

        // The reaper — not the owner — performs the rollback, and the old token stays fenced out.
        assertThat(journal.reap()).isEqualTo(1);
        AuditEntry afterRollback = journal.find(id).orElseThrow();
        assertThat(journal.renewReplayLease(id, owner)).isEmpty();
        assertThat(journal.completeReplay(id, owner, ReplayState.VERIFIED, "late")).isFalse();
        assertThat(journal.find(id).orElseThrow())
                .usingRecursiveComparison().isEqualTo(afterRollback);
        assertThat(afterRollback.state()).isEqualTo(JournalState.SUCCEEDED);

        // Non-quarantined entries are never transitioned by a renewal or completion attempt.
        assertThat(journal.reapLog(MAX_ENTRIES)).hasSize(1);
        assertThat(journal.reapLog(MAX_ENTRIES).get(0).reason())
                .isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
    }

    @Test
    @DisplayName("identical clock sequences journal identical retry schedules and quarantine decisions")
    void replayingTheSameSequenceJournalsTheSameDecisions() {
        List<String> first = runDeterministicSequence();
        List<String> second = runDeterministicSequence();
        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(3);
        // reapLog is newest-first: the quarantine decision precedes its two expiry tombstones.
        assertThat(first.get(0)).isEqualTo(ReapReason.REPLAY_LEASE_QUARANTINED.name()
                + "|replay lease expired 3 times; entry quarantined|"
                + START.plusSeconds(2L * (LEASE_SECONDS + 1L + 60L) + LEASE_SECONDS + 1L));
        assertThat(first.get(1)).startsWith(ReapReason.REPLAY_LEASE_EXPIRED.name());
        assertThat(first.get(2)).startsWith(ReapReason.REPLAY_LEASE_EXPIRED.name());
    }

    /** Two bounded retries then a quarantine, reduced to the deterministic tombstone trail. */
    private static List<String> runDeterministicSequence() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = new AuditJournalService(new ObjectMapper(), MAX_ENTRIES,
                MAX_REPLAY_BYTES, STALE_PENDING_SECONDS, LEASE_SECONDS, 3, 1000L, 60000L, clock);
        String id = succeeded(journal, "retry-determ01");
        for (int attempt = 1; attempt <= 3; attempt++) {
            journal.claimReplay(id);
            clock.advanceSeconds(LEASE_SECONDS + 1L);
            journal.reap();
            if (attempt < 3) {
                clock.advanceSeconds(60L);
            }
        }
        return journal.reapLog(MAX_ENTRIES).stream()
                .map(tombstone -> tombstone.reason() + "|" + tombstone.detail() + "|"
                        + tombstone.reapedAt())
                .toList();
    }
}
