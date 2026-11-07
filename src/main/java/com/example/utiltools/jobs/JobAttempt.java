package com.example.utiltools.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One execution attempt of a batch hash job: the 1-based attempt number, the status that attempt
 * reached, its start/finish instants and, for a failed attempt, the error text. History records
 * worker admissions only, so a withdrawal (cancel) never appends an entry, and an attempt is
 * upserted in place: it appears once as RUNNING and is then replaced by its terminal status.
 *
 * <p>{@code auditEntryId} is the id of the {@code BATCH_HASH} audit journal entry written for this
 * attempt, or null when the attempt was never started or no journal is wired. It is omitted from
 * the JSON snapshot when absent, so the response stays backward compatible.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAttempt(
        int attempt,
        JobStatus status,
        Instant startedAt,
        Instant completedAt,
        String error,
        String auditEntryId
) {

    /** Backward-compatible constructor for call sites that predate per-attempt journalling. */
    public JobAttempt(int attempt, JobStatus status, Instant startedAt, Instant completedAt, String error) {
        this(attempt, status, startedAt, completedAt, error, null);
    }
}
