package com.example.utiltools.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Read-only snapshot of the journal's replay-lease quarantine state.
 *
 * <p>{@code threshold} is the effective
 * {@code utiltools.audit.replay-lease-quarantine-threshold} (clamped to be at least
 * {@code 1}), i.e. how many times one entry's replay claim may be rolled back before the entry is
 * quarantined. {@code quarantinedCount} counts the bounded {@code REPLAY_LEASE_QUARANTINED}
 * tombstones the journal has recorded, so it is always
 * {@code <= utiltools.audit.max-entries} and drops oldest-first once the tombstone log
 * saturates. {@code totalExpirationCount} sums each live entry's replay-claim expiration counter —
 * the same counter the reaper reads before rolling a claim back or quarantining an entry — and
 * therefore includes entries that are already quarantined. It is <em>not</em> a tombstone count
 * (a {@code ROLLBACK_CLAIM} pass adds a tombstone and a counter increment, so mixing the two
 * sources would double count) and it is bounded by
 * {@code utiltools.audit.max-entries × threshold}. A live {@code IN_PROGRESS} claim that has not
 * lapsed contributes {@code 0}.
 *
 * <p>The body is counts only: no entry id, detail, stored request, stored result, replay owner
 * token or lease deadline is ever carried by this DTO, and {@code timestamp} / {@code requestId}
 * come from the {@code ApiResponse} envelope rather than being duplicated here.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuarantineStatusResponse(
        int threshold,
        int quarantinedCount,
        int totalExpirationCount
) {}
