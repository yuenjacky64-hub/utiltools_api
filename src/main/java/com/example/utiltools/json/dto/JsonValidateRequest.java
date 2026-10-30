package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonValidateRequest(
        @NotBlank(message = "json string is required")
        String json
) {}
