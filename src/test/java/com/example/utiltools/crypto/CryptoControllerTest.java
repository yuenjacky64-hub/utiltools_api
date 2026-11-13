package com.example.utiltools.crypto;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.example.utiltools.crypto.controller.CryptoController;
import com.example.utiltools.crypto.dto.UrlParseResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CryptoController.class, AuditController.class})
@Import({AuditTestSupport.class, AuditJournalService.class, AuditedOperations.class, AuditReplayService.class, CryptoService.class,
        DateTimeService.class, JsonService.class, TextService.class, ResilienceConfig.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class CryptoControllerTest {

    private static final String UUID_V7_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    private static final String UUID_V8_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditJournalService journal;

    @Autowired
    private AuditedOperations auditedOperations;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

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
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=V8 - should return two ascending UUIDv8 values")
    void shouldReturnUuidV8List() throws Exception {
        String body = mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V8")
                        .param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value("V8"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.uuids.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode uuids = objectMapper.readTree(body).path("data").path("uuids");
        List<String> values = new ArrayList<>();
        uuids.forEach(node -> values.add(node.asText()));

        assertThat(values).hasSize(2).isSorted();
        assertThat(values).allSatisfy(value -> {
            assertThat(value).matches(UUID_V8_PATTERN);
            assertThat(value.charAt(14)).isEqualTo('8');
        });
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=v8&compact=true - should return compact UUIDv8 values")
    void shouldReturnCompactUuidV8() throws Exception {
        String body = mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "v8")
                        .param("compact", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("V8"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.uuids.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String uuid = objectMapper.readTree(body).path("data").path("uuids").get(0).asText();
        assertThat(uuid).matches("^[0-9a-f]{32}$");
        assertThat(uuid.charAt(12)).isEqualTo('8');
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=V8 - should reject invalid count and version with 400")
    void shouldRejectInvalidUuidV8Inputs() throws Exception {
        for (String count : List.of("0", "101")) {
            mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                            .param("version", "V8")
                            .param("count", count))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message")
                            .value("UUID count must be between 1 and 100"));
        }

        mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V8")
                        .param("count", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Invalid version:")));

        List<AuditEntry> failed = journal.list(200, JournalState.FAILED).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_UUID)
                .toList();
        assertThat(failed).hasSizeGreaterThanOrEqualTo(3);
        assertThat(failed).allSatisfy(entry -> assertThat(entry.errorCode()).isEqualTo("BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid?version=V8 - should journal and replay through CRYPTO_UUID")
    void shouldJournalAndReplayUuidV8() throws Exception {
        int before = (int) journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_UUID)
                .count();

        mockMvc.perform(get("/api/v1/utils/crypto/uuid")
                        .param("version", "V8")
                        .param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("V8"));

        List<AuditEntry> entries = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_UUID)
                .toList();
        assertThat(entries).hasSize(before + 1);

        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.request().path("version").asText()).isEqualTo("V8");
        assertThat(entry.request().path("count").asInt()).isEqualTo(2);
        assertThat(entry.request().path("compact").asBoolean()).isFalse();

        assertThat(OperationType.CRYPTO_UUID.isDeterministic()).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> storedRequest = objectMapper.convertValue(entry.request(), Map.class);
        UuidResponse replayed = (UuidResponse) auditedOperations.replayInvoke(
                OperationType.CRYPTO_UUID, storedRequest);

        assertThat(replayed.version()).isEqualTo("V8");
        assertThat(replayed.count()).isEqualTo(2);
        assertThat(replayed.uuids()).hasSize(2)
                .allSatisfy(uuid -> assertThat(uuid).matches(UUID_V8_PATTERN));
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
    @DisplayName("GET /api/v1/utils/crypto/uuid/inspect - shouldInspectCanonicalV4")
    void shouldInspectCanonicalV4() throws Exception {
        String body = mockMvc.perform(get("/api/v1/utils/crypto/uuid/inspect")
                        .param("value", "550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.canonical").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.data.compact").value("550e8400e29b41d4a716446655440000"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.variant").value("RFC_4122"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.has("unixTimestampMillis")).isTrue();
        assertThat(data.path("unixTimestampMillis").isNull()).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid/inspect - shouldRejectInvalidInspectInputAsBadRequest")
    void shouldRejectInvalidInspectInputAsBadRequest() throws Exception {
        for (String value : List.of(
                "   ",
                "550e8400e29b41d4a71644665544000",
                "550e8400e29b41d4a71644665544000g",
                "550e8-400e29b41d4a716446655440000",
                "550e8400-e29b-41d4-a716-44665544000")) {
            mockMvc.perform(get("/api/v1/utils/crypto/uuid/inspect").param("value", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }

        mockMvc.perform(get("/api/v1/utils/crypto/uuid/inspect"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/crypto/uuid/inspect - shouldNotJournalInspectCall")
    void shouldNotJournalInspectCall() throws Exception {
        int before = journal.list(200, null).size();

        mockMvc.perform(get("/api/v1/utils/crypto/uuid/inspect")
                        .param("value", "550e8400e29b41d4a716446655440000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4));

        mockMvc.perform(get("/api/v1/utils/crypto/uuid/inspect")
                        .param("value", "550e8400e29b41d4a71644665544000"))
                .andExpect(status().isBadRequest());

        assertThat(journal.list(200, null).size()).isEqualTo(before);
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
    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should reject unsupported algorithm with validation error")
    void shouldRejectUnsupportedAlgorithmWithValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "INVALID", "content": "test"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]")
                        .value("Unsupported hash algorithm. Supported: SHA-256, SHA-384, SHA-512, MD5, SHA-1"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should accept all supported algorithms case-insensitively")
    void shouldAcceptAllSupportedAlgorithmsCaseInsensitively() throws Exception {
        for (String algorithm : List.of("SHA-256", "SHA-384", "SHA-512", "SHA-1", "MD5",
                "sha-256", "sha-384", "Sha-512", "md5", "sha-1")) {
            mockMvc.perform(post("/api/v1/utils/crypto/hash")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"algorithm": "%s", "content": "test"}
                                    """.formatted(algorithm)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.algorithm").value(algorithm.toUpperCase()));
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should accept SHA384 alias and canonicalise to SHA-384")
    void shouldAcceptSha384Alias() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "SHA384", "content": "abc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.algorithm").value("SHA-384"))
                .andExpect(jsonPath("$.data.isHmac").value(false))
                .andExpect(jsonPath("$.data.digest").value(
                        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed"
                                + "8086072ba1e7cc2358baeca134c825a7"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should reject SHA-224 as unsupported")
    void shouldRejectSha224AsUnsupported() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "SHA-224", "content": "abc"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value(
                        "Unsupported hash algorithm. Supported: SHA-256, SHA-384, SHA-512, MD5, SHA-1"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - should reject algorithm with surrounding whitespace")
    void shouldRejectAlgorithmWithSurroundingWhitespace() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": " SHA-256 ", "content": "test"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]")
                        .value("Unsupported hash algorithm. Supported: SHA-256, SHA-384, SHA-512, MD5, SHA-1"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/hash - echoes X-Request-Id on success and error envelopes")
    void shouldCarryRequestIdInEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .header("X-Request-Id", "corr-crypto1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "SHA-256", "content": "Hello World"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-crypto1234"))
                .andExpect(jsonPath("$.requestId").value("corr-crypto1234"));

        mockMvc.perform(post("/api/v1/utils/crypto/hash")
                        .header("X-Request-Id", "corr-crypto5678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"algorithm": "NOPE-1", "content": "Hello World"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "corr-crypto5678"))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("corr-crypto5678"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url - should encode for each mode")
    void shouldPercentEncodeViaController() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "a b/c?d=\\u00e9", "mode": "COMPONENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("URL operation completed successfully"))
                .andExpect(jsonPath("$.data.action").value("ENCODE"))
                .andExpect(jsonPath("$.data.mode").value("COMPONENT"))
                .andExpect(jsonPath("$.data.result").value("a%20b%2Fc%3Fd%3D%C3%A9"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "encode", "content": "a b/c?d=\\u00e9", "mode": "path"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("PATH"))
                .andExpect(jsonPath("$.data.result").value("a%20b/c%3Fd%3D%C3%A9"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "a b/c?d=\\u00e9", "mode": "FORM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("a+b%2Fc%3Fd%3D%C3%A9"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url - should default mode to COMPONENT and decode round trips")
    void shouldDecodeUrlWithModeSemantics() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "a%20b%2Fc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("COMPONENT"))
                .andExpect(jsonPath("$.data.result").value("a b/c"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "a+b", "mode": "FORM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("a b"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "a+b", "mode": "COMPONENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("a+b"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "%2B", "mode": "FORM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("+"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url - should answer 400 for malformed escapes and invalid input")
    void shouldReturnValidationAndBadRequestErrors() throws Exception {
        for (String malformed : List.of("%2", "%ZZ", "%E9")) {
            mockMvc.perform(post("/api/v1/utils/crypto/url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"action": "DECODE", "content": "%s", "mode": "COMPONENT"}
                                    """.formatted(malformed)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "SCRAMBLE", "content": "abc"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "abc", "mode": "SEGMENT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url/parse - should return URL components and decoded parameters")
    void shouldParseUrlViaController() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "url": "https://alice:s3cr3t@example.com:8443/a/b?tag=one&tag=two&empty=&q=a+b#frag"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("URL parse completed successfully"))
                .andExpect(jsonPath("$.data.scheme").value("https"))
                .andExpect(jsonPath("$.data.userInfo").value("alice:s3cr3t"))
                .andExpect(jsonPath("$.data.host").value("example.com"))
                .andExpect(jsonPath("$.data.port").value(8443))
                .andExpect(jsonPath("$.data.path").value("/a/b"))
                .andExpect(jsonPath("$.data.query").value("tag=one&tag=two&empty=&q=a+b"))
                .andExpect(jsonPath("$.data.fragment").value("frag"))
                .andExpect(jsonPath("$.data.parameters.length()").value(4))
                .andExpect(jsonPath("$.data.parameters[0].name").value("tag"))
                .andExpect(jsonPath("$.data.parameters[0].value").value("one"))
                .andExpect(jsonPath("$.data.parameters[1].name").value("tag"))
                .andExpect(jsonPath("$.data.parameters[1].value").value("two"))
                .andExpect(jsonPath("$.data.parameters[2].name").value("empty"))
                .andExpect(jsonPath("$.data.parameters[2].value").value(""))
                .andExpect(jsonPath("$.data.parameters[3].value").value("a b"));

        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "/api/v1/items"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheme").doesNotExist())
                .andExpect(jsonPath("$.data.host").doesNotExist())
                .andExpect(jsonPath("$.data.port").doesNotExist())
                .andExpect(jsonPath("$.data.path").value("/api/v1/items"))
                .andExpect(jsonPath("$.data.parameters.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url/parse - malformed input returns 400 BAD_REQUEST, never 500")
    void shouldReturnBadRequestForUrlParse() throws Exception {
        for (String url : List.of(
                "https://example.com/a b",
                "https://example.com:99999999/path",
                "mailto:alice@example.com",
                "/path?a=%2",
                "/path/caf%C3%",
                "/path?a=%FF")) {
            String body = objectMapper.writeValueAsString(Map.of("url", url));
            mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }

        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s"}
                                """.formatted("x".repeat(8193))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        // An over-cap query list is rejected, never truncated.
        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", urlWithParameters(129)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url/parse - journal and replay are SUCCEEDED and VERIFIED")
    void shouldJournalAndReplayUrlParseOperation() throws Exception {
        int before = (int) journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL_PARSE)
                .count();

        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://example.com/a?b=1&b=2#frag"}
                                """))
                .andExpect(status().isOk());

        AuditEntry entry = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL_PARSE)
                .findFirst()
                .orElseThrow();
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.result().path("host").asText()).isEqualTo("example.com");
        assertThat(entry.result().path("parameters")).hasSize(2);

        String replayBody = mockMvc.perform(post("/api/v1/utils/audit/entries/" + entry.id() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value(ReplayState.VERIFIED.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode replayed = objectMapper.readTree(replayBody).path("data");
        assertThat(replayed.path("state").asText()).isEqualTo(JournalState.SUCCEEDED.name());
        assertThat(replayed.path("completedAt").asText()).isEqualTo(entry.completedAt().toString());
        UrlParseResponse original = objectMapper.treeToValue(entry.result(), UrlParseResponse.class);
        UrlParseResponse replayedResult = objectMapper.treeToValue(replayed.path("result"), UrlParseResponse.class);
        assertThat(replayedResult).isEqualTo(original);
        assertThat(replayedResult.parameters()).hasSize(2);

        List<AuditEntry> after = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL_PARSE)
                .toList();
        assertThat(after).hasSize(before + 1);
        AuditEntry stored = after.stream().filter(e -> e.id().equals(entry.id())).findFirst().orElseThrow();
        assertThat(stored.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(stored.result()).isEqualTo(entry.result());
        assertThat(stored.completedAt()).isEqualTo(entry.completedAt());
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url/parse - a maximal 8192-char, 128-parameter URL stays replayable")
    void shouldKeepMaximalUrlParseReplayable() throws Exception {
        String url = maximalUrlParseUrl();
        assertThat(url).hasSize(8192);

        mockMvc.perform(post("/api/v1/utils/crypto/url/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parameters.length()").value(128));

        AuditEntry entry = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL_PARSE)
                .findFirst()
                .orElseThrow();
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.result().path("parameters")).hasSize(128);

        mockMvc.perform(post("/api/v1/utils/audit/entries/" + entry.id() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value(ReplayState.VERIFIED.name()))
                .andExpect(jsonPath("$.data.result.parameters.length()").value(128));
    }

    private static String maximalUrlParseUrl() {
        String prefix = "/deep/path?";
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            pairs.add("k" + i + "=" + "v".repeat(56));
        }
        String url = prefix + String.join("&", pairs);
        int pad = 8192 - url.length();
        assertThat(pad).isGreaterThanOrEqualTo(0);
        pairs.set(pairs.size() - 1, pairs.get(pairs.size() - 1) + "v".repeat(pad));
        return prefix + String.join("&", pairs);
    }

    private static String urlWithParameters(int count) {
        StringBuilder url = new StringBuilder("/path?");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                url.append('&');
            }
            url.append('p').append(i).append('=').append(i);
        }
        return url.toString();
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url/parse - is its own route, distinct from /url")
    void shouldExposeUrlParseAsItsOwnRoute() {
        Set<String> parsePatterns = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getMethod().getName().equals("parseUrl"))
                .map(e -> e.getKey().getPatternValues().toString())
                .collect(Collectors.toSet());
        Set<String> urlPatterns = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getMethod().getName().equals("url"))
                .map(e -> e.getKey().getPatternValues().toString())
                .collect(Collectors.toSet());

        assertThat(parsePatterns).containsExactly("[/api/v1/utils/crypto/url/parse]");
        assertThat(urlPatterns).containsExactly("[/api/v1/utils/crypto/url]");
        assertThat(handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getMethod().getName().equals("parseUrl"))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url - should journal a SUCCEEDED CRYPTO_URL entry")
    void shouldJournalUrlOperation() throws Exception {
        int before = (int) journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .count();

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "a b", "mode": "PATH"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("a%20b"));

        List<AuditEntry> urls = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .toList();
        assertThat(urls).hasSize(before + 1);
        AuditEntry entry = urls.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.result().path("result").asText()).isEqualTo("a%20b");

        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "%2", "mode": "COMPONENT"}
                                """))
                .andExpect(status().isBadRequest());

        urls = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .toList();
        assertThat(urls).hasSize(before + 2);
        assertThat(urls.get(0).state()).isEqualTo(JournalState.FAILED);
        assertThat(urls.get(0).errorCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/url - replay of a CRYPTO_URL entry is VERIFIED and leaves it untouched")
    void shouldReplayUrlOperationAsVerified() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "a b", "mode": "PATH"}
                                """))
                .andExpect(status().isOk());

        int before = (int) journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .count();
        AuditEntry entry = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .findFirst()
                .orElseThrow();

        String replayBody = mockMvc.perform(post("/api/v1/utils/audit/entries/" + entry.id() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value(ReplayState.VERIFIED.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode replayed = objectMapper.readTree(replayBody).path("data");
        assertThat(replayed.path("state").asText()).isEqualTo(JournalState.SUCCEEDED.name());
        assertThat(replayed.path("result").path("result").asText()).isEqualTo("a%20b");
        assertThat(replayed.path("completedAt").asText()).isEqualTo(entry.completedAt().toString());

        List<AuditEntry> after = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_URL)
                .toList();
        assertThat(after).hasSize(before);
        AuditEntry stored = after.stream().filter(e -> e.id().equals(entry.id())).findFirst().orElseThrow();
        assertThat(stored.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(stored.result()).isEqualTo(entry.result());
        assertThat(stored.completedAt()).isEqualTo(entry.completedAt());
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/base32 - should encode and decode RFC 4648 Base32")
    void shouldEncodeAndDecodeBase32() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "foobar"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("ENCODE"))
                .andExpect(jsonPath("$.data.result").value("MZXW6YTBOI======"));

        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "MZXW6YTBOI======"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("DECODE"))
                .andExpect(jsonPath("$.data.result").value("foobar"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/base32 - should accept lowercase and unpadded decode input")
    void shouldDecodeBase32Tolerantly() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "decode", "content": "mzxw6ytboi"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("DECODE"))
                .andExpect(jsonPath("$.data.result").value("foobar"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/base32 - should reject invalid input with the unified error envelope")
    void shouldRejectInvalidBase32() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "MZXW6YTBOI=====!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "DECODE", "content": "MZXW6YTBO"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ROT13", "content": "foobar"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/base32 - should journal and replay a CRYPTO_BASE32 entry")
    void shouldJournalAndReplayBase32Operation() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/base32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "content": "foobar"}
                                """))
                .andExpect(status().isOk());

        List<AuditEntry> entries = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_BASE32)
                .toList();
        assertThat(entries).isNotEmpty();
        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.result().path("result").asText()).isEqualTo("MZXW6YTBOI======");

        String replayBody = mockMvc.perform(post("/api/v1/utils/audit/entries/" + entry.id() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value(ReplayState.VERIFIED.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode replayed = objectMapper.readTree(replayBody).path("data");
        assertThat(replayed.path("result").path("result").asText()).isEqualTo("MZXW6YTBOI======");
    }
    // --- POST /api/v1/utils/crypto/jwt/decode -------------------------------------------------

    private static final String JWT_KNOWN_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    private static final String JWT_KNOWN_SIGNATURE = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    @Test
    @DisplayName("POST /api/v1/utils/crypto/jwt/decode - should decode the HS256 example as unverified claims")
    void shouldDecodeHs256Example() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + JWT_KNOWN_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.algorithm").value("HS256"))
                .andExpect(jsonPath("$.data.type").value("JWT"))
                .andExpect(jsonPath("$.data.header.alg").value("HS256"))
                .andExpect(jsonPath("$.data.header.typ").value("JWT"))
                .andExpect(jsonPath("$.data.payload.sub").value("1234567890"))
                .andExpect(jsonPath("$.data.payload.name").value("John Doe"))
                .andExpect(jsonPath("$.data.payload.iat").value(1516239022))
                .andExpect(jsonPath("$.data.signaturePresent").value(true))
                .andExpect(jsonPath("$.data.signatureBytes").value(32))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/jwt/decode - should answer BAD_REQUEST for malformed and VALIDATION_ERROR for blank tokens")
    void shouldRejectMalformedAndBlankTokens() throws Exception {
        mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"only-two.segments\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/jwt/decode - should journal a replayable CRYPTO_JWT_DECODE entry")
    void shouldJournalJwtDecodeOperation() throws Exception {
        Set<String> before = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_JWT_DECODE)
                .map(AuditEntry::id)
                .collect(Collectors.toSet());

        mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + JWT_KNOWN_TOKEN + "\"}"))
                .andExpect(status().isOk());

        List<AuditEntry> entries = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_JWT_DECODE)
                .filter(e -> !before.contains(e.id()))
                .toList();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.result().path("algorithm").asText()).isEqualTo("HS256");
        assertThat(entry.result().path("payload").path("sub").asText()).isEqualTo("1234567890");
        assertThat(entry.result().path("verified").asBoolean()).isFalse();

        String listing = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listing).contains("CRYPTO_JWT_DECODE");

        String replayBody = mockMvc.perform(post("/api/v1/utils/audit/entries/" + entry.id() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value(ReplayState.VERIFIED.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode replayed = objectMapper.readTree(replayBody).path("data");
        assertThat(replayed.path("replay").path("state").asText()).isEqualTo(ReplayState.VERIFIED.name());
        assertThat(replayed.path("result").path("algorithm").asText()).isEqualTo("HS256");
        assertThat(replayed.path("result").path("payload").path("sub").asText()).isEqualTo("1234567890");
        assertThat(replayed.path("result").path("verified").asBoolean()).isFalse();
        assertThat(Instant.parse(replayed.path("completedAt").asText())).isEqualTo(entry.completedAt());

        List<AuditEntry> after = journal.list(200, null).stream()
                .filter(e -> e.operation() == OperationType.CRYPTO_JWT_DECODE)
                .toList();
        assertThat(after).hasSize(before.size() + 1);
        assertThat(after).extracting(AuditEntry::id).contains(entry.id());
        AuditEntry stored = after.get(0);
        assertThat(stored.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(stored.result()).isEqualTo(entry.result());
        assertThat(stored.completedAt()).isEqualTo(entry.completedAt());
    }

    @Test
    @DisplayName("POST /api/v1/utils/crypto/jwt/decode - should never echo the raw token or signature value")
    void shouldNeverEchoTokenOrSignature() throws Exception {
        String successBody = mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + JWT_KNOWN_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(successBody).doesNotContain(JWT_KNOWN_TOKEN);
        assertThat(successBody).doesNotContain(JWT_KNOWN_SIGNATURE);

        String malformedBody = mockMvc.perform(post("/api/v1/utils/crypto/jwt/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(malformedBody).doesNotContain(JWT_KNOWN_SIGNATURE);

        String listing = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listing).contains("\"token\":\"***\"");
        assertThat(listing).doesNotContain(JWT_KNOWN_TOKEN);
        assertThat(listing).doesNotContain(JWT_KNOWN_SIGNATURE);
    }
}
