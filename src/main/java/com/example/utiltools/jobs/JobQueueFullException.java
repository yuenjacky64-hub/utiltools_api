package com.example.utiltools.jobs;

/**
 * Raised when the bounded executor rejects a submission. The job and its idempotency mapping have
 * already been rolled back, so a retry with the same key is accepted once capacity frees.
 * Mapped to HTTP 429 with error code QUEUE_FULL in {@code GlobalExceptionHandler}.
 */
public class JobQueueFullException extends RuntimeException {

    public JobQueueFullException(String message) {
        super(message);
    }
}
