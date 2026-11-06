package com.example.utiltools.jobs;

/**
 * Lifecycle of an accepted batch hash job.
 * The legal transitions are QUEUED -> RUNNING -> COMPLETED, QUEUED -> RUNNING -> FAILED,
 * QUEUED -> CANCELLED (withdrawal before a worker admitted the job) and the bounded
 * FAILED -> QUEUED retry (only while the job's attempt count is below its maxAttempts).
 * COMPLETED and CANCELLED are terminal; FAILED is terminal only once the attempt budget is spent.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
