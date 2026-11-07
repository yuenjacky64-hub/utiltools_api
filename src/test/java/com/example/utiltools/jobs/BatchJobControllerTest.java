package com.example.utiltools.jobs;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.ratelimit.ClientKeyResolver;
import com.example.utiltools.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The application class carries @EnableScheduling; in this slice the startup sweep of the
// @Scheduled evictExpiredJobs()/reapExpiredExecutions() would run on a background thread and could
// land between doReturn(...).when(spy) and the request, hijacking the recorded stubbing
// (Mockito CannotStubVoidMethodWithReturnValue) and letting the real getJob answer 404.
// A scheduler bean that never runs a task keeps this slice deterministic.
@WebMvcTest(BatchJobController.class)
@EnableConfigurationProperties(RateLimitProperties.class)
@Import({BatchHashJobService.class, CryptoService.class, GlobalExceptionHandler.class,
        BatchJobControllerTest.ClockConfig.class})
class BatchJobControllerTest {

    /** The application-wide clock (see {@code RateLimitConfig}); slices need their own. */
    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /** Accepts every scheduled registration and never executes it (see class comment). */
        @Bean
        TaskScheduler noOpTaskScheduler() {
            return new NoOpTaskScheduler();
        }
    }

    static final class NoOpTaskScheduler implements TaskScheduler {

        private static final ScheduledFuture<?> NEVER = new ScheduledFuture<>() {
            @Override
            public long getDelay(TimeUnit unit) {
                return Long.MAX_VALUE;
            }

            @Override
            public int compareTo(Delayed other) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return true;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public Object get() {
                throw new UnsupportedOperationException("no-op scheduler");
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException("no-op scheduler");
            }
        };

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return NEVER;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return NEVER;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return NEVER;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return NEVER;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return NEVER;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return NEVER;
        }

        @Override
        @SuppressWarnings("deprecation")
        public Clock getClock() {
            return Clock.systemUTC();
        }
    }

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
                .when(jobService).submit(any(BatchHashRequest.class), anyString());

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
                JobStatus.CANCELLED, 2, null, null, createdAt, null, completedAt, 1, 3,
                List.of(new JobAttempt(1, JobStatus.CANCELLED, null, completedAt, null)));
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

    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - should return 202 with the requeued snapshot")
    void shouldReturn202WhenRetryingFailedJob() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant failedAt = Instant.parse("2026-11-06T10:00:02Z");
        BatchHashJobResponse requeued = new BatchHashJobResponse("job-failed-1", "controller-retry-key",
                JobStatus.QUEUED, 2, null, null, createdAt, null, null, 2, 3,
                List.of(new JobAttempt(1, JobStatus.FAILED, createdAt, failedAt, "IllegalStateException: boom")));
        doReturn(requeued).when(jobService).retry("job-failed-1");

        mockMvc.perform(post("/api/v1/utils/jobs/job-failed-1/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job retried"))
                .andExpect(jsonPath("$.data.jobId").value("job-failed-1"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("controller-retry-key"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attempt").value(2))
                .andExpect(jsonPath("$.data.maxAttempts").value(3))
                .andExpect(jsonPath("$.data.attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.data.attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.attempts[0].error").value("IllegalStateException: boom"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - should map a conflict to 409 JOB_RETRY_CONFLICT")
    void shouldReturn409WhenRetryConflicts() throws Exception {
        doThrow(new JobRetryConflictException("job-running-1", JobStatus.RUNNING, 1, 3))
                .when(jobService).retry("job-running-1");

        mockMvc.perform(post("/api/v1/utils/jobs/job-running-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_RETRY_CONFLICT"))
                .andExpect(jsonPath("$.details.jobId").value("job-running-1"))
                .andExpect(jsonPath("$.details.status").value("RUNNING"))
                .andExpect(jsonPath("$.details.attempt").value(1))
                .andExpect(jsonPath("$.details.maxAttempts").value(3));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - should return 404 JOB_NOT_FOUND for an unknown job id")
    void shouldReturn404WhenRetryingUnknownJob() throws Exception {
        mockMvc.perform(post("/api/v1/utils/jobs/does-not-exist/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - attempt history serializes without a journal bean (auditEntryId omitted)")
    void shouldReturnAttemptHistoryWithoutJournalBean() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant completedAt = Instant.parse("2026-11-06T10:00:01Z");
        BatchHashJobResponse completed = new BatchHashJobResponse("job-completed-1", "controller-history-key",
                JobStatus.COMPLETED, 1, List.of(new HashResponse("SHA-256", "abc123", false)), null,
                createdAt, createdAt, completedAt, 1, 3,
                List.of(new JobAttempt(1, JobStatus.COMPLETED, createdAt, completedAt, null)));
        doReturn(completed).when(jobService).getJob("job-completed-1");

        // The slice wires BatchHashJobService without an AuditJournalService bean, so
        // attemptHistory entries carry a null auditEntryId and Jackson omits the field.
        mockMvc.perform(get("/api/v1/utils/jobs/job-completed-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results[0].digest").value("abc123"))
                .andExpect(jsonPath("$.data.attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.data.attempts[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.attempts[0].auditEntryId").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - the three retry failure modes answer 409, 429 and 404 in one run")
    void shouldReturn409Then429Then404ForRetryFailuresInOneRun() throws Exception {
        doThrow(new JobRetryConflictException("job-completed-1", JobStatus.COMPLETED, 3, 3))
                .when(jobService).retry("job-completed-1");
        doThrow(new JobQueueFullException("Hash job queue is full (worker-count=2, queue-capacity=100); retry after capacity frees"))
                .when(jobService).retry("job-failed-3");
        doThrow(new JobNotFoundException("evicted-1"))
                .when(jobService).retry("evicted-1");

        // Every retry on one snapshot of the route: conflict first, then saturation, then an id the
        // eviction pass has already unregistered. Each call must leave the others' routes intact.
        mockMvc.perform(post("/api/v1/utils/jobs/job-completed-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("JOB_RETRY_CONFLICT"))
                .andExpect(jsonPath("$.details.attempt").value(3))
                .andExpect(jsonPath("$.details.maxAttempts").value(3));
        mockMvc.perform(post("/api/v1/utils/jobs/job-failed-3/retry"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("QUEUE_FULL"));
        mockMvc.perform(post("/api/v1/utils/jobs/evicted-1/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs - should return 200 with a newest-first list envelope")
    void shouldReturn200WithJobList() throws Exception {
        Instant newestStamp = Instant.parse("2026-11-06T10:00:02Z");
        Instant olderStamp = Instant.parse("2026-11-06T10:00:00Z");
        doReturn(List.of(
                new BatchHashJobResponse("job-newest", null, JobStatus.COMPLETED, 1, List.of(),
                        null, newestStamp, newestStamp, newestStamp, 1, 3, List.of()),
                new BatchHashJobResponse("job-older", null, JobStatus.CANCELLED, 2, null,
                        null, olderStamp, null, olderStamp, 1, 3, List.of())))
                .when(jobService).listJobs(null, 20);

        mockMvc.perform(get("/api/v1/utils/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job list retrieved"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].jobId").value("job-newest"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].results").isArray())
                .andExpect(jsonPath("$.data[1].jobId").value("job-older"))
                .andExpect(jsonPath("$.data[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data[1].results").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs - should forward the status filter and the limit")
    void shouldForwardStatusAndLimit() throws Exception {
        doReturn(List.of()).when(jobService).listJobs(JobStatus.RUNNING, 50);

        mockMvc.perform(get("/api/v1/utils/jobs")
                        .param("status", "RUNNING")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(jobService).listJobs(JobStatus.RUNNING, 50);
        verify(jobService, never()).listJobs(JobStatus.RUNNING, 20);
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs - an unknown status answers 400 BAD_REQUEST")
    void shouldReturn400ForUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/utils/jobs").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs - an out-of-range limit is forwarded unchanged and still answers 200")
    void shouldForwardExplicitLimit() throws Exception {
        doReturn(List.of()).when(jobService).listJobs(null, 5000);

        mockMvc.perform(get("/api/v1/utils/jobs").param("limit", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        // The bounded window is the service's job (clamp to [1, 100]); the controller never rewrites it.
        verify(jobService).listJobs(null, 5000);
        verify(jobService, never()).listJobs(null, 100);
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs - an empty registry answers 200 with an empty data array")
    void shouldReturnJobListEmptyArray() throws Exception {
        doReturn(List.of()).when(jobService).listJobs(null, 20);

        mockMvc.perform(get("/api/v1/utils/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job list retrieved"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - should map a rejected requeue to 429 QUEUE_FULL")
    void shouldReturn429WhenRetryQueueFull() throws Exception {
        doThrow(new JobQueueFullException("Hash job queue is full (worker-count=2, queue-capacity=100); retry after capacity frees"))
                .when(jobService).retry("job-failed-2");

        mockMvc.perform(post("/api/v1/utils/jobs/job-failed-2/retry"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("QUEUE_FULL"));
    }
    /**
     * DOD-13 / REQ-04: the quota refusal surfaces as 429 JOB_QUOTA_EXCEEDED with bounded
     * {@code limit}/{@code activeJobs} details and no client identity.
     */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should map an exhausted quota to 429 JOB_QUOTA_EXCEEDED")
    void shouldReturn429WhenQuotaExceeded() throws Exception {
        doThrow(new JobQuotaExceededException(20, 20))
                .when(jobService).submit(any(BatchHashRequest.class), anyString());

        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"]
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.70");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.details.limit").value(20))
                .andExpect(jsonPath("$.details.activeJobs").value(20))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("activeJobs=20")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("203.0.113.70");
    }

    /** DOD-13 / REQ-06: an exhausted quota on the retry endpoint is also 429 JOB_QUOTA_EXCEEDED. */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/retry - should map an exhausted quota to 429")
    void shouldReturn429WhenRetryQuotaExceeded() throws Exception {
        doThrow(new JobQuotaExceededException(3, 3)).when(jobService).retry("job-failed");

        mockMvc.perform(post("/api/v1/utils/jobs/job-failed/retry"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.details.limit").value(3))
                .andExpect(jsonPath("$.details.activeJobs").value(3));
    }

    /**
     * DOD-13 / REQ-02: the quota key is the rate limiter's identity — the remote address, and not an
     * untrusted {@code X-Forwarded-For} header while {@code trust-forwarded-header} is false.
     */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should charge quota to the resolved client key")
    void shouldChargeQuotaToTheRateLimiterClientKey() throws Exception {
        List<String> resolvedKeys = new ArrayList<>();
        doAnswer(invocation -> {
            resolvedKeys.add(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(jobService).submit(any(BatchHashRequest.class), anyString());

        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.71");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.6");
                            return request;
                        })
                        .header(ClientKeyResolver.FORWARDED_HEADER, "203.0.113.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        assertThat(resolvedKeys).containsExactly("203.0.113.71", "198.51.100.6");
    }
}
