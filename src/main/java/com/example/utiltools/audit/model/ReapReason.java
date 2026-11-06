package com.example.utiltools.audit.model;

/**
 * Why the background journal reaper reclaimed an entry.
 *
 * <p>Exactly one reason is recorded per entry per pass, evaluated in the order declared here:
 * an abandoned replay claim is rolled back first, then a PENDING entry the reaper was asked to
 * reclaim by hand is failed, then an orphaned PENDING entry is failed, and only an entry that was
 * already terminal when the pass started may be reclaimed for its size.</p>
 */
public enum ReapReason {
    /** An {@code IN_PROGRESS} replay claim whose lease expired; the entry itself is untouched. */
    REPLAY_LEASE_EXPIRED,
    /**
     * Set when the background reaper manually reclaims a PENDING entry: the pass was asked, via
     * {@code AuditJournalService#requestManualReap(String)}, to reclaim that specific entry, so it
     * is moved to {@code FAILED} with the {@code MANUALLY_REAPED} error code and leaves one
     * bounded {@code REAPED} tombstone. Takes precedence over an age-based
     * {@link #STALE_PENDING} sweep, so a manual reclaim is never disguised as a stale sweep.
     */
    MANUALLY_REAPED,
    /** A PENDING entry older than {@code utiltools.audit.stale-pending-seconds}. */
    STALE_PENDING,
    /** A terminal entry whose stored payload exceeded {@code utiltools.audit.max-replay-bytes}. */
    OVERSIZE_PAYLOAD
}
