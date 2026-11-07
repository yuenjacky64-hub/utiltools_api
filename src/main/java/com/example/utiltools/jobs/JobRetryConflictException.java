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
        this(jobId, status, attempt, maxAttempts, "be retried");
    }

    /**
     * Same conflict, described with the caller's own verb: a heartbeat on a non-RUNNING job reports
     * that it cannot <em>renew its execution lease</em>, while a retry keeps the historical
     * "cannot be retried" message byte for byte.
     */
    public JobRetryConflictException(String jobId, JobStatus status, int attempt, int maxAttempts,
            String verb) {
        super("Job " + jobId + " cannot " + verb + " from status " + status
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
