package com.example.utiltools.audit.model;

/**
 * Why the background journal reaper reclaimed an entry.
 *
 * <p>Exactly one reason is recorded per entry per pass, evaluated in the order declared here:
 * an abandoned replay claim is rolled back first, then an orphaned PENDING entry is failed, and
 * only an entry that was already terminal when the pass started may be reclaimed for its size.</p>
 */
public enum ReapReason {
    /** An {@code IN_PROGRESS} replay claim whose lease expired; the entry itself is untouched. */
    REPLAY_LEASE_EXPIRED,
    /** A PENDING entry older than {@code utiltools.audit.stale-pending-seconds}. */
    STALE_PENDING,
    /** A terminal entry whose stored payload exceeded {@code utiltools.audit.max-replay-bytes}. */
    OVERSIZE_PAYLOAD
}
