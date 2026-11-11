package com.example.utiltools.jobs;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.ratelimit.ClientKeyResolver;
import com.example.utiltools.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @DisplayName("DELETE /api/v1/utils/jobs/{jobId} - should return 200 CANCELLING when cancelling a RUNNING job")
    void shouldReturn200WithCancellingWhenCancellingRunningJob() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant startedAt = Instant.parse("2026-11-06T10:00:01Z");
        Instant completedAt = Instant.parse("2026-11-06T10:00:05Z");
        BatchHashJobResponse cancelling = new BatchHashJobResponse("job-running-2", "controller-cancel-key",
                JobStatus.CANCELLING, 2, null, null, createdAt, startedAt, null, 1, 3,
                List.of(new JobAttempt(1, JobStatus.CANCELLING, startedAt, null, null)));
        BatchHashJobResponse cancelled = new BatchHashJobResponse("job-running-2", "controller-cancel-key",
                JobStatus.CANCELLED, 2, null, null, createdAt, startedAt, completedAt, 1, 3,
                List.of(new JobAttempt(1, JobStatus.CANCELLED, startedAt, completedAt, null)));
        doReturn(cancelling).when(jobService).cancel("job-running-2");
        doReturn(cancelled).when(jobService).getJob("job-running-2");

        mockMvc.perform(delete("/api/v1/utils/jobs/job-running-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job cancelled"))
                .andExpect(jsonPath("$.data.jobId").value("job-running-2"))
                .andExpect(jsonPath("$.data.status").value("CANCELLING"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist())
                .andExpect(jsonPath("$.data.completedItems").value(0))
                .andExpect(jsonPath("$.data.results").doesNotExist());
        // A repeated DELETE is idempotent: same snapshot, same 200.
        mockMvc.perform(delete("/api/v1/utils/jobs/job-running-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"));

        // Once the worker hits its checkpoint the poll projects the terminal CANCELLED snapshot.
        mockMvc.perform(get("/api/v1/utils/jobs/job-running-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.completedAt").value("2026-11-06T10:00:05Z"))
                .andExpect(jsonPath("$.data.completedItems").value(0))
                .andExpect(jsonPath("$.data.results").doesNotExist());
        verify(jobService, times(2)).cancel("job-running-2");
    }

    @Test
    @DisplayName("DELETE /api/v1/utils/jobs/{jobId} - should map cancelling a COMPLETED job to 409 JOB_CANCELLATION_CONFLICT")
    void shouldReturn409WhenCancellationConflicts() throws Exception {
        doThrow(new JobCancellationConflictException("job-completed-9", JobStatus.COMPLETED))
                .when(jobService).cancel("job-completed-9");

        mockMvc.perform(delete("/api/v1/utils/jobs/job-completed-9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_CANCELLATION_CONFLICT"))
                .andExpect(jsonPath("$.details.jobId").value("job-completed-9"))
                .andExpect(jsonPath("$.details.status").value("COMPLETED"));
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
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - should expose completedItems additively beside every existing field")
    void shouldExposeCompletedItemsInJobSnapshot() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant failedAt = Instant.parse("2026-11-06T10:00:02Z");
        // A FAILED job that checkpointed two of its four items: the count is public, the digests are not.
        BatchHashJobResponse failed = new BatchHashJobResponse("job-checkpoint-1", "controller-checkpoint-key",
                JobStatus.FAILED, 4, null, "IllegalStateException: transient", createdAt, createdAt, failedAt,
                1, 3, List.of(new JobAttempt(1, JobStatus.FAILED, createdAt, failedAt,
                        "IllegalStateException: transient", null, 0)),
                null, null, 2);
        doReturn(failed).when(jobService).getJob("job-checkpoint-1");

        mockMvc.perform(get("/api/v1/utils/jobs/job-checkpoint-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value("job-checkpoint-1"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("controller-checkpoint-key"))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.itemCount").value(4))
                .andExpect(jsonPath("$.data.completedItems").value(2))
                .andExpect(jsonPath("$.data.results").doesNotExist())
                .andExpect(jsonPath("$.data.error").value("IllegalStateException: transient"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.startedAt").exists())
                .andExpect(jsonPath("$.data.completedAt").exists())
                .andExpect(jsonPath("$.data.attempt").value(1))
                .andExpect(jsonPath("$.data.maxAttempts").value(3))
                .andExpect(jsonPath("$.data.attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.data.attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.attempts[0].resumedFrom").value(0));
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

    /** DOD-02 / REQ-01, REQ-03, REQ-04: a running job renews its lease with no request body. */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/heartbeat - should return 200 with the renewed snapshot")
    void shouldReturn200WhenHeartbeatRenewsLease() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        Instant renewedLease = Instant.parse("2026-11-06T10:01:00Z");
        BatchHashJobResponse renewed = new BatchHashJobResponse("job-running-1", "controller-heartbeat-key",
                JobStatus.RUNNING, 2, null, null, createdAt, createdAt, null, 1, 3,
                List.of(new JobAttempt(1, JobStatus.RUNNING, createdAt, null, null)),
                renewedLease, createdAt.plusSeconds(30));
        doReturn(renewed).when(jobService).heartbeat("job-running-1");

        // No content type and no body: the endpoint takes only the path variable (REQ-01).
        mockMvc.perform(post("/api/v1/utils/jobs/job-running-1/heartbeat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Execution lease renewed"))
                .andExpect(jsonPath("$.data.jobId").value("job-running-1"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.attempt").value(1))
                .andExpect(jsonPath("$.data.executionLeaseExpiresAt").value("2026-11-06T10:01:00Z"))
                .andExpect(jsonPath("$.data.lastHeartbeatAt").value("2026-11-06T10:00:30Z"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist())
                .andExpect(jsonPath("$.data.results").doesNotExist());

        verify(jobService).heartbeat("job-running-1");
    }

    /** DOD-02 / REQ-02: an unknown job id is 404 JOB_NOT_FOUND (real service, no stubbing). */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/heartbeat - should return 404 JOB_NOT_FOUND for an unknown job id")
    void shouldReturn404WhenHeartbeatOnUnknownJob() throws Exception {
        mockMvc.perform(post("/api/v1/utils/jobs/does-not-exist/heartbeat"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("does-not-exist")));
    }

    /** DOD-02 / REQ-02: a non-RUNNING job is 409 JOB_RETRY_CONFLICT. */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/heartbeat - should map a non-RUNNING job to 409 JOB_RETRY_CONFLICT")
    void shouldReturn409WhenHeartbeatOnNonRunningJob() throws Exception {
        doThrow(new JobRetryConflictException("job-completed-1", JobStatus.COMPLETED, 1, 3))
                .when(jobService).heartbeat("job-completed-1");

        mockMvc.perform(post("/api/v1/utils/jobs/job-completed-1/heartbeat"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_RETRY_CONFLICT"))
                .andExpect(jsonPath("$.details.jobId").value("job-completed-1"))
                .andExpect(jsonPath("$.details.status").value("COMPLETED"));
    }

    /** DOD-02 / REQ-02: an already-expired lease is 400 BAD_REQUEST, not a silent renewal. */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/heartbeat - should map an expired lease to 400 BAD_REQUEST")
    void shouldReturn400WhenHeartbeatLeaseExpired() throws Exception {
        doThrow(new IllegalArgumentException(
                "Execution lease for job job-running-1 expired at 2026-11-06T10:00:10Z; the reaper owns it now"))
                .when(jobService).heartbeat("job-running-1");

        mockMvc.perform(post("/api/v1/utils/jobs/job-running-1/heartbeat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("expired at 2026-11-06T10:00:10Z")));
    }

    /** DOD-02: the four heartbeat outcomes answer 200 / 404 / 409 / 400 on one route snapshot. */
    @Test
    @DisplayName("POST /api/v1/utils/jobs/{jobId}/heartbeat - 200, 404, 409 and 400 answer in one run")
    void shouldReturn200Then404Then409Then400ForHeartbeats() throws Exception {
        Instant createdAt = Instant.parse("2026-11-06T10:00:00Z");
        doReturn(new BatchHashJobResponse("job-running-2", null, JobStatus.RUNNING, 1, null, null,
                createdAt, createdAt, null, 1, 3,
                List.of(new JobAttempt(1, JobStatus.RUNNING, createdAt, null, null)),
                createdAt.plusSeconds(60), createdAt.plusSeconds(20)))
                .when(jobService).heartbeat("job-running-2");
        doThrow(new JobRetryConflictException("job-queued-2", JobStatus.QUEUED, 1, 3))
                .when(jobService).heartbeat("job-queued-2");
        doThrow(new IllegalArgumentException("Execution lease for job job-running-3 expired at 2026-11-06T10:00:05Z"))
                .when(jobService).heartbeat("job-running-3");

        mockMvc.perform(post("/api/v1/utils/jobs/job-running-2/heartbeat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        mockMvc.perform(post("/api/v1/utils/jobs/never-submitted/heartbeat"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/utils/jobs/job-queued-2/heartbeat"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("JOB_RETRY_CONFLICT"))
                .andExpect(jsonPath("$.details.status").value("QUEUED"));
        mockMvc.perform(post("/api/v1/utils/jobs/job-running-3/heartbeat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    /** DOD-13 / REQ-01: deadlineSeconds is validated to 1..3600 at the HTTP boundary. */
    @ParameterizedTest(name = "deadlineSeconds={0} is rejected")
    @ValueSource(ints = {0, -5, 3601})
    @DisplayName("POST /api/v1/utils/jobs/hash - should reject an out-of-range deadlineSeconds with 400")
    void shouldReturn400WhenDeadlineSecondsOutOfRange(int deadlineSeconds) throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"],
                    "deadlineSeconds": %d
                }
                """.formatted(deadlineSeconds);

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("deadlineSeconds"))));
    }

    /** DOD-14 / REQ-02: deadlineAt is exposed on both the single-job and the list read paths. */
    @Test
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - should expose deadlineAt for a live job")
    void shouldExposeDeadlineAtOnJobStatus() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha", "beta"],
                    "deadlineSeconds": 60
                }
                """;

        MvcResult accepted = mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.data.deadlineAt", org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")))
                .andReturn();
        String jobId = com.jayway.jsonpath.JsonPath.read(accepted.getResponse().getContentAsString(),
                "$.data.jobId");

        mockMvc.perform(get("/api/v1/utils/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.deadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.data.deadlineAt", org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")));

        mockMvc.perform(get("/api/v1/utils/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobId").value(jobId))
                .andExpect(jsonPath("$.data[0].deadlineAt").isNotEmpty());
    }
    // ---------------------------------------------------------------------------------------------
    // Auto-failover budget on the polling payload (REQ-07, DOD-06)
    // ---------------------------------------------------------------------------------------------

    private static final long AUTO_FAILOVER_LEASE_SECONDS = 30L;
    private static final long AUTO_FAILOVER_AWAIT_SECONDS = 10L;
    private static final String REDRIVEN_JOB_ID = "job-redriven-1";
    private static final String PRE_FEATURE_JOB_ID = "job-pre-feature-1";

    /**
     * DOD-06 / REQ-07: {@code autoFailovers} is additive on the polling payload - the redriven job
     * reports it next to the unchanged projections (checkpoint, deadline, results, attempts), a
     * response built through the pre-feature 18-component constructor still reports {@code 0}, and
     * {@link BatchHashJobService#retry(String)} neither increments nor resets the counter that a
     * lease-expiry failover consumed.
     *
     * <p>The snapshots come from a real service instance (a one-worker service blocked inside the gate
     * of {@link GatedCryptoService}) that the spy bean delegates to, so the JSON asserted below is the
     * production projection of a genuinely redriven job rather than a hand-built response.</p>
     */
    @Test
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - should expose autoFailovers additively without changing retry semantics")
    void shouldExposeAutoFailoversAdditivelyOnJobResponse() throws Exception {
        GatedCryptoService crypto = new GatedCryptoService("stalled", "resumed");
        TestClock clock = new TestClock(Instant.parse("2026-11-11T03:00:00Z"));
        BatchHashJobService local = new BatchHashJobService(crypto, 1, 4, 300, 3,
                AUTO_FAILOVER_LEASE_SECONDS, 5000L, 0L,
                BatchHashJobService.DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, 0L, 1, clock);
        try {
            // A response built the pre-feature way: no autoFailovers component reachable, so the
            // additive field must serialise as 0 without disturbing any existing projection.
            BatchHashJobResponse preFeature = new BatchHashJobResponse(PRE_FEATURE_JOB_ID, null,
                    JobStatus.QUEUED, 2, null, null, Instant.parse("2026-11-11T03:00:00Z"), null, null,
                    2, 3, List.of(), null, null, 0, Instant.parse("2026-11-11T03:30:00Z"),
                    List.of(), List.of());
            doAnswer(invocation -> {
                String jobId = invocation.getArgument(0);
                return PRE_FEATURE_JOB_ID.equals(jobId) ? preFeature : local.getJob(jobId);
            }).when(jobService).getJob(anyString());
            doAnswer(invocation -> local.retry(invocation.getArgument(0)))
                    .when(jobService).retry(anyString());

            String jobId = local
                    .submit(new BatchHashRequest("SHA-256", List.of("stalled", "resumed"), null, null),
                            "198.51.100.42")
                    .jobId();
            assertThat(crypto.awaitEntered("stalled"))
                    .as("attempt 1 must block inside the service gate")
                    .isTrue();
            awaitStatus(local, jobId, JobStatus.RUNNING);

            // 1) Lease expiry with the failover budget available: the job is redriven to QUEUED/attempt 2
            // and the only worker is still stuck in attempt 1, so the row stays observable as QUEUED.
            clock.advanceSeconds(AUTO_FAILOVER_LEASE_SECONDS);
            assertThat(local.reapExpiredExecutions(clock.instant())).isEqualTo(1);
            BatchHashJobResponse redriven = local.getJob(jobId);
            assertThat(redriven.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(redriven.attempt()).isEqualTo(2);
            assertThat(redriven.autoFailovers()).isEqualTo(1);

            mockMvc.perform(get("/api/v1/utils/jobs/" + jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.jobId").value(jobId))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.attempt").value(2))
                    .andExpect(jsonPath("$.data.autoFailovers").value(1))
                    .andExpect(jsonPath("$.data.completedItems").value(0))
                    .andExpect(jsonPath("$.data.itemCount").value(2))
                    .andExpect(jsonPath("$.data.maxAttempts").value(3))
                    .andExpect(jsonPath("$.data.results").doesNotExist())
                    .andExpect(jsonPath("$.data.deadlineAt").doesNotExist())
                    .andExpect(jsonPath("$.data.executionLeaseExpiresAt").doesNotExist())
                    .andExpect(jsonPath("$.data.lastHeartbeatAt").doesNotExist())
                    .andExpect(jsonPath("$.data.error").doesNotExist())
                    .andExpect(jsonPath("$.data.attempts[0].status").value("FAILED"))
                    .andExpect(jsonPath("$.data.attempts[0].error").value(BatchHashJobService.EXECUTION_LEASE_EXPIRED));

            // Additive, not a re-typed payload: the pre-feature constructor shape reports 0.
            mockMvc.perform(get("/api/v1/utils/jobs/" + PRE_FEATURE_JOB_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.autoFailovers").value(0))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.attempt").value(2))
                    .andExpect(jsonPath("$.data.maxAttempts").value(3))
                    .andExpect(jsonPath("$.data.completedItems").value(0))
                    .andExpect(jsonPath("$.data.deadlineAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.results").doesNotExist());

            // 2) Budget spent (maxAutoFailovers = 1): the next lapsed lease is terminal again.
            crypto.release("stalled");
            assertThat(crypto.awaitEntered("resumed"))
                    .as("attempt 2 must resume from the checkpoint and block on the next item")
                    .isTrue();
            awaitStatus(local, jobId, JobStatus.RUNNING);
            clock.advanceSeconds(AUTO_FAILOVER_LEASE_SECONDS);
            assertThat(local.reapExpiredExecutions(clock.instant())).isEqualTo(1);
            BatchHashJobResponse exhausted = local.getJob(jobId);
            assertThat(exhausted.status()).isEqualTo(JobStatus.FAILED);
            assertThat(exhausted.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
            assertThat(exhausted.attempt()).isEqualTo(2);
            assertThat(exhausted.autoFailovers()).isEqualTo(1);

            // 3) Manual retry must neither increment nor reset the consumed failover budget.
            mockMvc.perform(post("/api/v1/utils/jobs/" + jobId + "/retry"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.attempt").value(3))
                    .andExpect(jsonPath("$.data.autoFailovers").value(1));
            mockMvc.perform(get("/api/v1/utils/jobs/" + jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attempt").value(3))
                    .andExpect(jsonPath("$.data.autoFailovers").value(1));
        } finally {
            crypto.release("resumed");
            local.shutdown();
        }
    }

    private static void awaitStatus(BatchHashJobService service, String jobId, JobStatus expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(AUTO_FAILOVER_AWAIT_SECONDS);
        JobStatus seen = null;
        while (Instant.now().isBefore(deadline)) {
            seen = service.getJob(jobId).status();
            if (seen == expected) {
                return;
            }
            Thread.sleep(5L);
        }
        assertThat(seen).as("job %s never reached %s", jobId, expected).isEqualTo(expected);
    }

    /** Test clock the service derives its lease, heartbeat and reap instants from. */
    private static final class TestClock extends Clock {

        private final AtomicReference<Instant> now;

        private TestClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        private void advanceSeconds(long seconds) {
            now.updateAndGet(current -> current.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** {@link CryptoService} whose listed items block inside {@code hash} until released. */
    private static final class GatedCryptoService extends CryptoService {

        private final Map<String, Gate> gates = new ConcurrentHashMap<>();

        private GatedCryptoService(String... gatedItems) {
            for (String item : gatedItems) {
                gates.put(item, new Gate());
            }
        }

        private boolean awaitEntered(String item) throws InterruptedException {
            Gate gate = gates.get(item);
            return gate != null && gate.entered.await(AUTO_FAILOVER_AWAIT_SECONDS, TimeUnit.SECONDS);
        }

        private void release(String item) {
            Gate gate = gates.get(item);
            if (gate != null) {
                gate.released.countDown();
            }
        }

        @Override
        public HashResponse hash(HashRequest request) {
            Gate gate = gates.get(request.content());
            if (gate != null) {
                gate.entered.countDown();
                try {
                    gate.released.await(AUTO_FAILOVER_AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.hash(request);
        }

        private static final class Gate {
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
        }
    }

}
