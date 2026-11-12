package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.NotBlank;

public record DateTimeOverlapRequest(
        @NotBlank(message = "startA is required")
        String startA,
        @NotBlank(message = "endA is required")
        String endA,
        @NotBlank(message = "startB is required")
        String startB,
        @NotBlank(message = "endB is required")
        String endB
) {}
