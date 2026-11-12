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
 *   <li>{@code nextRetryAt} — the deterministic automatic-retry schedule; present only while the
 *       attempt is a retryable {@code FAILED} (dispatcher error or lease expiry, below the
 *       attempt budget) and omitted from JSON otherwise (REQ-02, additive null-omitted like
 *       the lease fields)</li>
 * </ul>
 *
 * <p>The execution-start fence for the {@code DISPATCHING -> CANCELLED} withdrawal edge is
 * internal service bookkeeping (the attempt row's {@code dispatchStarted} flag, written under
 * {@code registryLock} immediately before dispatch); it is deliberately not part of this
 * serialized view, so the JSON shape is unchanged.
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
        Instant executionLeaseExpiresAt,
        Instant nextRetryAt
) {
}
