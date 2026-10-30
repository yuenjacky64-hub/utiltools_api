package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record Base64Request(
        @NotBlank(message = "action is required: ENCODE or DECODE")
        @Pattern(regexp = "(?i)^(ENCODE|DECODE)$", message = "action must be either ENCODE or DECODE")
        String action,
        @NotBlank(message = "content is required")
        String content,
        Boolean urlSafe
) {}
