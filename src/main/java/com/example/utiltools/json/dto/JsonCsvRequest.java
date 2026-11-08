package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record JsonCsvRequest(
        @NotBlank(message = "csvContent is required")
        @Size(max = 1000000, message = "csvContent must be at most 1000000 characters")
        String csvContent,
        @Size(max = 1, message = "delimiter must be a single character")
        String delimiter,
        Map<String, String> headerMapping
) {}
