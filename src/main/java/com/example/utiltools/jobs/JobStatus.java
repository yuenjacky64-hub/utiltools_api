package com.example.utiltools.jobs;

/**
 * Lifecycle of an accepted batch hash job.
 * The legal transitions are QUEUED -> RUNNING -> COMPLETED, QUEUED -> RUNNING -> FAILED,
 * QUEUED -> CANCELLED (withdrawal before a worker admitted the job),
 * RUNNING -> CANCELLING -> CANCELLED (cooperative cancellation of an in-flight job) and the bounded
 * FAILED -> QUEUED retry (only while the job's attempt count is below its maxAttempts).
 *
 * <p>CANCELLING records a cancellation request that the worker (or, if it stalls, the lease reaper or
 * the shutdown drain) still has to honour: it is non-terminal, so the job keeps its quota slot and
 * stays visible to listing, and its only legal successor is CANCELLED. COMPLETED and CANCELLED are
 * terminal; FAILED is terminal only once the attempt budget is spent.</p>
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED
}
