package com.example.utiltools.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String message,
        String error,
        Object details,
        Instant timestamp
) {
    public static ErrorResponse of(String message, String error, Object details) {
        return new ErrorResponse(false, message, error, details, Instant.now());
    }
}
