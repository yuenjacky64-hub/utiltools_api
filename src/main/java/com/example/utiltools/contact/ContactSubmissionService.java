package com.example.utiltools.contact;

import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;

/**
 * Bounded, thread-safe intake for the contact form's network seam (REQ-01..REQ-08).
 *
 * <p>Shape mirrors {@code jobs}' {@code BatchHashJobService}: one registry
 * ({@link LinkedHashMap} insertion-ordered, bounded by the retention/cap sweep) guarded by a
 * single lock, a strict admission gate ({@code workerCount + queueCapacity} pending attempts), a
 * {@link ThreadPoolExecutor} with {@link ThreadPoolExecutor.AbortPolicy}, a package-private reaper
 * that a Spring {@code @Scheduled} cadence drives, and package-private {@code submit} /
 * {@code get} / {@code list} / {@code retry} entry points that are also the Spring routes —
 * {@code ContactController} delegates straight through to them.</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>INV-C1: every snapshot field is stamped under the registry lock; an {@link
 *       ContactSubmissionResponse} is a frozen copy, so readers never observe a half-written
 *       attempt; worker writes (admission, terminal, reap) take the same lock and re-check the
 *       generation fence before mutating (REQ-06 / REQ-07).</li>
 *   <li>INV-C2: the only place an attempt's journal entry is closed is a
 *       {@code succeedQuietly} / {@code failQuietly} call that removes the entry id first; a
 *       generation that no longer owns the row (superseded by a retry, reaped, or already
 *       closed) is a no-op, so each attempt's entry closes exactly once (REQ-08).</li>
 *   <li>INV-C3: {@code dispatch} failures surface only the bounded
 *       {@code CONTACT_DISPATCH_FAILED} code and reaper terminalizations only
 *       {@code CONTACT_DELIVERY_LEASE_EXPIRED}; dispatcher text and Java exception messages never
 *       reach a snapshot, an error field, or the journal (REQ-07).</li>
 *   <li>INV-C4: a {@link ContactQueueFullException} rolls back under the same lock the admission
 *       took, so a saturated queue leaves no row, no idempotency mapping, and no attempt
 *       correlation behind — a later submission with the same key is accepted again (REQ-05).</li>
 *   <li>INV-C5: a same-key resubmission either returns the existing row's snapshot with no second
 *       dispatch, or throws {@link ContactIdempotencyConflictException} with the row left
 *       untouched (REQ-04); the key→row index drops the key only when its row is evicted.</li>
 *   <li>INV-C6: the registry is bounded: terminal rows older than {@code retentionSeconds} are
 *       evicted by the periodic cleanup sweep and {@code maxRetainedSubmissions} caps the live
 *       rows, so a form flood cannot grow memory without bound.</li>
 * </ul>
 */
@Service
public class ContactSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ContactSubmissionService.class);

    /** Bounded error code stamped when a dispatcher call fails; never the dispatcher's text. */
    public static final String CONTACT_DISPATCH_FAILED = "CONTACT_DISPATCH_FAILED";

    /** Bounded error code the reaper stamps when an attempt's execution lease lapses. */
    public static final String CONTACT_DELIVERY_LEASE_EXPIRED = "CONTACT_DELIVERY_LEASE_EXPIRED";

    /** Bounded code stamped on the attempts a shutdown drain cancels or force-fails. */
    public static final String CONTACT_SUBMISSION_CANCELLED = "CONTACT_SUBMISSION_CANCELLED";

    static final int MAX_ERROR_TEXT = 200;
    public static final int DEFAULT_LIST_LIMIT = 20;
    public static final int MAX_LIST_LIMIT = 100;
    private static final int DEFAULT_MAX_RETAINED_SUBMISSIONS = 1000;
    private static final int DEFAULT_CLEANUP_INTERVAL_MS = 60_000;
    private static final long DRAIN_AWAIT_SECONDS = 30;

    private final ContactDispatcher dispatcher;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleanup;
    private final int workerCount;
    private final int queueCapacity;
    private final int maxAttempts;
    private final int maxRetainedSubmissions;
    private final long retentionSeconds;
    private final long executionLeaseSeconds;
    private final Clock clock;
    /** Optional audit journal; stays absent in test slices and direct construction. */
    private volatile AuditJournalService journal;

    /**
     * Registry: insertion-ordered (admission order), bounded by the retention/cap sweep. All
     * attempt-mutable state lives inside the row and is touched only under {@link
     * #registryLock} (INV-C1).
     */
    private final LinkedHashMap<String, MutableSubmission> submissions = new LinkedHashMap<>();
    /** Idempotency key -> submission id; only live rows (INV-C5). Guarded by {@link #registryLock}. */
    private final Map<String, String> idempotencyIndex = new HashMap<>();
    /** Monotone admission order; the {@code list} newest-first sort key. */
    private final AtomicLong admissionOrder = new AtomicLong();
    private final Object registryLock = new Object();
    /** Attempts currently QUEUED or DISPATCHING; the dispatch admission gate (REQ-05). */
    private int pendingAttempts;

    /**
     * Production wiring: a bounded pool per the {@code utiltools.contact.*} properties and the
     * out-of-scope no-op dispatcher seam (the package owns the contract; delivery wiring is
     * future work). The optional {@link AuditJournalService} is injected afterwards so auditing
     * stays absent in test slices and direct construction (exactly like the jobs' journal field).
     */
    @Autowired
    public ContactSubmissionService(
            @Value("${utiltools.contact.worker-count:2}") int workerCount,
            @Value("${utiltools.contact.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.contact.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.contact.max-attempts:3}") int maxAttempts,
            @Value("${utiltools.contact.execution-lease-seconds:30}") long executionLeaseSeconds,
            @Value("${utiltools.contact.max-retained-submissions:1000}") int maxRetainedSubmissions,
            @Value("${utiltools.contact.cleanup-interval-ms:60000}") int cleanupIntervalMs,
            Clock clock) {
        // Delivery (mailing-list / SMTP / API fan-out) is out of scope: the seam is a no-op.
        this((submissionId, generation) -> { },
                Math.max(1, workerCount),
                Math.max(1, queueCapacity),
                retentionSeconds,
                Math.max(1, maxAttempts),
                executionLeaseSeconds,
                Math.max(1, maxRetainedSubmissions),
                cleanupIntervalMs,
                clock);
    }

    /**
     * Test-visible constructor: everything is constructor-injected so the machine, fencing, and
     * reaper are fully controllable — {@code retentionSeconds == 0} disables the retention
     * eviction path, the {@code dispatcher} is a stand-in for a real sender, and the journal
     * starts null so auditing is absent until {@link #setAuditJournal}.
     */
    public ContactSubmissionService(ContactDispatcher dispatcher, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds, Clock clock) {
        this(dispatcher, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, DEFAULT_MAX_RETAINED_SUBMISSIONS, DEFAULT_CLEANUP_INTERVAL_MS,
                clock);
    }

    /**
     * Test-visible constructor with retention cap and cleanup interval explicit (the
     * {@code jobs}' construction pattern): {@code cleanupIntervalMs == 0} skips the periodic
     * cleanup sweep entirely.
     */
    public ContactSubmissionService(ContactDispatcher dispatcher, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            int maxRetainedSubmissions, int cleanupIntervalMs, Clock clock) {
        this.dispatcher = dispatcher;
        this.workerCount = Math.max(1, workerCount);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.retentionSeconds = retentionSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.executionLeaseSeconds = executionLeaseSeconds;
        this.maxRetainedSubmissions = Math.max(1, maxRetainedSubmissions);
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity),
                r -> {
                    Thread thread = new Thread(r,
                            "contact-dispatch-" + ThreadLocalRandom.current().nextInt(1_000_000));
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "contact-submission-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        if (cleanupIntervalMs > 0) {
            cleanup.scheduleAtFixedRate(this::evictExpiredSubmissionsSafe,
                    cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
        } else {
            // The sweep is a test-only kill switch; leave the (daemon) executor inert.
            cleanup.shutdown();
        }
    }

    /** Optional journal injection; auditing stays best effort (INV-C2 / INV-C3). */
    @Autowired(required = false)
    public void setAuditJournal(AuditJournalService journal) {
        this.journal = journal;
    }

    /**
     * Registers a submission and queues its first attempt (REQ-01 / REQ-02 / REQ-04 / REQ-05).
     *
     * <p>Same key + same normalized payload returns the existing snapshot without a second
     * dispatch; the same key + a different payload answers 409
     * {@code CONTACT_IDEMPOTENCY_CONFLICT} and leaves the original row untouched; a saturated
     * pool answers 429 {@code CONTACT_QUEUE_FULL} with the admission fully rolled back.</p>
     *
     * @return a snapshot of the accepted submission: {@code DISPATCHING} (the attempt is
     *         admitted and its execution lease stamped the moment its pool slot is reserved)
     */
    public ContactSubmissionResponse submit(ContactSubmissionRequest request) {
        String name = request.name().trim();
        String email = request.email().trim();
        String message = request.message().trim();
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint(name, email, message);
        synchronized (registryLock) {
            evictExpiredLocked(Instant.now(clock));
            if (key != null) {
                String existingId = idempotencyIndex.get(key);
                if (existingId != null) {
                    MutableSubmission existing = submissions.get(existingId);
                    if (existing == null) {
                        // Row already evicted: drop the dangling mapping so the key is usable again
                        // (INV-C5).
                        idempotencyIndex.remove(key);
                    } else {
                        if (existing.fingerprint.equals(fingerprint)) {
                            return snapshot(existing); // Idempotent duplicate: no second dispatch.
                        }
                        throw new ContactIdempotencyConflictException(key, existing.submissionId);
                    }
                }
            }
            reserveAdmissionLocked();
            MutableSubmission submission = new MutableSubmission(UUID.randomUUID().toString(), name,
                    email, message, key, fingerprint, Instant.now(clock),
                    admissionOrder.incrementAndGet(), maxAttempts);
            submissions.put(submission.submissionId, submission);
            if (key != null) {
                idempotencyIndex.put(key, submission.submissionId);
            }
            admitAttemptLocked(submission, 1);
            try {
                executor.execute(() -> runAttempt(submission.submissionId, 1));
            } catch (RejectedExecutionException e) {
                // INV-C4: atomic rollback — close the opened journal entry, drop the row.
                failQuietly(submission, 1, "CONTACT_QUEUE_FULL");
                rollbackAdmissionLocked(submission);
                throw new ContactQueueFullException();
            }
            return snapshot(submission);
        }
    }

    /**
     * Reads one submission's current snapshot (REQ-01); unknown ids answer 404
     * {@code CONTACT_SUBMISSION_NOT_FOUND}.
     *
     * @param submissionId the submission id
     * @return the submission's current snapshot
     */
    public ContactSubmissionResponse get(String submissionId) {
        synchronized (registryLock) {
            return snapshot(requireSubmissionLocked(submissionId));
        }
    }

    /**
     * Newest-first submission snapshots (REQ-01); a row's position is fixed at admission, so a
     * retry never bumps it.
     *
     * @param limit an optional limit clamped to {@code [1, MAX_LIST_LIMIT]}; {@code null} falls
     *        back to {@code DEFAULT_LIST_LIMIT}
     * @return at most {@code limit} snapshots, newest first
     */
    public List<ContactSubmissionResponse> list(Integer limit) {
        int clamped = clampLimit(limit);
        synchronized (registryLock) {
            evictExpiredLocked(Instant.now(clock));
            List<ContactSubmissionResponse> result = new ArrayList<>();
            submissions.values().stream()
                    .sorted(Comparator.comparingLong((MutableSubmission s) -> s.admissionOrder)
                            .reversed().thenComparing(s -> s.submissionId))
                    .limit(clamped)
                    .map(this::snapshot)
                    .forEach(result::add);
            return result;
        }
    }

    /**
     * Bounded retry of a failed submission (REQ-03 / REQ-07): legal only from {@code FAILED} with
     * room in the attempt budget; re-queues as attempt+1 and stamps the lease fields back to
     * {@code null} until the next admission. Anything else (or a retry with no room left) throws
     * {@link ContactTransitionConflictException} and the row is left untouched.
     *
     * @param submissionId the submission id
     * @return a snapshot of the re-queued submission: {@code DISPATCHING} ({@code attempt = N +
     *         1}) with the execution lease re-stamped at admission
     */
    public ContactSubmissionResponse retry(String submissionId) {
        synchronized (registryLock) {
            MutableSubmission submission = requireSubmissionLocked(submissionId);
            if (submission.status != ContactStatus.FAILED) {
                throw new ContactTransitionConflictException(submission.submissionId, submission.status,
                        submission.attempt, submission.maxAttempts,
                        "may only be retried while FAILED");
            }
            if (submission.attempt >= submission.maxAttempts) {
                throw new ContactTransitionConflictException(submission.submissionId, submission.status,
                        submission.attempt, submission.maxAttempts,
                        "has exhausted its " + submission.maxAttempts + " attempts");
            }
            reserveAdmissionLocked();
            int nextAttempt = submission.attempt + 1;
            String previousError = submission.error;
            submission.attempt = nextAttempt;
            submission.status = ContactStatus.QUEUED;
            submission.startedAt = null;
            submission.completedAt = null;
            submission.error = null;
            submission.lastHeartbeatAt = null;
            submission.executionLeaseExpiresAt = null;
            admitAttemptLocked(submission, nextAttempt);
            try {
                executor.execute(() -> runAttempt(submission.submissionId, nextAttempt));
            } catch (RejectedExecutionException e) {
                // INV-C4: roll the re-queue back so the machine is exactly what it was (FAILED,
                // previous attempt, previous error preserved).
                failQuietly(submission, nextAttempt, "CONTACT_QUEUE_FULL");
                submission.attempt = nextAttempt - 1;
                submission.status = ContactStatus.FAILED;
                submission.error = previousError;
                rollbackAdmissionLocked(submission);
                throw new ContactQueueFullException();
            }
            return snapshot(submission);
        }
    }

    /**
     * Terminalizes attempts whose execution lease has lapsed (REQ-06): a stale {@code DISPATCHING}
     * attempt moves to {@code FAILED} with {@code CONTACT_DELIVERY_LEASE_EXPIRED}, its journal
     * entry is closed exactly once, and its pending counter is released so the admission gate
     * frees up. A late completion from the superseded attempt is fenced out (INV-C1).
     *
     * @return the number of attempts reaped
     */
    int reapStaleDispatching() {
        int reaped;
        synchronized (registryLock) {
            Instant now = Instant.now(clock);
            reaped = 0;
            for (MutableSubmission submission : submissions.values()) {
                if (submission.status == ContactStatus.DISPATCHING
                        && submission.executionLeaseExpiresAt != null
                        && !submission.executionLeaseExpiresAt.isAfter(now)) {
                    terminalizeLocked(submission, ContactStatus.FAILED,
                            CONTACT_DELIVERY_LEASE_EXPIRED, now);
                    reaped++;
                }
            }
        }
        return reaped;
    }

    /**
     * Spring {@code @Scheduled} reaper cadence: the package-private {@link
     * #reapStaleDispatching()} is a no-op outside Spring, so the scheduled hook is the only
     * thing that keeps a stalled dispatcher from holding its dispatch slot forever (REQ-06).
     */
    @Scheduled(fixedDelayString = "${utiltools.contact.reaper-interval-ms:5000}")
    public void reapScheduled() {
        reapStaleDispatching();
    }

    /**
     * Evicts terminal rows older than {@code retentionSeconds} and, when the live registry is
     * over the cap, drops the oldest terminal rows until it fits (INV-C6); a dropped row's
     * idempotency mapping is dropped with it so the key becomes usable again (INV-C5).
     *
     * @param now the sweep's clock; injected so the retention path is deterministic in tests
     * @return the number of rows evicted
     */
    int evictExpiredSubmissions(Instant now) {
        int evicted;
        synchronized (registryLock) {
            evicted = evictExpiredLocked(now);
        }
        return evicted;
    }

    private void evictExpiredSubmissionsSafe() {
        try {
            evictExpiredSubmissions(Instant.now(clock));
        } catch (RuntimeException e) {
            log.debug("contact submission cleanup sweep failed", e);
        }
    }

    private int evictExpiredLocked(Instant now) {
        // Completed rows only (DELIVERED / FAILED / CANCELLED), oldest admission first: a row is
        // evictable once its retention window has lapsed or the registry sits over the cap
        // (INV-C6). Live QUEUED/DISPATCHING rows are never dropped.
        Instant retentionCutoff = now.minusSeconds(Math.max(0, retentionSeconds));
        int evicted = 0;
        java.util.Iterator<MutableSubmission> it = submissions.values().iterator();
        while (it.hasNext()) {
            MutableSubmission submission = it.next();
            if (submission.completedAt == null) {
                continue;
            }
            boolean retentionLapsed = retentionSeconds > 0
                    && !submission.completedAt.isAfter(retentionCutoff);
            boolean overCap = submissions.size() > maxRetainedSubmissions;
            if (!retentionLapsed && !overCap) {
                continue;
            }
            it.remove();
            dropRowLocked(submission);
            evicted++;
        }
        return evicted;
    }

    /**
     * Shutdown drain (jobs' {@code shutdown()} pattern): stop accepting, await in-flight
     * dispatches, force-cancel anything still QUEUED, force-fail whatever a killed worker left
     * DISPATCHING, and close the journal entries exactly once. Idempotent: a second call is a
     * no-op.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cleanup.shutdown();
        synchronized (registryLock) {
            for (MutableSubmission submission : submissions.values()) {
                switch (submission.status) {
                    case QUEUED -> terminalizeLocked(submission, ContactStatus.CANCELLED,
                            CONTACT_SUBMISSION_CANCELLED, Instant.now(clock));
                    case DISPATCHING -> terminalizeLocked(submission, ContactStatus.FAILED,
                            CONTACT_SUBMISSION_CANCELLED, Instant.now(clock));
                    default -> { } // terminal: nothing to drain
                }
            }
        }
    }

    /** Clamps an optional list limit into {@code [1, MAX_LIST_LIMIT]} (jobs' pattern). */
    public int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
    }

    // ---------------------------------------------------------------------
    //  Worker + fencing
    // ---------------------------------------------------------------------

    /**
     * Admission stamping (REQ-05 / REQ-06): the moment a slot is reserved the attempt is
     * {@code DISPATCHING} with its execution lease stamped, so a slot that is still waiting
     * in the pool can never strand behind a busy worker, and the at-most-{@code
     * workerCount + queueCapacity} live-attempt gate holds across admission, not just
     * execution. The attempt's journal entry is opened here and its id stored so the
     * terminal / reaper close happens exactly once (REQ-08 / INV-C2).
     *
     * <p>Called only under {@link #registryLock}.</p>
     */
    private void admitAttemptLocked(MutableSubmission submission, int generation) {
        Instant now = Instant.now(clock);
        submission.status = ContactStatus.DISPATCHING;
        submission.startedAt = now;
        submission.lastHeartbeatAt = now;
        submission.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
        String entryId = beginQuietly(submission, generation);
        if (entryId != null) {
            submission.auditEntryIds.put(generation, entryId);
        }
    }

    /**
     * Worker execution: re-checks that the row still exists and this generation still owns a
     * live {@code DISPATCHING} attempt (a late task for an evicted, reaped, or superseded
     * attempt is a no-op), then calls the dispatcher outside the lock (REQ-06 / REQ-07). The
     * {@code DISPATCHING} stamp and the execution lease were already taken at admission.
     */
    private void runAttempt(String submissionId, int generation) {
        synchronized (registryLock) {
            MutableSubmission submission = submissions.get(submissionId);
            if (submission == null || submission.attempt != generation
                    || submission.status != ContactStatus.DISPATCHING) {
                return; // fenced out: evicted, reaped, or superseded — a late task is a no-op
            }
        }
        boolean success;
        String boundedError;
        try {
            dispatcher.dispatch(submissionId, generation);
            success = true;
            boundedError = null;
        } catch (Exception e) {
            // INV-C3: only the bounded code reaches the row, the journal, and the snapshots.
            log.debug("contact dispatch attempt {} of {} failed", generation, submissionId, e);
            success = false;
            boundedError = CONTACT_DISPATCH_FAILED;
        }
        finishAttempt(submissionId, generation, success, boundedError);
    }

    /**
     * Worker completion: fenced on the generation <em>and</em> the {@code DISPATCHING} status —
     * a late completion from a reaped or superseded attempt must not overwrite the terminal
     * (or newer) state, its error, or its completion time (REQ-07).
     */
    private void finishAttempt(String submissionId, int generation, boolean success, String boundedError) {
        synchronized (registryLock) {
            MutableSubmission submission = submissions.get(submissionId);
            if (submission == null || submission.attempt != generation
                    || submission.status != ContactStatus.DISPATCHING) {
                return; // fenced out: the reaper or a newer generation already owns this row
            }
            terminalizeLocked(submission, success ? ContactStatus.DELIVERED : ContactStatus.FAILED,
                    success ? null : boundedError, Instant.now(clock));
        }
    }

    /**
     * The single funnel every terminal transition takes (INV-C1): stamp under the lock, close
     * the attempt's journal entry exactly once, and release the pending counter.
     */
    private void terminalizeLocked(MutableSubmission submission, ContactStatus to, String error,
            Instant now) {
        if (!submission.status.allows(submission.status, to)) {
            throw new IllegalStateException("Illegal contact transition " + submission.status + " -> " + to);
        }
        submission.status = to;
        submission.completedAt = now;
        submission.error = error;
        submission.lastHeartbeatAt = null;
        submission.executionLeaseExpiresAt = null;
        int generation = submission.attempt;
        if (to == ContactStatus.DELIVERED) {
            succeedQuietly(submission, generation);
        } else {
            failQuietly(submission, generation, error);
        }
        pendingAttempts--;
    }

    // ---------------------------------------------------------------------
    //  Admission gate
    // ---------------------------------------------------------------------

    /**
     * The dispatch admission gate (REQ-05): at most {@code workerCount + queueCapacity} attempts
     * may sit in QUEUED or DISPATCHING at once; a caller that cannot reserve answers 429.
     */
    private void reserveAdmissionLocked() {
        if (pendingAttempts >= workerCount + queueCapacity) {
            throw new ContactQueueFullException();
        }
        pendingAttempts++;
    }

    /** INV-C4: the atomic admission rollback — the row and its index entry are removed together. */
    private void rollbackAdmissionLocked(MutableSubmission submission) {
        submissions.remove(submission.submissionId);
        if (submission.idempotencyKey != null) {
            idempotencyIndex.remove(submission.idempotencyKey, submission.submissionId);
        }
        pendingAttempts--;
    }

    // ---------------------------------------------------------------------
    //  Registry access
    // ---------------------------------------------------------------------

    private MutableSubmission requireSubmissionLocked(String submissionId) {
        MutableSubmission submission = submissions.get(submissionId);
        if (submission == null) {
            throw new ContactSubmissionNotFoundException(submissionId);
        }
        return submission;
    }

    private void dropRowLocked(MutableSubmission submission) {
        submissions.remove(submission.submissionId);
        if (submission.idempotencyKey != null) {
            // Conditional drop: only when the index still maps this key to this row, so a newer
            // holder of the same key is never clobbered (INV-C5).
            idempotencyIndex.remove(submission.idempotencyKey, submission.submissionId);
        }
    }

    private ContactSubmissionResponse snapshot(MutableSubmission submission) {
        return new ContactSubmissionResponse(submission.submissionId, submission.status,
                submission.name, submission.email, submission.message, submission.idempotencyKey,
                submission.attempt, submission.maxAttempts, currentAttempt(submission));
    }

    private ContactAttempt currentAttempt(MutableSubmission submission) {
        return new ContactAttempt(submission.attempt, submission.status, submission.startedAt,
                submission.completedAt, submission.error,
                submission.auditEntryIds.get(submission.attempt),
                submission.lastHeartbeatAt, submission.executionLeaseExpiresAt);
    }

    // ---------------------------------------------------------------------
    //  Best-effort journal seams (INV-C2 / INV-C3 — the jobs quiet seam, verbatim in shape)
    // ---------------------------------------------------------------------

    /**
     * Best effort by contract: an audit failure must never change whether an attempt starts, so
     * every throwable is swallowed and a null id simply means "this attempt has no journal
     * entry". The descriptor is bounded and carries no payload: the message body never leaves
     * the registry ({@code ContactAuditDescriptor} holds the trimmed name, the message length,
     * and the key only).
     */
    private String beginQuietly(MutableSubmission submission, int generation) {
        AuditJournalService audit = this.journal;
        if (audit == null) {
            return null;
        }
        try {
            return audit.begin(OperationType.CONTACT_SUBMIT,
                    ContactAuditDescriptor.of(submission.submissionId, generation, submission.name,
                            submission.messageLength, submission.idempotencyKey),
                    submission.submissionId + ":attempt-" + generation).id();
        } catch (RuntimeException e) {
            log.debug("CONTACT_SUBMIT audit begin failed for submission {} attempt {}: {}",
                    submission.submissionId, generation, e.toString());
            return null;
        }
    }

    /** Closes the attempt's entry as SUCCEEDED; a missing or already-terminal entry is ignored. */
    private void succeedQuietly(MutableSubmission submission, int generation) {
        AuditJournalService audit = this.journal;
        String entryId = submission.auditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            audit.succeed(entryId, Map.of(
                    "submissionId", submission.submissionId,
                    "attempt", generation,
                    "outcome", "DELIVERED"));
        } catch (RuntimeException e) {
            log.debug("CONTACT_SUBMIT audit succeed failed for submission {} attempt {}: {}",
                    submission.submissionId, generation, e.toString());
        }
    }

    /** Closes the attempt's entry as FAILED with a bounded code; a missing entry is ignored. */
    private void failQuietly(MutableSubmission submission, int generation, String error) {
        AuditJournalService audit = this.journal;
        String entryId = submission.auditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            audit.fail(entryId, error == null ? CONTACT_DISPATCH_FAILED : error);
        } catch (RuntimeException e) {
            log.debug("CONTACT_SUBMIT audit fail failed for submission {} attempt {}: {}",
                    submission.submissionId, generation, e.toString());
        }
    }

    // ---------------------------------------------------------------------
    //  Normalization
    // ---------------------------------------------------------------------

    /** An omitted or blank key is "no key": it skips the idempotency index entirely. */
    private static String normalizeKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String trimmed = rawKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Canonical fingerprint of the normalized payload: length-prefixed fields plus SHA-256, the
     * same injective encoding the jobs' fingerprint uses — only fields that normalize to the
     * same canonical form collide (REQ-04).
     */
    private static String fingerprint(String name, String email, String message) {
        StringBuilder canonical = new StringBuilder(64);
        appendField(canonical, name);
        appendField(canonical, email);
        appendField(canonical, message);
        byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec but unavailable", e);
        }
    }

    private static void appendField(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append('\n');
    }

    /**
     * Mutable registry row: every attempt-mutable field is touched only under
     * {@link #registryLock} (INV-C1), and the journal-entry map is the exactly-once close
     * bookkeeping (INV-C2).
     */
    private static final class MutableSubmission {
        final String submissionId;
        final String name;
        final String email;
        final String message;
        final int messageLength;
        final String idempotencyKey;
        final String fingerprint;
        final Instant createdAt;
        final long admissionOrder;
        final int maxAttempts;
        final Map<Integer, String> auditEntryIds = new HashMap<>();

        ContactStatus status = ContactStatus.QUEUED;
        int attempt = 1;
        Instant startedAt;
        Instant completedAt;
        String error;
        Instant lastHeartbeatAt;
        Instant executionLeaseExpiresAt;

        MutableSubmission(String submissionId, String name, String email, String message,
                String idempotencyKey, String fingerprint, Instant createdAt, long admissionOrder,
                int maxAttempts) {
            this.submissionId = submissionId;
            this.name = name;
            this.email = email;
            this.message = message;
            this.messageLength = message.length();
            this.idempotencyKey = idempotencyKey;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
            this.admissionOrder = admissionOrder;
            this.maxAttempts = maxAttempts;
        }
    }
}
