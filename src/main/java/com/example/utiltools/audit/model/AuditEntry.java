package com.example.utiltools.audit.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Immutable, response-safe view of one journalled operation.
 *
 * <p>{@code request} is the redacted request tree; the original (replayable) request
 * is never exposed by this record. {@code replay} is null until a replay ran.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEntry(
        String id,
        String requestId,
        OperationType operation,
        JournalState state,
        Instant createdAt,
        Instant completedAt,
        JsonNode request,
        @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode result,
        String errorCode,
        boolean replayable,
        ReplayRecord replay
) {}
