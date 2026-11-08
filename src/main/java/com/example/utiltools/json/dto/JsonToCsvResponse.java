package com.example.utiltools.json.dto;

import java.util.List;

public record JsonToCsvResponse(
        String csv,
        int rowCount,
        int columnCount,
        List<String> headers,
        int sizeBytes
) {}
