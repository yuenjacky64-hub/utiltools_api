package com.example.utiltools.audit.service;

import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.dto.JournalVerifyResult;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReplayConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Re-invokes a stored SUCCEEDED, deterministic entry and compares the fresh result with
 * the recorded one. Only the {@code replay} field of the entry is ever written, so the
 * original {@code state}, {@code result} and {@code completedAt} stay untouched.
 *
 * <p>Every replay is a single-winner operation: the entry is claimed atomically under the journal
 * lock before the operation runs and the outcome is recorded owner-scoped afterwards, so two
 * concurrent replays of the same entry cannot both execute and a failed invocation still leaves a
 * {@code FAILED} trace.</p>
 *
 * <p>A replay that outlives {@code utiltools.audit.replay-lease-seconds} renews its own claim on
 * the {@code utiltools.audit.replay-heartbeat-seconds} cadence while the operation runs, so a long
 * replay still completes as {@code VERIFIED} / {@code MISMATCH} instead of losing its claim.</p>
 */
@Service
public class AuditReplayService {

    private static final Logger log = LoggerFactory.getLogger(AuditReplayService.class);

    private static final int MAX_DETAIL_LENGTH = 200;

    private final AuditJournalService journal;
    private final AuditedOperations auditedOperations;
    private final ObjectMapper objectMapper;
    private final long replayHeartbeatSeconds;
    private final ReplayHeartbeatScheduler heartbeatScheduler;

    @Autowired
    public AuditReplayService(AuditJournalService journal,
                              AuditedOperations auditedOperations,
                              ObjectMapper objectMapper,
                              @Value("${utiltools.audit.replay-heartbeat-seconds:#{null}}")
                              Long replayHeartbeatSeconds) {
        this(journal, auditedOperations, objectMapper, replayHeartbeatSeconds,
                ReplayHeartbeatScheduler.daemonThreadPool());
    }

    /** Fixed 30s-lease seam used by tests: no heartbeat is scheduled. */
    public AuditReplayService(AuditJournalService journal,
                              AuditedOperations auditedOperations,
                              ObjectMapper objectMapper) {
        this(journal, auditedOperations, objectMapper, null, ReplayHeartbeatScheduler.disabled());
    }

    /**
     * Canonical constructor. {@code replayHeartbeatSeconds} defaults to half the claim lease (at
     * least one second) and must be positive when configured; a non-positive value is a startup
     * error because it would renew in a loop.
     */
    public AuditReplayService(AuditJournalService journal,
                              AuditedOperations auditedOperations,
                              ObjectMapper objectMapper,
                              Long replayHeartbeatSeconds,
                              ReplayHeartbeatScheduler heartbeatScheduler) {
        this.journal = journal;
        this.auditedOperations = auditedOperations;
        this.objectMapper = objectMapper;
        this.replayHeartbeatSeconds = resolveHeartbeatSeconds(replayHeartbeatSeconds);
        this.heartbeatScheduler = heartbeatScheduler == null
                ? ReplayHeartbeatScheduler.disabled()
                : heartbeatScheduler;
    }

    private long resolveHeartbeatSeconds(Long configured) {
        if (configured == null) {
            return Math.max(1L, journal.replayLeaseSeconds() / 2L);
        }
        if (configured < 1L) {
            throw new IllegalArgumentException(
                    "utiltools.audit.replay-heartbeat-seconds must be positive, was " + configured);
        }
        return configured;
    }

    /** Shuts the daemon heartbeat pool down with the context; a no-op for other schedulers. */
    @PreDestroy
    void shutdownHeartbeatScheduler() {
        if (heartbeatScheduler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("replay lease heartbeat scheduler did not close cleanly: {}", describe(e));
            }
        }
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
        EntryAttempt attempt = attempt(id);
        if (attempt.skipReason() != null) {
            // The very exception the journal produced (or the lost-claim one) so 409 text is unchanged.
            throw attempt.conflict();
        }
        if (attempt.outcome() == ReplayState.FAILED) {
            throw new ReplayConflictException(id, attempt.detail());
        }
        return journal.find(id).orElseThrow(() -> new AuditEntryNotFoundException(id));
    }

    /**
     * Bounded integrity sweep: replays at most {@code limit} (clamped to
     * {@code utiltools.audit.max-entries}) newest {@code SUCCEEDED} entries and reports one row per
     * candidate. The sweep runs the same per-entry {@link #attempt(String)} helper as
     * {@link #replay(String)}, so claim conflicts, ineligible entries and throwing invocations
     * become {@code SKIPPED} / {@code FAILED} rows instead of aborting the batch.
     *
     * @throws IllegalArgumentException when {@code limit < 1}; never on a per-entry failure
     */
    public JournalVerifyResponse verify(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int effectiveLimit = Math.min(limit, journal.maxEntries());
        List<AuditEntry> candidates = journal.list(effectiveLimit, JournalState.SUCCEEDED);
        List<JournalVerifyResult> results = new ArrayList<>(candidates.size());
        int verified = 0;
        int mismatch = 0;
        int failed = 0;
        int skipped = 0;
        for (AuditEntry candidate : candidates) {
            EntryAttempt attempt;
            try {
                attempt = attempt(candidate.id());
            } catch (RuntimeException unexpected) {
                // Backstop: one pathological entry must never abort the sweep.
                attempt = new EntryAttempt(candidate.id(), candidate.operation(), ReplayState.FAILED,
                        boundedDetail("replay failed: " + describe(unexpected)), null, null);
            }
            results.add(new JournalVerifyResult(attempt.entryId(), attempt.operation(),
                    attempt.outcome(), attempt.detail(), attempt.skipReason()));
            if (attempt.skipReason() != null) {
                skipped++;
            } else if (attempt.outcome() == ReplayState.VERIFIED) {
                verified++;
            } else if (attempt.outcome() == ReplayState.MISMATCH) {
                mismatch++;
            } else {
                failed++;
            }
        }
        return new JournalVerifyResponse(effectiveLimit, results.size(), verified, mismatch, failed,
                skipped, List.copyOf(results));
    }

    /**
     * One per-entry attempt shared by {@link #replay(String)} and {@link #verify(int)}: it claims
     * the entry, invokes the operation without holding the journal lock and records the outcome
     * owner-scoped. Ineligibility, claim conflicts and lost claims are returned as skipped attempts
     * — this method throws only for unexpected programming errors.
     */
    private EntryAttempt attempt(String id) {
        ReplayClaim claim;
        try {
            claim = journal.claimReplay(id);
        } catch (ReplayConflictException e) {
            return skipped(id, e);
        }
        AuditEntry claimed = claim.entry();
        OperationType operation = claimed.operation();
        Object original = journal.originalRequest(id).orElse(null);
        if (original == null) {
            String detail = boundedDetail("replay failed: stored request is unavailable");
            journal.completeReplay(id, claim.ownerToken(), ReplayState.FAILED, detail);
            return new EntryAttempt(id, operation, ReplayState.FAILED, detail, null, null);
        }

        Object actual;
        ReplayHeartbeatScheduler.Handle heartbeat = null;
        try {
            try {
                heartbeat = heartbeatScheduler.schedule(() -> renewLease(id, claim.ownerToken()),
                        Duration.ofSeconds(replayHeartbeatSeconds));
            } catch (RuntimeException e) {
                // A heartbeat that cannot be scheduled must not replace the operation's outcome.
                log.warn("replay lease heartbeat could not be scheduled for {}: {}", id,
                        boundedDetail("replay lease heartbeat failed: " + describe(e)));
            }
            actual = auditedOperations.replayInvoke(operation, original);
        } catch (RuntimeException e) {
            String detail = boundedDetail("replay failed: " + describe(e));
            journal.completeReplay(id, claim.ownerToken(), ReplayState.FAILED, detail);
            return new EntryAttempt(id, operation, ReplayState.FAILED, detail, null, null);
        } finally {
            // Renewal never runs after the operation, so completion always sees the final expiry.
            if (heartbeat != null) {
                try {
                    heartbeat.cancel();
                } catch (RuntimeException e) {
                    log.warn("replay lease heartbeat could not be cancelled for {}: {}", id,
                            boundedDetail("replay lease heartbeat cancel failed: " + describe(e)));
                }
            }
        }

        JsonNode expected = claimed.result();
        JsonNode actualTree = objectMapper.valueToTree(actual);
        ReplayState state = expected != null && expected.equals(actualTree) ? ReplayState.VERIFIED : ReplayState.MISMATCH;
        String detail = state == ReplayState.VERIFIED ? "results match" : describeMismatch(expected, actualTree);

        if (!journal.completeReplay(id, claim.ownerToken(), state, detail)) {
            ReplayConflictException lost = new ReplayConflictException(id,
                    "replay claim was lost before the outcome was recorded");
            return new EntryAttempt(id, operation, null, boundedDetail(lost.getMessage()), "CLAIM_LOST", lost);
        }
        return new EntryAttempt(id, operation, state, detail, null, null);
    }

    /**
     * One heartbeat tick: renew the claim this attempt still owns. A refusal (claim completed, lost
     * or expired) is not an error — the operation's own outcome decides the result — and any
     * failure is bounded and logged rather than propagated into the replay.
     */
    private void renewLease(String id, String ownerToken) {
        try {
            if (journal.renewReplayLease(id, ownerToken).isEmpty()) {
                log.debug("replay lease renewal refused for {}", id);
            }
        } catch (RuntimeException e) {
            log.warn("replay lease renewal failed for {}: {}", id,
                    boundedDetail("replay lease renewal failed: " + describe(e)));
        }
    }

    /**
     * Classifies a rejected claim without parsing the conflict message, in the documented
     * precedence order: evicted, live claim, not SUCCEEDED, non-deterministic, not replayable,
     * anything else.
     */
    private EntryAttempt skipped(String id, ReplayConflictException conflict) {
        String detail = boundedDetail(conflict.getMessage());
        Optional<AuditEntry> current = journal.find(id);
        if (current.isEmpty()) {
            return new EntryAttempt(id, null, null, detail, "EVICTED", conflict);
        }
        AuditEntry entry = current.get();
        OperationType operation = entry.operation();
        String skipReason;
        ReplayRecord replay = entry.replay();
        if (replay != null && replay.state() == ReplayState.IN_PROGRESS) {
            // A live lease is left exactly as it is: the sweep never touches another caller's claim.
            skipReason = "LIVE_CLAIM";
        } else if (entry.state() != JournalState.SUCCEEDED) {
            skipReason = "NOT_SUCCEEDED";
        } else if (operation == null || !operation.isDeterministic()) {
            skipReason = "NON_DETERMINISTIC";
        } else if (!entry.replayable()) {
            skipReason = "NOT_REPLAYABLE";
        } else {
            skipReason = "CLAIM_CONFLICT";
        }
        return new EntryAttempt(id, operation, null, detail, skipReason, conflict);
    }

    /** One per-entry attempt: exactly one of {@code outcome} and {@code skipReason} is non-null. */
    private record EntryAttempt(String entryId, OperationType operation, ReplayState outcome,
                                String detail, String skipReason, ReplayConflictException conflict) {}

    /** Keeps a detail non-null and bounded to {@value #MAX_DETAIL_LENGTH} characters. */
    private String boundedDetail(String text) {
        String safe = text == null || text.isBlank() ? "replay skipped" : text;
        return safe.length() > MAX_DETAIL_LENGTH ? safe.substring(0, MAX_DETAIL_LENGTH) : safe;
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
