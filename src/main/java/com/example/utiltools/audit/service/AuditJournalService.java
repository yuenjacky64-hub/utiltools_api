package com.example.utiltools.audit.service;

import com.example.utiltools.audit.dto.JournalReapResponse;
import com.example.utiltools.audit.dto.QuarantineStatusResponse;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.model.ReplayRecord;
import com.example.utiltools.audit.model.ReplayState;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.utiltools.common.AuditEntryNotFoundException;
import com.example.utiltools.common.ReapConflictException;
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
import java.util.ArrayDeque;
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

    private static final String MANUALLY_REAPED = "MANUALLY_REAPED";

    private static final String MANUALLY_REAPED_DETAIL = "PENDING entry manually reaped";

    private static final String REPLAY_LEASE_EXPIRED_DETAIL = "replay claim lease expired";

    private static final String OVERSIZE_PAYLOAD_DETAIL =
            "request payload exceeds utiltools.audit.max-replay-bytes";

    private static final String STALE_PENDING_DETAIL =
            "PENDING entry older than utiltools.audit.stale-pending-seconds";

    /** Single source of truth for bounded detail length, shared with {@link ReapRecord}. */
    static final int MAX_DETAIL_LENGTH = ReapRecord.MAX_DETAIL_LENGTH;

    private final ObjectMapper objectMapper;
    private final int maxEntries;
    private final int maxReplayBytes;
    private final long stalePendingSeconds;
    private final long replayLeaseSeconds;
    /** Rollbacks of one entry's replay claim allowed before the entry is quarantined; REQ-05. */
    private final int replayLeaseQuarantineThreshold;
    private final Clock clock;

    private final Object lock = new Object();
    private final LinkedHashMap<String, JournalRecord> entries = new LinkedHashMap<>();
    /** Bounded, newest-last audit trail of reaped entries; trimmed to {@code maxEntries}. */
    private final ArrayDeque<ReapRecord> reaped = new ArrayDeque<>();

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
                               @Value("${utiltools.audit.replay-lease-quarantine-threshold:3}")
                               int replayLeaseQuarantineThreshold,
                               Clock clock) {
        this.objectMapper = objectMapper;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxReplayBytes = Math.max(1, maxReplayBytes);
        this.stalePendingSeconds = Math.max(1L, stalePendingSeconds);
        this.replayLeaseSeconds = Math.max(1L, replayLeaseSeconds);
        this.replayLeaseQuarantineThreshold = Math.max(1, replayLeaseQuarantineThreshold);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Convenience constructor defaulting the replay-lease quarantine threshold to
     * {@code utiltools.audit.replay-lease-quarantine-threshold}'s default of 3.
     */
    public AuditJournalService(ObjectMapper objectMapper, int maxEntries, int maxReplayBytes,
                               long stalePendingSeconds, long replayLeaseSeconds, Clock clock) {
        this(objectMapper, maxEntries, maxReplayBytes, stalePendingSeconds, replayLeaseSeconds, 3,
                clock);
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
        boolean requestOversize = tree != null && !replayable;
        JsonNode storedRequest = tree == null ? null : (replayable ? redact(tree) : withheldPayload());

        synchronized (lock) {
            while (entries.size() >= maxEntries) {
                entries.remove(entries.keySet().iterator().next());
            }
            String id = UUID.randomUUID().toString();
            AuditEntry base = new AuditEntry(id, requestId, operation, JournalState.PENDING,
                    Instant.now(clock), null, storedRequest, null, null, replayable, null);
            entries.put(id, new JournalRecord(base, replayable ? request : null, null, requestOversize));
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
     * PENDING -> FAILED, keeping the attempt's outcome payload when it fits
     * {@code utiltools.audit.max-replay-bytes}, so a partially completed batch still replays which of
     * its items succeeded and which failed. An oversize payload is withheld exactly as an oversize
     * success payload is, and the entry is still closed as FAILED with the given error code. An entry
     * closed this way is not replayable: a FAILED operation is never replayed.
     */
    public AuditEntry fail(String id, String errorCode, Object result) {
        synchronized (lock) {
            JournalRecord record = requirePending(id, JournalState.FAILED);
            AuditEntry base = record.base;
            JsonNode resultTree = objectMapper.valueToTree(result);
            boolean oversizeResult = resultTree != null && serializedBytes(resultTree) > maxReplayBytes;
            AuditEntry updated = new AuditEntry(base.id(), base.requestId(), base.operation(),
                    JournalState.FAILED, base.createdAt(), Instant.now(clock), base.request(),
                    oversizeResult ? withheldPayload() : resultTree, errorCode,
                    base.replayable() && !oversizeResult, record.replay);
            record.base = updated;
            return updated;
        }
    }

    /**
     * Records intent to reclaim a PENDING entry on the next {@link #reap()} pass. Changes no state
     * and writes no tombstone here.
     *
     * <p>Only a PENDING entry can be reclaimed, so a terminal ({@code SUCCEEDED}/{@code FAILED})
     * entry and an evicted or unknown id are refused — a valid audit row can never be falsified by
     * a manual reap request, which is the audit-safe rollback guarantee. Repeated requests while
     * the entry stays PENDING are idempotent; the intent is consumed by the pass that acts on it.</p>
     *
     * @return true when the request was recorded; false when the entry is unknown or not PENDING
     */
    public boolean requestManualReap(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        synchronized (lock) {
            JournalRecord record = entries.get(id);
            if (record == null || record.base.state() != JournalState.PENDING) {
                return false;
            }
            record.manualReapRequested = true;
            return true;
        }
    }

    /**
     * Scheduled cleanup sweep, the single scheduled entry point of the journal. Runs every
     * {@code utiltools.audit.cleanup-interval-ms} (fixed delay, so passes never overlap) and
     * delegates to the clock-free {@link #reap()} body.
     */
    @Scheduled(fixedDelayString = "${utiltools.audit.cleanup-interval-ms:60000}")
    public void reapJournal() {
        reap();
    }

    /**
     * Stale-PENDING sweep; delegates to the clock-free variant with a cutoff derived from
     * {@code utiltools.audit.stale-pending-seconds}. No longer scheduled on its own — the
     * {@link #reapJournal()} reaper covers this scenario.
     */
    public void reapStalePending() {
        reapStalePending(Instant.now(clock).minusSeconds(stalePendingSeconds));
    }

    /**
     * One reaping pass over the journal, executed entirely under the journal lock so a pass can
     * never observe or produce a half-applied transition.
     *
     * <p>Each entry is classified with a fixed precedence and receives at most one reason per
     * pass: an expired {@code IN_PROGRESS} replay claim is rolled back to {@code FAILED} without
     * touching the entry, a PENDING entry that was requested through
     * {@link #requestManualReap(String)} is moved to {@code FAILED} with {@code MANUALLY_REAPED}
     * (ahead of the age-based sweep, so a manual reclaim is never reported as stale), an orphaned
     * PENDING entry older than
     * {@code utiltools.audit.stale-pending-seconds} is moved to {@code FAILED} with
     * {@code STALE_PENDING_REAPED}, and an entry that was already terminal when the pass started
     * and whose <em>request</em> exceeded {@code utiltools.audit.max-replay-bytes} at {@code begin()}
     * (so the payload was withheld) is reclaimed (removed). An entry whose replayable flag was
     * cleared only because its <em>result</em> was oversize keeps a truthful {@code SUCCEEDED}
     * record and is never reclaimed. Every reap leaves one bounded {@link ReapRecord}
     * tombstone.</p>
     *
     * @return the number of reaped entries/tombstones produced by this pass
     */
    public int reap() {
        synchronized (lock) {
            return runPassLocked(false, null, Instant.now(clock)).reaped();
        }
    }

    /**
     * One operator-triggered, bounded and synchronous pass over the journal: the manual sibling of
     * the scheduled {@link #reapJournal()} pass.
     *
     * <p>Both passes execute the same classification and the same mutations, under the same journal
     * lock, so a manual pass can never interleave with a scheduled pass, can never reap an entry
     * twice and can never append a second {@link ReapRecord} for one entry. The only difference is
     * {@code dryRun}: a preview classifies and counts exactly like an applying pass but touches
     * nothing — no {@code PENDING -> FAILED} transition, no replay-claim rollback, no tombstone, no
     * manual-intent consumption and no eviction.</p>
     *
     * <p>{@code entryId}, when non-blank, targets one entry. Under the lock the entry must exist
     * ({@link AuditEntryNotFoundException} otherwise, answered as 404) and must still be
     * {@code PENDING} ({@link ReapConflictException} otherwise, answered as 409
     * {@code REAP_CONFLICT}); the conflict path mutates nothing and leaves any stored intent for a
     * different entry untouched. Intent for the target is recorded through
     * {@link #requestManualReap(String)} and consumed by the pass it triggers, so a
     * {@code PENDING} target is always reported once as {@code MANUALLY_REAPED} — in preview and in
     * apply alike. The pass itself still sweeps the whole journal, so a targeted call also reports
     * any other entry that became eligible. An entry that reached a terminal state between two
     * passes is never rewritten: it is simply not classified as {@code PENDING} any more.</p>
     *
     * <p>The returned DTO carries counts only. A pass invoked here never appends an audit journal
     * entry (no recursive journalling) and every reclaim still goes through the
     * {@code utiltools.audit.max-entries} clamp.</p>
     *
     * @param entryId blank/null for a full pass, otherwise the id of one {@code PENDING} entry
     * @param dryRun  true to preview the pass without mutating anything
     * @return the per-reason counts produced (or, for a preview, that would be produced) by the pass
     */
    public JournalReapResponse reapManually(String entryId, boolean dryRun) {
        String target = (entryId == null || entryId.isBlank()) ? null : entryId;
        Instant now = Instant.now(clock);
        synchronized (lock) {
            if (target != null) {
                JournalRecord record = entries.get(target);
                if (record == null) {
                    throw new AuditEntryNotFoundException(target);
                }
                if (record.base.state() != JournalState.PENDING) {
                    throw new ReapConflictException(target, "state is " + record.base.state());
                }
                if (!dryRun) {
                    record.manualReapRequested = true;
                }
            }
            return runPassLocked(dryRun, target, now);
        }
    }

    /**
     * Shared body of the manual and scheduled passes. The caller must hold {@link #lock} and must
     * have resolved {@code manualTargetId} already (validated, intent recorded).
     *
     * <p>The classification snapshot is taken up front, so each entry is decided at most once per
     * pass with fixed precedence — expired replay claim, manual intent, stale PENDING, oversize
     * request — and carries at most one reason. A pass therefore never both rolls back and fails
     * one entry, and never double-tombstones it.</p>
     */
    private JournalReapResponse runPassLocked(boolean dryRun, String manualTargetId, Instant now) {
        Instant staleCutoff = now.minusSeconds(stalePendingSeconds);
        List<Map.Entry<String, JournalRecord>> snapshot = new ArrayList<>(entries.entrySet());
        List<String> reclaimQueue = new ArrayList<>();
        int replayLeaseExpired = 0;
        int manuallyReaped = 0;
        int stalePending = 0;
        int oversizePayload = 0;
        int reapedCount = 0;
        for (Map.Entry<String, JournalRecord> candidate : snapshot) {
            String id = candidate.getKey();
            JournalRecord record = candidate.getValue();
            // A preview of a targeted pass simulates the intent the applying pass would record,
            // without touching the flag: the same classification, no mutation.
            boolean manualIntent = record.manualReapRequested
                    || (dryRun && id.equals(manualTargetId));
            ReapDecision decision = classify(record, now, staleCutoff, manualIntent);
            if (decision == null) {
                continue;
            }
            if (!dryRun) {
                applyDecision(id, record, decision, reclaimQueue, now);
            }
            switch (decision.reason()) {
                case REPLAY_LEASE_EXPIRED, REPLAY_LEASE_QUARANTINED -> replayLeaseExpired++;
                case MANUALLY_REAPED -> manuallyReaped++;
                case STALE_PENDING -> stalePending++;
                case OVERSIZE_PAYLOAD -> oversizePayload++;
                default -> throw new IllegalStateException(
                        "Unclassified reap reason: " + decision.reason());
            }
            reapedCount++;
        }
        if (!dryRun) {
            for (String id : reclaimQueue) {
                entries.remove(id);
            }
            enforceBound();
        }
        return new JournalReapResponse(dryRun, snapshot.size(), reapedCount, replayLeaseExpired,
                manuallyReaped, stalePending, oversizePayload, manualTargetId);
    }

    /**
     * Pure eligibility classification: reads the record, never writes it (a preview runs exactly
     * this code, so classification must be free of side effects). Returns null when the entry is
     * not eligible in this pass. {@code manualIntent} is the stored flag plus, for a targeted
     * preview, the intent that the applying pass would have recorded.
     */
    private ReapDecision classify(JournalRecord record, Instant now, Instant staleCutoff,
                                  boolean manualIntent) {
        AuditEntry base = record.base;
        JournalState stateAtScan = base.state();

        // REQ-06: a quarantined entry is terminal and permanently ineligible — the fall-through.
        if (record.replayLeaseQuarantined) {
            return null;
        }

        ReplayRecord claim = record.replay;
        if (claim != null && claim.state() == ReplayState.IN_PROGRESS
                && claim.leaseExpiresAt() != null
                && !claim.leaseExpiresAt().isAfter(now)) {
            // REQ-03: project the post-increment count without mutating (dryRun parity).
            int projected = record.replayLeaseExhaustionCount + 1;
            if (projected >= replayLeaseQuarantineThreshold) {
                return new ReapDecision(ReapReason.REPLAY_LEASE_QUARANTINED, ReapAction.FAIL_ENTRY,
                        ReapReason.REPLAY_LEASE_QUARANTINED.name(), quarantineDetail(projected));
            }
            return new ReapDecision(ReapReason.REPLAY_LEASE_EXPIRED, ReapAction.ROLLBACK_CLAIM,
                    null, REPLAY_LEASE_EXPIRED_DETAIL);
        }

        if (stateAtScan == JournalState.PENDING && manualIntent) {
            return new ReapDecision(ReapReason.MANUALLY_REAPED, ReapAction.FAIL_ENTRY,
                    MANUALLY_REAPED, MANUALLY_REAPED_DETAIL);
        }

        if (stateAtScan == JournalState.PENDING && base.createdAt() != null
                && base.createdAt().isBefore(staleCutoff)) {
            return new ReapDecision(ReapReason.STALE_PENDING, ReapAction.FAIL_ENTRY,
                    STALE_PENDING_REAPED, STALE_PENDING_DETAIL);
        }

        if (stateAtScan != JournalState.PENDING && record.requestOversize) {
            return new ReapDecision(ReapReason.OVERSIZE_PAYLOAD, ReapAction.RECLAIM_ENTRY,
                    null, OVERSIZE_PAYLOAD_DETAIL);
        }

        return null;
    }

    /**
     * Applies one {@link ReapDecision} to the live record and appends its single tombstone. Assumes
     * the lock is held and that {@code decision} came from {@link #classify} for the same record in
     * the same pass. Reclaims are queued and removed by the caller so the entry map is never
     * structurally modified while iterating over the snapshot.
     */
    private void applyDecision(String id, JournalRecord record, ReapDecision decision,
                               List<String> reclaimQueue, Instant now) {
        AuditEntry base = record.base;
        switch (decision.action()) {
            case ROLLBACK_CLAIM -> {
                ReplayRecord claim = record.replay;
                ReplayRecord rolledBack = new ReplayRecord(ReplayState.FAILED, now,
                        boundedDetail(ReplayState.FAILED, REPLAY_LEASE_EXPIRED_DETAIL
                                + " at " + (claim == null ? null : claim.leaseExpiresAt())), null);
                record.replayLeaseExhaustionCount++;
                record.replayOwnerToken = null;
                record.replay = rolledBack;
                record.base = withReplay(base, rolledBack);
            }
            case FAIL_ENTRY -> {
                if (decision.reason() == ReapReason.MANUALLY_REAPED) {
                    record.manualReapRequested = false;
                }
                if (decision.reason() == ReapReason.REPLAY_LEASE_QUARANTINED) {
                    // REQ-04: one final rollback of the lapsed claim, then the entry is failed
                    // for good — the flag makes every later pass skip it.
                    ReplayRecord quarantinedClaim = new ReplayRecord(ReplayState.FAILED, now,
                            boundedDetail(ReplayState.FAILED, decision.detail()), null);
                    record.replayLeaseExhaustionCount++;
                    record.replayLeaseQuarantined = true;
                    record.replayOwnerToken = null;
                    record.replay = quarantinedClaim;
                }
                record.base = new AuditEntry(base.id(), base.requestId(), base.operation(),
                        JournalState.FAILED, base.createdAt(), now, base.request(), null,
                        decision.errorCode(), base.replayable(), record.replay);
            }
            case RECLAIM_ENTRY -> reclaimQueue.add(id);
            default -> throw new IllegalStateException("Unclassified reap action: " + decision.action());
        }
        recordReap(id, base.operation(), decision.reason(), decision.detail(), now);
    }

    /** What to do to the entry behind one eligible reap reason. */
    private enum ReapAction { ROLLBACK_CLAIM, FAIL_ENTRY, RECLAIM_ENTRY }

    /** One classified eligibility decision: the reason, the action it implies and its tombstone text. */
    private record ReapDecision(ReapReason reason, ReapAction action, String errorCode, String detail) {}

    /**
     * Newest-first snapshot of reaped-entry tombstones, clamped to {@code [1, maxEntries]} and,
     * when {@code reason} is non-null, restricted to tombstones carrying that reason.
     *
     * <p>Read-only: it never runs a reap pass, never appends a tombstone and never touches an
     * entry, so polling it cannot reclaim anything. The filter is applied before the size check,
     * so {@code limit} bounds the returned rows, not the scanned rows.</p>
     */
    public List<ReapRecord> reapLog(int limit, ReapReason reason) {
        int effectiveLimit = Math.min(Math.max(1, limit), maxEntries);
        synchronized (lock) {
            List<ReapRecord> snapshot = new ArrayList<>();
            Iterator<ReapRecord> it = reaped.descendingIterator();
            while (it.hasNext() && snapshot.size() < effectiveLimit) {
                ReapRecord tombstone = it.next();
                if (reason == null || tombstone.reason() == reason) {
                    snapshot.add(tombstone);
                }
            }
            return snapshot;
        }
    }

    /**
     * Newest-first snapshot of reaped-entry tombstones, clamped to {@code [1, maxEntries]}.
     */
    public List<ReapRecord> reapLog(int limit) {
        return reapLog(limit, null);
    }

    /** Assumes the lock is held. Appends a tombstone and keeps the log bounded. */
    private void recordReap(String entryId, OperationType operation, ReapReason reason,
                            String detail, Instant reapedAt) {
        reaped.addLast(ReapRecord.reaped(entryId, operation, reason, detail, reapedAt));
        while (reaped.size() > maxEntries) {
            reaped.removeFirst();
        }
    }

    /** Assumes the lock is held. Oldest-first eviction so {@code size() <= maxEntries} holds. */
    private void enforceBound() {
        while (entries.size() > maxEntries) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /**
     * Marks PENDING entries created before {@code cutoff} as FAILED with STALE_PENDING_REAPED.
     * Public and clock-free so tests advance the cutoff instead of waiting.
     *
     * <p>Leaves the same bounded {@code STALE_PENDING} {@link ReapRecord} tombstone as the
     * {@link #reap()} pass, so this legacy sweep cannot bypass REQ-03's audit trail.</p>
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
                    recordReap(base.id(), base.operation(), ReapReason.STALE_PENDING,
                            STALE_PENDING_DETAIL, reapedAt);
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
     * The configured journal bound ({@code utiltools.audit.max-entries}); consumers clamp their own
     * limits to it so a sweep can never scan more entries than the journal can hold.
     */
    public int maxEntries() {
        return maxEntries;
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
                        + " and cannot be replayed");
            }
            if (base.operation() == null || !base.operation().isDeterministic()) {
                throw new ReplayConflictException(id, "operation " + base.operation()
                        + " is non-deterministic");
            }
            if (!base.replayable()) {
                throw new ReplayConflictException(id,
                        "entry is not replayable (payload exceeds utiltools.audit.max-replay-bytes)");
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

    /**
     * Renews the lease of the live claim on {@code id} for one more
     * {@code utiltools.audit.replay-lease-seconds} window, but only for its current owner: the
     * entry must exist, the replay must still be {@code IN_PROGRESS}, the token must be the one
     * currently issued by {@link #claimReplay(String)} and the lease must not already have expired.
     * A stale token, a missing entry, a terminal claim and an expired lease are all refused without
     * mutating the journal, so a renewal can never resurrect a claim the reaper may already have
     * rolled back.
     *
     * <p>Renewal and reaping both run under the journal lock and read the same
     * {@code leaseExpiresAt}, so a renewal racing a {@code REPLAY_LEASE_EXPIRED} pass resolves to
     * exactly one winner.</p>
     *
     * <p>Public so journal-level tests can assert the fence; production callers are
     * {@link AuditReplayService} only.</p>
     *
     * @return the new {@code leaseExpiresAt}, or empty when the renewal was refused
     */
    public Optional<Instant> renewReplayLease(String id, String ownerToken) {
        synchronized (lock) {
            JournalRecord record = id == null ? null : entries.get(id);
            if (record == null) {
                return Optional.empty();
            }
            ReplayRecord current = record.replay;
            if (current == null || current.state() != ReplayState.IN_PROGRESS) {
                return Optional.empty();
            }
            if (ownerToken == null || !ownerToken.equals(record.replayOwnerToken)) {
                return Optional.empty();
            }
            Instant now = Instant.now(clock);
            // Same boundary as completeReplay and classify, so renewal and reap cannot disagree.
            if (current.leaseExpiresAt() == null || !current.leaseExpiresAt().isAfter(now)) {
                return Optional.empty();
            }
            Instant renewedUntil = now.plusSeconds(replayLeaseSeconds);
            if (renewedUntil.isBefore(current.leaseExpiresAt())) {
                // A renewal may extend a lease but never shorten it.
                renewedUntil = current.leaseExpiresAt();
            }
            ReplayRecord renewed = new ReplayRecord(ReplayState.IN_PROGRESS, current.replayedAt(),
                    "replay in progress", renewedUntil);
            record.replay = renewed;
            record.base = withReplay(record.base, renewed);
            return Optional.of(renewedUntil);
        }
    }

    /** The claim lease window, so the replay service can derive its heartbeat cadence. */
    public long replayLeaseSeconds() {
        return replayLeaseSeconds;
    }

    /** Rollbacks of one entry's replay claim allowed before quarantine; exposed read-only. */
    public int replayLeaseQuarantineThreshold() {
        return replayLeaseQuarantineThreshold;
    }

    /**
     * Read-only quarantine snapshot: the effective quarantine threshold, how many bounded
     * {@code REPLAY_LEASE_QUARANTINED} tombstones the journal holds, and how many times replay claim
     * leases have expired across the journal's entries.
     *
     * <p>Read-only (INV-QS-1): it never runs a reap pass and never calls {@code classify},
     * {@code applyDecision}, {@code recordReap}, {@code enforceBound} or {@code claimReplay}, so
     * polling it cannot roll back a claim, fail an entry or append a tombstone. One
     * {@code synchronized (lock)} block (INV-QS-3) makes the three values one journal instant, and
     * the work is {@code O(maxEntries)} field reads with no snapshot list allocation (INV-QS-6).</p>
     *
     * <p>Each counter has exactly one source (INV-QS-2): {@code totalExpirationCount} sums the
     * per-entry {@code replayLeaseExhaustionCount} — the counter that drives quarantine, including
     * entries already quarantined — and {@code quarantinedCount} counts tombstones carrying
     * {@link ReapReason#REPLAY_LEASE_QUARANTINED}. Tombstone counts are never added to the
     * expiration sum, because a {@code ROLLBACK_CLAIM} reap both increments the counter and appends
     * a {@code REPLAY_LEASE_EXPIRED} tombstone.</p>
     */
    public QuarantineStatusResponse quarantineStatus() {
        synchronized (lock) {
            int expirations = 0;
            for (JournalRecord record : entries.values()) {
                expirations += record.replayLeaseExhaustionCount;
            }
            int quarantined = 0;
            for (ReapRecord tombstone : reaped) {
                if (tombstone.reason() == ReapReason.REPLAY_LEASE_QUARANTINED) {
                    quarantined++;
                }
            }
            return new QuarantineStatusResponse(replayLeaseQuarantineThreshold, quarantined,
                    expirations);
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

    /** REQ-02: "replay lease expired N times; entry quarantined", clamped like every other detail. */
    private static String quarantineDetail(int expirations) {
        return boundedDetail(ReplayState.FAILED,
                ReapReason.REPLAY_LEASE_QUARANTINED_DETAIL.formatted(expirations));
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
        /** Set when {@code begin()} withheld the request because it exceeded max-replay-bytes. */
        private final boolean requestOversize;
        private ReplayRecord replay;
        private String replayOwnerToken;
        /** Set by {@link #requestManualReap(String)} and cleared by the pass that reclaims it. */
        private boolean manualReapRequested;
        /** Rollbacks applied to this entry's replay claim; REQ-01, mutated under the journal lock. */
        private int replayLeaseExhaustionCount;
        /** Set once the entry is quarantined; REQ-06, makes the entry permanently ineligible. */
        private boolean replayLeaseQuarantined;

        private JournalRecord(AuditEntry base, Object originalRequest, ReplayRecord replay,
                              boolean requestOversize) {
            this.base = base;
            this.originalRequest = originalRequest;
            this.replay = replay;
            this.requestOversize = requestOversize;
            this.replayLeaseExhaustionCount = 0;
            this.replayLeaseQuarantined = false;
        }
    }
}
