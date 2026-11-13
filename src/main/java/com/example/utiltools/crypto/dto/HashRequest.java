package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HashRequest(
        @NotBlank(message = "algorithm is required (e.g., SHA-256, SHA-512, MD5)")
        @Pattern(regexp = "(?i)^(SHA-256|SHA-384|SHA-512|SHA-1|MD5|SHA384)$",
                message = "Unsupported hash algorithm. Supported: SHA-256, SHA-384, SHA-512, MD5, SHA-1")
        String algorithm,
        @NotBlank(message = "content to hash is required")
        String content,
        String secretKey
) {}
