package com.example.utiltools.contact;

/**
 * Raised when a route names a submission id that has no live registry row — unknown, or evicted
 * by the retention sweep (REQ-01): the service answers with {@code 404
 * CONTACT_SUBMISSION_NOT_FOUND}.
 *
 * <p>Handled by the dedicated row in {@code GlobalExceptionHandler}:
 * {@code @ExceptionHandler(ContactSubmissionNotFoundException.class) → 404,
 * "CONTACT_SUBMISSION_NOT_FOUND"}.</p>
 */
public class ContactSubmissionNotFoundException extends RuntimeException {

    private final String submissionId;

    /**
     * @param submissionId the id no live registry row exists for
     */
    public ContactSubmissionNotFoundException(String submissionId) {
        super("Contact submission " + submissionId + " not found");
        this.submissionId = submissionId;
    }

    public String submissionId() {
        return submissionId;
    }
}
