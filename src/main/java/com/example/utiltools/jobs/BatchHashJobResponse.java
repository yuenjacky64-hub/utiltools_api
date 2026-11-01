package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time snapshot of a batch hash job. All mutable job state is derived from the in-memory
 * job record under its own monitor, so a caller never observes a half-written state.
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
        Instant completedAt
) {}
