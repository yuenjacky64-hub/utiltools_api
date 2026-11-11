package com.example.utiltools.contact;

/**
 * The item-outcome payload of a contact attempt's {@code AuditJournalService} entry (REQ-08,
 * {@code DOD-06}): what attempt of which submission ran and how big its payload was.
 *
 * <p>Deliberately bounded and deliberately <em>not</em> the message body (the
 * {@code BatchHashAuditDescriptor} pattern in {@code jobs}): the raw message is never journalled
 * or echoed; only the trimmed {@code name} (capped at the 100-character request bound) and the
 * message's character length travel in the journal entry, plus the attempt's generation and its
 * idempotency key (capped at the 128-character request bound).</p>
 */
public final class ContactAuditDescriptor {

    static final int MAX_TEXT = 200;

    private final String submissionId;
    private final int attempt;
    private final String name;
    private final int messageLength;
    private final String idempotencyKey;

    private ContactAuditDescriptor(String submissionId, int attempt, String name,
            int messageLength, String idempotencyKey) {
        this.submissionId = submissionId;
        this.attempt = attempt;
        this.name = name;
        this.messageLength = messageLength;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * @param submissionId the submission the attempt belongs to
     * @param attempt the 1-based attempt number (the generation)
     * @param name the trimmed contact name; truncated to the descriptor bound
     * @param messageLength the character length of the stored message, never the message itself
     * @param idempotencyKey the caller key, or null when the submission was keyless
     */
    public static ContactAuditDescriptor of(String submissionId, int attempt, String name,
            int messageLength, String idempotencyKey) {
        return new ContactAuditDescriptor(submissionId, attempt, bound(name), messageLength,
                bound(idempotencyKey));
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getName() {
        return name;
    }

    public int getMessageLength() {
        return messageLength;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    private static String bound(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
}
