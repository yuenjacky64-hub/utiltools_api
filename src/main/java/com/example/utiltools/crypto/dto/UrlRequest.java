package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UrlRequest(
        @NotBlank(message = "action is required: ENCODE or DECODE")
        @Pattern(regexp = "(?i)^(ENCODE|DECODE)$", message = "action must be either ENCODE or DECODE")
        String action,
        @NotBlank(message = "content is required")
        String content,
        @Pattern(regexp = "(?i)^(COMPONENT|PATH|FORM)?$", message = "mode must be one of COMPONENT, PATH or FORM")
        String mode
) {}
