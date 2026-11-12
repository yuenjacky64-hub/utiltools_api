package com.example.utiltools.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UrlParseRequest(
        @NotBlank(message = "url is required")
        @Size(max = 8192, message = "url must be at most 8192 characters")
        String url
) {}
