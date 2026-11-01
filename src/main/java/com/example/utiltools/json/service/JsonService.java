package com.example.utiltools.json.service;

import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class JsonService {

    private static final int DEFAULT_INDENT_WIDTH = 2;

    private final ObjectMapper objectMapper;

    public JsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonFormatResponse format(JsonFormatRequest request) {
        try {
            JsonNode node = objectMapper.readTree(request.json());
            String formatted;
            if (Boolean.TRUE.equals(request.minify())) {
                formatted = objectMapper.writeValueAsString(node);
            } else {
                formatted = objectMapper.writer(prettyPrinter(request.indent())).writeValueAsString(node);
            }
            int sizeBytes = formatted.getBytes(StandardCharsets.UTF_8).length;
            return new JsonFormatResponse(formatted, sizeBytes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON content: " + e.getOriginalMessage(), e);
        }
    }

    private PrettyPrinter prettyPrinter(Integer indent) {
        int width = indent == null ? DEFAULT_INDENT_WIDTH : indent;
        if (width < 1 || width > 8) {
            throw new IllegalArgumentException("indent must be between 1 and 8");
        }
        return new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter(" ".repeat(width), "\n"));
    }

    public JsonValidateResponse validate(JsonValidateRequest request) {
        try {
            JsonNode node = objectMapper.readTree(request.json());
            String type = node.getNodeType().name();
            return new JsonValidateResponse(true, null, null, null, type);
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            Integer line = loc != null ? loc.getLineNr() : null;
            Integer column = loc != null ? loc.getColumnNr() : null;
            return new JsonValidateResponse(false, e.getOriginalMessage(), line, column, null);
        }
    }

    public JsonQueryResponse query(JsonQueryRequest request) {
        try {
            Object result = JsonPath.read(request.json(), request.jsonPath());
            return new JsonQueryResponse(request.jsonPath(), result);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid JSONPath syntax: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to evaluate JSONPath: " + e.getMessage(), e);
        }
    }
}
