package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonQueryRequest(
        @NotBlank(message = "json content is required")
        String json,
        @NotBlank(message = "jsonPath expression is required (e.g. $.store.books[*].title)")
        String jsonPath
) {}
