package com.example.utiltools.json.dto;

public record JsonFormatResponse(
        String formattedJson,
        int sizeBytes
) {}
