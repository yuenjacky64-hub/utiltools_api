package com.example.utiltools.json.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonPatchResponse(
        JsonNode resultJson,
        int operationsApplied,
        int operationsRolledBack,
        boolean dryRun,
        List<JsonPatchOperation> audit
) {}
