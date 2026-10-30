package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.NotBlank;

public record DateTimeConvertRequest(
        @NotBlank(message = "input datetime or timestamp is required")
        String input,
        String targetTimeZone,
        String customPattern
) {}
