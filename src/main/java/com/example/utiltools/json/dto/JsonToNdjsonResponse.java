package com.example.utiltools.json.dto;

public record JsonToNdjsonResponse(
        String ndjson,
        int lineCount,
        int sizeBytes
) {}
