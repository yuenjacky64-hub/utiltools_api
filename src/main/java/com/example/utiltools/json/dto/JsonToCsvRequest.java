package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JsonToCsvRequest(
        @NotBlank(message = "json content is required")
        @Size(max = 1000000, message = "json content must be at most 1000000 characters")
        String json,
        String delimiter
) {}
