package com.example.utiltools.jobs;

/**
 * Lifecycle of an accepted batch hash job.
 * The only legal transitions are QUEUED -> RUNNING -> COMPLETED, QUEUED -> RUNNING ->
 * FAILED, and QUEUED -> CANCELLED (withdrawal before a worker admitted the job).
 * COMPLETED, FAILED and CANCELLED are terminal.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
