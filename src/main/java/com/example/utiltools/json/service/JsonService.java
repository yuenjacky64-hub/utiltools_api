package com.example.utiltools.json.service;

import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonPatchOperation;
import com.example.utiltools.json.dto.JsonPatchRequest;
import com.example.utiltools.json.dto.JsonPatchResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonUnflattenRequest;
import com.example.utiltools.json.dto.JsonUnflattenResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.example.utiltools.json.exception.JsonPatchRollbackException;
import com.example.utiltools.resilience.AuditTrail;
import com.example.utiltools.resilience.CircuitBreaker;
import com.example.utiltools.resilience.CircuitBreakerConfig;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class JsonService {

    private static final int DEFAULT_INDENT_WIDTH = 2;
    private static final int DEFAULT_AUDIT_CAPACITY = 1024;
    /** Separator used by flatten/unflatten when the request does not supply one. */
    static final String DEFAULT_SEPARATOR = ".";
    private static final int MAX_SEPARATOR_LENGTH = 8;
    /** Deepest accepted path: 64 keys joined by the separator. */
    static final int MAX_DEPTH = 64;
    /** Most leaves a flatten response, or keys a flat document, may carry. */
    static final int MAX_KEYS = 10000;

    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;

    public JsonService(ObjectMapper objectMapper) {
        this(objectMapper, new CircuitBreaker("json-query", CircuitBreakerConfig.defaults(),
                new AuditTrail(DEFAULT_AUDIT_CAPACITY), Clock.systemUTC()));
    }

    @Autowired
    public JsonService(ObjectMapper objectMapper, CircuitBreaker circuitBreaker) {
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
    }

    public JsonFormatResponse format(JsonFormatRequest request) {
        try {
            JsonNode node = objectMapper.readTree(request.json());
            if (Boolean.TRUE.equals(request.sortKeys())) {
                node = sortKeysRecursively(node);
            }
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

    /**
     * Rewrites the parsed tree so that every object's field names appear in ascending
     * {@link String#compareTo(String)} order. Array element order and scalar values are preserved;
     * the input node is never mutated, a new tree is returned instead.
     */
    private JsonNode sortKeysRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, sortKeysRecursively(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                sorted.add(sortKeysRecursively(element));
            }
            return sorted;
        }
        return node;
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
        return circuitBreaker.execute(() -> queryInternal(request));
    }

    private JsonQueryResponse queryInternal(JsonQueryRequest request) {
        try {
            Object result = JsonPath.read(request.json(), request.jsonPath());
            return new JsonQueryResponse(request.jsonPath(), result);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid JSONPath syntax: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to evaluate JSONPath: " + e.getMessage(), e);
        }
    }

    /**
     * Applies an ordered RFC 6902-style operation list to a deep copy of the caller's document.
     * All-or-nothing: on the first failing operation every previously applied change is reverted in
     * reverse order and {@link JsonPatchRollbackException} carries the full transition audit.
     */
    public JsonPatchResponse patch(JsonPatchRequest request) {
        JsonNode original;
        try {
            original = objectMapper.readTree(request.json());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON content: " + e.getOriginalMessage(), e);
        }
        if (original == null) {
            throw new IllegalArgumentException("Invalid JSON content: document is empty");
        }

        PatchDocument doc = new PatchDocument(original.deepCopy());
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());

        List<JsonPatchOperation> inputs = request.operations();
        List<JsonPatchOperation> audit = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            JsonPatchOperation input = inputs.get(i);
            String op = input == null ? null : trimToNull(input.op());
            String path = input == null ? null : trimToNull(input.path());
            String from = input == null ? null : trimToNull(input.from());
            JsonNode value = input == null ? null : deepCopyOrNull(input.value());
            // Caller-supplied index / status / snapshots / error are ignored: the service re-derives them.
            audit.add(new JsonPatchOperation(i, op, path, from, value,
                    JsonPatchOperation.PENDING, null, null, null));
        }

        List<Runnable> inverses = new ArrayList<>(audit.size());
        for (int i = 0; i < audit.size(); i++) {
            JsonPatchOperation entry = audit.get(i);
            JsonNode before = deepCopyOrNull(doc.resolveQuietly(entry.path()));
            try {
                inverses.add(applyOperation(doc, entry));
                JsonNode after = deepCopyOrNull(doc.resolveQuietly(entry.path()));
                audit.set(i, entry.withAudit(dryRun ? JsonPatchOperation.VALIDATED
                        : JsonPatchOperation.APPLIED, before, after, null));
            } catch (PatchFailure | IllegalArgumentException e) {
                String reason = e.getMessage() == null ? "Operation failed" : e.getMessage();
                audit.set(i, entry.withAudit(JsonPatchOperation.FAILED, before, null, reason));

                int rolledBack = 0;
                for (int j = i - 1; j >= 0; j--) {
                    JsonPatchOperation previous = audit.get(j);
                    if (!JsonPatchOperation.APPLIED.equals(previous.status())
                            && !JsonPatchOperation.VALIDATED.equals(previous.status())) {
                        continue;
                    }
                    String inverseError = null;
                    try {
                        inverses.get(j).run();
                    } catch (Exception ignored) {
                        // A failing inverse must never surface as a 500 or leak a half-patched document.
                        inverseError = "Rollback failed: " + ignored.getMessage();
                    }
                    rolledBack++;
                    audit.set(j, previous.withAudit(JsonPatchOperation.ROLLED_BACK,
                            previous.beforeValue(), deepCopyOrNull(doc.resolveQuietly(previous.path())),
                            inverseError));
                }
                throw new JsonPatchRollbackException(i, entry.op(), entry.path(), rolledBack, audit, reason);
            }
        }

        int applied = dryRun ? 0 : audit.size();
        return new JsonPatchResponse(dryRun ? null : doc.root(), applied, 0, dryRun, List.copyOf(audit));
    }

    /**
     * Collapses an object tree into dot-path key/value pairs. Insertion order is preserved, arrays
     * and empty objects stay untouched leaf values, and a key that is empty or already contains the
     * separator is rejected so that {@link #unflatten(JsonUnflattenRequest)} can always rebuild the tree.
     * Every emitted path is also re-split on the separator and must yield exactly the walked name
     * chain, which keeps the round trip exact for multi-character separators as well.
     */
    public JsonFlattenResponse flatten(JsonFlattenRequest request) {
        String separator = normaliseSeparator(request.separator());
        JsonNode root = parseTree(request.json());
        if (!root.isObject()) {
            throw new IllegalArgumentException("json root must be a JSON object");
        }

        ObjectNode flattened = objectMapper.createObjectNode();
        FlattenTally tally = new FlattenTally();
        walkFlatten((ObjectNode) root, new String[0], "", 1, separator, flattened, tally);
        if (tally.keyCount > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "flattened document must contain at most " + MAX_KEYS + " keys");
        }
        return new JsonFlattenResponse(flattened, tally.keyCount, tally.maxDepth);
    }

    private void walkFlatten(ObjectNode node, String[] chain, String prefix, int depth, String separator,
                             ObjectNode flattened, FlattenTally tally) {
        Iterator<Map.Entry<String, JsonNode>> members = node.fields();
        while (members.hasNext()) {
            Map.Entry<String, JsonNode> member = members.next();
            String key = member.getKey();
            // An empty member name would flatten to a path with an empty segment, which unflatten rejects
            // (row 4), so it is refused here to keep the round-trip invariant true for every accepted document.
            if (key.isEmpty()) {
                throw new IllegalArgumentException("key must not be empty");
            }
            if (key.contains(separator)) {
                throw new IllegalArgumentException(
                        "key '" + key + "' must not contain the separator '" + separator + "'");
            }
            String path = prefix.isEmpty() ? key : prefix + separator + key;
            // A self-overlapping multi-character separator can swallow a join boundary: with "aa",
            // {"ba":{"b":1}} joins to "baaab", which unflatten re-splits as ["b","ab"] and rebuilds a
            // different document. Requiring the emitted path to split back into exactly the walked name
            // chain refuses those inputs here instead of corrupting them silently there.
            String[] expected = Arrays.copyOf(chain, chain.length + 1);
            expected[chain.length] = key;
            if (!Arrays.equals(path.split(Pattern.quote(separator), -1), expected)) {
                throw new IllegalArgumentException("ambiguous key path '" + path + "' for separator '"
                        + separator + "': it does not split back into its own key segments");
            }
            JsonNode value = member.getValue();
            if (value.isObject() && !value.isEmpty()) {
                if (depth + 1 > MAX_DEPTH) {
                    throw new IllegalArgumentException("document depth must not exceed " + MAX_DEPTH);
                }
                walkFlatten((ObjectNode) value, expected, path, depth + 1, separator, flattened, tally);
            } else {
                // Arrays, empty objects and scalars are leaves: they are stored verbatim as their own value.
                flattened.set(path, value);
                tally.keyCount++;
                tally.maxDepth = Math.max(tally.maxDepth, depth);
            }
        }
    }

    /**
     * Rebuilds a nested object from flat separator-delimited keys. Every reject is an
     * {@link IllegalArgumentException} whose message names the offending key or limit.
     */
    public JsonUnflattenResponse unflatten(JsonUnflattenRequest request) {
        String separator = normaliseSeparator(request.separator());
        JsonNode root = parseTree(request.json());
        if (!root.isObject()) {
            throw new IllegalArgumentException("json root must be a JSON object");
        }

        ObjectNode flat = (ObjectNode) root;
        List<String[]> paths = new ArrayList<>();
        List<JsonNode> values = new ArrayList<>();
        int maxDepth = 0;
        Iterator<Map.Entry<String, JsonNode>> members = flat.fields();
        while (members.hasNext()) {
            Map.Entry<String, JsonNode> member = members.next();
            String key = member.getKey();
            // -1 keeps trailing empty segments so that 'a.' is reported instead of silently dropped.
            String[] segments = key.split(Pattern.quote(separator), -1);
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException("empty path segment in key '" + key + "'");
                }
            }
            if (segments.length > MAX_DEPTH) {
                throw new IllegalArgumentException("document depth must not exceed " + MAX_DEPTH);
            }
            maxDepth = Math.max(maxDepth, segments.length);
            paths.add(segments);
            values.add(member.getValue());
        }
        int keyCount = paths.size();
        if (keyCount > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "flat document must contain at most " + MAX_KEYS + " keys");
        }

        ObjectNode nested = objectMapper.createObjectNode();
        // Identity set of nodes this call created as interior containers: a container that arrived as a
        // leaf value must never be descended into, otherwise a key like 'a' with value {} plus 'a.b'
        // would silently swallow the leaf.
        Set<ObjectNode> branches = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < paths.size(); i++) {
            String[] segments = paths.get(i);
            String key = String.join(separator, segments);
            ObjectNode current = nested;
            for (int s = 0; s < segments.length - 1; s++) {
                JsonNode child = current.get(segments[s]);
                if (child == null) {
                    ObjectNode created = current.putObject(segments[s]);
                    branches.add(created);
                    current = created;
                } else if (child.isObject() && branches.contains(child)) {
                    current = (ObjectNode) child;
                } else {
                    throw new IllegalArgumentException("conflicting path: '" + key
                            + "' cannot be nested under '" + String.join(separator,
                            Arrays.copyOf(segments, s + 1)) + "' which is already a leaf");
                }
            }
            String leaf = segments[segments.length - 1];
            if (current.has(leaf)) {
                throw new IllegalArgumentException("conflicting path: '" + key
                        + "' duplicates an existing key or leaf");
            }
            current.set(leaf, values.get(i));
        }
        return new JsonUnflattenResponse(nested, keyCount, maxDepth);
    }

    private static String normaliseSeparator(String separator) {
        String value = separator == null ? DEFAULT_SEPARATOR : separator;
        if (value.isBlank()) {
            throw new IllegalArgumentException("separator must not be blank");
        }
        if (value.length() > MAX_SEPARATOR_LENGTH) {
            throw new IllegalArgumentException(
                    "separator must be at most " + MAX_SEPARATOR_LENGTH + " characters");
        }
        return value;
    }

    private JsonNode parseTree(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null) {
                throw new IllegalArgumentException("Invalid JSON content: document is empty");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON content: " + e.getOriginalMessage(), e);
        }
    }

    /** Mutable counters for the flatten walk: the service bean itself stays stateless. */
    private static final class FlattenTally {
        private int keyCount;
        private int maxDepth;
    }

    private Runnable applyOperation(PatchDocument doc, JsonPatchOperation entry) {
        String op = entry.op();
        String path = entry.path();
        if (op == null || op.isBlank()) {
            throw new PatchFailure("Unknown operation: " + op);
        }
        return switch (op) {
            case "add" -> doc.addNode(path, entry.value());
            case "remove" -> doc.removeNode(path);
            case "replace" -> doc.replaceNode(path, entry.value());
            case "test" -> doc.testNode(path, entry.value());
            case "move" -> doc.moveNode(entry.from(), path);
            case "copy" -> doc.copyNode(entry.from(), path);
            default -> throw new PatchFailure("Unknown operation: " + op);
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static JsonNode deepCopyOrNull(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    /** Working copy holder: a {@link JsonNode} reference cannot be reassigned from a helper. */
    private static final class PatchDocument {

        private JsonNode root;

        private PatchDocument(JsonNode root) {
            this.root = root;
        }

        private JsonNode root() {
            return root;
        }

        private JsonNode resolveQuietly(String pointer) {
            try {
                return resolve(pointer);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private JsonNode resolve(String pointer) {
            List<String> tokens = parsePointer(pointer);
            JsonNode current = root;
            for (String token : tokens) {
                current = child(current, token);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        private JsonNode child(JsonNode node, String token) {
            if (node instanceof ObjectNode objectNode) {
                return objectNode.get(token);
            }
            if (node instanceof ArrayNode arrayNode) {
                return arrayNode.get(parseIndex(token, arrayNode.size(), false));
            }
            return null;
        }

        private JsonNode requireParent(List<String> tokens, String pointer) {
            JsonNode current = root;
            for (int i = 0; i < tokens.size() - 1; i++) {
                current = child(current, tokens.get(i));
                if (current == null) {
                    throw new PatchFailure("Missing path: " + pointer);
                }
            }
            return current;
        }

        private Runnable addNode(String pointer, JsonNode value) {
            if (value == null) {
                throw new PatchFailure("'value' is required for add/replace/test");
            }
            List<String> tokens = parsePointer(pointer);
            JsonNode added = value.deepCopy();
            if (tokens.isEmpty()) {
                JsonNode previous = root;
                root = added;
                return () -> root = previous;
            }
            JsonNode parent = requireParent(tokens, pointer);
            String last = tokens.get(tokens.size() - 1);
            if (parent instanceof ObjectNode objectNode) {
                JsonNode previous = deepCopyOrNull(objectNode.get(last));
                objectNode.set(last, added);
                if (previous == null) {
                    return () -> objectNode.remove(last);
                }
                return () -> objectNode.set(last, previous);
            }
            if (parent instanceof ArrayNode arrayNode) {
                int index = parseIndex(last, arrayNode.size(), true);
                JsonNode previous = index < arrayNode.size() ? deepCopyOrNull(arrayNode.get(index)) : null;
                arrayNode.insert(index, added);
                if (previous == null) {
                    return () -> arrayNode.remove(index);
                }
                return () -> arrayNode.set(index, previous);
            }
            throw new PatchFailure("Missing path: " + pointer);
        }

        private Runnable removeNode(String pointer) {
            List<String> tokens = parsePointer(pointer);
            if (tokens.isEmpty()) {
                throw new PatchFailure("Cannot remove the root document");
            }
            JsonNode parent = requireParent(tokens, pointer);
            String last = tokens.get(tokens.size() - 1);
            if (parent instanceof ObjectNode objectNode) {
                JsonNode removed = objectNode.remove(last);
                if (removed == null) {
                    throw new PatchFailure("Missing path: " + pointer);
                }
                JsonNode snapshot = removed.deepCopy();
                return () -> objectNode.set(last, snapshot);
            }
            if (parent instanceof ArrayNode arrayNode) {
                int index = parseIndex(last, arrayNode.size(), false);
                JsonNode snapshot = arrayNode.get(index).deepCopy();
                arrayNode.remove(index);
                return () -> arrayNode.insert(index, snapshot);
            }
            throw new PatchFailure("Missing path: " + pointer);
        }

        private Runnable replaceNode(String pointer, JsonNode value) {
            if (value == null) {
                throw new PatchFailure("'value' is required for add/replace/test");
            }
            List<String> tokens = parsePointer(pointer);
            JsonNode replacement = value.deepCopy();
            if (tokens.isEmpty()) {
                JsonNode previous = root;
                root = replacement;
                return () -> root = previous;
            }
            JsonNode parent = requireParent(tokens, pointer);
            String last = tokens.get(tokens.size() - 1);
            if (parent instanceof ObjectNode objectNode) {
                JsonNode previous = objectNode.get(last);
                if (previous == null) {
                    throw new PatchFailure("Missing path: " + pointer);
                }
                JsonNode snapshot = previous.deepCopy();
                objectNode.set(last, replacement);
                return () -> objectNode.set(last, snapshot);
            }
            if (parent instanceof ArrayNode arrayNode) {
                int index = parseIndex(last, arrayNode.size(), false);
                JsonNode snapshot = arrayNode.get(index).deepCopy();
                arrayNode.set(index, replacement);
                return () -> arrayNode.set(index, snapshot);
            }
            throw new PatchFailure("Missing path: " + pointer);
        }

        private Runnable testNode(String pointer, JsonNode value) {
            if (value == null) {
                throw new PatchFailure("'value' is required for add/replace/test");
            }
            JsonNode target = resolve(pointer);
            if (target == null) {
                throw new PatchFailure("Missing path: " + pointer);
            }
            if (!target.equals(value)) {
                throw new PatchFailure("Test operation failed at " + pointer);
            }
            return () -> {
            };
        }

        private Runnable moveNode(String from, String path) {
            if (from == null || from.isBlank()) {
                throw new PatchFailure("'from' is required for move/copy");
            }
            if (path == null || path.isBlank()) {
                throw new PatchFailure("Malformed JSON Pointer: pointer must not be null or blank");
            }
            if (path.equals(from) || path.startsWith(from + "/")) {
                throw new PatchFailure("Cannot move a node into its own descendant");
            }
            JsonNode source = resolve(from);
            if (source == null) {
                throw new PatchFailure("Missing path: " + from);
            }
            JsonNode moved = source.deepCopy();
            validateAddTarget(path);
            removeNode(from);
            Runnable addInverse = addNode(path, moved);
            return () -> {
                addInverse.run();
                addNode(from, moved.deepCopy());
            };
        }

        private Runnable copyNode(String from, String path) {
            if (from == null || from.isBlank()) {
                throw new PatchFailure("'from' is required for move/copy");
            }
            JsonNode source = resolve(from);
            if (source == null) {
                throw new PatchFailure("Missing path: " + from);
            }
            return addNode(path, source.deepCopy());
        }

        private void validateAddTarget(String pointer) {
            List<String> tokens = parsePointer(pointer);
            if (tokens.isEmpty()) {
                return;
            }
            JsonNode parent = requireParent(tokens, pointer);
            String last = tokens.get(tokens.size() - 1);
            if (parent instanceof ObjectNode) {
                return;
            }
            if (parent instanceof ArrayNode arrayNode) {
                parseIndex(last, arrayNode.size(), true);
                return;
            }
            throw new PatchFailure("Missing path: " + pointer);
        }

        /** RFC 6901 pointer parsing with {@code ~1 -> /} then {@code ~0 -> ~} unescaping. */
        private static List<String> parsePointer(String pointer) {
            if (pointer == null) {
                throw new PatchFailure("Malformed JSON Pointer: pointer must not be null or blank");
            }
            if (pointer.isEmpty()) {
                return List.of();
            }
            if (pointer.isBlank()) {
                throw new PatchFailure("Malformed JSON Pointer: pointer must not be null or blank");
            }
            if (!pointer.startsWith("/")) {
                throw new PatchFailure("Malformed JSON Pointer: " + pointer);
            }
            String[] raw = pointer.substring(1).split("/", -1);
            List<String> tokens = new ArrayList<>(raw.length);
            for (String token : raw) {
                if (token.matches(".*~(?![01]).*")) {
                    throw new PatchFailure("Malformed JSON Pointer: " + pointer);
                }
                tokens.add(token.replace("~1", "/").replace("~0", "~"));
            }
            return tokens;
        }

        private static int parseIndex(String token, int size, boolean allowAppend) {
            if ("-".equals(token)) {
                if (allowAppend) {
                    return size;
                }
                throw new PatchFailure("Invalid array index: -");
            }
            if (!token.matches("0|[1-9][0-9]*")) {
                throw new PatchFailure("Invalid array index: " + token);
            }
            int index;
            try {
                index = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new PatchFailure("Array index out of bounds: " + token);
            }
            if (allowAppend ? index > size : index >= size) {
                throw new PatchFailure("Array index out of bounds: " + token);
            }
            return index;
        }
    }

    /** Internal operation failure carrier: the message is the audit reason surfaced to the caller. */
    private static final class PatchFailure extends RuntimeException {

        private PatchFailure(String message) {
            super(message);
        }
    }
}
