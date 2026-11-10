package com.example.utiltools.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Bounded result of one operator-triggered journal reap pass.
 *
 * <p>{@code scanned} is the number of journal entries examined at the start of the pass, so it is
 * always {@code <= utiltools.audit.max-entries}. {@code reaped} counts the tombstones produced by
 * the pass and is therefore the sum of the four per-reason counts:
 * {@code reaped == replayLeaseExpired + manuallyReaped + stalePending + oversizePayload}. A
 * {@code REPLAY_LEASE_EXPIRED} reap counts as reaped because it appends a tombstone, even though
 * the entry itself is left untouched. A {@code REPLAY_LEASE_QUARANTINED} reap is a replay-lease
 * expiry outcome and is therefore also counted in {@code replayLeaseExpired}: the entry's claim
 * has now expired {@code utiltools.audit.replay-lease-quarantine-threshold} times, so it is failed
 * for good instead of rolled back again.</p>
 *
 * <p>{@code dryRun} marks a preview: the counts are those the equivalent applying pass would
 * report, and no entry, tombstone, replay claim or manual intent was changed. {@code entryId}
 * echoes the caller-supplied target of a single-entry pass and is null for a full pass.</p>
 *
 * <p>The body is counts only: no stored request, stored result, replay owner token or lease
 * deadline is ever carried by this DTO.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalReapResponse(
        boolean dryRun,
        int scanned,
        int reaped,
        int replayLeaseExpired,
        int manuallyReaped,
        int stalePending,
        int oversizePayload,
        String entryId
) {}
