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
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditReplayServiceTest {

    private ObjectMapper objectMapper;
    private AuditJournalService journal;
    private AuditedOperations auditedOperations;
    private AuditReplayService replayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        journal = new AuditJournalService(objectMapper, 200, 65536);
        auditedOperations = new AuditedOperations(new DateTimeService(), new CryptoService(),
                new JsonService(objectMapper), journal);
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
    @DisplayName("the entry request view never exposes the original secret key")
    void shouldNotExposeSecretsInReplayResponse() {
        auditedOperations.hash(new HashRequest("SHA-256", "Hello World", "top-secret"));
        AuditEntry entry = journal.list(1, null).get(0);

        AuditEntry replayed = replayService.replay(entry.id());

        assertThat(replayed.request().get("secretKey").asText()).isEqualTo("***");
        assertThat(replayed.toString()).doesNotContain("top-secret");
        assertThat(replayed.replay().state()).isEqualTo(ReplayState.VERIFIED);
    }
}
