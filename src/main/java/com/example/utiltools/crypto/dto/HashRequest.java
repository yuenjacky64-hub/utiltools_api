package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;

public record HashRequest(
        @NotBlank(message = "algorithm is required (e.g., SHA-256, SHA-512, MD5)")
        String algorithm,
        @NotBlank(message = "content to hash is required")
        String content,
        String secretKey
) {}
