package com.example.utiltools.jobs;

import com.example.utiltools.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/jobs")
@Tag(name = "Batch Hash Jobs", description = "Asynchronous, bounded batch hashing with idempotent submission")
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

    @GetMapping("/{jobId}")
    @Operation(summary = "Poll batch hash job status",
            description = "Returns the current job snapshot; 404 when the id is unknown or was evicted")
    public ApiResponse<BatchHashJobResponse> status(@PathVariable String jobId) {
        return ApiResponse.success(jobService.getJob(jobId), "Job status retrieved");
    }
}
