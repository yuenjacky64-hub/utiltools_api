package com.example.utiltools.crypto;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.controller.CryptoController;
import com.example.utiltools.crypto.service.CryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CryptoController.class)
@Import({CryptoService.class, GlobalExceptionHandler.class})
class CryptoControllerTest {

    private static final String UUID_V7_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=V7 - should return a sorted UUIDv7 list")
    void shouldReturnUuidV7List() throws Exception {
        String body = mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V7")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value("V7"))
                .andExpect(jsonPath("$.data.count").value(5))
                .andExpect(jsonPath("$.data.uuids.length()").value(5))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode uuids = objectMapper.readTree(body).path("data").path("uuids");
        List<String> values = new ArrayList<>();
        uuids.forEach(node -> values.add(node.asText()));

        assertThat(values).hasSize(5).isSorted();
        assertThat(values).allSatisfy(value -> assertThat(value).matches(UUID_V7_PATTERN));
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=v7&compact=true - should return compact UUIDv7 values")
    void shouldReturnCompactUuidV7() throws Exception {
        String body = mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "v7")
                        .param("compact", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("V7"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.uuids.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String uuid = objectMapper.readTree(body).path("data").path("uuids").get(0).asText();
        assertThat(uuid).matches("^[0-9a-f]{32}$");
        assertThat(uuid.charAt(12)).isEqualTo('7');
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=V9 - should reject the unknown version with 400")
    void shouldRejectInvalidUuidVersion() throws Exception {
        mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid version:")));
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
