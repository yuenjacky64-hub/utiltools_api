package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JwtDecodeRequest(
        @NotBlank(message = "token is required")
        @Size(max = 16384, message = "token must not exceed 16384 characters")
        String token
) {}
