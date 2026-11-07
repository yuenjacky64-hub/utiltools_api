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
 *
 * <p>{@code completedItems} is the additive checkpoint projection: it reports how many leading
 * items of the batch are durably hashed and retained by the job's private checkpoint prefix. It is
 * {@code 0} before anything was committed (including on a {@code QUEUED} job and on a
 * {@code CANCELLED} job, which discards its prefix), the committed prefix size while the job is
 * {@code RUNNING} or {@code FAILED}, and {@code itemCount} once the job is {@code COMPLETED} and
 * has published {@code results}. {@code results} stays null until completion, so a partial prefix
 * is never disclosed as results. Being a primitive, it is serialized on every status; every
 * pre-existing component keeps its name and meaning, and both earlier constructor shapes are
 * retained.</p>
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
        Instant lastHeartbeatAt,
        int completedItems
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

    /**
     * Backward-compatible constructor for callers compiled against the 14-component lease shape:
     * such a caller cannot express a partial prefix, so the checkpoint projection is derived from
     * the published results (0 while a job has not published them).
     */
    public BatchHashJobResponse(String jobId, String idempotencyKey, JobStatus status, int itemCount,
            List<HashResponse> results, String error, Instant createdAt, Instant startedAt,
            Instant completedAt, int attempt, int maxAttempts, List<JobAttempt> attempts,
            Instant executionLeaseExpiresAt, Instant lastHeartbeatAt) {
        this(jobId, idempotencyKey, status, itemCount, results, error, createdAt, startedAt, completedAt,
                attempt, maxAttempts, attempts, executionLeaseExpiresAt, lastHeartbeatAt,
                results == null ? 0 : results.size());
    }
}
