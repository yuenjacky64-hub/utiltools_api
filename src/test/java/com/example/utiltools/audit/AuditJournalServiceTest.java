package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditJournalServiceTest {

    private AuditJournalService journal;

    @BeforeEach
    void setUp() {
        journal = new AuditJournalService(new ObjectMapper(), 3, 65536, 300L);
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", "super-secret-key");
    }

    private static HashResponse hashResponse() {
        return new HashResponse("SHA-256", "abc123", false);
    }

    @Test
    @DisplayName("begin stores a PENDING entry carrying the correlation id")
    void shouldBeginPendingEntry() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");

        assertThat(entry.state()).isEqualTo(JournalState.PENDING);
        assertThat(entry.requestId()).isEqualTo("req-12345678");
        assertThat(entry.operation()).isEqualTo(OperationType.CRYPTO_HASH);
        assertThat(entry.completedAt()).isNull();
        assertThat(entry.result()).isNull();
        assertThat(journal.find(entry.id())).contains(entry);
    }

    @Test
    @DisplayName("PENDING -> SUCCEEDED records the result and a completion timestamp")
    void shouldSucceedPendingEntry() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");

        AuditEntry succeeded = journal.succeed(entry.id(), hashResponse());

        assertThat(succeeded.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(succeeded.completedAt()).isNotNull();
        assertThat(succeeded.result().get("digest").asText()).isEqualTo("abc123");
        assertThat(succeeded.errorCode()).isNull();
    }

    @Test
    @DisplayName("PENDING -> FAILED leaves result null and records the error code")
    void shouldFailPendingEntryAndClearResult() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");

        AuditEntry failed = journal.fail(entry.id(), "BAD_REQUEST");

        assertThat(failed.state()).isEqualTo(JournalState.FAILED);
        assertThat(failed.result()).isNull();
        assertThat(failed.errorCode()).isEqualTo("BAD_REQUEST");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("a late transition after a terminal state throws IllegalStateException")
    void lateSucceedThrows() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");
        AuditEntry failed = journal.fail(entry.id(), "BAD_REQUEST");
        Instant completedAt = failed.completedAt();

        assertThatThrownBy(() -> journal.succeed(entry.id(), hashResponse()))
                .isInstanceOf(IllegalStateException.class);

        AuditEntry after = journal.find(entry.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.FAILED);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isNull();
        assertThat(after.errorCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("duplicate transition and unknown id both throw IllegalStateException")
    void shouldRejectDuplicateTransitionAndUnknownId() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");
        journal.succeed(entry.id(), hashResponse());

        assertThatThrownBy(() -> journal.succeed(entry.id(), hashResponse()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> journal.fail("missing-id", "BAD_REQUEST"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the journal keeps at most max-entries and evicts the oldest id")
    void evictsOldestEntryAtBound() {
        AuditEntry first = journal.begin(OperationType.DATETIME_CONVERT, Map.of("input", "2024-01-01"), "req-00000001");
        journal.begin(OperationType.DATETIME_CONVERT, Map.of("input", "2024-01-02"), "req-00000002");
        journal.begin(OperationType.DATETIME_CONVERT, Map.of("input", "2024-01-03"), "req-00000003");
        AuditEntry fourth = journal.begin(OperationType.DATETIME_CONVERT, Map.of("input", "2024-01-04"), "req-00000004");

        assertThat(journal.size()).isEqualTo(3);
        assertThat(journal.find(first.id())).isEmpty();
        assertThat(journal.find(fourth.id())).isPresent();
    }

    @Test
    @DisplayName("secret fields are redacted in the stored request view")
    void shouldRedactSecretFields() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");

        assertThat(entry.request().has("secretKey")).isTrue();
        assertThat(entry.request().get("secretKey").asText()).isEqualTo("***");
        assertThat(entry.request().get("content").asText()).isEqualTo("Hello World");
        assertThat(entry.request().toString()).doesNotContain("super-secret-key");
    }

    @Test
    @DisplayName("an oversized request payload is neither retained nor re-served")
    void shouldNotRetainOversizedPayload() {
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), 3, 64, 300L);
        String huge = "x".repeat(4096);
        HashRequest oversized = new HashRequest("SHA-256", huge, "super-secret-key");

        AuditEntry entry = bounded.begin(OperationType.CRYPTO_HASH, oversized, "req-oversized1");

        assertThat(entry.replayable()).isFalse();
        assertThat(entry.request().has("omitted")).isTrue();
        assertThat(entry.request().toString()).doesNotContain(huge);
        assertThat(entry.request().toString()).doesNotContain("super-secret-key");
        assertThat(bounded.find(entry.id()).orElseThrow().request().toString())
                .isEqualTo(entry.request().toString());
        assertThat(bounded.list(10, null)).allSatisfy(listed ->
                assertThat(listed.request().toString()).doesNotContain(huge));
    }

    @Test
    @DisplayName("list returns newest first, filters by state and clamps the limit")
    void shouldListNewestFirstFilteredByState() {
        AuditEntry succeeded = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        journal.succeed(succeeded.id(), hashResponse());
        AuditEntry failed = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        journal.fail(failed.id(), "BAD_REQUEST");

        List<AuditEntry> all = journal.list(10, null);
        assertThat(all).extracting(AuditEntry::id).containsExactly(failed.id(), succeeded.id());

        assertThat(journal.list(10, JournalState.FAILED)).extracting(AuditEntry::id)
                .containsExactly(failed.id());
        assertThat(journal.list(1, null)).hasSize(1);
    }

    @Test
    @DisplayName("a PENDING entry older than the cutoff is reaped to FAILED/STALE_PENDING_REAPED")
    void shouldReapStalePendingEntry() {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        // A cutoff one tick after the stale entry's own stamp: the stale entry is strictly older,
        // the entry begun below can only be equal-or-later, so the split is exact with no sleeping.
        Instant cutoff = stale.createdAt().plusNanos(1);
        AuditEntry fresh = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");

        assertThat(journal.reapStalePending(cutoff)).isEqualTo(1);

        AuditEntry reaped = journal.find(stale.id()).orElseThrow();
        assertThat(reaped.state()).isEqualTo(JournalState.FAILED);
        assertThat(reaped.errorCode()).isEqualTo("STALE_PENDING_REAPED");
        assertThat(reaped.completedAt()).isNotNull();
        assertThat(reaped.result()).isNull();
        assertThat(reaped.request()).isEqualTo(stale.request());
        assertThat(reaped.createdAt()).isEqualTo(stale.createdAt());

        AuditEntry untouched = journal.find(fresh.id()).orElseThrow();
        assertThat(untouched.state()).isEqualTo(JournalState.PENDING);
        assertThat(untouched.completedAt()).isNull();
        assertThat(untouched.errorCode()).isNull();
        assertThat(journal.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a second sweep with the same cutoff reaps nothing (idempotent)")
    void shouldBeIdempotentOnSecondSweep() {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        Instant cutoff = stale.createdAt().plusNanos(1);

        assertThat(journal.reapStalePending(cutoff)).isEqualTo(1);
        assertThat(journal.reapStalePending(cutoff)).isZero();
    }

    @Test
    @DisplayName("terminal SUCCEEDED and FAILED entries are never reaped")
    void shouldNotReapTerminalEntries() {
        AuditEntry succeeded = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        AuditEntry succeededAfter = journal.succeed(succeeded.id(), hashResponse());
        AuditEntry failed = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        AuditEntry failedAfter = journal.fail(failed.id(), "BAD_REQUEST");

        assertThat(journal.reapStalePending(Instant.now().plusSeconds(86_400))).isZero();

        AuditEntry stillSucceeded = journal.find(succeeded.id()).orElseThrow();
        assertThat(stillSucceeded.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(stillSucceeded.errorCode()).isNull();
        assertThat(stillSucceeded.completedAt()).isEqualTo(succeededAfter.completedAt());

        AuditEntry stillFailed = journal.find(failed.id()).orElseThrow();
        assertThat(stillFailed.state()).isEqualTo(JournalState.FAILED);
        assertThat(stillFailed.errorCode()).isEqualTo("BAD_REQUEST");
        assertThat(stillFailed.completedAt()).isEqualTo(failedAfter.completedAt());
    }

    @Test
    @DisplayName("a sweep with no PENDING entries is a no-op and does not throw")
    void shouldNoOpSweepWithoutPendingEntries() {
        AuditJournalService empty = new AuditJournalService(new ObjectMapper(), 3, 65536, 300L);

        assertThatCode(() -> empty.reapStalePending()).doesNotThrowAnyException();
        assertThat(empty.size()).isZero();
        assertThat(empty.reapStalePending(Instant.now())).isZero();
    }

    @Test
    @DisplayName("a reaped entry rejects later transitions and keeps its reap stamp")
    void shouldRejectTransitionAfterReap() {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000001");
        assertThat(journal.reapStalePending(entry.createdAt().plusNanos(1))).isEqualTo(1);
        Instant completedAt = journal.find(entry.id()).orElseThrow().completedAt();

        assertThatThrownBy(() -> journal.succeed(entry.id(), hashResponse()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> journal.fail(entry.id(), "BAD_REQUEST"))
                .isInstanceOf(IllegalStateException.class);

        AuditEntry after = journal.find(entry.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.FAILED);
        assertThat(after.errorCode()).isEqualTo("STALE_PENDING_REAPED");
        assertThat(after.completedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("the sweep runs under the lock so concurrent begin/succeed never sees a half transition")
    void shouldSweepUnderLockAlongsideConcurrentBegin() throws Exception {
        AuditJournalService roomy = new AuditJournalService(new ObjectMapper(), 5_000, 65536, 300L);
        Instant cutoff = Instant.now();
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        AuditEntry entry = roomy.begin(OperationType.CRYPTO_HASH, hashRequest(),
                                String.format("req-%08d", i));
                        if (i % 2 == 0) {
                            roomy.succeed(entry.id(), hashResponse());
                        }
                    }
                } catch (Throwable e) {
                    escaped.compareAndSet(null, e);
                }
            });
            worker.start();
            threads.add(worker);
        }
        Thread sweeper = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    roomy.reapStalePending(cutoff);
                }
            } catch (Throwable e) {
                escaped.compareAndSet(null, e);
            }
        });
        sweeper.start();
        threads.add(sweeper);
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(escaped.get()).isNull();
        for (AuditEntry listed : roomy.list(5_000, null)) {
            assertThat(listed.state()).isIn(JournalState.PENDING, JournalState.SUCCEEDED, JournalState.FAILED);
            if (listed.state() == JournalState.FAILED) {
                assertThat(listed.errorCode()).isEqualTo("STALE_PENDING_REAPED");
                assertThat(listed.completedAt()).isNotNull();
            }
            if (listed.state() == JournalState.PENDING) {
                assertThat(listed.completedAt()).isNull();
            }
            if (listed.state() == JournalState.SUCCEEDED) {
                assertThat(listed.completedAt()).isNotNull();
            }
        }
        assertThat(roomy.size()).isLessThanOrEqualTo(5_000);
    }

    @Test
    @DisplayName("non-deterministic operations are flagged on the enum")
    void shouldFlagNonDeterministicOperations() {
        assertThat(OperationType.DATETIME_CURRENT.isDeterministic()).isFalse();
        assertThat(OperationType.CRYPTO_UUID.isDeterministic()).isFalse();
        assertThat(OperationType.DATETIME_CONVERT.isDeterministic()).isTrue();
        assertThat(OperationType.CRYPTO_HASH.isDeterministic()).isTrue();
        assertThat(OperationType.JSON_QUERY.isDeterministic()).isTrue();
    }


    private AuditEntry succeededEntry() {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");
        return journal.succeed(pending.id(), hashResponse());
    }

    @Test
    @DisplayName("an active replay claim rejects a second claim with REPLAY_CONFLICT")
    void shouldRejectSecondClaimWhileLeaseIsLive() {
        AuditEntry succeeded = succeededEntry();
        ReplayClaim claim = journal.claimReplay(succeeded.id());

        assertThat(claim.ownerToken()).isNotBlank();
        assertThat(journal.find(succeeded.id()).orElseThrow().replay().state())
                .isEqualTo(ReplayState.IN_PROGRESS);
        assertThatThrownBy(() -> journal.claimReplay(succeeded.id()))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("active replay claim");
    }

    @Test
    @DisplayName("only the live owner may record the terminal replay outcome")
    void shouldRejectCompletionFromAStaleOrUnknownToken() {
        AuditEntry succeeded = succeededEntry();
        ReplayClaim claim = journal.claimReplay(succeeded.id());

        assertThat(journal.completeReplay(succeeded.id(), "not-the-owner", ReplayState.VERIFIED, "x"))
                .isFalse();
        assertThat(journal.completeReplay(succeeded.id(), null, ReplayState.VERIFIED, "x")).isFalse();
        assertThat(journal.find(succeeded.id()).orElseThrow().replay().state())
                .isEqualTo(ReplayState.IN_PROGRESS);

        assertThat(journal.completeReplay(succeeded.id(), claim.ownerToken(), ReplayState.MISMATCH,
                "digest differs")).isTrue();
        assertThat(journal.completeReplay(succeeded.id(), claim.ownerToken(), ReplayState.VERIFIED,
                "late")).isFalse();

        AuditEntry after = journal.find(succeeded.id()).orElseThrow();
        assertThat(after.replay().state()).isEqualTo(ReplayState.MISMATCH);
        assertThat(after.replay().detail()).isEqualTo("digest differs");
    }

    @Test
    @DisplayName("completion never rewrites the original state, result or completedAt")
    void failedCompletionLeavesOriginalEntryUntouched() {
        AuditEntry succeeded = succeededEntry();
        ReplayClaim claim = journal.claimReplay(succeeded.id());

        assertThat(journal.completeReplay(succeeded.id(), claim.ownerToken(), ReplayState.FAILED,
                "replay failed: boom")).isTrue();

        AuditEntry after = journal.find(succeeded.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.result()).isEqualTo(succeeded.result());
        assertThat(after.completedAt()).isEqualTo(succeeded.completedAt());
        assertThat(after.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(after.replay().detail()).isEqualTo("replay failed: boom");
    }

    @Test
    @DisplayName("completing an evicted or unknown entry returns false without throwing or writing")
    void shouldReturnFalseWhenCompletingAnEvictedEntry() {
        AuditEntry succeeded = succeededEntry();
        ReplayClaim claim = journal.claimReplay(succeeded.id());

        // fill the bounded journal past max-entries so the claimed entry is evicted
        journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000002");
        journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000003");
        journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-00000004");
        assertThat(journal.find(succeeded.id())).isEmpty();

        assertThatCode(() -> assertThat(journal.completeReplay(succeeded.id(), claim.ownerToken(),
                ReplayState.VERIFIED, "late owner")).isFalse()).doesNotThrowAnyException();
        assertThat(journal.find(succeeded.id())).isEmpty();

        assertThatCode(() -> assertThat(journal.completeReplay("missing-entry", claim.ownerToken(),
                ReplayState.FAILED, "unknown")).isFalse()).doesNotThrowAnyException();
        assertThat(journal.find("missing-entry")).isEmpty();
    }

    @Test
    @DisplayName("a failed replay detail is bounded to the journal detail limit")
    void shouldBoundReplayDetail() {
        AuditEntry succeeded = succeededEntry();
        ReplayClaim claim = journal.claimReplay(succeeded.id());
        String tooLong = "x".repeat(1000);

        assertThat(journal.completeReplay(succeeded.id(), claim.ownerToken(), ReplayState.FAILED, tooLong))
                .isTrue();
        assertThat(journal.find(succeeded.id()).orElseThrow().replay().detail()).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("an expired IN_PROGRESS claim can be taken over and the old owner is rejected")
    void shouldAllowTakeoverAfterLeaseExpiry() {
        MutableAuditClock clock = new MutableAuditClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuditJournalService leased = new AuditJournalService(new ObjectMapper(), 3, 65536, 300L, 30L, clock);
        AuditEntry pending = leased.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-12345678");
        AuditEntry succeeded = leased.succeed(pending.id(), hashResponse());

        ReplayClaim oldOwner = leased.claimReplay(succeeded.id());
        assertThat(oldOwner.leaseExpiresAt()).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));

        clock.advanceSeconds(30);
        assertThat(leased.completeReplay(succeeded.id(), oldOwner.ownerToken(), ReplayState.VERIFIED,
                "stale owner")).isFalse();

        ReplayClaim newOwner = leased.claimReplay(succeeded.id());
        assertThat(newOwner.ownerToken()).isNotEqualTo(oldOwner.ownerToken());
        assertThat(leased.completeReplay(succeeded.id(), oldOwner.ownerToken(), ReplayState.VERIFIED,
                "still stale")).isFalse();
        assertThat(leased.completeReplay(succeeded.id(), newOwner.ownerToken(), ReplayState.MISMATCH,
                "fresh owner")).isTrue();

        AuditEntry after = leased.find(succeeded.id()).orElseThrow();
        assertThat(after.replay().state()).isEqualTo(ReplayState.MISMATCH);
        assertThat(after.replay().detail()).isEqualTo("fresh owner");
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.completedAt()).isEqualTo(succeeded.completedAt());
    }
}
