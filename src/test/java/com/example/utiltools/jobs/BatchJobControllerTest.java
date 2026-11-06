package com.example.utiltools.jobs;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchJobController.class)
@Import({BatchHashJobService.class, CryptoService.class, GlobalExceptionHandler.class})
class BatchJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private BatchHashJobService jobService;

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should return 202 with a jobId")
    void shouldReturn202WithJobId() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha", "beta"],
                    "idempotencyKey": "controller-key"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.itemCount").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should reject blank items with 400")
    void shouldReturn400WhenItemsBlank() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": [""]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - should return 404 for an unknown job id")
    void shouldReturn404ForUnknownJobId() throws Exception {
        mockMvc.perform(get("/api/v1/utils/jobs/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should map a full queue to 429 QUEUE_FULL")
    void shouldReturn429WhenQueueFull() throws Exception {
        doThrow(new JobQueueFullException("Hash job queue is full (worker-count=2, queue-capacity=100); retry after capacity frees"))
                .when(jobService).submit(any(BatchHashRequest.class));

        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("QUEUE_FULL"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - reused key with a different body is 409 IDEMPOTENCY_CONFLICT")
    void shouldReturn409WhenIdempotencyKeyReusedWithDifferentBody() throws Exception {
        String acceptedBody = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"],
                    "idempotencyKey": "controller-conflict-key"
                }
                """;
        String conflictingBody = """
                {
                    "algorithm": "SHA-256",
                    "items": ["gamma"],
                    "idempotencyKey": "controller-conflict-key"
                }
                """;

        MvcResult accepted = mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptedBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andReturn();
        String jobId = com.jayway.jsonpath.JsonPath.read(accepted.getResponse().getContentAsString(),
                "$.data.jobId");

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("controller-conflict-key")))
                .andExpect(jsonPath("$.details.idempotencyKey").value("controller-conflict-key"))
                .andExpect(jsonPath("$.details.existingJobId").value(jobId));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - identical retry still returns 202 with the same jobId")
    void shouldReturn202WithSameJobIdForIdenticalRetry() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha", "beta"],
                    "secretKey": "s3cret",
                    "idempotencyKey": "controller-identical-key"
                }
                """;

        MvcResult first = mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.itemCount").value(2))
                .andReturn();
        String jobId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),
                "$.data.jobId");

        MvcResult retry = mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        String retryJobId = com.jayway.jsonpath.JsonPath.read(retry.getResponse().getContentAsString(),
                "$.data.jobId");
        assertThat(retryJobId).isEqualTo(jobId);
    }

    @Test
    @DisplayName("DELETE /api/v1/utils/jobs/{jobId} - should return 200 with the CANCELLED snapshot")
    void shouldReturn200WhenCancellingQueuedJob() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant completedAt = Instant.parse("2026-11-06T10:00:01Z");
        BatchHashJobResponse cancelled = new BatchHashJobResponse("job-cancelled-1", "controller-cancel-key",
                JobStatus.CANCELLED, 2, null, null, createdAt, null, completedAt);
        doReturn(cancelled).when(jobService).cancel("job-cancelled-1");
        doReturn(cancelled).when(jobService).getJob("job-cancelled-1");

        mockMvc.perform(delete("/api/v1/utils/jobs/job-cancelled-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job cancelled"))
                .andExpect(jsonPath("$.data.jobId").value("job-cancelled-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.itemCount").value(2))
                .andExpect(jsonPath("$.data.startedAt").doesNotExist())
                .andExpect(jsonPath("$.data.results").doesNotExist());

        // Polling afterwards returns the very same cancelled snapshot.
        mockMvc.perform(get("/api/v1/utils/jobs/job-cancelled-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("job-cancelled-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.completedAt").value("2026-11-06T10:00:01Z"))
                .andExpect(jsonPath("$.data.results").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/utils/jobs/{jobId} - should map a late cancellation to 409 JOB_CANCELLATION_CONFLICT")
    void shouldReturn409WhenCancellationConflicts() throws Exception {
        doThrow(new JobCancellationConflictException("job-running-1", JobStatus.RUNNING))
                .when(jobService).cancel("job-running-1");

        mockMvc.perform(delete("/api/v1/utils/jobs/job-running-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_CANCELLATION_CONFLICT"))
                .andExpect(jsonPath("$.details.jobId").value("job-running-1"))
                .andExpect(jsonPath("$.details.status").value("RUNNING"));
    }

    @Test
    @DisplayName("DELETE /api/v1/utils/jobs/{jobId} - should return 404 JOB_NOT_FOUND for an unknown job id")
    void shouldReturn404WhenCancellingUnknownJob() throws Exception {
        mockMvc.perform(delete("/api/v1/utils/jobs/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }
}
