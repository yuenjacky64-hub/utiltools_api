package com.example.utiltools.common;

import com.example.utiltools.jobs.JobNotFoundException;
import com.example.utiltools.jobs.JobQueueFullException;
import com.example.utiltools.resilience.CircuitBreakerOpenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        ApiResponse<Void> response = ApiResponse.error("Validation failed", "VALIDATION_ERROR", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "BAD_REQUEST", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ApiResponse<Void> response = ApiResponse.error("Malformed JSON request body: " + ex.getMessage(), "BAD_REQUEST", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(JobQueueFullException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobQueueFull(JobQueueFullException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "QUEUE_FULL", null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobNotFound(JobNotFoundException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "JOB_NOT_FOUND", null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(CircuitBreakerOpenException.class)
    public ResponseEntity<ApiResponse<Void>> handleCircuitBreakerOpen(CircuitBreakerOpenException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", ex.state() != null ? ex.state().name() : null);
        details.put("resetAt", ex.resetAt());
        details.put("reason", ex.reason());
        ApiResponse<Void> response = ApiResponse.error("Service unavailable: circuit breaker is open", "CIRCUIT_OPEN", details);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        ApiResponse<Void> response = ApiResponse.error("An unexpected error occurred: " + ex.getMessage(), "INTERNAL_SERVER_ERROR", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
