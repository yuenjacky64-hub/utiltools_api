package com.example.utiltools.common;

import com.example.utiltools.contact.ContactIdempotencyConflictException;
import com.example.utiltools.contact.ContactQueueFullException;
import com.example.utiltools.contact.ContactSubmissionNotFoundException;
import com.example.utiltools.contact.ContactTransitionConflictException;
import com.example.utiltools.json.exception.JsonPatchRollbackException;
import com.example.utiltools.jobs.IdempotencyConflictException;
import com.example.utiltools.jobs.JobCancellationConflictException;
import com.example.utiltools.jobs.JobNotFoundException;
import com.example.utiltools.jobs.JobQueueFullException;
import com.example.utiltools.jobs.JobQuotaExceededException;
import com.example.utiltools.jobs.JobRetryConflictException;
import com.example.utiltools.resilience.CircuitBreakerOpenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    @ExceptionHandler(JsonPatchRollbackException.class)
    public ResponseEntity<ApiResponse<Void>> handleJsonPatchRollback(JsonPatchRollbackException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failedOperationIndex", ex.failedIndex());
        details.put("failedOperation", ex.failedOperation());
        details.put("failedPath", ex.failedPath());
        details.put("operationsRolledBack", ex.operationsRolledBack());
        details.put("reason", ex.reason());
        details.put("audit", ex.audit());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "PATCH_ROLLBACK", details);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
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

    @ExceptionHandler(ReapConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleReapConflictException(ReapConflictException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "REAP_CONFLICT", null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
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

    @ExceptionHandler(JobQuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobQuotaExceeded(JobQuotaExceededException ex) {
        // Bounded integers only: no client key and no job id, so a 429 cannot reveal another
        // client's activity.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limit", ex.limit());
        details.put("activeJobs", ex.activeJobs());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "JOB_QUOTA_EXCEEDED", details);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", ex.idempotencyKey());
        details.put("existingJobId", ex.existingJobId());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "IDEMPOTENCY_CONFLICT", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(JobCancellationConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobCancellationConflict(JobCancellationConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", ex.jobId());
        details.put("status", ex.status() != null ? ex.status().name() : null);
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "JOB_CANCELLATION_CONFLICT", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
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

    @ExceptionHandler(JobRetryConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobRetryConflict(JobRetryConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", ex.jobId());
        details.put("status", ex.status() != null ? ex.status().name() : null);
        details.put("attempt", ex.attempt());
        details.put("maxAttempts", ex.maxAttempts());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "JOB_RETRY_CONFLICT", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ContactTransitionConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleContactTransitionConflict(
            ContactTransitionConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("submissionId", ex.submissionId());
        details.put("status", ex.status() != null ? ex.status().name() : null);
        details.put("attempt", ex.attempt());
        details.put("maxAttempts", ex.maxAttempts());
        details.put("reason", ex.reason());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "CONTACT_TRANSITION_CONFLICT", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ContactIdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleContactIdempotencyConflict(
            ContactIdempotencyConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", ex.idempotencyKey());
        details.put("existingSubmissionId", ex.existingSubmissionId());
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "CONTACT_IDEMPOTENCY_CONFLICT", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ContactQueueFullException.class)
    public ResponseEntity<ApiResponse<Void>> handleContactQueueFull(ContactQueueFullException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "CONTACT_QUEUE_FULL", null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler(ContactSubmissionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleContactSubmissionNotFound(
            ContactSubmissionNotFoundException ex) {
        ApiResponse<Void> response = ApiResponse.error(ex.getMessage(), "CONTACT_SUBMISSION_NOT_FOUND", null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        ApiResponse<Void> response = ApiResponse.error("An unexpected error occurred: " + ex.getMessage(), "INTERNAL_SERVER_ERROR", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
