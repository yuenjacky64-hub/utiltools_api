package com.example.utiltools.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        String error,
        Object details,
        Instant timestamp,
        String requestId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Operation successful", data, null, null, Instant.now(),
                RequestContext.get());
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, null, null, Instant.now(),
                RequestContext.get());
    }

    public static <T> ApiResponse<T> error(String message, String error, Object details) {
        return new ApiResponse<>(false, message, null, error, details, Instant.now(),
                RequestContext.get());
    }
}
