package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonFlattenRequest(
        @NotBlank(message = "json content is required")
        String json,
        String separator
) {}
