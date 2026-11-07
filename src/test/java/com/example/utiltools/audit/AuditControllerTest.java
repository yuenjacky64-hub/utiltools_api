package com.example.utiltools.audit;

import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.audit.service.ReplayClaim;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.controller.CryptoController;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.ResilienceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

@WebMvcTest({AuditController.class, CryptoController.class})
@Import({AuditJournalService.class, AuditedOperations.class, AuditReplayService.class,
        CryptoService.class, DateTimeService.class, JsonService.class, TextService.class, ResilienceConfig.class,
        GlobalExceptionHandler.class, AuditTestSupport.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditJournalService journal;

    @Autowired
    private AuditedOperations auditedOperations;

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries - returns an empty list and echoes the request id")
    void shouldListEmptyJournal() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/entries").header("X-Request-Id", "audit-listing01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "audit-listing01"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").value("audit-listing01"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries?state=FAILED - filters by state")
    void filtersByStateAndShowsNullResultForFailedEntries() throws Exception {
        AuditEntry failed = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("NOPE-1", "Hello World", null), "audit-filter001");
        journal.fail(failed.id(), "BAD_REQUEST");
        AuditEntry succeeded = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", null), "audit-filter002");
        journal.succeed(succeeded.id(), new HashResponse("SHA-256", "abc", false));

        MvcResult listed = mockMvc.perform(get("/api/v1/utils/audit/entries")
                        .param("state", "FAILED").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> data = com.jayway.jsonpath.JsonPath.read(
                listed.getResponse().getContentAsString(), "$.data");
        assertThat(data).extracting(row -> row.get("requestId")).doesNotContain("audit-filter002");
        assertThat(data).allSatisfy(row -> assertThat(row.get("state")).isEqualTo("FAILED"));

        Map<String, Object> entry = data.stream()
                .filter(row -> "audit-filter001".equals(row.get("requestId")))
                .findFirst().orElseThrow();
        assertThat(entry.get("id")).isEqualTo(failed.id());
        assertThat(entry.get("errorCode")).isEqualTo("BAD_REQUEST");
        assertThat(entry.get("completedAt")).isNotNull();
        assertThat(entry.get("result")).isNull();
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries/{id} - returns the entry with a redacted request")
    void shouldReturnSingleEntry() throws Exception {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", "super-secret"), "audit-single001");
        journal.succeed(entry.id(), new HashResponse("SHA-256", "abc", false));

        mockMvc.perform(get("/api/v1/utils/audit/entries/{id}", entry.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(entry.id()))
                .andExpect(jsonPath("$.data.request.secretKey").value("***"))
                .andExpect(jsonPath("$.data.request.content").value("Hello World"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries/{id} - unknown id returns 404 NOT_FOUND")
    void shouldReturn404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/entries/{id}", "missing-entry").header("X-Request-Id", "audit-missing01"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "audit-missing01"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("audit-missing01"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/entries/{id}/replay - FAILED entry returns 409 REPLAY_CONFLICT")
    void shouldConflictOnFailedEntryReplay() throws Exception {
        AuditEntry failed = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("NOPE-1", "Hello World", null), "audit-conflict01");
        journal.fail(failed.id(), "BAD_REQUEST");

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", failed.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REPLAY_CONFLICT"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/entries/{id}/replay - SUCCEEDED deterministic entry is VERIFIED")
    void shouldReplayVerifiedEntry() throws Exception {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", null), "audit-replay001");
        journal.succeed(entry.id(), new CryptoService().hash(new HashRequest("SHA-256", "Hello World", null)));

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entry.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andExpect(jsonPath("$.data.replay.leaseExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("leaseExpiresAt");
        assertThat(journal.find(entry.id()).orElseThrow().replay().leaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/entries/{id}/replay - non-deterministic entry returns 409")
    void shouldConflictOnNonDeterministicReplay() throws Exception {
        AuditEntry entry = journal.begin(OperationType.DATETIME_CURRENT, java.util.Map.of("timeZone", "UTC"),
                "audit-nondet001");
        journal.succeed(entry.id(), new DateTimeService().getCurrentTime("UTC"));
        assertThat(entry.operation()).isEqualTo(OperationType.DATETIME_CURRENT);

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entry.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REPLAY_CONFLICT"));
        assertThat(journal.find(entry.id()).orElseThrow().state()).isEqualTo(JournalState.SUCCEEDED);
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries?state=BANANA - invalid enum value returns 400 BAD_REQUEST")
    void invalidStateParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/entries").param("state", "BANANA")
                        .header("X-Request-Id", "audit-badstate1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "audit-badstate1"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("audit-badstate1"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/entries?limit=abc - non-numeric limit returns 400 BAD_REQUEST")
    void nonNumericLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/entries").param("limit", "abc")
                        .header("X-Request-Id", "audit-badlimit1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("audit-badlimit1"));
    }

    @Test
    @DisplayName("POSTing an unsupported hash algorithm over HTTP - 400 VALIDATION_ERROR envelope and no CRYPTO_HASH audit row")
    void failedHashIsRejectedAtBoundaryWithoutAuditRow() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .header("X-Request-Id", "failed-hash-hdr1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "NOPE-1", "content": "Hello World"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "failed-hash-hdr1"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("failed-hash-hdr1"));

        MvcResult listed = mockMvc.perform(get("/api/v1/utils/audit/entries").param("state", "FAILED")
                        .header("X-Request-Id", "failed-hash-hdr1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("failed-hash-hdr1"))
                .andReturn();

        List<Map<String, Object>> data = com.jayway.jsonpath.JsonPath.read(
                listed.getResponse().getContentAsString(), "$.data");
        assertThat(data).allSatisfy(row -> assertThat(row.get("state")).isEqualTo("FAILED"));
        List<Map<String, Object>> mine = data.stream()
                .filter(row -> "failed-hash-hdr1".equals(row.get("requestId")))
                .toList();
        assertThat(mine).isEmpty();
    }

    @Test
    @DisplayName("no audit response - list, find, or replay - ever contains the submitted secretKey value")
    void auditResponsesNeverExposeSecretKey() throws Exception {
        RequestContext.set("secret-check-01");
        try {
            auditedOperations.hash(new HashRequest("SHA-256", "Hello World", "super-secret-value"));
        } finally {
            RequestContext.clear();
        }

        MvcResult listed = mockMvc.perform(get("/api/v1/utils/audit/entries").param("limit", "200"))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> data = com.jayway.jsonpath.JsonPath.read(
                listed.getResponse().getContentAsString(), "$.data");
        String id = data.stream()
                .filter(row -> "secret-check-01".equals(row.get("requestId")))
                .map(row -> (String) row.get("id")).findFirst().orElseThrow();

        MvcResult found = mockMvc.perform(get("/api/v1/utils/audit/entries/{id}", id))
                .andExpect(status().isOk()).andReturn();
        MvcResult replayed = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk()).andReturn();

        String everything = listed.getResponse().getContentAsString()
                + found.getResponse().getContentAsString()
                + replayed.getResponse().getContentAsString();
        assertThat(everything).doesNotContain("super-secret-value");
        assertThat(everything).contains("\"secretKey\":\"***\"");
    }

    @Test
    @DisplayName("POST replay - an entry with a live claim returns 409 REPLAY_CONFLICT")
    void shouldConflictOnActiveClaim() throws Exception {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", null), "audit-claim0001");
        journal.succeed(entry.id(), new CryptoService().hash(new HashRequest("SHA-256", "Hello World", null)));
        ReplayClaim claim = journal.claimReplay(entry.id());

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entry.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REPLAY_CONFLICT"))
                .andExpect(jsonPath("$.success").value(false));

        AuditEntry after = journal.find(entry.id()).orElseThrow();
        assertThat(after.replay().state()).isEqualTo(ReplayState.IN_PROGRESS);
        assertThat(journal.completeReplay(entry.id(), claim.ownerToken(), ReplayState.VERIFIED, "owner"))
                .isTrue();
        assertThat(journal.find(entry.id()).orElseThrow().replay().state())
                .isEqualTo(ReplayState.VERIFIED);
    }

    @Test
    @DisplayName("GET entries/{id} - an IN_PROGRESS claim is visible with its lease expiry and no owner token")
    void shouldExposeInProgressClaimWithoutOwnerToken() throws Exception {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", null), "audit-claim0002");
        journal.succeed(entry.id(), new CryptoService().hash(new HashRequest("SHA-256", "Hello World", null)));
        ReplayClaim claim = journal.claimReplay(entry.id());

        MvcResult result = mockMvc.perform(get("/api/v1/utils/audit/entries/{id}", entry.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.replay.leaseExpiresAt").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(claim.ownerToken());
    }
}
