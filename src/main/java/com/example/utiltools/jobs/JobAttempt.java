package com.example.utiltools.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One execution attempt of a batch hash job: the 1-based attempt number, the status that attempt
 * reached, its start/finish instants and, for a failed attempt, the error text. History records
 * worker admissions only, so a withdrawal (cancel) never appends an entry, and an attempt is
 * upserted in place: it appears once as RUNNING and is then replaced by its terminal status.
 *
 * <p>{@code auditEntryId} is the id of the {@code BATCH_HASH} audit journal entry written for this
 * attempt, or null when the attempt was never started or no journal is wired. It is omitted from
 * the JSON snapshot when absent, so the response stays backward compatible.</p>
 *
 * <p>{@code resumedFrom} is the additive checkpoint marker: the index of the first item this
 * attempt hashed, i.e. the size of the durable prefix the job carried into the attempt. It is
 * {@code 0} for a first attempt and for a retry that had no committed progress, and non-zero for a
 * retry that resumed at a checkpoint. Being a primitive, it is always serialized.</p>
 *
 * <p>{@code succeededItems} and {@code failedItems} are the additive per-item projection of this
 * attempt ({@code REQ-02}): ascending input indices, empty when the attempt has none, and bounded
 * by the job's {@code itemCount}. A {@code RUNNING} row therefore reports the progress so far and a
 * terminal row the attempt's frozen outcome, so a partially failed batch is readable as
 * "these indices hashed, those threw" without failing the whole attempt as a unit.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAttempt(
        int attempt,
        JobStatus status,
        Instant startedAt,
        Instant completedAt,
        String error,
        String auditEntryId,
        int resumedFrom,
        List<Integer> succeededItems,
        List<Integer> failedItems
) {

    /** Backward-compatible constructor for the 7-component checkpoint-resume shape. */
    public JobAttempt(int attempt, JobStatus status, Instant startedAt, Instant completedAt, String error,
            String auditEntryId, int resumedFrom) {
        this(attempt, status, startedAt, completedAt, error, auditEntryId, resumedFrom, List.of(), List.of());
    }

    /** Backward-compatible constructor for call sites that predate per-attempt journalling. */
    public JobAttempt(int attempt, JobStatus status, Instant startedAt, Instant completedAt, String error) {
        this(attempt, status, startedAt, completedAt, error, null, 0, List.of(), List.of());
    }

    /**
     * Backward-compatible constructor for call sites that predate checkpoint resume: an attempt
     * built this way started at index 0, which is literally true for a first attempt.
     */
    public JobAttempt(int attempt, JobStatus status, Instant startedAt, Instant completedAt, String error,
            String auditEntryId) {
        this(attempt, status, startedAt, completedAt, error, auditEntryId, 0, List.of(), List.of());
    }
}
