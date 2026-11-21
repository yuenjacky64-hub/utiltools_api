package com.example.utiltools.crypto.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Bitcoin-alphabet Base58 encode/decode request")
public record Base58Request(
        @Schema(description = "ENCODE or DECODE (case-insensitive)", example = "ENCODE")
        @NotBlank(message = "action is required: ENCODE or DECODE")
        @Pattern(regexp = "(?i)^(ENCODE|DECODE)$", message = "action must be either ENCODE or DECODE")
        String action,
        @Schema(description = "UTF-8 text to encode, or Base58 text to decode", example = "Hello World!")
        @NotBlank(message = "content is required")
        @Size(max = 8192, message = "content must be at most 8192 characters")
        String content
) {}
