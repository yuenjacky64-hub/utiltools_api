package com.example.utiltools.audit;

import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
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

    private static final CryptoService CRYPTO = new CryptoService();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static HashRequest request() {
        return new HashRequest("SHA-256", "Hello World", null);
    }

    private static String succeeded(AuditJournalService journal, String requestId) {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, request(), requestId);
        journal.succeed(entry.id(), CRYPTO.hash(request()));
        return entry.id();
    }

    private AuditJournalService newJournal(MutableAuditClock clock, int threshold, long baseMs,
                                           long maxMs) {
        return new AuditJournalService(objectMapper, MAX_ENTRIES, MAX_REPLAY_BYTES,
                STALE_PENDING_SECONDS, LEASE_SECONDS, threshold, baseMs, maxMs, clock);
    }

    private AuditReplayService newReplayService(AuditJournalService journal) {
        AuditedOperations operations = new AuditedOperations(new DateTimeService(),
                new CryptoService(), new JsonService(objectMapper), new TextService(), journal);
        return new AuditReplayService(journal, operations, objectMapper);
    }

    @Test
    @DisplayName("lapsed replay claim is rolled back with a fresh attempt and a published retry deadline")
    void retryAdmissionIncrementsAttemptAndStampsNextRetryAt() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = newJournal(clock, 3, 1000L, 60000L);
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
        // REQ-05: the tombstone carries the very same per-attempt detail as the rolled-back record.
        assertThat(journal.reapLog(MAX_ENTRIES).get(0).detail()).isEqualTo(rolledBack.detail());

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
    @DisplayName("retry backoff doubles per attempt, saturates at the cap and is immediately due at base 0")
    void backoffDoublesUntilCappedAndGatesAdmission() {
        MutableAuditClock clock = new MutableAuditClock(START);
        // Threshold 5 leaves room for four rollbacks; base 1000ms, cap 3000ms so attempt 3 and 4
        // would be 4000ms/8000ms uncapped and must clamp to 3000ms.
        AuditJournalService journal = newJournal(clock, 5, 1000L, 3000L);
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

        // REQ-02 / DOD-02 (base = 0 clause): a zero base makes the retry immediately due, so the
        // very next claim is admitted with no clock advance at all.
        MutableAuditClock zeroClock = new MutableAuditClock(START);
        AuditJournalService zeroBase = newJournal(zeroClock, 3, 0L, 60000L);
        String zeroId = succeeded(zeroBase, "retry-basezero1");
        zeroBase.claimReplay(zeroId);
        zeroClock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(zeroBase.reap()).isEqualTo(1);

        ReplayRecord zeroRolledBack = zeroBase.find(zeroId).orElseThrow().replay();
        assertThat(zeroRolledBack.nextRetryAt()).isEqualTo(zeroRolledBack.replayedAt());
        assertThat(zeroBase.claimReplay(zeroId).leaseExpiresAt())
                .isEqualTo(Instant.now(zeroClock).plusSeconds(LEASE_SECONDS));

        // REQ-02 hardening: an absurd cap is clamped to the documented ceiling rather than
        // overflowing now.plusMillis(), and the pass still journals exactly one rollback.
        MutableAuditClock ceilingClock = new MutableAuditClock(START);
        AuditJournalService hostile = newJournal(ceilingClock, 3, Long.MAX_VALUE, Long.MAX_VALUE);
        String hostileId = succeeded(hostile, "retry-ceiling1");
        hostile.claimReplay(hostileId);
        ceilingClock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(hostile.reap()).isEqualTo(1);
        ReplayRecord hostileRolledBack = hostile.find(hostileId).orElseThrow().replay();
        assertThat(hostileRolledBack.nextRetryAt())
                .isEqualTo(Instant.now(ceilingClock).plus(Duration.ofDays(3650L)))
                .isAfter(Instant.now(ceilingClock).plus(Duration.ofDays(3649L)));
    }

    @Test
    @DisplayName("reaching the quarantine threshold quarantines the entry with a tombstone")
    void quarantinesAfterThresholdRetries() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = newJournal(clock, 3, 1000L, 60000L);
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
    void fencesRenewalAndCompletionToTheClaimOwner() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = newJournal(clock, 2, 1000L, 60000L);
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
    @DisplayName("a retry-pending entry is skipped by the sweep as BACKOFF_PENDING, then verifies once due")
    void verifySweepReportsBackoffPendingSkipReason() {
        MutableAuditClock clock = new MutableAuditClock(START);
        AuditJournalService journal = newJournal(clock, 3, 1000L, 60000L);
        AuditReplayService replayService = newReplayService(journal);
        String id = succeeded(journal, "retry-verify01");
        journal.claimReplay(id);
        clock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(journal.reap()).isEqualTo(1);

        // REQ-02/REQ-05: while the backoff deadline is pending the sweep never executes the entry
        // and reports the structural skip reason instead of a parsed conflict message.
        JournalVerifyResponse pending = replayService.verify(10);
        assertThat(pending.scanned()).isEqualTo(1);
        assertThat(pending.results()).hasSize(1);
        assertThat(pending.results().get(0).entryId()).isEqualTo(id);
        assertThat(pending.results().get(0).outcome()).isNull();
        assertThat(pending.results().get(0).skipReason()).isEqualTo("BACKOFF_PENDING");
        assertThat(pending.results().get(0).detail()).contains("replay backoff in effect");
        assertThat(pending.skipped()).isEqualTo(1);
        assertThat(journal.find(id).orElseThrow().replay().state()).isEqualTo(ReplayState.FAILED);

        // Once the deadline passes the retried entry is admitted again and verifies normally.
        clock.advanceSeconds(1L);
        JournalVerifyResponse due = replayService.verify(10);
        assertThat(due.results()).hasSize(1);
        assertThat(due.results().get(0).outcome()).isEqualTo(ReplayState.VERIFIED);
        assertThat(due.results().get(0).skipReason()).isNull();
        assertThat(due.verified()).isEqualTo(1);
    }

    @Test
    @DisplayName("identical clock sequences journal identical retry schedules and quarantine decisions")
    void journalsDeterministicRetryAndQuarantineSequence() {
        // One retry cycle: lease + 1s past expiry, then the mandated 60s backoff wait.
        long cycle = LEASE_SECONDS + 1L + 60L;
        List<String> first = runDeterministicSequence();
        List<String> second = runDeterministicSequence();
        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(3);
        // reapLog is newest-first: the quarantine decision precedes its two expiry tombstones.
        assertThat(first.get(0)).isEqualTo(ReapReason.REPLAY_LEASE_QUARANTINED.name()
                + "|replay lease expired 3 times; entry quarantined|"
                + START.plusSeconds(2L * cycle + LEASE_SECONDS + 1L));
        // REQ-05/F-01: each expiry tombstone names its own attempt and that attempt's deadline, so
        // the two retries are byte-distinguishable and the schedule is re-playable verbatim.
        assertThat(first.get(1)).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED.name()
                + "|replay claim lease expired at " + START.plusSeconds(cycle + LEASE_SECONDS)
                + "; retry 2 of 3 at " + START.plusSeconds(cycle + LEASE_SECONDS + 1L + 2L)
                + "|" + START.plusSeconds(cycle + LEASE_SECONDS + 1L));
        assertThat(first.get(2)).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED.name()
                + "|replay claim lease expired at " + START.plusSeconds(LEASE_SECONDS)
                + "; retry 1 of 3 at " + START.plusSeconds(LEASE_SECONDS + 1L + 1L)
                + "|" + START.plusSeconds(LEASE_SECONDS + 1L));

        // DOD-05 (verify leg): inside the same deterministic clock grammar, an admitted retry still
        // replays VERIFIED through the verify path, proving the journalled decision is re-playable.
        MutableAuditClock verifyClock = new MutableAuditClock(START);
        AuditJournalService verifyJournal = newJournal(verifyClock, 3, 1000L, 60000L);
        AuditReplayService verifyService = newReplayService(verifyJournal);
        String verifyId = succeeded(verifyJournal, "retry-determ02");
        verifyJournal.claimReplay(verifyId);
        verifyClock.advanceSeconds(LEASE_SECONDS + 1L);
        assertThat(verifyJournal.reap()).isEqualTo(1);
        verifyClock.advanceSeconds(1L);
        JournalVerifyResponse verified = verifyService.verify(1);
        assertThat(verified.results()).hasSize(1);
        assertThat(verified.results().get(0).outcome()).isEqualTo(ReplayState.VERIFIED);
        assertThat(verified.results().get(0).skipReason()).isNull();
        assertThat(verified.verified()).isEqualTo(1);
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
