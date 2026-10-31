package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, in-memory journal of utility operations.
 *
 * <p>Entries follow exactly {@code PENDING -> SUCCEEDED} or {@code PENDING -> FAILED};
 * any other transition raises {@link IllegalStateException}. Entries are evicted
 * oldest-first once {@code utiltools.audit.max-entries} is reached. The original
 * request object is kept privately (never on {@link AuditEntry}) so replay can
 * re-invoke the operation without exposing raw secrets in responses.</p>
 */
@Service
public class AuditJournalService {

    private static final Set<String> SENSITIVE_FIELDS =
            Set.of("secretkey", "secret", "password", "token", "authorization");

    private static final String REDACTED = "***";

    private final ObjectMapper objectMapper;
    private final int maxEntries;
    private final int maxReplayBytes;

    private final Object lock = new Object();
    private final LinkedHashMap<String, JournalRecord> entries = new LinkedHashMap<>();

    public AuditJournalService(ObjectMapper objectMapper,
                               @Value("${utiltools.audit.max-entries:200}") int maxEntries,
                               @Value("${utiltools.audit.max-replay-bytes:65536}") int maxReplayBytes) {
        this.objectMapper = objectMapper;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxReplayBytes = Math.max(1, maxReplayBytes);
    }

    /**
     * Starts a PENDING entry, evicting the oldest entry when the bound is reached.
     */
    public AuditEntry begin(OperationType operation, Object request, String requestId) {
        JsonNode tree = objectMapper.valueToTree(request);
        boolean replayable = tree != null && tree.toString().length() <= maxReplayBytes;
        JsonNode storedRequest = tree == null ? null : (replayable ? redact(tree) : withheldPayload());

        synchronized (lock) {
            while (entries.size() >= maxEntries) {
                entries.remove(entries.keySet().iterator().next());
            }
            String id = UUID.randomUUID().toString();
            AuditEntry base = new AuditEntry(id, requestId, operation, JournalState.PENDING,
                    Instant.now(), null, storedRequest, null, null, replayable, null);
            entries.put(id, new JournalRecord(base, replayable ? request : null, null));
            return base;
        }
    }

    /**
     * Stand-in stored in place of a request that exceeds {@code utiltools.audit.max-replay-bytes}:
     * neither the original nor the redacted tree is retained, so an oversized body can never be
     * re-served by {@link #list} or {@link #find}.
     */
    private JsonNode withheldPayload() {
        ObjectNode placeholder = objectMapper.createObjectNode();
        placeholder.put("omitted", true);
        placeholder.put("reason", "request payload exceeds utiltools.audit.max-replay-bytes");
        return placeholder;
    }

    /** PENDING -> SUCCEEDED. */
    public AuditEntry succeed(String id, Object result) {
        synchronized (lock) {
            JournalRecord record = requirePending(id, JournalState.SUCCEEDED);
            AuditEntry base = record.base;
            AuditEntry updated = new AuditEntry(base.id(), base.requestId(), base.operation(),
                    JournalState.SUCCEEDED, base.createdAt(), Instant.now(), base.request(),
                    objectMapper.valueToTree(result), null, base.replayable(), record.replay);
            record.base = updated;
            return updated;
        }
    }

    /** PENDING -> FAILED; any staged result is cleared. */
    public AuditEntry fail(String id, String errorCode) {
        synchronized (lock) {
            JournalRecord record = requirePending(id, JournalState.FAILED);
            AuditEntry base = record.base;
            AuditEntry updated = new AuditEntry(base.id(), base.requestId(), base.operation(),
                    JournalState.FAILED, base.createdAt(), Instant.now(), base.request(), null,
                    errorCode, base.replayable(), record.replay);
            record.base = updated;
            return updated;
        }
    }

    public Optional<AuditEntry> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            JournalRecord record = entries.get(id);
            return record == null ? Optional.empty() : Optional.of(record.base);
        }
    }

    /**
     * Newest-first snapshot, clamped to {@code [1, maxEntries]}; a null state means all states.
     */
    public List<AuditEntry> list(int limit, JournalState state) {
        int effectiveLimit = Math.min(Math.max(1, limit), maxEntries);
        synchronized (lock) {
            List<AuditEntry> snapshot = new ArrayList<>();
            Iterator<JournalRecord> it = entries.values().iterator();
            List<JournalRecord> ordered = new ArrayList<>();
            it.forEachRemaining(ordered::add);
            for (int i = ordered.size() - 1; i >= 0 && snapshot.size() < effectiveLimit; i--) {
                AuditEntry entry = ordered.get(i).base;
                if (state == null || entry.state() == state) {
                    snapshot.add(entry);
                }
            }
            return snapshot;
        }
    }

    /** Attaches the latest replay outcome; returns false when the id was evicted. */
    public boolean recordReplay(String id, ReplayRecord replay) {
        synchronized (lock) {
            JournalRecord record = entries.get(id);
            if (record == null) {
                return false;
            }
            record.replay = replay;
            AuditEntry base = record.base;
            record.base = new AuditEntry(base.id(), base.requestId(), base.operation(), base.state(),
                    base.createdAt(), base.completedAt(), base.request(), base.result(),
                    base.errorCode(), base.replayable(), replay);
            return true;
        }
    }

    /** Package-visible: only {@link AuditReplayService} may read the private original request. */
    Optional<Object> originalRequest(String id) {
        synchronized (lock) {
            JournalRecord record = entries.get(id);
            return record == null ? Optional.empty() : Optional.ofNullable(record.originalRequest);
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private JournalRecord requirePending(String id, JournalState target) {
        JournalRecord record = id == null ? null : entries.get(id);
        if (record == null) {
            throw new IllegalStateException("Invalid journal transition: entry " + id + " does not exist, "
                    + "cannot transition to " + target);
        }
        if (record.base.state() != JournalState.PENDING) {
            throw new IllegalStateException("Invalid journal transition: entry " + id + " is "
                    + record.base.state() + ", cannot transition to " + target);
        }
        return record;
    }

    /** Recursively replaces secret values with {@code "***"}; field presence is preserved. */
    JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            copy.fields().forEachRemaining(fields::add);
            for (Map.Entry<String, JsonNode> field : fields) {
                if (isSensitive(field.getKey())) {
                    copy.put(field.getKey(), REDACTED);
                } else {
                    copy.set(field.getKey(), redact(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, redact(copy.get(i)));
            }
            return copy;
        }
        return node;
    }

    private boolean isSensitive(String fieldName) {
        return fieldName != null && SENSITIVE_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT));
    }

    private static final class JournalRecord {
        private AuditEntry base;
        private final Object originalRequest;
        private ReplayRecord replay;

        private JournalRecord(AuditEntry base, Object originalRequest, ReplayRecord replay) {
            this.base = base;
            this.originalRequest = originalRequest;
            this.replay = replay;
        }
    }
}
