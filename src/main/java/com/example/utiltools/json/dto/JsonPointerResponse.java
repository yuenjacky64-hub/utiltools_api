package com.example.utiltools.json.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonPointerResponse(
        String pointer,
        boolean found,
        JsonNode value,
        String valueType
) {}
