package com.example.utiltools.jobs;

/**
 * Raised when cancellation targets a job that has already started or already finished.
 * Mapped to HTTP 409 with error code JOB_CANCELLATION_CONFLICT in {@code GlobalExceptionHandler};
 * unknown or evicted ids keep raising {@link JobNotFoundException} instead.
 */
public class JobCancellationConflictException extends RuntimeException {

    private final String jobId;
    private final JobStatus status;

    public JobCancellationConflictException(String jobId, JobStatus status) {
        super("Job " + jobId + " cannot be cancelled from status " + status);
        this.jobId = jobId;
        this.status = status;
    }

    public String jobId() {
        return jobId;
    }

    public JobStatus status() {
        return status;
    }
}
