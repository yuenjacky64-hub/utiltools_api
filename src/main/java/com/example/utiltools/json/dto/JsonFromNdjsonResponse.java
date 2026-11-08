package com.example.utiltools.json.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonFromNdjsonResponse(
        JsonNode resultJson,
        int recordCount
) {}
