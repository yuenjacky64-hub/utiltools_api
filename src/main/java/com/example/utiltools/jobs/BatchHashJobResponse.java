package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time snapshot of a batch hash job. All mutable job state is derived from the in-memory
 * job record under its own monitor, so a caller never observes a half-written state.
 *
 * <p>The first nine components are unchanged; {@code attempt}, {@code maxAttempts} and
 * {@code attempts} are additive, so existing pollers keep working. {@code attempt} is 1-based (a
 * first submission is attempt 1) and {@code attempts} is the bounded per-attempt history, oldest
 * first, with at most {@code maxAttempts} entries.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchHashJobResponse(
        String jobId,
        String idempotencyKey,
        JobStatus status,
        int itemCount,
        List<HashResponse> results,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        int attempt,
        int maxAttempts,
        List<JobAttempt> attempts
) {}
