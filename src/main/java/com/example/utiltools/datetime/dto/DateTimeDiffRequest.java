package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.NotBlank;

public record DateTimeDiffRequest(
        @NotBlank(message = "start datetime is required")
        String start,
        @NotBlank(message = "end datetime is required")
        String end
) {}
