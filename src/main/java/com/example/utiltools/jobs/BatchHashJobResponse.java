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
 *
 * <p>{@code executionLeaseExpiresAt} and {@code lastHeartbeatAt} are the additive execution-lease
 * projection: they are non-null only while the job is RUNNING, because only a RUNNING attempt holds
 * a lease that its worker renews before hashing each item. A job whose lease lapses is moved to
 * FAILED with error {@code EXECUTION_LEASE_EXPIRED}, and {@code POST
 * /api/v1/utils/jobs/{jobId}/retry} then starts {@code attempt + 1}. The explicit 12-argument
 * constructor keeps callers compiled against the pre-lease shape working, with both lease fields
 * null.</p>
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
        List<JobAttempt> attempts,
        Instant executionLeaseExpiresAt,
        Instant lastHeartbeatAt
) {

    /**
     * Backward-compatible constructor for callers compiled against the 12-component shape: a
     * response built this way carries no lease stamps.
     */
    public BatchHashJobResponse(String jobId, String idempotencyKey, JobStatus status, int itemCount,
            List<HashResponse> results, String error, Instant createdAt, Instant startedAt,
            Instant completedAt, int attempt, int maxAttempts, List<JobAttempt> attempts) {
        this(jobId, idempotencyKey, status, itemCount, results, error, createdAt, startedAt, completedAt,
                attempt, maxAttempts, attempts, null, null);
    }
}
