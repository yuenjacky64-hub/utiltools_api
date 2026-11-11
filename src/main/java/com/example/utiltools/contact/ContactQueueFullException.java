package com.example.utiltools.contact;

/**
 * Raised when the bounded dispatch pool plus its queue cannot reserve a slot for a new attempt
 * (REQ-05): the service answers with {@code 429 CONTACT_QUEUE_FULL} and rolls back atomically so
 * no submission row, idempotency mapping, attempt correlation, or quota counter residue remains —
 * a later submission with the same key is accepted again once capacity frees up (the
 * {@code JobQueueFullException} contract in {@code jobs}).
 *
 * <p>Handled by the dedicated row in {@code GlobalExceptionHandler}:
 * {@code @ExceptionHandler(ContactQueueFullException.class) → 429, "CONTACT_QUEUE_FULL"}.</p>
 */
public class ContactQueueFullException extends RuntimeException {

    /** The single client-facing message (INV-C2: never echoes queue capacity or thread names). */
    public ContactQueueFullException() {
        super("Contact dispatch capacity is exhausted; retry later.");
    }
}
