package com.example.utiltools.contact;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One execution attempt of a contact submission, mirrored by the service's attempt bookkeeping:
 *
 * <ul>
 *   <li>{@code attempt} — the 1-based attempt number; this number <em>is</em> the attempt's
 *       generation fence (REQ-06). Only a completion still carrying the current generation may
 *       write a status change for it.</li>
 *   <li>{@code status} — the attempt's {@link ContactStatus}</li>
 *   <li>{@code startedAt} / {@code completedAt} — stamped on admission and on any terminal
 *       transition ({@code DELIVERED} / {@code FAILED} / {@code CANCELLED})</li>
 *   <li>{@code error} — the bounded error code on {@code FAILED} (never raw dispatcher text)</li>
 *   <li>{@code auditEntryId} — the {@code AuditJournalService} entry opened for this attempt;
 *       present only while the attempt is {@code DISPATCHING} and omitted from JSON otherwise
 *       (REQ-08 "exactly once" bookkeeping)</li>
 *   <li>{@code lastHeartbeatAt} / {@code executionLeaseExpiresAt} — the execution lease; present
 *       only while the attempt is {@code DISPATCHING} and omitted from JSON otherwise
 *       (REQ-06 fencing, additive null-omitted like {@code JobAttempt}'s RUNNING lease fields)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactAttempt(
        int attempt,
        ContactStatus status,
        Instant startedAt,
        Instant completedAt,
        String error,
        String auditEntryId,
        Instant lastHeartbeatAt,
        Instant executionLeaseExpiresAt
) {
}
