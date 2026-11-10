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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

    @Test
    @DisplayName("POST /api/v1/utils/audit/verify - aggregates match the bounded per-entry results")
    void shouldVerifyMixedJournalAndReturnMatchingAggregates() throws Exception {
        AuditEntry intact = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", null), "audit-verify001");
        journal.succeed(intact.id(), new CryptoService().hash(new HashRequest("SHA-256", "Hello World", null)));
        AuditEntry tampered = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Goodbye World", null), "audit-verify002");
        journal.succeed(tampered.id(), new HashResponse("SHA-256", "deadbeef", false));

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/verify")
                        .param("limit", "200")
                        .header("X-Request-Id", "audit-verify01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "audit-verify01"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.limit").value(200))
                .andExpect(jsonPath("$.data.results").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Integer scanned = com.jayway.jsonpath.JsonPath.read(body, "$.data.scanned");
        Integer verified = com.jayway.jsonpath.JsonPath.read(body, "$.data.verified");
        Integer mismatch = com.jayway.jsonpath.JsonPath.read(body, "$.data.mismatch");
        Integer failed = com.jayway.jsonpath.JsonPath.read(body, "$.data.failed");
        Integer skipped = com.jayway.jsonpath.JsonPath.read(body, "$.data.skipped");
        List<Map<String, Object>> rows = com.jayway.jsonpath.JsonPath.read(body, "$.data.results");

        assertThat(verified + mismatch + failed + skipped).isEqualTo(scanned);
        assertThat(scanned).isEqualTo(rows.size());

        Map<String, Object> intactRow = rows.stream()
                .filter(row -> intact.id().equals(row.get("entryId"))).findFirst().orElseThrow();
        Map<String, Object> tamperedRow = rows.stream()
                .filter(row -> tampered.id().equals(row.get("entryId"))).findFirst().orElseThrow();
        assertThat(intactRow.get("outcome")).isEqualTo("VERIFIED");
        assertThat(tamperedRow.get("outcome")).isEqualTo("MISMATCH");
        assertThat(intactRow).doesNotContainKey("skipReason");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    @DisplayName("POST /api/v1/utils/audit/verify - an empty journal answers zeroed counts")
    void shouldVerifyEmptyJournalWithZeroedCounts() throws Exception {
        mockMvc.perform(post("/api/v1/utils/audit/verify").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andExpect(jsonPath("$.data.scanned").value(0))
                .andExpect(jsonPath("$.data.verified").value(0))
                .andExpect(jsonPath("$.data.mismatch").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.results").isEmpty())
                .andExpect(jsonPath("$.message").value("Journal verification sweep completed"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/verify - limit=0, limit=-5 and limit=abc answer 400 BAD_REQUEST")
    void shouldRejectNonPositiveAndNonNumericLimits() throws Exception {
        for (String limit : List.of("0", "-5")) {
            mockMvc.perform(post("/api/v1/utils/audit/verify").param("limit", limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("limit must be >= 1")));
        }

        mockMvc.perform(post("/api/v1/utils/audit/verify").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/verify - no stored payloads leave the journal, detail is bounded")
    void verifyResponseOmitsStoredPayloadsAndBoundsDetail() throws Exception {
        AuditEntry entry = journal.begin(OperationType.CRYPTO_HASH,
                new HashRequest("SHA-256", "Hello World", "super-secret-value"), "audit-verify003");
        journal.succeed(entry.id(), new CryptoService().hash(new HashRequest("SHA-256", "Hello World", null)));

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/verify").param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("super-secret-value");
        assertThat(body).doesNotContain("\"request\"");
        assertThat(body).doesNotContain("\"result\"");

        List<Map<String, Object>> rows = com.jayway.jsonpath.JsonPath.read(body, "$.data.results");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsKeys("entryId", "detail");
            assertThat(row.get("detail").toString().length()).isLessThanOrEqualTo(200);
            boolean hasOutcome = row.containsKey("outcome");
            boolean hasSkipReason = row.containsKey("skipReason");
            assertThat(hasOutcome ^ hasSkipReason).as("exactly one of outcome/skipReason").isTrue();
        });
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps - a fresh journal returns an empty tombstone log")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void shouldReturnEmptyReapLog() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/reaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Reap tombstones retrieved successfully"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps - tombstones are returned newest first and never carry payloads")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void shouldListReapLogNewestFirst() throws Exception {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reaps0001");
        journal.reapStalePending(Instant.now().plusSeconds(3600));

        AuditEntry manual = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reaps0002");
        assertThat(journal.requestManualReap(manual.id())).isTrue();
        assertThat(journal.reap()).isEqualTo(1);

        MvcResult result = mockMvc.perform(get("/api/v1/utils/audit/reaps").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Reap tombstones retrieved successfully"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("REAPED"))
                .andExpect(jsonPath("$.data[0].entryId").value(manual.id()))
                .andExpect(jsonPath("$.data[0].reason").value("MANUALLY_REAPED"))
                .andExpect(jsonPath("$.data[1].status").value("REAPED"))
                .andExpect(jsonPath("$.data[1].entryId").value(stale.id()))
                .andExpect(jsonPath("$.data[1].reason").value("STALE_PENDING"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Hello World").doesNotContain("super-secret-key");
        assertThat(body).doesNotContain("\"request\"").doesNotContain("\"result\"");
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps?reason=STALE_PENDING - only matching tombstones are returned")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void shouldFilterReapLogByReason() throws Exception {
        AuditEntry stale = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reaps0003");
        journal.reapStalePending(Instant.now().plusSeconds(3600));

        AuditEntry manual = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reaps0004");
        assertThat(journal.requestManualReap(manual.id())).isTrue();
        assertThat(journal.reap()).isEqualTo(1);

        MvcResult result = mockMvc.perform(get("/api/v1/utils/audit/reaps")
                        .param("reason", "STALE_PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].entryId").value(stale.id()))
                .andExpect(jsonPath("$.data[0].reason").value("STALE_PENDING"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(manual.id());

        mockMvc.perform(get("/api/v1/utils/audit/reaps").param("reason", "OVERSIZE_PAYLOAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps?reason=BANANA - invalid enum value returns 400 BAD_REQUEST")
    void shouldRejectUnknownReapReason() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/reaps").param("reason", "BANANA")
                        .header("X-Request-Id", "audit-badreap1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "audit-badreap1"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'BANANA' for parameter 'reason'"))
                .andExpect(jsonPath("$.requestId").value("audit-badreap1"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps?limit=abc - non-numeric limit returns 400 BAD_REQUEST")
    void shouldRejectNonNumericReapLimit() throws Exception {
        mockMvc.perform(get("/api/v1/utils/audit/reaps").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'abc' for parameter 'limit'"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/audit/reaps - limit is clamped, never rejected")
    void shouldClampReapLogLimit() throws Exception {
        for (String limit : List.of("0", "-7", "100000")) {
            mockMvc.perform(get("/api/v1/utils/audit/reaps").param("limit", limit))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ---- UTILTOOLS-84: POST /api/v1/utils/audit/reaps ----

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps - dryRun defaults to false and answers per-reason counts")
    void manualReapEndpointReturnsReasonCounts() throws Exception {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0001");
        assertThat(journal.requestManualReap(pending.id())).isTrue();

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/reaps")
                        .header("X-Request-Id", "audit-reap0002"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "audit-reap0002"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").value("audit-reap0002"))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.entryId").doesNotExist())
                .andExpect(jsonPath("$.data.scanned").isNumber())
                .andExpect(jsonPath("$.data.reaped").isNumber())
                .andExpect(jsonPath("$.data.replayLeaseExpired").isNumber())
                .andExpect(jsonPath("$.data.manuallyReaped").isNumber())
                .andExpect(jsonPath("$.data.stalePending").isNumber())
                .andExpect(jsonPath("$.data.oversizePayload").isNumber())
                .andReturn();

        Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data");
        int reaped = ((Number) data.get("reaped")).intValue();
        int perReason = ((Number) data.get("replayLeaseExpired")).intValue()
                + ((Number) data.get("manuallyReaped")).intValue()
                + ((Number) data.get("stalePending")).intValue()
                + ((Number) data.get("oversizePayload")).intValue();
        assertThat(reaped).isEqualTo(perReason);
        assertThat(reaped).isGreaterThanOrEqualTo(1);
        assertThat(((Number) data.get("manuallyReaped")).intValue()).isGreaterThanOrEqualTo(1);

        AuditEntry reapedEntry = journal.find(pending.id()).orElseThrow();
        assertThat(reapedEntry.state()).isEqualTo(JournalState.FAILED);
        assertThat(reapedEntry.errorCode()).isEqualTo("MANUALLY_REAPED");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("super-secret-key");
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps?dryRun=true - leaves entry state and GET /reaps tombstones unchanged")
    void dryRunLeavesEntryAndTombstonesUnchanged() throws Exception {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0010");
        assertThat(journal.requestManualReap(pending.id())).isTrue();

        List<Map<String, Object>> tombstonesBefore = reapTombstones();

        mockMvc.perform(post("/api/v1/utils/audit/reaps")
                        .param("dryRun", "true").param("entryId", pending.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.entryId").value(pending.id()))
                .andExpect(jsonPath("$.data.scanned").isNumber())
                .andExpect(jsonPath("$.data.reaped").value(1))
                .andExpect(jsonPath("$.data.manuallyReaped").value(1));

        assertThat(journal.find(pending.id())).contains(pending);
        assertThat(reapTombstones()).isEqualTo(tombstonesBefore);

        mockMvc.perform(post("/api/v1/utils/audit/reaps").param("entryId", pending.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.manuallyReaped").value(1));
        assertThat(journal.find(pending.id()).orElseThrow().state()).isEqualTo(JournalState.FAILED);
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps?entryId=unknown - returns 404 NOT_FOUND")
    void unknownEntryIdAnswers404() throws Exception {
        mockMvc.perform(post("/api/v1/utils/audit/reaps").param("entryId", "no-such-entry")
                        .header("X-Request-Id", "audit-reap0020"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "audit-reap0020"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("audit-reap0020"));

        assertThat(journal.reapLog(200)).noneMatch(record -> "no-such-entry".equals(record.entryId()));
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps?entryId=terminal - returns 409 REAP_CONFLICT and mutates nothing")
    void terminalEntryIdAnswers409ReapConflict() throws Exception {
        AuditEntry succeeded = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0030");
        journal.succeed(succeeded.id(), new HashResponse("SHA-256", "abc", false));
        AuditEntry before = journal.find(succeeded.id()).orElseThrow();

        List<Map<String, Object>> tombstonesBefore = reapTombstones();

        mockMvc.perform(post("/api/v1/utils/audit/reaps").param("entryId", succeeded.id())
                        .header("X-Request-Id", "audit-reap0032"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Request-Id", "audit-reap0032"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("REAP_CONFLICT"))
                .andExpect(jsonPath("$.requestId").value("audit-reap0032"));

        assertThat(journal.find(succeeded.id())).contains(before);
        assertThat(reapTombstones()).isEqualTo(tombstonesBefore);
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps?entryId=pending - one FAILED entry with exactly one tombstone")
    void manualReapEndpointReclaimsTargetedPendingEntry() throws Exception {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0040");

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/reaps")
                        .param("entryId", pending.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.entryId").value(pending.id()))
                .andExpect(jsonPath("$.data.manuallyReaped").value(1))
                .andExpect(jsonPath("$.data.reaped").value(1))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("super-secret-key");

        AuditEntry reaped = journal.find(pending.id()).orElseThrow();
        assertThat(reaped.state()).isEqualTo(JournalState.FAILED);
        assertThat(reaped.errorCode()).isEqualTo("MANUALLY_REAPED");
        assertThat(reaped.result()).isNull();

        MvcResult tombstones = mockMvc.perform(get("/api/v1/utils/audit/reaps")
                        .param("reason", "MANUALLY_REAPED").param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> rows = com.jayway.jsonpath.JsonPath.read(
                tombstones.getResponse().getContentAsString(), "$.data");
        assertThat(rows.stream().filter(row -> pending.id().equals(row.get("entryId")))).hasSize(1);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.get("reason")).isEqualTo("MANUALLY_REAPED"));
        assertThat(tombstones.getResponse().getContentAsString())
                .doesNotContain("ownerToken").doesNotContain("leaseExpiresAt");
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps - appends no journal entry and leaks no stored payload")
    void reapEndpointAppendsNoJournalEntryAndLeaksNothing() throws Exception {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0060");
        assertThat(journal.requestManualReap(pending.id())).isTrue();
        List<AuditEntry> before = journal.list(200, null);

        MvcResult result = mockMvc.perform(post("/api/v1/utils/audit/reaps"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        // no recursive journalling: the pass adds no entry of its own and never grows the journal
        assertThat(journal.list(200, null)).hasSizeLessThanOrEqualTo(before.size());
        assertThat(journal.size()).isLessThanOrEqualTo(before.size());
        assertThat(body).doesNotContain("super-secret-key")
                .doesNotContain("abc123")
                .doesNotContain("ownerToken")
                .doesNotContain("leaseExpiresAt");
    }

    @Test
    @DisplayName("POST /api/v1/utils/audit/reaps - pass counts match the tombstones exposed by GET /reaps")
    void manualPassCountsMatchTombstoneLog() throws Exception {
        AuditEntry pending = journal.begin(OperationType.CRYPTO_HASH, hashRequest(), "audit-reap0050");
        assertThat(journal.requestManualReap(pending.id())).isTrue();

        MvcResult pass = mockMvc.perform(post("/api/v1/utils/audit/reaps"))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(
                pass.getResponse().getContentAsString(), "$.data");
        int reaped = ((Number) data.get("reaped")).intValue();
        assertThat(reaped).isGreaterThanOrEqualTo(1);
        assertThat(((Number) data.get("manuallyReaped")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) data.get("scanned")).intValue())
                .isGreaterThanOrEqualTo(Math.max(journal.size(), 1));
        assertThat(journal.size()).isLessThanOrEqualTo(journal.maxEntries());

        MvcResult tombstones = mockMvc.perform(get("/api/v1/utils/audit/reaps")
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> rows = com.jayway.jsonpath.JsonPath.read(
                tombstones.getResponse().getContentAsString(), "$.data");
        assertThat(rows).hasSize(journal.reapLog(200).size());
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("entryId")).isEqualTo(pending.id());
            assertThat(row.get("reason")).isEqualTo("MANUALLY_REAPED");
            assertThat(row.get("status")).isEqualTo("REAPED");
        });
    }

    /** Tombstones as exposed by GET /api/v1/utils/audit/reaps (data only, no envelope timestamps). */
    private List<Map<String, Object>> reapTombstones() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/utils/audit/reaps").param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data");
    }

    private static HashRequest hashRequest() {
        return new HashRequest("SHA-256", "Hello World", "super-secret-key");
    }
}
