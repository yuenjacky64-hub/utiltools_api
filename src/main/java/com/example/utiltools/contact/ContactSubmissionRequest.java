package com.example.utiltools.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Submission payload for the contact form intake. The limits mirror the contact form state
 * machine declared in {@code src/main/resources/static/script.js}
 * ({@code CONTACT_FIELD_LIMITS} = name 100 / email 254 / message 2000,
 * {@code CONTACT_EMAIL_PATTERN} = {@code ^[^@\s]+@[^@\s]+\.[^@\s]+$}):
 *
 * <ul>
 *   <li>{@code name} required, 100 characters or fewer</li>
 *   <li>{@code email} required, 254 characters or fewer, existing email shape</li>
 *   <li>{@code message} required, 2000 characters or fewer</li>
 *   <li>{@code idempotencyKey} optional, 128 characters or fewer; omitted or blank keys skip
 *       the idempotency index entirely (REQ-04)</li>
 * </ul>
 *
 * <p>Blank-or-shorter payloads are rejected up front by the {@code @Valid} path of
 * {@code POST /api/v1/utils/contact/submissions} (400 {@code VALIDATION_ERROR}), so the service
 * only ever sees well-formed fields.</p>
 */
public record ContactSubmissionRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be 100 characters or fewer")
        String name,

        @NotBlank(message = "email is required")
        @Size(max = 254, message = "email must be 254 characters or fewer")
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
                message = "email must match the contact form's email shape")
        String email,

        @NotBlank(message = "message is required")
        @Size(max = 2000, message = "message must be 2000 characters or fewer")
        String message,

        @Size(max = 128, message = "idempotencyKey must be 128 characters or fewer")
        String idempotencyKey
) {

    /**
     * Backward-compatible constructor for callers that carry no idempotency key: such a request
     * is stamped with a blank-free key of {@code null} and skips the idempotency index.
     */
    public ContactSubmissionRequest(String name, String email, String message) {
        this(name, email, message, null);
    }
}
