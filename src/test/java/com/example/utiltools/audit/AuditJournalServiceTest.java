package com.example.utiltools.audit;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditJournalServiceTest {

    private AuditJournalService journal;

    @BeforeEach
    void setUp() {
        journal = new AuditJournalService(new ObjectMapper(), 3, 65536);
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
        AuditJournalService bounded = new AuditJournalService(new ObjectMapper(), 3, 64);
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
    @DisplayName("non-deterministic operations are flagged on the enum")
    void shouldFlagNonDeterministicOperations() {
        assertThat(OperationType.DATETIME_CURRENT.isDeterministic()).isFalse();
        assertThat(OperationType.CRYPTO_UUID.isDeterministic()).isFalse();
        assertThat(OperationType.DATETIME_CONVERT.isDeterministic()).isTrue();
        assertThat(OperationType.CRYPTO_HASH.isDeterministic()).isTrue();
        assertThat(OperationType.JSON_QUERY.isDeterministic()).isTrue();
    }
}
