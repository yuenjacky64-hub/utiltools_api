package com.example.utiltools.jobs;

/**
 * Raised when a retry targets a job that cannot be requeued: it is QUEUED, RUNNING, COMPLETED or
 * CANCELLED, or it is FAILED with its attempt budget already exhausted ({@code attempt} equals
 * {@code maxAttempts}). Nothing is mutated before this is thrown, so the job snapshot is unchanged.
 * Mapped to HTTP 409 with error code JOB_RETRY_CONFLICT in {@code GlobalExceptionHandler}; unknown or
 * evicted ids keep raising {@link JobNotFoundException} instead.
 */
public class JobRetryConflictException extends RuntimeException {

    private final String jobId;
    private final JobStatus status;
    private final int attempt;
    private final int maxAttempts;

    public JobRetryConflictException(String jobId, JobStatus status, int attempt, int maxAttempts) {
        super("Job " + jobId + " cannot be retried from status " + status
                + " (attempt " + attempt + " of " + maxAttempts + ")");
        this.jobId = jobId;
        this.status = status;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
    }

    public String jobId() {
        return jobId;
    }

    public JobStatus status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
