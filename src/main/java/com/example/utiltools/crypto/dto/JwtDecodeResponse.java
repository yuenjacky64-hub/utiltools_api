package com.example.utiltools.crypto.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JwtDecodeResponse(
        String algorithm,
        String type,
        JsonNode header,
        JsonNode payload,
        boolean signaturePresent,
        int signatureBytes,
        boolean verified
) {}
