package com.example.utiltools.contact;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Snapshot of a contact submission as returned by every {@code /api/v1/utils/contact/submissions}
 * route and wrapped unchanged in the standard {@code ApiResponse} envelope:
 *
 * <ul>
 *   <li>{@code id} — the submission identifier</li>
 *   <li>{@code status} — the current {@link ContactStatus} of the attempt below</li>
 *   <li>{@code name} / {@code email} / {@code message} — the trimmed payload the submission was
 *       registered with; preserved untouched on {@code FAILED} so a later retry re-sends exactly
 *       what was first submitted (REQ-07)</li>
 *   <li>{@code idempotencyKey} — the caller key when one was supplied; omitted from JSON when
 *       absent</li>
 *   <li>{@code attempt} — the 1-based attempt number of the {@code currentAttempt}; this is the
 *       generation that fences every worker write</li>
 *   <li>{@code maxAttempts} — the retry budget; {@code retry} is legal only while
 *       {@code attempt < maxAttempts} and the status is {@code FAILED}</li>
 *   <li>{@code currentAttempt} — the full {@link ContactAttempt} record of the attempt below</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactSubmissionResponse(
        String id,
        ContactStatus status,
        String name,
        String email,
        String message,
        String idempotencyKey,
        int attempt,
        int maxAttempts,
        ContactAttempt currentAttempt
) {
}
