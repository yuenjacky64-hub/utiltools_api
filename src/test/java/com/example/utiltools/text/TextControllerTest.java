package com.example.utiltools.text;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.example.utiltools.text.controller.TextController;
import com.example.utiltools.text.service.TextService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TextController.class, AuditController.class})
@Import({AuditTestSupport.class, AuditJournalService.class, AuditedOperations.class, AuditReplayService.class,
        CryptoService.class, DateTimeService.class, JsonService.class, TextService.class,
        GlobalExceptionHandler.class, ResilienceConfig.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class TextControllerTest {

    private static final String SUPPORTED_OPERATIONS_MESSAGE =
            "Supported operations: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE, REVERSE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditJournalService journal;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - should transform to snake case")
    void shouldTransformToSnakeCase() throws Exception {
        String body = """
                {
                    "text": "Hello, World!",
                    "operation": "SNAKE"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Text transformed successfully"))
                .andExpect(jsonPath("$.data.operation").value("SNAKE"))
                .andExpect(jsonPath("$.data.result").value("hello_world"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(11));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - slugs diacritics and rejects unknown operations with 400")
    void shouldSlugDiacriticsAndRejectUnknownOperation() throws Exception {
        String body = """
                {
                    "text": "Café déjà vu!",
                    "operation": "SLUG"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("SLUG"))
                .andExpect(jsonPath("$.data.result").value("cafe-deja-vu"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(12));

        String unknown = """
                {
                    "text": "Hello, World!",
                    "operation": "shout"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details", hasItem(SUPPORTED_OPERATIONS_MESSAGE)));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - sentence-cases text and echoes the normalised operation")
    void shouldSentenceCaseAndEchoOperation() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "text": "the API is FAST. try it.",
                                    "operation": "sentence_case"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("SENTENCE_CASE"))
                .andExpect(jsonPath("$.data.result").value("The api is fast. Try it."))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(24));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - reverses text and echoes the normalised operation")
    void shouldTransformToReverse() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "text": "hello",
                                    "operation": "REVERSE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.operation").value("REVERSE"))
                .andExpect(jsonPath("$.data.result").value("olleh"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(5));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - an unsupported operation is rejected by bean validation")
    void shouldRejectUnsupportedOperationWith400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "text": "Hello, World!",
                                    "operation": "FOO"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem(SUPPORTED_OPERATIONS_MESSAGE)))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0]").value(SUPPORTED_OPERATIONS_MESSAGE));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - every supported operation is accepted")
    void shouldAcceptEverySupportedOperation() throws Exception {
        String[] operations = {"UPPER", "LOWER", "TITLE", "CAMEL", "SNAKE", "KEBAB", "SLUG", "SENTENCE_CASE", "REVERSE"};

        for (String operation : operations) {
            mockMvc.perform(post("/api/v1/utils/text/transform")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "text": "Hello, World!",
                                        "operation": "%s"
                                    }
                                    """.formatted(operation)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.operation").value(operation))
                    .andExpect(jsonPath("$.data.result").isNotEmpty());
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/transform - a whitespace-padded operation is rejected by bean validation")
    void shouldRejectPaddedOperation() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "hi", "operation": " sentence_case "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details", hasItem(SUPPORTED_OPERATIONS_MESSAGE)));
    }

    @Test
    @DisplayName("blank text returns 400 VALIDATION_ERROR on both endpoints")
    void shouldRejectBlankText() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "   ", "operation": "SNAKE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));

        mockMvc.perform(post("/api/v1/utils/text/stats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));
    }

    @Test
    @DisplayName("a transform call is journalled as TEXT_TRANSFORM and replays as VERIFIED")
    void shouldJournalAndReplayTextTransform() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Hello, World!", "operation": "SNAKE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("hello_world"));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_TRANSFORM"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.result").value("hello_world"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        AuditEntry before = journal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("TEXT_TRANSFORM"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.result").value("hello_world"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"));

        AuditEntry after = journal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.TEXT_TRANSFORM);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/replace - should replace a regex with group references")
    void shouldReplaceTextRegexEnvelope() throws Exception {
        String body = """
                {
                    "text": "a1 b2",
                    "pattern": "(\\\\w)(\\\\d)",
                    "replacement": "$2$1"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Text replaced successfully"))
                .andExpect(jsonPath("$.data.result").value("1a 2b"))
                .andExpect(jsonPath("$.data.replacements").value(2))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(5));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/replace - invalid regex returns 400 BAD_REQUEST and is journalled FAILED")
    void shouldRejectInvalidRegexWithBadRequest() throws Exception {
        String body = """
                {
                    "text": "abc",
                    "pattern": "(",
                    "replacement": "x"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Invalid regex pattern '('")));

        mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_REPLACE"))
                .andExpect(jsonPath("$.data[0].state").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorCode").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/replace - blank text, absent replacement and oversized fields return 400 VALIDATION_ERROR")
    void shouldRejectInvalidReplaceWithValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "   ", "pattern": "a", "replacement": "b"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));

        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "abc", "pattern": "a"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("replacement is required")));

        String oversized = objectMapper.writeValueAsString(
                java.util.Map.of("text", "abc", "pattern", "a".repeat(513), "replacement", "b"));
        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("pattern must be at most 512 characters")));
    }

    @Test
    @DisplayName("a replace call is journalled as TEXT_REPLACE and replays as VERIFIED")
    void shouldJournalAndReplayTextReplace() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Hello HELLO", "pattern": "hello", "replacement": "bye",
                                 "caseInsensitive": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("bye bye"))
                .andExpect(jsonPath("$.data.replacements").value(2));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_REPLACE"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.result").value("bye bye"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        AuditEntry before = journal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("TEXT_REPLACE"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.result").value("bye bye"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"));

        AuditEntry after = journal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.TEXT_REPLACE);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/html-entities - encodes with ampersand first and returns the envelope")
    void shouldEncodeHtmlEntities() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/html-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "text": "<b>Fish & chips</b>"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("HTML entities processed successfully"))
                .andExpect(jsonPath("$.data.action").value("ENCODE"))
                .andExpect(jsonPath("$.data.result").value("&lt;b&gt;Fish &amp; chips&lt;/b&gt;"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(35));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/html-entities - decodes named and numeric references case-insensitively")
    void shouldDecodeHtmlEntities() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/html-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "decode", "text": "&lt;p&gt; &amp; &#39;q&#39; &#x41;"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("decode"))
                .andExpect(jsonPath("$.data.result").value("<p> & 'q' A"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.length").value(11));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/html-entities - blank text and an invalid action return 400 VALIDATION_ERROR")
    void shouldRejectInvalidHtmlEntitiesRequest() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/html-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "text": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));

        mockMvc.perform(post("/api/v1/utils/text/html-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "SHRUG", "text": "abc"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("action must be either ENCODE or DECODE")));
    }

    @Test
    @DisplayName("an html-entities call is journalled as TEXT_HTML_ENTITIES and replays as VERIFIED without a new entry")
    void shouldJournalAndReplayTextHtmlEntities() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/html-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ENCODE", "text": "Fish & <chips>"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("Fish &amp; &lt;chips&gt;"));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_HTML_ENTITIES"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.result").value("Fish &amp; &lt;chips&gt;"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        AuditEntry before = journal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();
        int entriesBefore = journal.size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("TEXT_HTML_ENTITIES"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.result").value("Fish &amp; &lt;chips&gt;"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"));

        AuditEntry after = journal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.TEXT_HTML_ENTITIES);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
        assertThat(journal.size()).isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/truncate - a 200 hard cut when wordBoundary=false")
    void shouldTruncateWithHardCut() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "text": "Hello, World! This is a longer sentence.",
                                    "maxLength": 12,
                                    "ellipsis": "...",
                                    "wordBoundary": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Text truncated successfully"))
                .andExpect(jsonPath("$.data.result").value("Hello, Wo..."))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.originalLength").value(40))
                .andExpect(jsonPath("$.data.length").value(12));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/truncate - a 200 word-boundary cut with the default ellipsis")
    void shouldTruncateAtWordBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "text": "Hello, World! This is a longer sentence.",
                                    "maxLength": 15,
                                    "wordBoundary": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("Hello, World!…"))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.originalLength").value(40))
                .andExpect(jsonPath("$.data.length").value(14));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/truncate - blank text and a missing or zero maxLength return 400 VALIDATION_ERROR")
    void shouldRejectInvalidTruncateRequestWith400() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "   ", "maxLength": 10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));

        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "hello"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("maxLength is required")));

        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "hello", "maxLength": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("maxLength must be at least 1")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/truncate - a maxLength that cannot hold content is journalled FAILED")
    void shouldRejectMaxLengthNotExceedingEllipsisWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "abcdefghij", "maxLength": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("maxLength must exceed the ellipsis length")));

        mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_TRUNCATE"))
                .andExpect(jsonPath("$.data[0].state").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorCode").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a truncate call is journalled as TEXT_TRUNCATE and replays as VERIFIED without a new entry")
    void shouldJournalAndReplayTextTruncate() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/truncate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Hello, World!", "maxLength": 8, "ellipsis": "...", "wordBoundary": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("Hello..."))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.originalLength").value(13))
                .andExpect(jsonPath("$.data.length").value(8));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_TRUNCATE"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.result").value("Hello..."))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        AuditEntry before = journal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();
        int entriesBefore = journal.size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("TEXT_TRUNCATE"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.result").value("Hello..."))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"));

        AuditEntry after = journal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.TEXT_TRUNCATE);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
        assertThat(journal.size()).isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/word-frequency - returns deterministic top words with ignored words excluded")
    void shouldReturnWordFrequencyWithIgnoreWordsAndLimit() throws Exception {
        String body = """
                {
                    "text": "the cat and the dog and the cat",
                    "limit": 2,
                    "ignoreWords": ["the"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/text/word-frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalWords").value(5))
                .andExpect(jsonPath("$.data.distinctWords").value(3))
                .andExpect(jsonPath("$.data.topWords.length()").value(2))
                .andExpect(jsonPath("$.data.topWords[0].word").value("and"))
                .andExpect(jsonPath("$.data.topWords[0].count").value(2))
                .andExpect(jsonPath("$.data.topWords[1].word").value("cat"))
                .andExpect(jsonPath("$.data.topWords[1].count").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/text/word-frequency - blank text, oversized limit and bad ignoreWords return 400 VALIDATION_ERROR")
    void shouldRejectInvalidWordFrequencyRequestWith400() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/word-frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("text is required")));

        mockMvc.perform(post("/api/v1/utils/text/word-frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "abc", "limit": 101}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("limit must be at most 100")));

        mockMvc.perform(post("/api/v1/utils/text/word-frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "abc", "ignoreWords": ["   "]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem("ignoreWords entries must not be blank")));
    }

    @Test
    @DisplayName("a word-frequency call is journalled as TEXT_WORD_FREQUENCY and replays as VERIFIED without a new entry")
    void shouldJournalAndReplayTextWordFrequency() throws Exception {
        mockMvc.perform(post("/api/v1/utils/text/word-frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "the cat and the dog", "limit": 3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalWords").value(5))
                .andExpect(jsonPath("$.data.distinctWords").value(4))
                .andExpect(jsonPath("$.data.topWords[0].word").value("the"));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("TEXT_WORD_FREQUENCY"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.totalWords").value(5))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        AuditEntry before = journal.find(id).orElseThrow();
        int entriesBefore = journal.size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("TEXT_WORD_FREQUENCY"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.totalWords").value(5))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"));

        AuditEntry after = journal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.TEXT_WORD_FREQUENCY);
        assertThat(after.completedAt()).isEqualTo(before.completedAt());
        assertThat(after.result()).isEqualTo(before.result());
        assertThat(journal.size()).isEqualTo(entriesBefore);
    }
}
