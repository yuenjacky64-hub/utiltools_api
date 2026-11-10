package com.example.utiltools.audit;

import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.audit.service.ReplayHeartbeatScheduler;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The replay heartbeat at service level: {@code AuditReplayService} renews its own claim on the
 * {@code utiltools.audit.replay-heartbeat-seconds} cadence while {@code replayInvoke} runs,
 * cancels the schedule in a {@code finally} block, and never lets a renewal (or its failure)
 * replace the operation's own outcome. The clock and the scheduler are injected, so no test
 * sleeps for the lease duration.
 */
class AuditReplayHeartbeatTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 30L;
    private static final long STALE_SECONDS = 300L;
    private static final int MAX_ENTRIES = 20;
    private static final int MAX_REPLAY_BYTES = 65536;

    private ObjectMapper objectMapper;
    private MutableAuditClock clock;
    private AuditJournalService journal;
    private AuditedOperations auditedOperations;
    private ManualReplayHeartbeatScheduler heartbeat;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        clock = new MutableAuditClock(START);
        journal = new AuditJournalService(objectMapper, MAX_ENTRIES, MAX_REPLAY_BYTES,
                STALE_SECONDS, LEASE_SECONDS, clock);
        auditedOperations = new AuditedOperations(new DateTimeService(), new CryptoService(),
                new JsonService(objectMapper), new TextService(), journal);
        heartbeat = new ManualReplayHeartbeatScheduler();
        RequestContext.set("req-heart01");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", null);
    }

    private static HashResponse expectedHash() {
        return new CryptoService().hash(hashRequest());
    }

    private AuditEntry succeededEntry(String requestId) {
        return succeededEntry(journal, requestId);
    }

    private AuditEntry succeededEntry(AuditJournalService target, String requestId) {
        AuditEntry pending = target.begin(OperationType.CRYPTO_HASH, hashRequest(), requestId);
        return target.succeed(pending.id(), expectedHash());
    }

    private AuditJournalService journalWithLease(long leaseSeconds) {
        return new AuditJournalService(objectMapper, MAX_ENTRIES, MAX_REPLAY_BYTES, STALE_SECONDS,
                leaseSeconds, clock);
    }

    @Test
    @DisplayName("the heartbeat cadence defaults to half the lease, never below one second")
    void renewalCadenceDefaultsToHalfTheLeaseAndMinimumOneSecond() {
        AuditJournalService defaultJournal = journalWithLease(LEASE_SECONDS);
        AuditReplayService defaultService = new AuditReplayService(defaultJournal, auditedOperations,
                objectMapper, null, heartbeat);
        AuditEntry entry = succeededEntry(defaultJournal, "req-00000001");

        assertThat(defaultService.replay(entry.id()).replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(heartbeat.scheduledCount()).isEqualTo(1);
        assertThat(heartbeat.period()).isEqualTo(Duration.ofSeconds(15));

        ManualReplayHeartbeatScheduler oneSecondScheduler = new ManualReplayHeartbeatScheduler();
        AuditJournalService shortLeaseJournal = journalWithLease(1L);
        AuditReplayService oneSecondService = new AuditReplayService(shortLeaseJournal,
                auditedOperations, objectMapper, null, oneSecondScheduler);
        AuditEntry shortLeaseEntry = succeededEntry(shortLeaseJournal, "req-00000002");

        assertThat(oneSecondService.replay(shortLeaseEntry.id()).replay().state())
                .isEqualTo(ReplayState.VERIFIED);
        assertThat(oneSecondScheduler.period()).isEqualTo(Duration.ofSeconds(1));

        ManualReplayHeartbeatScheduler explicitScheduler = new ManualReplayHeartbeatScheduler();
        AuditJournalService explicitJournal = journalWithLease(LEASE_SECONDS);
        AuditReplayService explicitService = new AuditReplayService(explicitJournal,
                auditedOperations, objectMapper, 7L, explicitScheduler);
        AuditEntry explicitEntry = succeededEntry(explicitJournal, "req-00000003");

        assertThat(explicitService.replay(explicitEntry.id()).replay().state())
                .isEqualTo(ReplayState.VERIFIED);
        assertThat(explicitScheduler.period()).isEqualTo(Duration.ofSeconds(7));

        assertThatThrownBy(() -> new AuditReplayService(journalWithLease(LEASE_SECONDS),
                auditedOperations, objectMapper, 0L, new ManualReplayHeartbeatScheduler()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.audit.replay-heartbeat-seconds");
        assertThatThrownBy(() -> new AuditReplayService(journalWithLease(LEASE_SECONDS),
                auditedOperations, objectMapper, -5L, new ManualReplayHeartbeatScheduler()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.audit.replay-heartbeat-seconds");
    }

    @Test
    @DisplayName("the schedule is cancelled after completion, so a later tick changes nothing")
    void renewalRefusedAfterCompletionDoesNotChangeTheOutcome() {
        AuditEntry entry = succeededEntry("req-00000001");
        AuditReplayService replayService = new AuditReplayService(journal, auditedOperations,
                objectMapper, null, heartbeat);

        AuditEntry replayed = replayService.replay(entry.id());

        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(heartbeat.scheduledCount()).isEqualTo(1);
        assertThat(heartbeat.isCancelled()).isTrue();
        AuditEntry completed = journal.find(entry.id()).orElseThrow();
        assertThat(completed.replay().leaseExpiresAt()).isNull();

        clock.advanceSeconds(LEASE_SECONDS);
        heartbeat.tick();
        heartbeat.tick();

        AuditEntry afterTicks = journal.find(entry.id()).orElseThrow();
        assertThat(afterTicks.replay()).isEqualTo(completed.replay());
        assertThat(afterTicks.completedAt()).isEqualTo(completed.completedAt());
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("a scheduling or renewal failure never replaces the operation's outcome")
    void heartbeatFailureDoesNotReplaceTheOperationOutcome() {
        // A scheduler that refuses to schedule the tick at all.
        AuditEntry entry = succeededEntry("req-00000001");
        ReplayHeartbeatScheduler brokenScheduler = (tick, period) -> {
            throw new IllegalStateException("scheduler unavailable");
        };
        AuditReplayService unschedulable = new AuditReplayService(journal, auditedOperations,
                objectMapper, null, brokenScheduler);
        AuditEntry replayed = unschedulable.replay(entry.id());
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.replay().detail()).isEqualTo("results match");

        // A journal whose renewal throws while the operation is running.
        AuditJournalService throwingJournal = spy(journal);
        doThrow(new IllegalStateException("journal unavailable"))
                .when(throwingJournal).renewReplayLease(anyString(), anyString());
        AuditedOperations ticking = mock(AuditedOperations.class);
        when(ticking.replayInvoke(any(), any())).thenAnswer(call -> {
            heartbeat.tick();
            return expectedHash();
        });
        AuditReplayService failing = new AuditReplayService(throwingJournal, ticking, objectMapper,
                1L, heartbeat);
        AuditEntry second = succeededEntry("req-00000002");

        AuditEntry survived = failing.replay(second.id());

        assertThat(survived.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(survived.replay().detail()).isEqualTo("results match");
        assertThat(heartbeat.scheduledCount()).isEqualTo(1);
        assertThat(heartbeat.isCancelled()).isTrue();
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("a replay that outlives the lease but renews completes VERIFIED with no tombstone")
    void longRenewedReplayCompletesVerifiedWithoutATombstone() {
        AuditEntry entry = succeededEntry("req-00000001");
        AuditReplayService replayService = new AuditReplayService(journal, slowOperation(expectedHash()),
                objectMapper, null, heartbeat);

        AuditEntry replayed = replayService.replay(entry.id());

        assertThat(replayed.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.replay().leaseExpiresAt()).isNull();
        assertThat(heartbeat.tickCount()).isEqualTo(2);
        assertThat(heartbeat.isCancelled()).isTrue();
        // Renewal stopped with the operation: a forced later tick cannot resurrect the claim.
        clock.advanceSeconds(LEASE_SECONDS);
        heartbeat.tick();
        assertThat(journal.find(entry.id()).orElseThrow().replay().state())
                .isEqualTo(ReplayState.VERIFIED);
        assertThat(journal.reapLog(10)).isEmpty();
        assertThat(journal.reap()).isZero();
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("a long renewed replay that returns a different result is MISMATCH, not a conflict")
    void longRenewedReplayReportsMismatch() {
        AuditEntry entry = succeededEntry("req-00000001");
        AuditedOperations mismatching = slowOperation(new HashResponse("SHA-256", "different", false));
        AuditReplayService replayService = new AuditReplayService(journal, mismatching, objectMapper,
                null, heartbeat);

        JournalVerifyResponse sweep = replayService.verify(1);

        assertThat(sweep.scanned()).isEqualTo(1);
        assertThat(sweep.mismatch()).isEqualTo(1);
        assertThat(sweep.failed()).isZero();
        AuditEntry mismatched = journal.find(entry.id()).orElseThrow();
        assertThat(mismatched.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(mismatched.replay().state()).isEqualTo(ReplayState.MISMATCH);
        assertThat(mismatched.replay().leaseExpiresAt()).isNull();
        assertThat(journal.reapLog(10)).isEmpty();
    }

    @Test
    @DisplayName("owner tokens stay private and only leaseExpiresAt is exposed")
    void responsesNeverExposeTheOwnerToken() {
        // A renewing claim, viewed while it is still live.
        AuditEntry claimed = succeededEntry("req-00000001");
        ReplayClaim claim = journal.claimReplay(claimed.id());
        clock.advanceSeconds(5);
        assertThat(journal.renewReplayLease(claimed.id(), claim.ownerToken())).isPresent();

        String findJson = toJson(journal.find(claimed.id()).orElseThrow());
        String listJson = toJson(journal.list(10, null));
        assertThat(findJson).doesNotContain(claim.ownerToken());
        assertThat(listJson).doesNotContain(claim.ownerToken());
        assertThat(findJson).doesNotContain("ownerToken");
        assertThat(listJson).doesNotContain("ownerToken");
        assertThat(findJson).contains("leaseExpiresAt");

        // A heartbeat-renewed replay's terminal record exposes no token either.
        AuditEntry replayed = succeededEntry("req-00000002");
        AuditReplayService replayService = new AuditReplayService(journal, slowOperation(expectedHash()),
                objectMapper, null, heartbeat);
        assertThat(replayService.replay(replayed.id()).replay().state())
                .isEqualTo(ReplayState.VERIFIED);
        String terminalJson = toJson(journal.find(replayed.id()).orElseThrow());
        assertThat(terminalJson).doesNotContain("ownerToken");
        assertThat(journal.reapLog(10)).isEmpty();
    }

    /**
     * An operation that outlives the whole lease: it advances the injected clock past
     * {@code replay-lease-seconds} twice and ticks the heartbeat where a renewal would have fired,
     * so the claim is renewed twice without any test sleeping.
     */
    private AuditedOperations slowOperation(Object result) {
        AuditedOperations slow = mock(AuditedOperations.class);
        when(slow.replayInvoke(any(), any())).thenAnswer(call -> {
            clock.advanceSeconds(LEASE_SECONDS / 2);
            heartbeat.tick();
            clock.advanceSeconds(LEASE_SECONDS / 2);
            heartbeat.tick();
            clock.advanceSeconds(LEASE_SECONDS / 2);
            return result;
        });
        return slow;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.copy().findAndRegisterModules().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
