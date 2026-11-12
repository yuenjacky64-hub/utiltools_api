package com.example.utiltools.contact;

import com.example.utiltools.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

/**
 * HTTP intake for the contact form's network seam (REQ-01): five thin routes over
 * {@link ContactSubmissionService}, each wrapped in the standard {@link ApiResponse} envelope with
 * the jobs-style {@code @Operation} + explicit error {@code @ApiResponses} metadata.
 *
 * <p>Every error body is an envelope of {@code ApiResponse.error}, and the error-code constants
 * are the same single-quoted literals the browser form state machine ({@code src/main/resources/
 * static/script.js}) already declares.
 */
@Validated
@RestController
@RequestMapping("/api/v1/utils/contact/submissions")
@Tag(name = "Contact", description = "Server-side intake for the contact form (idempotent, fenced, journaled)")
public class ContactController {

    private final ContactSubmissionService service;

    @Autowired
    public ContactController(ContactSubmissionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Accept a new contact submission",
            description = "Reserves a slot in the bounded dispatch pool and queues a first attempt. "
                    + "The same idempotency key with the same normalized payload replays the "
                    + "existing snapshot; with a different payload it is 409 "
                    + "CONTACT_IDEMPOTENCY_CONFLICT; a saturated pool is 429 CONTACT_QUEUE_FULL.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "Malformed or oversize payload (VALIDATION_ERROR)",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONTACT_IDEMPOTENCY_CONFLICT: same key, different payload",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                            description = "CONTACT_QUEUE_FULL: no dispatch slot was reservable",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
            })
    public ResponseEntity<ApiResponse<ContactSubmissionResponse>> submit(
            @Valid @RequestBody ContactSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.submit(request)));
    }

    @GetMapping
    @Operation(
            summary = "List contact submissions newest first",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Page of submission snapshots"))
    public ResponseEntity<ApiResponse<List<ContactSubmissionResponse>>> list(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.success(service.list(limit)));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one contact submission by id",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "CONTACT_SUBMISSION_NOT_FOUND",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
            })
    public ResponseEntity<ApiResponse<ContactSubmissionResponse>> get(
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping("/{id}/retry")
    @Operation(
            summary = "Re-queue a FAILED submission as attempt+1",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "CONTACT_SUBMISSION_NOT_FOUND",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONTACT_TRANSITION_CONFLICT: the current status or "
                                    + "attempt budget does not allow a retry",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                            description = "CONTACT_QUEUE_FULL: retry cannot reserve a dispatch slot",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
            })
    public ResponseEntity<ApiResponse<ContactSubmissionResponse>> retry(
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResponse.success(service.retry(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Withdraw a contact submission before dispatch starts",
            description = "Terminalizes a QUEUED or not-yet-dispatching submission to CANCELLED "
                    + "with CONTACT_SUBMISSION_CANCELLED. A repeat cancellation is idempotent (200, "
                    + "the existing CANCELLED snapshot); an in-flight dispatch or a terminal "
                    + "DELIVERED / FAILED row is 409 and leaves the row untouched.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                            description = "Cancelled, or already-cancelled (idempotent) snapshot"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "CONTACT_SUBMISSION_NOT_FOUND",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONTACT_TRANSITION_CONFLICT: the dispatch is in flight "
                                    + "or the submission is terminal (DELIVERED / FAILED)",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
            })
    public ResponseEntity<ApiResponse<ContactSubmissionResponse>> cancel(
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResponse.success(service.cancel(id)));
    }
}
