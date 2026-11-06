package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import com.example.utiltools.text.service.TextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditReplayServiceTest {

    private ObjectMapper objectMapper;
    private AuditJournalService journal;
    private AuditedOperations auditedOperations;
    private AuditReplayService replayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        journal = new AuditJournalService(objectMapper, 200, 65536, 300L);
        auditedOperations = new AuditedOperations(new DateTimeService(), new CryptoService(),
                new JsonService(objectMapper), new TextService(), journal);
        replayService = new AuditReplayService(journal, auditedOperations, objectMapper);
        RequestContext.set("req-replay01");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", null);
    }

    @Test
    @DisplayName("replay of a deterministic SUCCEEDED entry is VERIFIED and leaves the entry untouched")
    void successfulHashReplayIsVerifiedAndLeavesOriginalUnchanged() {
        HashResponse result = auditedOperations.hash(hashRequest());
        AuditEntry original = journal.list(1, null).get(0);
        assertThat(original.state()).isEqualTo(JournalState.SUCCEEDED);
        Instant completedAt = original.completedAt();

        AuditEntry replayed = replayService.replay(original.id());

        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(replayed.completedAt()).isEqualTo(completedAt);
        assertThat(replayed.result()).isEqualTo(original.result());
        assertThat(replayed.result().get("digest").asText()).isEqualTo(result.digest());
        assertThat(journal.size()).isEqualTo(1);
    }

    private static DateTimeShiftRequest shiftRequest() {
        return new DateTimeShiftRequest("2026-01-31T12:00:00Z", "UTC", 1L, "MONTHS", null);
    }

    @Test
    @DisplayName("replay of a SUCCEEDED DATETIME_SHIFT entry is VERIFIED and leaves the entry untouched")
    void successfulShiftReplayIsVerifiedAndLeavesOriginalEntryUnchanged() {
        DateTimeShiftResponse result = auditedOperations.shift(shiftRequest());
        AuditEntry original = journal.list(1, null).get(0);
        assertThat(original.operation()).isEqualTo(OperationType.DATETIME_SHIFT);
        assertThat(original.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(original.request().get("input").asText()).isEqualTo("2026-01-31T12:00:00Z");
        assertThat(original.request().get("targetTimeZone").asText()).isEqualTo("UTC");
        assertThat(original.request().get("amount").asLong()).isEqualTo(1L);
        assertThat(original.request().get("unit").asText()).isEqualTo("MONTHS");
        assertThat(original.request().has("amountNonZero")).isFalse();
        Instant completedAt = original.completedAt();
        int sizeBeforeReplay = journal.size();

        AuditEntry replayed = replayService.replay(original.id());

        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(replayed.completedAt()).isEqualTo(completedAt);
        assertThat(replayed.result()).isEqualTo(original.result());
        assertThat(replayed.result().get("isoUtc").asText()).isEqualTo(result.isoUtc());
        assertThat(replayed.result().get("isoUtc").asText()).isEqualTo("2026-02-28T12:00:00Z");
        assertThat(journal.size()).isEqualTo(sizeBeforeReplay);
    }

    @Test
    @DisplayName("a shift with an unknown timezone is journalled as a FAILED DATETIME_SHIFT entry")
    void unknownTimeZoneShiftIsJournalledAsFailed() {
        DateTimeShiftRequest unknownZone =
                new DateTimeShiftRequest("2026-01-31T12:00:00Z", "Invalid/Zone", 1L, "MONTHS", null);

        assertThatThrownBy(() -> auditedOperations.shift(unknownZone))
                .isInstanceOf(IllegalArgumentException.class);

        AuditEntry failed = journal.list(1, null).get(0);
        assertThat(failed.operation()).isEqualTo(OperationType.DATETIME_SHIFT);
        assertThat(failed.state()).isEqualTo(JournalState.FAILED);
        assertThat(failed.errorCode()).isEqualTo("BAD_REQUEST");
        assertThat(failed.result()).isNull();
        assertThat(failed.request().get("targetTimeZone").asText()).isEqualTo("Invalid/Zone");
        assertThat(failed.request().get("amount").asLong()).isEqualTo(1L);

        assertThatThrownBy(() -> replayService.replay(failed.id()))
                .isInstanceOf(ReplayConflictException.class);
        assertThat(journal.find(failed.id()).orElseThrow().replay()).isNull();
    }

    @Test
    @DisplayName("replay of a TEXT_TRANSFORM entry is VERIFIED and leaves the original result untouched")
    void textTransformReplayIsVerified() {
        TextTransformResponse result = auditedOperations.transform(
                new TextTransformRequest("Café déjà vu!", "SLUG"));
        assertThat(result.result()).isEqualTo("cafe-deja-vu");

        AuditEntry original = journal.list(1, null).get(0);
        assertThat(original.operation()).isEqualTo(OperationType.TEXT_TRANSFORM);
        assertThat(original.state()).isEqualTo(JournalState.SUCCEEDED);
        Instant completedAt = original.completedAt();

        AuditEntry replayed = replayService.replay(original.id());

        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.replay().detail()).isEqualTo("results match");
        assertThat(replayed.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(replayed.completedAt()).isEqualTo(completedAt);
        assertThat(replayed.result()).isEqualTo(original.result());
        assertThat(replayed.result().get("result").asText()).isEqualTo("cafe-deja-vu");
        assertThat(replayed.result().get("operation").asText()).isEqualTo("SLUG");
        assertThat(replayed.result().get("changed").asBoolean()).isTrue();
        assertThat(journal.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stored result that differs from the replay produces MISMATCH")
    void shouldReportMismatchWithoutRewritingResult() {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-replay01");
        journal.succeed(pending.id(), new HashResponse("SHA-256", "tampered", false));

        AuditEntry replayed = replayService.replay(pending.id());

        assertThat(replayed.replay().state()).isEqualTo(ReplayState.MISMATCH);
        assertThat(replayed.result().get("digest").asText()).isEqualTo("tampered");
        assertThat(replayed.replay().detail()).contains("/digest");
    }

    @Test
    @DisplayName("replaying a FAILED entry is rejected with REPLAY_CONFLICT and keeps the entry unchanged")
    void replayingFailedEntryConflicts() {
        HashRequest unsupported = new HashRequest("NOPE-1", "Hello World", null);
        assertThatThrownBy(() -> auditedOperations.hash(unsupported))
                .isInstanceOf(IllegalArgumentException.class);

        AuditEntry failed = journal.list(1, null).get(0);
        assertThat(failed.state()).isEqualTo(JournalState.FAILED);
        assertThat(failed.result()).isNull();
        assertThat(failed.errorCode()).isEqualTo("BAD_REQUEST");
        Instant completedAt = failed.completedAt();

        assertThatThrownBy(() -> replayService.replay(failed.id()))
                .isInstanceOf(ReplayConflictException.class);

        AuditEntry after = journal.find(failed.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.FAILED);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.replay()).isNull();
    }

    @Test
    @DisplayName("non-deterministic operations are rejected with REPLAY_CONFLICT")
    void shouldRejectNonDeterministicReplay() {
        auditedOperations.current("UTC");
        AuditEntry entry = journal.list(1, null).get(0);
        assertThat(entry.operation()).isEqualTo(OperationType.DATETIME_CURRENT);

        assertThatThrownBy(() -> replayService.replay(entry.id()))
                .isInstanceOf(ReplayConflictException.class);
        assertThat(journal.find(entry.id()).orElseThrow().replay()).isNull();
    }

    @Test
    @DisplayName("an unknown id is reported as AuditEntryNotFoundException")
    void shouldRejectUnknownId() {
        assertThatThrownBy(() -> replayService.replay("does-not-exist"))
                .isInstanceOf(AuditEntryNotFoundException.class);
    }

    @Test
    @DisplayName("a journal transition fault never masks the operation outcome")
    void journalTransitionFaultDoesNotMaskOutcome() {
        AuditJournalService faulty = org.mockito.Mockito.mock(AuditJournalService.class);
        AuditEntry pending = new AuditEntry("entry-1", "req-replay01", OperationType.CRYPTO_HASH,
                JournalState.PENDING, Instant.now(), null, null, null, null, true, null);
        org.mockito.Mockito.when(faulty.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-replay01"))
                .thenReturn(pending);
        org.mockito.Mockito.when(faulty.succeed(org.mockito.ArgumentMatchers.eq("entry-1"),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("entry entry-1 was evicted"));
        AuditedOperations faultyOperations = new AuditedOperations(new DateTimeService(), new CryptoService(),
                new JsonService(objectMapper), new TextService(), faulty);

        HashResponse response = faultyOperations.hash(hashRequest());

        assertThat(response.digest()).isEqualTo(new CryptoService().hash(hashRequest()).digest());
    }

    @Test
    @DisplayName("the entry request view never exposes the original secret key")
    void shouldNotExposeSecretsInReplayResponse() {
        auditedOperations.hash(new HashRequest("SHA-256", "Hello World", "top-secret"));
        AuditEntry entry = journal.list(1, null).get(0);

        AuditEntry replayed = replayService.replay(entry.id());

        assertThat(replayed.request().get("secretKey").asText()).isEqualTo("***");
        assertThat(replayed.toString()).doesNotContain("top-secret");
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
    }

    @Test
    @DisplayName("concurrent replays: exactly one invocation records VERIFIED, the loser gets REPLAY_CONFLICT")
    void concurrentReplaysHaveASingleWinner() throws Exception {
        HashResponse expected = new CryptoService().hash(hashRequest());
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-replay01");
        journal.succeed(pending.id(), expected);

        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AuditedOperations slow = mock(AuditedOperations.class);
        when(slow.replayInvoke(any(), any())).thenAnswer(call -> {
            invocations.incrementAndGet();
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return expected;
        });
        AuditReplayService service = new AuditReplayService(journal, slow, objectMapper);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AuditEntry> first = pool.submit(() -> service.replay(pending.id()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AuditEntry> second = pool.submit(() -> service.replay(pending.id()));
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ReplayConflictException.class);

            release.countDown();
            AuditEntry replayed = first.get(5, TimeUnit.SECONDS);
            assertThat(invocations.get()).isEqualTo(1);
            assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        AuditEntry after = journal.find(pending.id()).orElseThrow();
        assertThat(after.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(after.replay().detail()).isEqualTo("results match");
        assertThat(journal.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a throwing replayInvoke records FAILED and a later replay still succeeds")
    void failedReplayIsRecordedAndCanBeRetried() {
        HashResponse expected = new CryptoService().hash(hashRequest());
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "req-replay01");
        AuditEntry succeeded = journal.succeed(pending.id(), expected);

        AuditedOperations broken = mock(AuditedOperations.class);
        when(broken.replayInvoke(any(), any())).thenThrow(new IllegalStateException("boom"));
        AuditReplayService brokenService = new AuditReplayService(journal, broken, objectMapper);

        assertThatThrownBy(() -> brokenService.replay(pending.id()))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("boom");

        AuditEntry afterFailure = journal.find(pending.id()).orElseThrow();
        assertThat(afterFailure.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(afterFailure.result()).isEqualTo(succeeded.result());
        assertThat(afterFailure.completedAt()).isEqualTo(succeeded.completedAt());
        assertThat(afterFailure.replay().state()).isEqualTo(ReplayState.FAILED);
        assertThat(afterFailure.replay().detail()).isNotNull().contains("boom");

        AuditEntry replayed = replayService.replay(pending.id());
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
        assertThat(replayed.replay().detail()).isEqualTo("results match");
        assertThat(replayed.state()).isEqualTo(JournalState.SUCCEEDED);
    }
}
