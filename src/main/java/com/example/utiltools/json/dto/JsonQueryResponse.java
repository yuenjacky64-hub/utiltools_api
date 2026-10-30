package com.example.utiltools.json.dto;

public record JsonQueryResponse(
        String jsonPath,
        Object result
) {}
