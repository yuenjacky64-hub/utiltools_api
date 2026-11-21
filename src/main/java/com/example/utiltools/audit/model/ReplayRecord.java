package com.example.utiltools.audit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayRecord(
        ReplayState state,
        Instant replayedAt,
        String detail,
        Instant leaseExpiresAt,
        /**
         * Bounded-retry deadline (REQ-02): set only on the {@code FAILED} record written by a
         * replay-lease rollback, so a claim is refused until {@code min(maxBackoff,
         * base * 2^attempt)} milliseconds have passed. Cleared by every other transition.
         */
        Instant nextRetryAt
) {}
