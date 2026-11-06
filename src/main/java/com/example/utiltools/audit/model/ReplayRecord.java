package com.example.utiltools.audit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayRecord(
        ReplayState state,
        Instant replayedAt,
        String detail,
        Instant leaseExpiresAt
) {}
