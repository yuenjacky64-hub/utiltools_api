package com.example.utiltools.scheduling;

/**
 * The eight maintenance passes that share the bounded {@link MaintenanceTaskScheduler} pool.
 *
 * <p>{@link #name()} is the single identifier for a pass: it is the {@code resilience} circuit
 * breaker fingerprint (so every pass owns its own CLOSED / OPEN / HALF_OPEN machine on the one
 * {@code maintenance-passes} breaker) and it is the {@code pass} field of the status route. All
 * names match {@code ^[A-Za-z0-9_.:-]{1,64}$}, so {@code resilience.Fingerprints.normalize} is the
 * identity and no pass can collapse into the {@code default} machine.</p>
 *
 * <p>Declaration order is the order the read-only status route reports.</p>
 */
public enum MaintenancePass {

    /** {@code AuditJournalService.reapJournal}. */
    AUDIT_JOURNAL_REAP,

    /** {@code RateLimitConfig.evictIdleBuckets}. */
    RATE_LIMIT_EVICT_IDLE_BUCKETS,

    /** {@code ResilienceConfig.reapExpiredProbes}. */
    RESILIENCE_REAP_EXPIRED_PROBES,

    /** {@code ResilienceConfig.sweepExpiredFallbackEntries}. */
    RESILIENCE_SWEEP_FALLBACK_ENTRIES,

    /** {@code BatchHashJobService.evictExpiredJobs}. */
    JOBS_EVICT_EXPIRED_JOBS,

    /** {@code BatchHashJobService.reapExpiredExecutions}. */
    JOBS_REAP_EXPIRED_EXECUTIONS,

    /** {@code ContactSubmissionService.reapScheduled}. */
    CONTACT_REAP_SCHEDULED,

    /** {@code ContactSubmissionService.retryScheduled}. */
    CONTACT_RETRY_SCHEDULED
}
