package com.example.utiltools.jobs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Submission payload for a bounded asynchronous batch hash job.
 * The algorithm is only checked for presence here: an unsupported algorithm must reach the worker
 * and be reported as a FAILED job rather than rejected up front.
 *
 * <p>{@code deadlineSeconds} is the optional, additive total-life bound: it covers queue wait plus
 * execution and must be between 1 and 3600 seconds when present. Omitting it takes the server
 * default from {@code utiltools.jobs.default-deadline-seconds} ({@code 0} disables the bound), so a
 * caller that sends no deadline keeps today's unbounded behaviour. The 4-argument constructor keeps
 * every pre-deadline caller compiled, with no bound at all.</p>
 */
public record BatchHashRequest(
        @NotBlank(message = "algorithm is required (e.g., SHA-256, SHA-512, MD5)")
        String algorithm,
        @NotEmpty(message = "items must contain at least one entry")
        @Size(max = 1000, message = "items must contain at most 1000 entries")
        List<@NotBlank(message = "each item must be a non-blank string") String> items,
        String secretKey,
        String idempotencyKey,
        @Min(value = 1, message = "deadlineSeconds must be at least 1")
        @Max(value = 3600, message = "deadlineSeconds must be at most 3600")
        Integer deadlineSeconds
) {

    /**
     * Backward-compatible constructor for callers compiled against the pre-deadline shape: such a
     * request carries no per-request bound and falls back to the configured default.
     */
    public BatchHashRequest(String algorithm, List<String> items, String secretKey, String idempotencyKey) {
        this(algorithm, items, secretKey, idempotencyKey, null);
    }
}
