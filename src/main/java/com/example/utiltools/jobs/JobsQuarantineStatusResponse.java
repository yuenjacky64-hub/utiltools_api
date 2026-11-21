package com.example.utiltools.jobs;

/**
 * Read-only aggregate of the batch-hash auto-failover/quarantine budgets (REQ-01 / REQ-02),
 * the jobs-side sibling of {@code AuditJournalService.quarantineStatus()}.
 *
 * <p>{@code maxAutoFailovers} is the effective {@code utiltools.jobs.max-auto-failovers} budget
 * ({@code 0} disables automatic lease-expiry failover) and {@code autoFailoverEnabled} is the
 * derived {@code maxAutoFailovers > 0} flag, so a caller does not have to know that the budget
 * doubles as the feature switch. {@code autoFailoverExhaustedCount} counts the jobs that already
 * spent the whole budget and are still non-terminal — {@code COMPLETED} and {@code CANCELLED} are
 * the terminal states, {@code FAILED} stays non-terminal until its attempt budget is spent — i.e.
 * the rows an automatic failover can no longer rescue and that only an operator retry or an
 * eviction will move. {@code failedLeaseExpiredCount} counts the {@code FAILED} jobs whose error is
 * {@link BatchHashJobService#EXECUTION_LEASE_EXPIRED} (the lease reaper's stamp).
 * {@code queuedCount} counts the queued rows and {@code retainedJobCount} is the registry size that
 * denominates all of the above.
 *
 * <p>The body is counts only: no job id, item, secret key, fingerprint, error text or lease
 * deadline is carried here, and {@code timestamp} / {@code requestId} come from the
 * {@code ApiResponse} envelope rather than being duplicated.</p>
 */
public record JobsQuarantineStatusResponse(
        int maxAutoFailovers,
        boolean autoFailoverEnabled,
        int autoFailoverExhaustedCount,
        int failedLeaseExpiredCount,
        int queuedCount,
        int retainedJobCount) {
}
