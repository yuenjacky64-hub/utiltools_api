package com.example.utiltools.jobs;

/**
 * Raised when an idempotency key is reused with a different canonical request payload
 * (algorithm, ordered items, or secretKey). Nothing is registered and nothing executes.
 * Mapped to HTTP 409 with error code IDEMPOTENCY_CONFLICT in {@code GlobalExceptionHandler}.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;
    private final String existingJobId;

    public IdempotencyConflictException(String idempotencyKey, String existingJobId) {
        super("Idempotency key '" + idempotencyKey
                + "' was already used with a different request payload (existing job " + existingJobId
                + "); reuse a key only for an identical retry");
        this.idempotencyKey = idempotencyKey;
        this.existingJobId = existingJobId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String existingJobId() {
        return existingJobId;
    }
}
