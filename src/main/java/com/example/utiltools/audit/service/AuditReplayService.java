package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReplayConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;

/**
 * Re-invokes a stored SUCCEEDED, deterministic entry and compares the fresh result with
 * the recorded one. Only the {@code replay} field of the entry is ever written, so the
 * original {@code state}, {@code result} and {@code completedAt} stay untouched.
 *
 * <p>Every replay is a single-winner operation: the entry is claimed atomically under the journal
 * lock before the operation runs and the outcome is recorded owner-scoped afterwards, so two
 * concurrent replays of the same entry cannot both execute and a failed invocation still leaves a
 * {@code FAILED} trace.</p>
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

    /**
     * Single-winner replay: claim the entry under the journal lock, invoke the operation, then
     * record {@code VERIFIED} / {@code MISMATCH} — or {@code FAILED} with a bounded detail when
     * the invocation throws — through the owner-scoped completion. A live claim held by another
     * caller, a claim lost to lease expiry or an ineligible entry surfaces as the existing
     * {@code 409 REPLAY_CONFLICT} response.
     */
    public AuditEntry replay(String id) {
        // Unknown ids stay 404: the claim below reports them as a replay conflict.
        if (journal.find(id).isEmpty()) {
            throw new AuditEntryNotFoundException(id);
        }
        ReplayClaim claim = journal.claimReplay(id);
        AuditEntry claimed = claim.entry();
        Object original = journal.originalRequest(id).orElse(null);
        if (original == null) {
            journal.completeReplay(id, claim.ownerToken(), ReplayState.FAILED,
                    "replay failed: stored request is unavailable");
            throw new ReplayConflictException(id, "entry is not replayable (payload exceeds "
                    + "utiltools.audit.max-replay-bytes)");
        }

        Object actual;
        try {
            actual = auditedOperations.replayInvoke(claimed.operation(), original);
        } catch (RuntimeException e) {
            String detail = "replay failed: " + describe(e);
            journal.completeReplay(id, claim.ownerToken(), ReplayState.FAILED, detail);
            throw new ReplayConflictException(id, detail);
        }

        JsonNode expected = claimed.result();
        JsonNode actualTree = objectMapper.valueToTree(actual);
        ReplayState state = expected != null && expected.equals(actualTree) ? ReplayState.VERIFIED : ReplayState.MISMATCH;
        String detail = state == ReplayState.VERIFIED ? "results match" : describeMismatch(expected, actualTree);

        if (!journal.completeReplay(id, claim.ownerToken(), state, detail)) {
            throw new ReplayConflictException(id, "replay claim was lost before the outcome was recorded");
        }
        return journal.find(id).orElseThrow(() -> new AuditEntryNotFoundException(id));
    }

    /** Short, always-present reason for a failed replay invocation. */
    private String describe(Throwable error) {
        String message = error.getMessage();
        String text = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        return text.length() > MAX_DETAIL_LENGTH ? text.substring(0, MAX_DETAIL_LENGTH) : text;
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
