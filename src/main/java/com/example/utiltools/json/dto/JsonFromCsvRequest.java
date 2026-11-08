package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JsonFromCsvRequest(
        @NotBlank(message = "csv content is required")
        @Size(max = 1000000, message = "csv content must be at most 1000000 characters")
        String csv,
        String delimiter,
        Boolean hasHeader
) {}
