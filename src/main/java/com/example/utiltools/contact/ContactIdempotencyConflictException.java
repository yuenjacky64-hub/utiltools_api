package com.example.utiltools.contact;

/**
 * Raised when the same idempotency key arrives with a <em>different</em> normalized payload
 * (REQ-04): the service answers with {@code 409 CONTACT_IDEMPOTENCY_CONFLICT} and the original
 * submission is left untouched.
 *
 * <p>Handled by the dedicated row in {@code GlobalExceptionHandler}:
 * {@code @ExceptionHandler(ContactIdempotencyConflictException.class) → 409,
 * "CONTACT_IDEMPOTENCY_CONFLICT"} whose body details carry {@code idempotencyKey} and
 * {@code existingSubmissionId} so the caller can fetch what it actually collided with.</p>
 */
public class ContactIdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;
    private final String existingSubmissionId;

    /**
     * @param idempotencyKey the caller key that is already bound to another payload
     * @param existingSubmissionId the id of the submission that owns the key
     */
    public ContactIdempotencyConflictException(String idempotencyKey, String existingSubmissionId) {
        super("Idempotency key " + idempotencyKey + " is already bound to submission "
                + existingSubmissionId);
        this.idempotencyKey = idempotencyKey;
        this.existingSubmissionId = existingSubmissionId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String existingSubmissionId() {
        return existingSubmissionId;
    }
}
