package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner-fenced renewal of a replay claim: {@code renewReplayLease} extends
 * {@code ReplayRecord.leaseExpiresAt} for the live owner only, keeps every other field of the
 * record and the entry byte-identical, and shares the "expired" boundary with the reaper so a
 * renewal and a reap can never both win. All expiry is driven by {@link MutableAuditClock}, so no
 * test sleeps for the lease duration.
 */
class AuditReplayLeaseRenewalTest {

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
    @DisplayName("renewReplayLease extends only the deadline of the live owner's claim")
    void renewalExtendsTheLeaseForTheLiveOwner() {
        AuditEntry original = succeeded("req-00000001");
        ReplayClaim claim = journal.claimReplay(original.id());
        Instant claimedUntil = journal.find(original.id()).orElseThrow().replay().leaseExpiresAt();
        assertThat(claimedUntil).isEqualTo(START.plusSeconds(LEASE_SECONDS));

        clock.advanceSeconds(15);
        Optional<Instant> renewed = journal.renewReplayLease(original.id(), claim.ownerToken());

        assertThat(renewed).contains(START.plusSeconds(45));
        AuditEntry afterRenewal = journal.find(original.id()).orElseThrow();
        ReplayRecord replay = afterRenewal.replay();
        assertThat(replay.state()).isEqualTo(ReplayState.IN_PROGRESS);
        assertThat(replay.leaseExpiresAt()).isEqualTo(START.plusSeconds(45));
        assertThat(replay.replayedAt()).isEqualTo(START);
        assertThat(replay.detail()).isEqualTo("replay in progress");
        // The entry itself is untouched by a renewal.
        assertThat(afterRenewal.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(afterRenewal.result()).isEqualTo(original.result());
        assertThat(afterRenewal.completedAt()).isEqualTo(original.completedAt());
        assertThat(afterRenewal.replayable()).isTrue();
        assertThat(journal.reapLog(10)).isEmpty();

        // A renewal may only extend: the deadline never moves backwards.
        clock.advanceSeconds(15);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken()))
                .contains(START.plusSeconds(60));

        // The same owner token still completes the renewed claim.
        clock.advanceSeconds(15);
        assertThat(journal.completeReplay(original.id(), claim.ownerToken(), ReplayState.VERIFIED,
                "results match")).isTrue();
        AuditEntry completed = journal.find(original.id()).orElseThrow();
        assertThat(completed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(completed.replay().leaseExpiresAt()).isNull();
        assertThat(journal.reap()).isZero();
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("a stale token can neither renew nor complete a live claim")
    void staleTokenCannotRenewOrCompleteTheClaim() {
        AuditEntry original = succeeded("req-00000001");
        journal.claimReplay(original.id());
        ReplayRecord claimed = journal.find(original.id()).orElseThrow().replay();

        assertThat(journal.renewReplayLease(original.id(), "stale-owner-token")).isEmpty();
        assertThat(journal.renewReplayLease(original.id(), null)).isEmpty();
        assertThat(journal.renewReplayLease(null, "stale-owner-token")).isEmpty();
        assertThat(journal.find(original.id()).orElseThrow().replay()).isEqualTo(claimed);
        assertThat(journal.reapLog(10)).isEmpty();

        assertThat(journal.completeReplay(original.id(), "stale-owner-token", ReplayState.VERIFIED,
                "late owner")).isFalse();
        AuditEntry afterCompletion = journal.find(original.id()).orElseThrow();
        assertThat(afterCompletion.replay().state()).isEqualTo(ReplayState.IN_PROGRESS);
        assertThat(afterCompletion.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("an unknown or evicted entry cannot be renewed")
    void unknownOrEvictedEntryCannotBeRenewed() {
        assertThat(journal.renewReplayLease("no-such-entry", "any-token")).isEmpty();

        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), 1, MAX_REPLAY_BYTES,
                STALE_SECONDS, LEASE_SECONDS, clock);
        AuditEntry pending = bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        AuditEntry claimable = bounded.succeed(pending.id(), hashResponse());
        ReplayClaim claim = bounded.claimReplay(claimable.id());
        bounded.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        assertThat(bounded.find(claimable.id())).isEmpty();

        assertThat(bounded.renewReplayLease(claimable.id(), claim.ownerToken())).isEmpty();
        assertThat(bounded.reap()).isZero();
    }

    @Test
    @DisplayName("renewal after expiry is refused and leaves the journal untouched")
    void renewalAfterExpiryIsRefusedAndLeavesTheJournalUntouched() {
        AuditEntry original = succeeded("req-00000001");
        ReplayClaim claim = journal.claimReplay(original.id());
        ReplayRecord claimed = journal.find(original.id()).orElseThrow().replay();

        // The boundary is shared with the reaper: a deadline that is not after now is expired.
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken())).isEmpty();
        clock.advanceSeconds(5);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken())).isEmpty();

        AuditEntry afterRefusal = journal.find(original.id()).orElseThrow();
        assertThat(afterRefusal.replay()).isEqualTo(claimed);
        assertThat(afterRefusal.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(afterRefusal.completedAt()).isEqualTo(original.completedAt());
        assertThat(journal.reapLog(10)).isEmpty();

        // Once reaped, the claim is terminal and renewal stays refused without mutating anything.
        assertThat(journal.reap()).isEqualTo(1);
        ReplayRecord reaped = journal.find(original.id()).orElseThrow().replay();
        assertThat(reaped.state()).isEqualTo(ReplayState.FAILED);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken())).isEmpty();
        assertThat(journal.find(original.id()).orElseThrow().replay()).isEqualTo(reaped);
        assertThat(journal.reapLog(10)).hasSize(1);

        // A completed claim is terminal too.
        AuditEntry other = succeeded("req-00000002");
        ReplayClaim otherClaim = journal.claimReplay(other.id());
        assertThat(journal.completeReplay(other.id(), otherClaim.ownerToken(), ReplayState.VERIFIED,
                "results match")).isTrue();
        ReplayRecord completed = journal.find(other.id()).orElseThrow().replay();
        assertThat(journal.renewReplayLease(other.id(), otherClaim.ownerToken())).isEmpty();
        assertThat(journal.find(other.id()).orElseThrow().replay()).isEqualTo(completed);
    }

    @Test
    @DisplayName("a renewed claim is never tombstoned, and is reaped exactly once after renewal stops")
    void reapedExactlyOnceAfterRenewalStops() {
        AuditEntry original = succeeded("req-00000001");
        ReplayClaim claim = journal.claimReplay(original.id());

        clock.advanceSeconds(15);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken()))
                .contains(START.plusSeconds(45));
        // The original deadline (START + 30s) passes with a renewal in force: nothing is reaped.
        clock.advanceSeconds(15);
        assertThat(journal.reap()).isZero();
        assertThat(journal.reapLog(10)).isEmpty();
        assertThat(journal.find(original.id()).orElseThrow().replay().state())
                .isEqualTo(ReplayState.IN_PROGRESS);

        // Renewal stops here; once the renewed lease expires the claim is rolled back exactly once.
        clock.advanceSeconds(15);
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken())).isEmpty();
        assertThat(journal.renewReplayLease(original.id(), claim.ownerToken())).isEmpty();
        assertThat(journal.reapLog(10)).isEmpty();

        assertThat(journal.reap()).isEqualTo(1);
        assertThat(journal.reap()).isZero();
        List<ReapRecord> tombstones = journal.reapLog(10);
        assertThat(tombstones).hasSize(1);
        assertThat(tombstones.get(0).reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
        assertThat(tombstones.get(0).status()).isEqualTo("REAPED");
        AuditEntry after = journal.find(original.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(after.replay().leaseExpiresAt()).isNull();
        assertThat(after.replay().detail()).contains("replay claim lease expired");
        assertThat(journal.completeReplay(original.id(), claim.ownerToken(), ReplayState.VERIFIED,
                "late owner")).isFalse();
    }

    @Test
    @DisplayName("a renewal racing a reap at one instant yields exactly one winner")
    void renewalRacingReapHasExactlyOneWinner() throws Exception {
        // Live instant (deadline still in the future): the renewal wins and the reap does nothing.
        for (int i = 0; i < 5; i++) {
            MutableAuditClock raceClock = new MutableAuditClock(START);
            AuditJournalService raceJournal = new AuditJournalService(new ObjectMapper(),
                    MAX_ENTRIES, MAX_REPLAY_BYTES, STALE_SECONDS, LEASE_SECONDS, raceClock);
            AuditEntry pending = raceJournal.begin(OperationType.CRYPTO_HASH, hashRequest(),
                    "req-0000000" + i);
            AuditEntry entry = raceJournal.succeed(pending.id(), hashResponse());
            ReplayClaim claim = raceJournal.claimReplay(entry.id());
            raceClock.advanceSeconds(LEASE_SECONDS - 1);

            Optional<Instant> renewal;
            int reaps;
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<Instant>> renewalTask = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return raceJournal.renewReplayLease(entry.id(), claim.ownerToken());
                });
                Future<Integer> reapTask = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return raceJournal.reap();
                });
                renewal = renewalTask.get(5, TimeUnit.SECONDS);
                reaps = reapTask.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            AuditEntry after = raceJournal.find(entry.id()).orElseThrow();
            assertThat(reaps).isZero();
            assertThat(raceJournal.reapLog(10)).isEmpty();
            assertThat(renewal).contains(START.plusSeconds(59));
            assertThat(after.replay().state()).isEqualTo(ReplayState.IN_PROGRESS);
            assertThat(after.replay().leaseExpiresAt()).isEqualTo(START.plusSeconds(59));
            assertThat(after.replay().replayedAt()).isEqualTo(START);
            assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
            assertThat(raceJournal.completeReplay(entry.id(), claim.ownerToken(),
                    ReplayState.VERIFIED, "results match")).isTrue();
        }

        // Expired instant (deadline is exactly now): the reap wins and the renewal is refused.
        for (int i = 0; i < 5; i++) {
            MutableAuditClock raceClock = new MutableAuditClock(START);
            AuditJournalService raceJournal = new AuditJournalService(new ObjectMapper(),
                    MAX_ENTRIES, MAX_REPLAY_BYTES, STALE_SECONDS, LEASE_SECONDS, raceClock);
            AuditEntry pending = raceJournal.begin(OperationType.CRYPTO_HASH, hashRequest(),
                    "req-0000000" + i);
            AuditEntry entry = raceJournal.succeed(pending.id(), hashResponse());
            ReplayClaim claim = raceJournal.claimReplay(entry.id());
            raceClock.advanceSeconds(LEASE_SECONDS);

            Optional<Instant> renewal;
            int reaps;
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<Instant>> renewalTask = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return raceJournal.renewReplayLease(entry.id(), claim.ownerToken());
                });
                Future<Integer> reapTask = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return raceJournal.reap();
                });
                renewal = renewalTask.get(5, TimeUnit.SECONDS);
                reaps = reapTask.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            AuditEntry after = raceJournal.find(entry.id()).orElseThrow();
            assertThat(renewal).isEmpty();
            assertThat(reaps).isEqualTo(1);
            List<ReapRecord> tombstones = raceJournal.reapLog(10);
            assertThat(tombstones).hasSize(1);
            assertThat(tombstones.get(0).reason()).isEqualTo(ReapReason.REPLAY_LEASE_EXPIRED);
            assertThat(tombstones.get(0).reapedAt()).isEqualTo(START.plusSeconds(LEASE_SECONDS));
            assertThat(after.replay().state()).isEqualTo(ReplayState.FAILED);
            assertThat(after.replay().leaseExpiresAt()).isNull();
            assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
            assertThat(after.result()).isEqualTo(entry.result());
            assertThat(after.completedAt()).isEqualTo(entry.completedAt());
            assertThat(raceJournal.completeReplay(entry.id(), claim.ownerToken(),
                    ReplayState.VERIFIED, "late owner")).isFalse();
        }
    }
}
