package com.example.utiltools.json;

import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.example.utiltools.json.service.JsonService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonServiceTest {

    private JsonService jsonService;

    @BeforeEach
    void setUp() {
        jsonService = new JsonService(new ObjectMapper());
    }

    @Test
    @DisplayName("Should pretty print JSON correctly")
    void shouldPrettyPrintJson() {
        String compact = "{\"name\":\"Alice\",\"age\":30}";
        JsonFormatRequest request = new JsonFormatRequest(compact, false, 2);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).contains("\n");
        assertThat(response.formattedJson()).contains("\"name\" : \"Alice\"");
        assertThat(response.sizeBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should minify formatted JSON correctly")
    void shouldMinifyJson() {
        String formatted = """
                {
                   "name": "Alice",
                   "age": 30
                }
                """;
        JsonFormatRequest request = new JsonFormatRequest(formatted, true, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"name\":\"Alice\",\"age\":30}");
    }

    @Test
    @DisplayName("Should indent nested JSON with the supplied width of four spaces")
    void shouldIndentNestedOutputWithWidthFour() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", false, 4);
        JsonFormatResponse response = jsonService.format(request);

        String[] lines = response.formattedJson().split("\\R");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo("{");
        assertThat(lines[1]).isEqualTo("    \"a\" : {");
        assertThat(lines[2]).isEqualTo("        \"b\" : 1");
        assertThat(lines[3]).isEqualTo("    }");
        assertThat(lines[4]).isEqualTo("}");
    }

    @Test
    @DisplayName("Should default to two spaces when indent is omitted")
    void shouldDefaultToTwoSpacesWhenIndentOmitted() throws JsonProcessingException {
        String json = "{\"a\":{\"b\":1}}";
        JsonFormatRequest request = new JsonFormatRequest(json, false, null);
        JsonFormatResponse response = jsonService.format(request);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertThat(response.formattedJson())
                .isEqualTo(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    @Test
    @DisplayName("Should ignore indent when minify is true")
    void shouldIgnoreIndentWhenMinifyTrue() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", true, 8);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(response.formattedJson()).doesNotContain("\n");
    }

    @Test
    @DisplayName("Should compute sizeBytes from the final formatted output")
    void shouldComputeSizeBytesFromFormattedOutput() {
        String json = "{\"a\":{\"b\":1}}";
        JsonFormatResponse indented = jsonService.format(new JsonFormatRequest(json, false, 4));
        JsonFormatResponse minified = jsonService.format(new JsonFormatRequest(json, true, null));

        assertThat(indented.sizeBytes())
                .isEqualTo(indented.formattedJson().getBytes(StandardCharsets.UTF_8).length);
        assertThat(indented.sizeBytes()).isNotEqualTo(minified.sizeBytes());
    }

    @Test
    @DisplayName("Should validate valid JSON")
    void shouldValidateValidJson() {
        JsonValidateRequest request = new JsonValidateRequest("{\"status\": \"active\", \"code\": 200}");
        JsonValidateResponse response = jsonService.validate(request);

        assertThat(response.valid()).isTrue();
        assertThat(response.error()).isNull();
        assertThat(response.type()).isEqualTo("OBJECT");
    }

    @Test
    @DisplayName("Should detect invalid JSON and report error details")
    void shouldDetectInvalidJson() {
        JsonValidateRequest request = new JsonValidateRequest("{\"unclosed\": [1, 2, }");
        JsonValidateResponse response = jsonService.validate(request);

        assertThat(response.valid()).isFalse();
        assertThat(response.error()).isNotBlank();
        assertThat(response.line()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should execute JSONPath query accurately")
    @SuppressWarnings("unchecked")
    void shouldExecuteJsonPathQuery() {
        String json = """
                {
                  "store": {
                    "books": [
                      {"title": "Java 21 in Action", "price": 45.0},
                      {"title": "Spring Boot 4 Guide", "price": 50.0}
                    ]
                  }
                }
                """;
        JsonQueryRequest request = new JsonQueryRequest(json, "$.store.books[*].title");
        JsonQueryResponse response = jsonService.query(request);

        assertThat(response.jsonPath()).isEqualTo("$.store.books[*].title");
        assertThat(response.result()).isInstanceOf(List.class);
        List<String> titles = (List<String>) response.result();
        assertThat(titles).containsExactly("Java 21 in Action", "Spring Boot 4 Guide");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on malformed JSONPath")
    void shouldThrowOnMalformedJsonPath() {
        JsonQueryRequest request = new JsonQueryRequest("{\"a\": 1}", "invalid$$path[");
        assertThatThrownBy(() -> jsonService.query(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSONPath syntax");
    }
}
