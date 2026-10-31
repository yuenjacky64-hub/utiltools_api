package com.example.utiltools.jobs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Submission payload for a bounded asynchronous batch hash job.
 * The algorithm is only checked for presence here: an unsupported algorithm must reach the worker
 * and be reported as a FAILED job rather than rejected up front.
 */
public record BatchHashRequest(
        @NotBlank(message = "algorithm is required (e.g., SHA-256, SHA-512, MD5)")
        String algorithm,
        @NotEmpty(message = "items must contain at least one entry")
        @Size(max = 1000, message = "items must contain at most 1000 entries")
        List<@NotBlank(message = "each item must be a non-blank string") String> items,
        String secretKey,
        String idempotencyKey
) {}
