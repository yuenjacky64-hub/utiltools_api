package com.example.utiltools.jobs;

import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.ratelimit.ClientKeyResolver;
import com.example.utiltools.ratelimit.RateLimitProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/utils/jobs")
@Tag(name = "Batch Hash Jobs", description = "Asynchronous, bounded batch hashing with idempotent submission and cooperative job cancellation")
public class BatchJobController {

    private final BatchHashJobService jobService;
    private final RateLimitProperties rateLimitProperties;

    @Autowired
    public BatchJobController(BatchHashJobService jobService, RateLimitProperties rateLimitProperties) {
        this.jobService = jobService;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * Compatibility constructor for callers that inject no rate-limit configuration: the quota key
     * is resolved against {@link RateLimitProperties}'s defaults (client remote address, with an
     * untrusted {@code X-Forwarded-For}), i.e. exactly the identity the rate limiter would use under
     * that configuration. Only {@code BatchHashJobService.submit(request)} without a caller identity
     * falls back to {@link ClientKeyResolver}'s documented {@code unknown} key.
     */
    public BatchJobController(BatchHashJobService jobService) {
        this(jobService, new RateLimitProperties(true, 60, 60000, 30000, 10000, false));
    }

    @PostMapping("/hash")
    @Operation(summary = "Submit a batch hash job",
            description = "Accepts a batch and returns 202 with a job id; an optional idempotencyKey deduplicates submissions")
    public ResponseEntity<ApiResponse<BatchHashJobResponse>> submit(@Valid @RequestBody BatchHashRequest request,
            HttpServletRequest httpServletRequest) {
        // The per-client admission quota is charged to the identity the rate limiter resolves, so a
        // caller can never be throttled under one identity and quota-charged under another.
        String clientKey = ClientKeyResolver.resolve(httpServletRequest, rateLimitProperties);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(jobService.submit(request, clientKey), "Batch hash job accepted"));
    }

    @GetMapping
    @Operation(summary = "List recent batch hash jobs",
            description = "Returns a bounded, newest-first list of job snapshots; an optional status filter "
                    + "accepts QUEUED/RUNNING/CANCELLING/COMPLETED/FAILED/CANCELLED, limit defaults to 20 and is clamped "
                    + "to 1..100, an empty registry or a filter that matches nothing answers 200 with an empty "
                    + "data array (never 404), and an unknown status value answers 400 BAD_REQUEST")
    public ApiResponse<List<BatchHashJobResponse>> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(jobService.listJobs(status, limit), "Job list retrieved");
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Poll batch hash job status",
            description = "Returns the current job snapshot; 404 when the id is unknown or was evicted. "
                    + "A RUNNING job carries an executionLeaseExpiresAt/lastHeartbeatAt lease that its "
                    + "worker renews before hashing each item; a job whose lease lapses is moved to FAILED "
                    + "with error EXECUTION_LEASE_EXPIRED, after which POST /api/v1/utils/jobs/{jobId}/retry "
                    + "starts attempt+1. A DELETE on a RUNNING job moves it to CANCELLING, which the worker "
                    + "honours at its next checkpoint (completedItems stays 0 and results stay null while the job "
                    + "is CANCELLING); a CANCELLING job whose lease lapses is reaped straight to CANCELLED. "
                    + "Hashing is checkpointed per item: completedItems reports how many "
                    + "leading items are durably hashed (0..itemCount), results stays null until the job is "
                    + "COMPLETED, and a retry resumes at completedItems instead of rehashing the prefix")
    public ApiResponse<BatchHashJobResponse> status(@PathVariable String jobId) {
        return ApiResponse.success(jobService.getJob(jobId), "Job status retrieved");
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "Cancel a batch hash job",
            description = "Cancellation is cooperative. A QUEUED job is withdrawn immediately (QUEUED -> CANCELLED); "
                    + "a RUNNING job is moved to CANCELLING and answers 200 with status CANCELLING, its worker stops "
                    + "at the next checkpoint and terminalizes CANCELLED (the lease reaper or the shutdown drain does "
                    + "it if the worker never returns). A repeated DELETE on a CANCELLING or CANCELLED job is "
                    + "idempotent and returns the current snapshot, a COMPLETED/FAILED job returns "
                    + "409 JOB_CANCELLATION_CONFLICT, and an unknown or evicted id returns 404 JOB_NOT_FOUND")
    public ApiResponse<BatchHashJobResponse> cancel(@PathVariable String jobId) {
        return ApiResponse.success(jobService.cancel(jobId), "Job cancelled");
    }

    @PostMapping("/{jobId}/retry")
    @Operation(summary = "Retry a failed batch hash job",
            description = "Only FAILED -> QUEUED is allowed; returns 202 with the requeued snapshot, "
                    + "409 JOB_RETRY_CONFLICT for a QUEUED/RUNNING/COMPLETED/CANCELLED job or an exhausted "
                    + "attempt budget, 404 JOB_NOT_FOUND for an unknown or evicted id, and 429 QUEUE_FULL "
                    + "when the retry is rejected by a saturated queue")
    public ResponseEntity<ApiResponse<BatchHashJobResponse>> retry(@PathVariable String jobId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(jobService.retry(jobId), "Job retried"));
    }

    @PostMapping("/{jobId}/heartbeat")
    @Operation(summary = "Renew a running job's execution lease",
            description = "Extends executionLeaseExpiresAt by utiltools.jobs.execution-lease-seconds and stamps "
                    + "lastHeartbeatAt; 404 JOB_NOT_FOUND for an unknown or evicted id, 409 JOB_RETRY_CONFLICT "
                    + "unless the job is RUNNING, 400 BAD_REQUEST once the lease has already expired (the reaper "
                    + "then fails the attempt and POST /api/v1/utils/jobs/{jobId}/retry starts attempt+1)")
    public ApiResponse<BatchHashJobResponse> heartbeat(@PathVariable String jobId) {
        return ApiResponse.success(jobService.heartbeat(jobId), "Execution lease renewed");
    }
}
