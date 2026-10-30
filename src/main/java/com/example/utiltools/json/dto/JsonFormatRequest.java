package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonFormatRequest(
        @NotBlank(message = "json string is required")
        String json,
        Boolean minify,
        Integer indent
) {}
