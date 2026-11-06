package com.example.utiltools.audit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Bounded audit-trail tombstone left behind by the background journal reaper.
 *
 * <p>{@code status} is always the literal {@code "REAPED"}. The record deliberately carries no
 * request or result content: {@code detail} is a short, caller-supplied constant text clamped to
 * {@link #MAX_DETAIL_LENGTH}, so a tombstone can never re-serve a redacted-away payload.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReapRecord(
        String status,
        String entryId,
        OperationType operation,
        ReapReason reason,
        String detail,
        Instant reapedAt
) {

    /** The literal status carried by every tombstone. */
    public static final String STATUS_REAPED = "REAPED";

    /** Upper bound on {@code detail}, mirroring the journal's bounded replay detail. */
    public static final int MAX_DETAIL_LENGTH = 200;

    /** Builds a tombstone, clamping {@code detail} to {@link #MAX_DETAIL_LENGTH} characters. */
    public static ReapRecord reaped(String entryId, OperationType operation, ReapReason reason,
                                    String detail, Instant reapedAt) {
        String bounded = detail;
        if (bounded != null && bounded.length() > MAX_DETAIL_LENGTH) {
            bounded = bounded.substring(0, MAX_DETAIL_LENGTH);
        }
        return new ReapRecord(STATUS_REAPED, entryId, operation, reason, bounded, reapedAt);
    }
}
