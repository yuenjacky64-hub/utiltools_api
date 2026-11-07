package com.example.utiltools.json.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record JsonFormatRequest(
        @NotBlank(message = "json string is required")
        String json,
        Boolean minify,
        @Min(value = 1, message = "indent must be between 1 and 8")
        @Max(value = 8, message = "indent must be between 1 and 8")
        Integer indent,
        Boolean sortKeys
) {}
