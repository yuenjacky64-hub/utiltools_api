package com.example.utiltools.json.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonPatchOperation(
        Integer index,
        String op,
        String path,
        String from,
        JsonNode value,
        String status,
        JsonNode beforeValue,
        JsonNode afterValue,
        String error
) {

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String VALIDATED = "VALIDATED";

    /** Immutable transition helper: same operands, new status / snapshots / error. */
    public JsonPatchOperation withAudit(String newStatus, JsonNode before, JsonNode after, String newError) {
        return new JsonPatchOperation(index, op, path, from, value, newStatus, before, after, newError);
    }
}
