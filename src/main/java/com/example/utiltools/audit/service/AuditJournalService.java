package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.utiltools.common.ReplayConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p>Replay is a single-winner operation: {@link #claimReplay(String)} takes ownership of a
 * SUCCEEDED entry under the same lock with a lease of {@code utiltools.audit.replay-lease-seconds},
 * and only {@link #completeReplay} with that owner token may record the terminal
 * {@code VERIFIED} / {@code MISMATCH} / {@code FAILED} outcome.</p>
 */
@Service
public class AuditJournalService {

    private static final Set<String> SENSITIVE_FIELDS =
            Set.of("secretkey", "secret", "password", "token", "authorization");

    private static final String REDACTED = "***";

    private static final String STALE_PENDING_REAPED = "STALE_PENDING_REAPED";

    static final int MAX_DETAIL_LENGTH = 200;

    private final ObjectMapper objectMapper;
    private final int maxEntries;
    private final int maxReplayBytes;
    private final long stalePendingSeconds;
    private final long replayLeaseSeconds;
    private final Clock clock;

    private final Object lock = new Object();
    private final LinkedHashMap<String, JournalRecord> entries = new LinkedHashMap<>();

    /**
     * Production constructor: lease deadlines are measured with the application-wide
     * {@link Clock} bean, so replay lease expiry is testable without sleeping.
     */
    @Autowired
    public AuditJournalService(ObjectMapper objectMapper,
                               @Value("${utiltools.audit.max-entries:200}") int maxEntries,
                               @Value("${utiltools.audit.max-replay-bytes:65536}") int maxReplayBytes,
                               @Value("${utiltools.audit.stale-pending-seconds:300}") long stalePendingSeconds,
                               @Value("${utiltools.audit.replay-lease-seconds:30}") long replayLeaseSeconds,
                               Clock clock) {
        this.objectMapper = objectMapper;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxReplayBytes = Math.max(1, maxReplayBytes);
        this.stalePendingSeconds = Math.max(1L, stalePendingSeconds);
        this.replayLeaseSeconds = Math.max(1L, replayLeaseSeconds);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Defaults the replay lease to 30s and the clock to system UTC. */
    public AuditJournalService(ObjectMapper objectMapper, int maxEntries, int maxReplayBytes,
                               long stalePendingSeconds) {
        this(objectMapper, maxEntries, maxReplayBytes, stalePendingSeconds, 30L, Clock.systemUTC());
    }

    /**
     * Starts a PENDING entry, evicting the oldest entry when the bound is reached.
     */
    public AuditEntry begin(OperationType operation, Object request, String requestId) {
        JsonNode tree = objectMapper.valueToTree(request);
        boolean replayable = tree != null && serializedBytes(tree) <= maxReplayBytes;
        JsonNode storedRequest = tree == null ? null : (replayable ? redact(tree) : withheldPayload());

        synchronized (lock) {
            while (entries.size() >= maxEntries) {
                entries.remove(entries.keySet().iterator().next());
            }
            String id = UUID.randomUUID().toString();
            AuditEntry base = new AuditEntry(id, requestId, operation, JournalState.PENDING,
                    Instant.now(clock), null, storedRequest, null, null, replayable, null);
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
            JsonNode resultTree = objectMapper.valueToTree(result);
            boolean oversizeResult = resultTree != null && serializedBytes(resultTree) > maxReplayBytes;
            AuditEntry updated = new AuditEntry(base.id(), base.requestId(), base.operation(),
                    JournalState.SUCCEEDED, base.createdAt(), Instant.now(clock), base.request(),
                    oversizeResult ? withheldPayload() : resultTree, null,
                    base.replayable() && !oversizeResult, record.replay);
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
                    JournalState.FAILED, base.createdAt(), Instant.now(clock), base.request(), null,
                    errorCode, base.replayable(), record.replay);
            record.base = updated;
            return updated;
        }
    }

    /**
     * Scheduled stale-PENDING sweep; delegates to the clock-free variant with a cutoff derived
     * from {@code utiltools.audit.stale-pending-seconds}.
     */
    @Scheduled(fixedDelayString = "${utiltools.audit.cleanup-interval-ms:60000}")
    public void reapStalePending() {
        reapStalePending(Instant.now(clock).minusSeconds(stalePendingSeconds));
    }

    /**
     * Marks PENDING entries created before {@code cutoff} as FAILED with STALE_PENDING_REAPED.
     * Public and clock-free so tests advance the cutoff instead of waiting.
     *
     * @return the number of reaped entries
     */
    public int reapStalePending(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        Instant reapedAt = Instant.now(clock);
        int reaped = 0;
        synchronized (lock) {
            for (JournalRecord record : entries.values()) {
                AuditEntry base = record.base;
                if (base.state() == JournalState.PENDING
                        && base.createdAt() != null
                        && base.createdAt().isBefore(cutoff)) {
                    record.base = new AuditEntry(base.id(), base.requestId(), base.operation(),
                            JournalState.FAILED, base.createdAt(), reapedAt, base.request(), null,
                            STALE_PENDING_REAPED, base.replayable(), record.replay);
                    reaped++;
                }
            }
        }
        return reaped;
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

    /**
     * Atomically claims {@code id} for replay. Under the journal lock the entry must still exist,
     * be {@code SUCCEEDED}, deterministic and replayable, and must not already carry a live claim;
     * an {@code IN_PROGRESS} claim whose lease expired may be taken over (lazy expiry, evaluated
     * here — there is no sweeper).
     *
     * <p>Public so journal-level tests can assert ownership; production callers are
     * {@link AuditReplayService} only.</p>
     *
     * @return the entry with an {@code IN_PROGRESS} replay record plus the opaque owner token
     * @throws ReplayConflictException when the entry is unknown, ineligible or actively claimed
     */
    public ReplayClaim claimReplay(String id) {
        synchronized (lock) {
            JournalRecord record = id == null ? null : entries.get(id);
            if (record == null) {
                throw new ReplayConflictException(id, "entry no longer exists");
            }
            Instant now = Instant.now(clock);
            ReplayRecord current = record.replay;
            if (current != null && current.state() == ReplayState.IN_PROGRESS
                    && current.leaseExpiresAt() != null && current.leaseExpiresAt().isAfter(now)) {
                throw new ReplayConflictException(id,
                        "entry already has an active replay claim (lease expires at "
                                + current.leaseExpiresAt() + ")");
            }
            AuditEntry base = record.base;
            if (base.state() != JournalState.SUCCEEDED) {
                throw new ReplayConflictException(id, "entry is " + base.state()
                        + " and can only replay SUCCEEDED entries");
            }
            if (base.operation() == null || !base.operation().isDeterministic()) {
                throw new ReplayConflictException(id, "operation " + base.operation()
                        + " is non-deterministic");
            }
            if (!base.replayable()) {
                throw new ReplayConflictException(id,
                        "entry is not replayable (the stored request is unavailable)");
            }
            Instant leaseExpiresAt = now.plusSeconds(replayLeaseSeconds);
            ReplayRecord claimed = new ReplayRecord(ReplayState.IN_PROGRESS, now, "replay in progress",
                    leaseExpiresAt);
            record.replayOwnerToken = UUID.randomUUID().toString();
            record.replay = claimed;
            record.base = withReplay(base, claimed);
            return new ReplayClaim(record.base, record.replayOwnerToken, leaseExpiresAt);
        }
    }

    /**
     * Records the terminal replay outcome, but only for the owner of a live claim: the token must
     * match the one issued by {@link #claimReplay(String)}, the claim must still be
     * {@code IN_PROGRESS} and its lease must not have expired. A stale token, an expired or
     * already-completed claim and an evicted entry are all rejected without touching the current
     * replay record or the entry's original state, result and completedAt.
     *
     * <p>Public so journal-level tests can assert ownership; production callers are
     * {@link AuditReplayService} only.</p>
     *
     * @param state {@code VERIFIED}, {@code MISMATCH} or {@code FAILED}
     * @param detail human-readable outcome, truncated to a bounded length
     * @return true when the outcome was written by the live owner, false otherwise
     */
    public boolean completeReplay(String id, String ownerToken, ReplayState state, String detail) {
        if (state != ReplayState.VERIFIED && state != ReplayState.MISMATCH && state != ReplayState.FAILED) {
            throw new IllegalArgumentException("Replay can only complete as VERIFIED, MISMATCH or FAILED, not " + state);
        }
        synchronized (lock) {
            JournalRecord record = id == null ? null : entries.get(id);
            if (record == null) {
                return false;
            }
            ReplayRecord current = record.replay;
            if (current == null || current.state() != ReplayState.IN_PROGRESS) {
                return false;
            }
            if (ownerToken == null || !ownerToken.equals(record.replayOwnerToken)) {
                return false;
            }
            Instant now = Instant.now(clock);
            if (current.leaseExpiresAt() != null && !current.leaseExpiresAt().isAfter(now)) {
                return false;
            }
            ReplayRecord completed = new ReplayRecord(state, now, boundedDetail(state, detail), null);
            record.replayOwnerToken = null;
            record.replay = completed;
            record.base = withReplay(record.base, completed);
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

    /** Keeps a bounded, always-present detail on the recorded outcome. */
    private static String boundedDetail(ReplayState state, String detail) {
        String text = detail;
        if (text == null || text.isBlank()) {
            text = state == ReplayState.FAILED ? "replay failed" : "replay completed";
        }
        return text.length() > MAX_DETAIL_LENGTH ? text.substring(0, MAX_DETAIL_LENGTH) : text;
    }

    private static AuditEntry withReplay(AuditEntry base, ReplayRecord replay) {
        return new AuditEntry(base.id(), base.requestId(), base.operation(), base.state(),
                base.createdAt(), base.completedAt(), base.request(), base.result(),
                base.errorCode(), base.replayable(), replay);
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

    /** UTF-8 encoded size of a stored tree, so the byte bound is measured in bytes, not UTF-16 units. */
    private int serializedBytes(JsonNode tree) {
        return tree.toString().getBytes(StandardCharsets.UTF_8).length;
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
        private String replayOwnerToken;

        private JournalRecord(AuditEntry base, Object originalRequest, ReplayRecord replay) {
            this.base = base;
            this.originalRequest = originalRequest;
            this.replay = replay;
        }
    }
}
