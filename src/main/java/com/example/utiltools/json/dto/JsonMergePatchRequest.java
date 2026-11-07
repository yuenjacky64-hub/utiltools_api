package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

public record JsonMergePatchRequest(
        @NotBlank(message = "json content is required")
        String json,
        @NotBlank(message = "patch content is required")
        String patch
) {}
