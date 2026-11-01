package com.example.utiltools.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ApiResponse<Void> response = ApiResponse.error(
                "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'", "BAD_REQUEST", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(AuditEntryNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuditEntryNotFoundException(AuditEntryNotFoundException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "NOT_FOUND", null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(ReplayConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleReplayConflictException(ReplayConflictException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "REPLAY_CONFLICT", null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        ApiResponse<Void> response = ApiResponse.error("An unexpected error occurred: " + ex.getMessage(), "INTERNAL_SERVER_ERROR", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
