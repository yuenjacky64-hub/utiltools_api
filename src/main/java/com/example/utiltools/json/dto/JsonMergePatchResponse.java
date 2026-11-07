package com.example.utiltools.json.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonMergePatchResponse(
        JsonNode resultJson,
        int sizeBytes,
        boolean changed,
        int removedKeys
) {}
