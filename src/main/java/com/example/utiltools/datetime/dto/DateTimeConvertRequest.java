package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DateTimeConvertRequest(
        @NotBlank(message = "input datetime or timestamp is required")
        String input,
        String targetTimeZone,
        String customPattern,
        @Pattern(regexp = "(?i)^\\s*(EPOCH_SECONDS|EPOCH_MILLIS|ISO_8601)?\\s*$",
                 message = "inputFormat must be one of EPOCH_SECONDS, EPOCH_MILLIS, ISO_8601")
        String inputFormat
) {}
