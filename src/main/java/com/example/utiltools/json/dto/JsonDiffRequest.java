package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonDiffRequest(
        @NotBlank(message = "jsonA content is required")
        String jsonA,
        @NotBlank(message = "jsonB content is required")
        String jsonB
) {}
