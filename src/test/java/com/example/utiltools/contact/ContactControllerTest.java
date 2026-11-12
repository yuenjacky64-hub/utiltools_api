package com.example.utiltools.contact;

import com.example.utiltools.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc surface for {@link ContactController} (DOD-05): every route answers in the standard
 * {@code ApiResponse} envelope, and the four error codes the browser form machine declares
 * ({@code CONTACT_TRANSITION_CONFLICT} / {@code CONTACT_IDEMPOTENCY_CONFLICT} /
 * {@code CONTACT_QUEUE_FULL} / {@code CONTACT_SUBMISSION_NOT_FOUND}) reach the body.
 */
@WebMvcTest(ContactController.class)
@Import(GlobalExceptionHandler.class)
class ContactControllerTest {

    private static final String BASE = "/api/v1/utils/contact/submissions";

    private static final String VALID_BODY = """
            {"name": "Ada Lovelace", "email": "ada@example.com",
             "message": "Saturday in June - is the venue step-free?"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContactSubmissionService service;

    private static ContactSubmissionResponse snapshot(String id, ContactStatus status, int attempt,
            Instant now) {
        return new ContactSubmissionResponse(id, status, "Ada Lovelace", "ada@example.com",
                "Saturday in June - is the venue step-free?", null, attempt, 3,
                new ContactAttempt(attempt, status, now,
                        status == ContactStatus.DELIVERED || status == ContactStatus.FAILED
                                ? now : null,
                        status == ContactStatus.FAILED
                                ? ContactSubmissionService.CONTACT_DISPATCH_FAILED : null,
                        null, null, null, null));
    }

    @Test
    @DisplayName("POST accepts a valid payload with 202 and a QUEUED snapshot in the envelope")
    void shouldReturn202WithQueuedSnapshot() throws Exception {
        when(service.submit(any(ContactSubmissionRequest.class))).thenReturn(
                snapshot("sub-1", ContactStatus.QUEUED, 1, Instant.now()));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attempt").value(1));
    }

    @Test
    @DisplayName("POST answers 400 VALIDATION_ERROR for an invalid or oversize payload")
    void shouldReturn400ForInvalidPayload() throws Exception {
        // Missing name.
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ada@example.com\", \"message\": \"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());

        // An email longer than the 254-character limit the form machine declares.
        String longLocalPart = "a".repeat(253);
        String oversizeEmail = longLocalPart + "@example.com";
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Ada\", \"email\": \"" + oversizeEmail
                        + "\", \"message\": \"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST answers 409 CONTACT_IDEMPOTENCY_CONFLICT for a reused key with a different payload")
    void shouldReturn409ForIdempotencyConflict() throws Exception {
        when(service.submit(any(ContactSubmissionRequest.class))).thenThrow(
                new ContactIdempotencyConflictException("conflict-key", "sub-original"));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY + ", \"idempotencyKey\": \"conflict-key\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.details.idempotencyKey").value("conflict-key"))
                .andExpect(jsonPath("$.details.existingSubmissionId").value("sub-original"));
    }

    @Test
    @DisplayName("POST answers 429 CONTACT_QUEUE_FULL when the dispatch pool cannot reserve a slot")
    void shouldReturn429WhenQueueFull() throws Exception {
        when(service.submit(any(ContactSubmissionRequest.class)))
                .thenThrow(new ContactQueueFullException());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_QUEUE_FULL"));
    }

    @Test
    @DisplayName("GET {id} returns the terminal snapshot with 200 in the envelope")
    void shouldReturn200ForSubmissionSnapshot() throws Exception {
        when(service.get("sub-1")).thenReturn(
                snapshot("sub-1", ContactStatus.DELIVERED, 1, Instant.now()));

        mockMvc.perform(get(BASE + "/{id}", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-1"))
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
    }

    @Test
    @DisplayName("GET {id} answers 404 CONTACT_SUBMISSION_NOT_FOUND for an unknown id")
    void shouldReturn404ForUnknownSubmission() throws Exception {
        when(service.get("unknown")).thenThrow(new ContactSubmissionNotFoundException("unknown"));

        mockMvc.perform(get(BASE + "/{id}", "unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_SUBMISSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET lists submissions newest first with an optional limit")
    void shouldListSubmissionsNewestFirst() throws Exception {
        Instant now = Instant.now();
        when(service.list(isNull())).thenReturn(List.of(
                snapshot("newer", ContactStatus.DELIVERED, 1, now),
                snapshot("older", ContactStatus.FAILED, 2, now)));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("newer"))
                .andExpect(jsonPath("$.data[1].id").value("older"));
    }

    @Test
    @DisplayName("POST {id}/retry returns the re-queued attempt+1 snapshot with 200")
    void shouldReturn200ForRetry() throws Exception {
        when(service.retry("sub-1")).thenReturn(
                snapshot("sub-1", ContactStatus.QUEUED, 2, Instant.now()));

        mockMvc.perform(post(BASE + "/{id}/retry", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-1"))
                .andExpect(jsonPath("$.data.attempt").value(2));
    }

    @Test
    @DisplayName("POST {id}/retry answers 409 CONTACT_TRANSITION_CONFLICT for an illegal transition")
    void shouldReturn409ForIllegalTransition() throws Exception {
        when(service.retry("sub-1")).thenThrow(new ContactTransitionConflictException("sub-1",
                ContactStatus.DELIVERED, 1, 3, "may not leave terminal state DELIVERED"));

        mockMvc.perform(post(BASE + "/{id}/retry", "sub-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_TRANSITION_CONFLICT"))
                .andExpect(jsonPath("$.details.submissionId").value("sub-1"))
                .andExpect(jsonPath("$.details.status").value("DELIVERED"))
                .andExpect(jsonPath("$.details.attempt").value(1));
    }

    @Test
    @DisplayName("POST {id}/retry answers 404 for an unknown id and 429 when the pool is saturated")
    void shouldReturn404And429ForRetryEdgeCases() throws Exception {
        when(service.retry(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if ("unknown".equals(id)) {
                throw new ContactSubmissionNotFoundException(id);
            }
            throw new ContactQueueFullException();
        });

        mockMvc.perform(post(BASE + "/unknown/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONTACT_SUBMISSION_NOT_FOUND"));

        mockMvc.perform(post(BASE + "/{id}/retry", "sub-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("CONTACT_QUEUE_FULL"));
    }

    @Test
    @DisplayName("DELETE {id} returns 200 with the CANCELLED snapshot (or the idempotent repeat)")
    void shouldReturn200ForCancelSnapshot() throws Exception {
        when(service.cancel("sub-1")).thenReturn(
                snapshot("sub-1", ContactStatus.CANCELLED, 1, Instant.now()));

        mockMvc.perform(delete(BASE + "/{id}", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("DELETE {id} answers 404 CONTACT_SUBMISSION_NOT_FOUND for an unknown id")
    void shouldReturn404ForUnknownCancel() throws Exception {
        when(service.cancel("unknown")).thenThrow(new ContactSubmissionNotFoundException("unknown"));

        mockMvc.perform(delete(BASE + "/{id}", "unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_SUBMISSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE {id} answers 409 CONTACT_TRANSITION_CONFLICT when the dispatch is in flight")
    void shouldReturn409ForInFlightCancel() throws Exception {
        when(service.cancel("sub-1")).thenThrow(new ContactTransitionConflictException("sub-1",
                ContactStatus.DISPATCHING, 1, 3, "dispatch in flight: cancellation refused"));

        mockMvc.perform(delete(BASE + "/{id}", "sub-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CONTACT_TRANSITION_CONFLICT"))
                .andExpect(jsonPath("$.details.submissionId").value("sub-1"))
                .andExpect(jsonPath("$.details.status").value("DISPATCHING"))
                .andExpect(jsonPath("$.details.attempt").value(1));
    }
}
