package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record JsonPointerRequest(
        @NotBlank(message = "json content is required")
        String json,
        // NotNull, not NotBlank: "" is the legal whole-document pointer.
        @NotNull(message = "pointer must not be null")
        String pointer
) {}
