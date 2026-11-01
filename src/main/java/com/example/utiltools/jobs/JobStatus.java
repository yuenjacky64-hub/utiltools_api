package com.example.utiltools.jobs;

/**
 * Lifecycle of an accepted batch hash job.
 * The only legal transitions are QUEUED -> RUNNING -> COMPLETED or QUEUED -> RUNNING -> FAILED.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
