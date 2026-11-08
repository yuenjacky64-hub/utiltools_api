package com.example.utiltools.json.dto;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

public record JsonFromCsvResponse(
        ArrayNode resultJson,
        int rowCount,
        int columnCount,
        List<String> columns
) {}
