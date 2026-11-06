package com.example.utiltools.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One execution attempt of a batch hash job: the 1-based attempt number, the status that attempt
 * reached, its start/finish instants and, for a failed attempt, the error text. History records
 * worker admissions only, so a withdrawal (cancel) never appends an entry, and an attempt is
 * upserted in place: it appears once as RUNNING and is then replaced by its terminal status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAttempt(
        int attempt,
        JobStatus status,
        Instant startedAt,
        Instant completedAt,
        String error
) {}
