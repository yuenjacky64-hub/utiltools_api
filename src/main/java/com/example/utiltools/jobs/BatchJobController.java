package com.example.utiltools.jobs;

import com.example.utiltools.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "Batch Hash Jobs", description = "Asynchronous, bounded batch hashing with idempotent submission and queued-job cancellation")
public class BatchJobController {

    private final BatchHashJobService jobService;

    public BatchJobController(BatchHashJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/hash")
    @Operation(summary = "Submit a batch hash job",
            description = "Accepts a batch and returns 202 with a job id; an optional idempotencyKey deduplicates submissions")
    public ResponseEntity<ApiResponse<BatchHashJobResponse>> submit(@Valid @RequestBody BatchHashRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(jobService.submit(request), "Batch hash job accepted"));
    }

    @GetMapping
    @Operation(summary = "List recent batch hash jobs",
            description = "Returns a bounded, newest-first list of job snapshots; an optional status filter "
                    + "accepts QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED, limit defaults to 20 and is clamped "
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
                    + "starts attempt+1")
    public ApiResponse<BatchHashJobResponse> status(@PathVariable String jobId) {
        return ApiResponse.success(jobService.getJob(jobId), "Job status retrieved");
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "Cancel a queued batch hash job",
            description = "Withdraws a job that is still QUEUED; a repeated DELETE on a CANCELLED job is "
                    + "idempotent, a RUNNING/COMPLETED/FAILED job returns 409 JOB_CANCELLATION_CONFLICT, "
                    + "and an unknown or evicted id returns 404 JOB_NOT_FOUND")
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
}
