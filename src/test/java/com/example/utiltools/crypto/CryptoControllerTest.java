package com.example.utiltools.crypto;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.controller.CryptoController;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CryptoController.class)
@Import({CryptoService.class, GlobalExceptionHandler.class})
class CryptoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should return SHA-256 digest")
    void shouldReturnSha256() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "content": "Hello World"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.data.digest").value("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"))
                .andExpect(jsonPath("$.data.isHmac").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/base64 - should encode string to Base64")
    void shouldEncodeBase64() throws Exception {
        String body = """
                {
                    "action": "ENCODE",
                    "content": "Hello World"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/crypto/base64")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("ENCODE"))
                .andExpect(jsonPath("$.data.result").value("SGVsbG8gV29ybGQ="));
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid - should generate UUID list")
    void shouldGenerateUuids() throws Exception {
        mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("count", "3")
                        .param("compact", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.uuids.length()").value(3));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should reject blank content")
    void shouldRejectBlankContent() throws Exception {
        String body = """
                {
                    "algorithm": "SHA-256",
                    "content": ""
                }
                """;

        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
