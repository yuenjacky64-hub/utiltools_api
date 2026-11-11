package com.example.utiltools.contact;

/**
 * Raised when a requested transition on a submission's state machine is illegal (REQ-03) or the
 * retry budget is exhausted: the service answers with {@code 409
 * CONTACT_TRANSITION_CONFLICT} and the submission, its attempt counter, and its idempotency
 * mapping are untouched.
 *
 * <p>Handled by the dedicated row in {@code GlobalExceptionHandler}:
 * {@code @ExceptionHandler(ContactTransitionConflictException.class) → 409,
 * "CONTACT_TRANSITION_CONFLICT"} whose body details carry {@code submissionId} / {@code status} /
 * {@code attempt} / {@code maxAttempts} / {@code reason}.</p>
 */
public class ContactTransitionConflictException extends RuntimeException {

    private final String submissionId;
    private final ContactStatus status;
    private final int attempt;
    private final int maxAttempts;
    private final String reason;

    /**
     * @param submissionId the submission the illegal transition targeted
     * @param status its unchanged status at rejection time
     * @param attempt its unchanged 1-based attempt counter
     * @param maxAttempts its configured retry budget
     * @param reason the machine-readable explanation; truncated to the bounded error text so a
     *               long detail can never overflow the response
     */
    public ContactTransitionConflictException(String submissionId, ContactStatus status,
            int attempt, int maxAttempts, String reason) {
        super("Submission " + submissionId + " in state " + status + " (attempt " + attempt + " of "
                + maxAttempts + ") " + reason);
        this.submissionId = submissionId;
        this.status = status;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.reason = bound(reason);
    }

    public String submissionId() {
        return submissionId;
    }

    public ContactStatus status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public String reason() {
        return reason;
    }

    private static String bound(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ContactSubmissionService.MAX_ERROR_TEXT
                ? value : value.substring(0, ContactSubmissionService.MAX_ERROR_TEXT);
    }
}
