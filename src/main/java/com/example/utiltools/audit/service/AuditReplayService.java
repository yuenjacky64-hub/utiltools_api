package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReplayConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * Re-invokes a stored SUCCEEDED, deterministic entry and compares the fresh result with
 * the recorded one. Only the {@code replay} field of the entry is ever written, so the
 * original {@code state}, {@code result} and {@code completedAt} stay untouched.
 */
@Service
public class AuditReplayService {

    private static final int MAX_DETAIL_LENGTH = 200;

    private final AuditJournalService journal;
    private final AuditedOperations auditedOperations;
    private final ObjectMapper objectMapper;

    public AuditReplayService(AuditJournalService journal,
                              AuditedOperations auditedOperations,
                              ObjectMapper objectMapper) {
        this.journal = journal;
        this.auditedOperations = auditedOperations;
        this.objectMapper = objectMapper;
    }

    public AuditEntry replay(String id) {
        AuditEntry entry = journal.find(id).orElseThrow(() -> new AuditEntryNotFoundException(id));
        if (entry.state() != JournalState.SUCCEEDED) {
            throw new ReplayConflictException(id, "entry is " + entry.state() + " and cannot be replayed");
        }
        if (!entry.operation().isDeterministic()) {
            throw new ReplayConflictException(id, "operation " + entry.operation() + " is non-deterministic");
        }
        Object original = journal.originalRequest(id)
                .orElseThrow(() -> new ReplayConflictException(id,
                        "entry is not replayable (payload exceeds utiltools.audit.max-replay-bytes)"));
        if (!entry.replayable()) {
            throw new ReplayConflictException(id,
                    "entry is not replayable (payload exceeds utiltools.audit.max-replay-bytes)");
        }

        Object actual;
        try {
            actual = auditedOperations.replayInvoke(entry.operation(), original);
        } catch (RuntimeException e) {
            throw new ReplayConflictException(id, "replay failed: " + e.getMessage());
        }

        JsonNode expected = entry.result();
        JsonNode actualTree = objectMapper.valueToTree(actual);
        ReplayState state = expected != null && expected.equals(actualTree) ? ReplayState.VERIFIED : ReplayState.MISMATCH;
        String detail = state == ReplayState.VERIFIED ? "results match" : describeMismatch(expected, actualTree);

        journal.recordReplay(id, new ReplayRecord(state, Instant.now(), detail));
        return journal.find(id).orElseThrow(() -> new AuditEntryNotFoundException(id));
    }

    private String describeMismatch(JsonNode expected, JsonNode actual) {
        String pointer = firstDifference(expected, actual, "");
        String detail = pointer == null ? "replay result differs" : "first mismatch at " + pointer;
        return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
    }

    private String firstDifference(JsonNode expected, JsonNode actual, String pointer) {
        String here = pointer.isEmpty() ? "/" : pointer;
        if (expected == null || actual == null || expected.getNodeType() != actual.getNodeType()) {
            return here;
        }
        if (expected.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String child = pointer + "/" + field.getKey();
                if (!actual.has(field.getKey())) {
                    return child;
                }
                String difference = firstDifference(field.getValue(), actual.get(field.getKey()), child);
                if (difference != null) {
                    return difference;
                }
            }
            return actual.size() == expected.size() ? null : here;
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return here;
            }
            for (int i = 0; i < expected.size(); i++) {
                String difference = firstDifference(expected.get(i), actual.get(i), pointer + "/" + i);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        return expected.equals(actual) ? null : here;
    }
}
