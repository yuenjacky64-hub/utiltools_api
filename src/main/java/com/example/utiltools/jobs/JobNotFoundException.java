package com.example.utiltools.jobs;

/**
 * Raised when a job id is unknown or has already been evicted.
 * Mapped to HTTP 404 with error code JOB_NOT_FOUND in {@code GlobalExceptionHandler}.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String jobId) {
        super("Job not found: " + jobId);
    }
}
