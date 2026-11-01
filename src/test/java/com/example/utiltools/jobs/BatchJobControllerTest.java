package com.example.utiltools.jobs;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchJobController.class)
@Import({BatchHashJobService.class, CryptoService.class, GlobalExceptionHandler.class})
class BatchJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private BatchHashJobService jobService;

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should return 202 with a jobId")
    void shouldReturn202WithJobId() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha", "beta"],
                    "idempotencyKey": "controller-key"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.itemCount").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should reject blank items with 400")
    void shouldReturn400WhenItemsBlank() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": [""]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/utils/jobs/{jobId} - should return 404 for an unknown job id")
    void shouldReturn404ForUnknownJobId() throws Exception {
        mockMvc.perform(get("/api/v1/utils/jobs/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/jobs/hash - should map a full queue to 429 QUEUE_FULL")
    void shouldReturn429WhenQueueFull() throws Exception {
        doThrow(new JobQueueFullException("Hash job queue is full (worker-count=2, queue-capacity=100); retry after capacity frees"))
                .when(jobService).submit(any(BatchHashRequest.class));

        String body = """
                {
                    "algorithm": "SHA-256",
                    "items": ["alpha"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/jobs/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("QUEUE_FULL"));
    }
}
