package com.example.utiltools.jobs;

import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.ratelimit.ClientKeyResolver;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single owner of all mutable job state.
 *
 * <p>Jobs are executed by a bounded {@link ThreadPoolExecutor} backed by an
 * {@link ArrayBlockingQueue} with an {@link ThreadPoolExecutor.AbortPolicy}, so saturation is
 * observable as a {@link JobQueueFullException} instead of blocking a request thread or growing
 * the heap. Every accepted job moves QUEUED -> RUNNING -> COMPLETED|FAILED exactly once, or
 * QUEUED -> CANCELLED when it is withdrawn before a worker admits it; a FAILED job may be requeued
 * as FAILED -> QUEUED while its attempt count is below the configured maximum. An optional
 * idempotency key maps to exactly one job and one execution, and stored results keep the input item
 * order.</p>
 *
 * <p>Each execution attempt carries a generation equal to its 1-based attempt number. A worker
 * captures its generation at admission and writes RUNNING, COMPLETED or FAILED only while
 * {@code job.attempt} still equals it, so a late worker from a superseded attempt cannot overwrite
 * the retried attempt's status, results or error.</p>
 *
 * <p>Each admitted attempt also holds an execution lease: the worker stamps
 * {@code lastHeartbeatAt} and {@code executionLeaseExpiresAt} at admission and renews the lease
 * before hashing every item. A scheduled reaper moves a RUNNING job whose lease has lapsed to
 * FAILED with error {@code EXECUTION_LEASE_EXPIRED}, so a worker stalled inside
 * {@code CryptoService.hash} can no longer leave a job RUNNING forever. Reaping reuses the
 * generation fence: a reaped attempt keeps its attempt number but is no longer RUNNING, so a late
 * worker from that attempt writes nothing, and the FAILED job stays retryable.</p>
 *
 * <p>Progress is checkpointed per item. A private ordered prefix of hashed {@link HashResponse}
 * values lives on the job, and each successful hash is committed to it only while the worker still
 * owns the job ({@code job.attempt == generation && job.status == RUNNING}), so a fenced, cancelled,
 * reaped or superseded worker never appends. An attempt resumes at the prefix's next index, and a
 * {@code FAILED -> QUEUED} retry keeps the prefix, so a transient fault late in a batch no longer
 * rehashes everything before it. The prefix is published as {@code results} and cleared only on
 * {@code COMPLETED}; {@code completedItems} reports its size while the job is not completed, and a
 * {@code CANCELLED} job clears it so a withdrawal never retains partial digests.</p>
 *
 * <p>Every <em>started</em> attempt is journalled exactly once as a {@code BATCH_HASH} entry. The
 * correlation id ({@link RequestContext}) is captured on the caller thread at
 * {@link #submit(BatchHashRequest)} / {@link #retry(String)} time and carried on the attempt, then
 * installed on the worker thread for the duration of the attempt and cleared in a {@code finally}
 * block, so a reused pool thread never leaks one job's {@code X-Request-Id} into the next. The
 * journal entry is opened at worker admission and closed in the same job-monitor section that
 * stamps the attempt's terminal status (worker completion, worker failure, or reaper fencing), so
 * the audit state can never disagree with the job snapshot; every journal call is best effort and
 * cannot move a job's status, results or error.</p>
 */
@Service
public class BatchHashJobService {

    /** Error recorded on a RUNNING attempt whose execution lease lapsed without a renewal. */
    public static final String EXECUTION_LEASE_EXPIRED = "EXECUTION_LEASE_EXPIRED";

    /** Error recorded on a non-terminal job the {@code @PreDestroy} drain failed on the way down. */
    public static final String JOB_SHUTDOWN = "JOB_SHUTDOWN";

    /** Grace window the drain waits for in-flight work when the property is not configured. */
    public static final long DEFAULT_SHUTDOWN_GRACE_SECONDS = 30L;

    /** Per-client ceiling on non-terminal jobs when the property is not configured. */
    static final int DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT = 20;

    /** Hard ceiling on retained terminal job rows when the property is not configured. */
    static final int DEFAULT_MAX_RETAINED_JOBS = 1000;

    private static final long DEFAULT_EXECUTION_LEASE_SECONDS = 30;
    private static final long DEFAULT_EXECUTION_REAPER_INTERVAL_MS = 5000;
    private static final String BATCH_HASH_FAILED = "BATCH_HASH_FAILED";
    /** Bounded, payload-free detail of a heartbeat row; compile-time constant (27 chars, far under 200). */
    private static final String HEARTBEAT_DETAIL = "lease renewed via heartbeat";
    /** Result marker that distinguishes a lease-renewal row from an attempt row. */
    private static final String HEARTBEAT_STATUS = "HEARTBEAT";
    /** Bound shared by every client-controlled string that enters the audit journal. */
    private static final int MAX_AUDIT_TEXT = 200;

    private static final Logger log = LoggerFactory.getLogger(BatchHashJobService.class);

    /** Default number of entries returned by {@link #listJobs(JobStatus, int)}. */
    public static final int DEFAULT_LIST_LIMIT = 20;

    /** Hard ceiling on the number of entries returned by {@link #listJobs(JobStatus, int)}. */
    public static final int MAX_LIST_LIMIT = 100;

    /** Newest first: createdAt descending, jobId ascending as the deterministic tie-breaker. */
    private static final Comparator<MutableJob> LIST_ORDER =
            Comparator.comparing((MutableJob job) -> job.createdAt).reversed()
                    .thenComparing(job -> job.jobId);

    /** Exact inverse of LIST_ORDER (tie-break included); used only as the bounded-heap eviction order. */
    private static final Comparator<MutableJob> LIST_ORDER_INVERSE = LIST_ORDER.reversed();

    /**
     * Oldest {@code completedAt} first, {@code jobId} ascending as the deterministic tie-break. A
     * terminal row always carries {@code completedAt}; the defensive {@code nullsFirst} arm keeps a
     * hypothetical null from throwing inside the registry section and treats it as the oldest row.
     */
    private static final Comparator<MutableJob> EVICTION_ORDER =
            Comparator.comparing((MutableJob job) -> job.completedAt,
                            Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                    .thenComparing(job -> job.jobId);

    private final CryptoService cryptoService;
    private final ThreadPoolExecutor executor;
    private final int workerCount;
    private final int queueCapacity;
    private final long retentionSeconds;
    private final int maxAttempts;
    private final long executionLeaseSeconds;
    private final long executionReaperIntervalMs;
    private final long shutdownGraceSeconds;
    private final int maxActiveJobsPerClient;
    private final int maxRetainedJobs;
    private final Clock clock;

    /**
     * Serializes concurrent drain callers only. Workers never take it, so it cannot deadlock with
     * the pool, and it is always taken first (never while a job monitor is held).
     */
    private final Object shutdownMonitor = new Object();

    /** Null means auditing is disabled (test slices and direct construction). */
    private volatile AuditJournalService journal;

    private final ConcurrentHashMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    /**
     * Live (QUEUED or RUNNING) job count per client key. Guarded by {@link #registryLock}; an entry
     * is removed at zero, so the map stays bounded by the number of non-terminal jobs
     * ({@code worker-count + queue-capacity}) and not by the client population.
     */
    private final Map<String, Integer> activeJobsByClient = new HashMap<>();

    /**
     * Compatibility overload for callers (and tests) that build the service directly: the system
     * clock and the default lease and reaper cadence are used.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                DEFAULT_EXECUTION_LEASE_SECONDS, DEFAULT_EXECUTION_REAPER_INTERVAL_MS,
                DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, Clock.systemUTC());
    }

    /** The checkpoint projection used by the audit outcome; mirrors {@link #snapshot(MutableJob)}. */
    private static int completedItems(MutableJob job) {
        return switch (job.status) {
            case COMPLETED -> job.items.size();
            case CANCELLING, CANCELLED -> 0; // a cancelled job never exposes a prefix
            default -> job.checkpoint.size();
        };
    }

    /**
     * Compatibility overload for callers (and tests) that pass an explicit clock but not the
     * execution lease / reaper cadence.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                DEFAULT_EXECUTION_LEASE_SECONDS, DEFAULT_EXECUTION_REAPER_INTERVAL_MS,
                DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
    }

    /**
     * The Spring-selected constructor: every knob is bound here, including the {@code @PreDestroy}
     * drain's grace window.
     */
    @Autowired
    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.jobs.max-attempts:3}") int maxAttempts,
            @Value("${utiltools.jobs.execution-lease-seconds:30}") long executionLeaseSeconds,
            @Value("${utiltools.jobs.execution-reaper-interval-ms:5000}") long executionReaperIntervalMs,
            @Value("${utiltools.jobs.shutdown-grace-seconds:30}") long shutdownGraceSeconds,
            @Value("${utiltools.jobs.max-active-jobs-per-client:20}") int maxActiveJobsPerClient,
            @Value("${utiltools.jobs.max-retained-jobs:1000}") int maxRetainedJobs,
            Clock clock) {
        this.cryptoService = cryptoService;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.retentionSeconds = retentionSeconds;
        // A non-positive configuration must not authorise unlimited retries.
        this.maxAttempts = Math.max(1, maxAttempts);
        // A non-positive lease would fail every attempt mid-hash and a non-positive reaper cadence
        // is meaningless, so both are rejected loudly at startup instead of being clamped.
        if (executionLeaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.execution-lease-seconds must be positive, was " + executionLeaseSeconds);
        }
        if (executionReaperIntervalMs <= 0) {
            throw new IllegalArgumentException("utiltools.jobs.execution-reaper-interval-ms must be positive, was "
                    + executionReaperIntervalMs);
        }
        // Zero is legal and means "no grace": the drain forces immediately, which is what a test
        // (and an operator who wants a hard stop) needs. Only a negative budget is rejected.
        if (shutdownGraceSeconds < 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.shutdown-grace-seconds must not be negative, was " + shutdownGraceSeconds);
        }
        // A non-positive quota would refuse every submission, so it is rejected loudly at startup.
        if (maxActiveJobsPerClient <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.max-active-jobs-per-client must be positive, was " + maxActiveJobsPerClient);
        }
        // A non-positive cap would either evict every terminal row or disable retention entirely.
        if (maxRetainedJobs <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.max-retained-jobs must be positive, was " + maxRetainedJobs);
        }
        this.executionLeaseSeconds = executionLeaseSeconds;
        this.executionReaperIntervalMs = executionReaperIntervalMs;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
        this.maxActiveJobsPerClient = maxActiveJobsPerClient;
        this.maxRetainedJobs = maxRetainedJobs;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Compatibility overload for callers (and tests) that predate the drain grace: the parameter
     * list is unchanged and the configured default window applies. Unannotated on purpose — Spring
     * must keep selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, int maxActiveJobsPerClient, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_SHUTDOWN_GRACE_SECONDS,
                maxActiveJobsPerClient, clock);
    }

    /**
     * Test/compatibility overload: same as the {@code @Autowired} constructor, without the
     * per-client quota (the default applies).
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
    }

    /**
     * Test convenience: the same service with an explicit journal, so a unit test can assert the
     * per-attempt audit trail without a Spring context. Unannotated on purpose — Spring must keep
     * selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit per-client quota and journal, so a unit test can assert quota
     * admission and the per-attempt audit trail without a Spring context.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, int maxActiveJobsPerClient, Clock clock,
            AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, maxActiveJobsPerClient, clock);
        this.journal = journal;
    }

    /**
     * Test/compatibility overload: an explicit drain grace with auditing disabled (the retained-row
     * cap keeps its default). The parameter list is unchanged from before the cap existed.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, DEFAULT_MAX_RETAINED_JOBS, clock, null);
    }

    /**
     * Test convenience: an explicit drain grace so a test can exercise the forced phase without
     * sleeping. The journal stays last, so {@code null} still means "auditing disabled".
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, DEFAULT_MAX_RETAINED_JOBS, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit retained-row cap so a test can force count pressure with a
     * handful of jobs. The journal stays last, so {@code null} still means "auditing disabled".
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            int maxRetainedJobs, Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, maxRetainedJobs, clock);
        this.journal = journal;
    }

    /**
     * Optional journal injection: auditing stays absent in test slices and in direct construction,
     * in which case attempts run unjournalled and {@link JobAttempt#auditEntryId()} stays null.
     *
     * @param journal the audit journal, or null to disable per-attempt journaling
     */
    @Autowired(required = false)
    public void setAuditJournal(AuditJournalService journal) {
        this.journal = journal;
    }

    /**
     * Registers the job, enqueues it, and returns its snapshot. Registration, {@code execute} and
     * the rejection rollback share one critical section, so "remove the job and its idempotency
     * mapping atomically" is literally true: no concurrent submitter can ever observe a job id
     * whose job was rolled back.
     */
    public BatchHashJobResponse submit(BatchHashRequest request) {
        return submit(request, ClientKeyResolver.UNKNOWN_CLIENT);
    }

    /**
     * Registers the job for {@code clientKey}, enqueues it, and returns its snapshot.
     *
     * <p>Registration and admission are one critical section: the per-client quota is reserved
     * (incremented) in the same {@code registryLock} section that publishes the job row and its
     * idempotency mapping, so no concurrent submitter can observe a job that holds no slot, and a
     * same-key/same-payload duplicate returns the existing snapshot without consuming a second slot.
     * {@link JobQuotaExceededException} is thrown before any registration or reservation, so a
     * refused submission leaves no job row, no idempotency mapping and no counter change.</p>
     *
     * @param request   the batch to hash
     * @param clientKey the identity to charge; {@code null}/blank is charged to
     *                  {@link ClientKeyResolver#UNKNOWN_CLIENT}
     * @throws IdempotencyConflictException when the key is reused with a different payload
     * @throws JobQuotaExceededException    when this client already holds the configured maximum
     *                                      non-terminal jobs
     * @throws JobQueueFullException        when the bounded executor rejects the submission
     */
    public BatchHashJobResponse submit(BatchHashRequest request, String clientKey) {
        String quotaKey = normalizeClientKey(clientKey);
        // Captured on the servlet thread: this is the id the accepted request will be audited under,
        // and it must survive the handoff to the pool.
        String requestId = RequestContext.get();
        String key = (request.idempotencyKey() == null || request.idempotencyKey().isBlank())
                ? null
                : request.idempotencyKey();
        String fingerprint = fingerprint(request.algorithm(), request.items(), request.secretKey());

        synchronized (registryLock) {
            if (key != null) {
                String existingJobId = idempotencyIndex.get(key);
                if (existingJobId != null) {
                    MutableJob existing = jobs.get(existingJobId);
                    if (existing != null) {
                        if (!existing.fingerprint.equals(fingerprint)) {
                            // An idempotency key identifies exactly one canonical payload: the
                            // conflicting submission is refused before any registration or enqueue,
                            // so it leaves no job row and no idempotency mapping behind.
                            throw new IdempotencyConflictException(key, existing.jobId);
                        }
                        return snapshot(existing); // duplicate submission: same jobId, no second execution
                    }
                    // The mapping is dangling: every removal of a job is paired with a conditional
                    // removal of its mapping, but a mapping can still outlive its row (a rollout, a
                    // legacy row). Drop it so this key starts exactly one new job instead of
                    // answering a 404-id snapshot forever.
                    idempotencyIndex.remove(key, existingJobId);
                }
            }

            // Count enforcement runs here, still holding registryLock and before the new row is
            // published (Plan §4.4 order: duplicate resolution, cap, quota, registration): the
            // registry is trimmed to the cap on every submission, including one that is about to be
            // refused, and the QUEUED row created below can never be selected as its own victim.
            enforceRetainedJobCapLocked();

            // Read-only: a refusal below leaves the job and idempotency registries untouched.
            int active = activeJobsByClient.getOrDefault(quotaKey, 0);
            if (active >= maxActiveJobsPerClient) {
                throw new JobQuotaExceededException(maxActiveJobsPerClient, active);
            }

            MutableJob job = new MutableJob(UUID.randomUUID().toString(), key,
                    request.algorithm(), request.items(), request.secretKey(), fingerprint, Instant.now(clock),
                    quotaKey, maxAttempts);
            // Reserve before publishing: the increment and the registrations below share this
            // registryLock section, so the quota can neither be bypassed nor double-counted.
            activeJobsByClient.merge(quotaKey, 1, Integer::sum);
            job.quotaReserved.set(true);
            if (key != null) {
                idempotencyIndex.put(key, job.jobId);
            }
            jobs.put(job.jobId, job);

            // Snapshot taken at registration, before enqueue, so the 202 body of a *first*
            // submission always reports the accepted state (QUEUED) even if a worker thread starts
            // and finishes immediately. A duplicate submission (fast path above) instead returns the
            // current live snapshot of the already-registered job, which may already be COMPLETED.
            BatchHashJobResponse accepted = snapshot(job);

            // Correlation is recorded before the handoff, so the worker can install the *submitter's*
            // id rather than whatever the reused pool thread happened to carry.
            job.attemptRequestIds.put(1, requestId);

            try {
                // The first attempt's generation is explicit rather than implicit.
                executor.execute(() -> executeAttempt(job.jobId, 1));
            } catch (RejectedExecutionException e) {
                // Rollback must undo BOTH registrations: the idempotency index stores job ids
                // (String), so the conditional remove must be given the id, not the job object.
                jobs.remove(job.jobId, job);
                if (key != null) {
                    idempotencyIndex.remove(key, job.jobId);
                }
                // A rejected submission never started, so it must leave no correlation behind either.
                job.attemptRequestIds.remove(1);
                // REQ-07: undo the reservation in the same section, so a rejected submission can
                // never leave a client looking busier than it is.
                job.quotaReserved.set(false);
                decrementQuotaLocked(quotaKey);
                throw new JobQueueFullException("Hash job queue is full (worker-count=" + workerCount
                        + ", queue-capacity=" + queueCapacity + "); retry after capacity frees");
            }

            return accepted;
        }
    }

    /**
     * The client's current non-terminal (QUEUED or RUNNING) job count. Package-private test
     * observability only: it is not exposed by any controller and has no production caller.
     */
    int activeJobsForClient(String clientKey) {
        String quotaKey = normalizeClientKey(clientKey);
        synchronized (registryLock) {
            return activeJobsByClient.getOrDefault(quotaKey, 0);
        }
    }

    private static String normalizeClientKey(String clientKey) {
        return (clientKey == null || clientKey.isBlank()) ? ClientKeyResolver.UNKNOWN_CLIENT : clientKey;
    }

    /** Decrements (and removes at zero) a client's counter entry. Caller must hold {@code registryLock}. */
    private void decrementQuotaLocked(String clientKey) {
        Integer current = activeJobsByClient.get(clientKey);
        if (current == null || current <= 0) {
            return; // defensively a no-op: a missing entry must never underflow into over-admission
        }
        if (current == 1) {
            activeJobsByClient.remove(clientKey); // zero entries are never retained (REQ-01)
            return;
        }
        activeJobsByClient.put(clientKey, current - 1);
    }

    /**
     * Releases one reserved slot. Always called <em>after</em> the job-monitor block that claimed the
     * reservation, so {@code registryLock} is never acquired while a job monitor is held
     * (registryLock -&gt; job monitor is the only order this class allows).
     */
    private void releaseQuota(String clientKey) {
        synchronized (registryLock) {
            decrementQuotaLocked(clientKey);
        }
    }

    /** Current snapshot of a job; throws {@link JobNotFoundException} for unknown or evicted ids. */
    public BatchHashJobResponse getJob(String jobId) {
        return snapshot(requireJob(jobId));
    }

    /**
     * Lists a bounded, newest-first window of the jobs currently in the registry.
     *
     * <p>The registry is iterated without {@code registryLock} (a weakly consistent view), so a job
     * submitted or evicted while the list is built is simply present or absent. Listing is strictly
     * read-only: no job field is written, the idempotency index is untouched and no clock is
     * advanced. The status filter is evaluated under the job monitor, so a half-written transition
     * is never observed. Every element is built by {@link #snapshot(MutableJob)}, i.e. exactly the
     * {@code GET /{jobId}} projection, so no {@code secretKey} or raw item payload can leak.</p>
     *
     * @param status optional status filter; {@code null} lists every status
     * @param limit  requested window size, clamped to {@code [1, 100]} (never rejected)
     * @return matching snapshots, newest first ({@code createdAt} descending, {@code jobId} ascending);
     *         an empty list when the registry is empty or nothing matches
     */
    public List<BatchHashJobResponse> listJobs(JobStatus status, int limit) {
        int bounded = Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
        PriorityQueue<MutableJob> kept = new PriorityQueue<>(bounded, LIST_ORDER_INVERSE);
        for (MutableJob job : jobs.values()) { // weakly consistent view, no registryLock
            if (status != null) {
                synchronized (job) {
                    if (job.status != status) {
                        continue; // filtered under the job monitor
                    }
                }
            }
            kept.offer(job);
            if (kept.size() > bounded) {
                kept.poll(); // drops the entry that ranks last under LIST_ORDER
            }
        }
        List<MutableJob> selected = new ArrayList<>(kept);
        selected.sort(LIST_ORDER);
        List<BatchHashJobResponse> listed = new ArrayList<>(selected.size());
        for (MutableJob job : selected) {
            listed.add(snapshot(job)); // the single projection, point-in-time per element
        }
        return List.copyOf(listed);
    }

    /**
     * Requeues a FAILED job for one more execution attempt.
     *
     * <p>Validation and the {@code FAILED -> QUEUED} transition happen in a single monitor section,
     * so a worker can never complete the job between the status check and the requeue. The attempt
     * counter is incremented in that same section and doubles as the new generation: a worker from
     * the previous attempt fails its generation check and writes nothing. The jobId and the
     * idempotency mapping are untouched by a retry.</p>
     *
     * <p>If the executor rejects the requeued attempt, the previous FAILED snapshot is restored -
     * including the attempt counter - and the existing {@link JobQueueFullException} is thrown, so
     * the attempt is not consumed and the retry can be repeated once capacity frees.</p>
     *
     * @throws JobNotFoundException        when the id is unknown or was already evicted
     * @throws JobRetryConflictException   when the job is not FAILED, or its attempt budget is spent
     * @throws JobQuotaExceededException   when the job's client already holds the configured maximum
     *                                     non-terminal jobs (the FAILED job is left untouched)
     * @throws JobQueueFullException       when the bounded executor rejects the requeued attempt
     */
    public BatchHashJobResponse retry(String jobId) {
        // The retry is its own audited request: the new attempt is correlated to *this* caller's id,
        // never to the id that created (or first retried) the job.
        String requestId = RequestContext.get();
        MutableJob job = requireJob(jobId); // unknown/evicted -> JobNotFoundException (404 JOB_NOT_FOUND)
        // A retry that cannot succeed must not touch the client's counter at all: the reservation
        // below is visible to concurrent submissions by the same client, so an ineligible retry
        // checked only *after* it would briefly expose a spurious 429 window. This monitor section
        // is the cheap fail-fast; the authoritative checks repeat after the reservation because the
        // job can change state in between.
        synchronized (job) {
            if (jobs.get(job.jobId) != job) {
                throw new JobNotFoundException(job.jobId);
            }
            if (job.status != JobStatus.FAILED || job.attempt >= job.maxAttempts) {
                throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts);
            }
        }
        // REQ-06: the reservation belongs to the job's client, not to the caller, so a retry cannot
        // escape to another bucket. It is taken under registryLock *before* the monitor below, so an
        // exhausted quota refuses the retry before a single field of the FAILED snapshot is touched.
        reserveQuota(job);
        boolean keepReservation = false;
        try {
            synchronized (job) {
                // Registry membership is re-checked under the monitor: an eviction pass that acquired
                // the monitor first has already unregistered this instance, and a 202 for a job the
                // caller can no longer poll (or that a same-key submit would duplicate) must not happen.
                if (jobs.get(job.jobId) != job) {
                    throw new JobNotFoundException(job.jobId);
                }
                if (job.status != JobStatus.FAILED || job.attempt >= job.maxAttempts) {
                    // Thrown before a single field is touched: the 409 leaves the snapshot identical.
                    throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts);
                }
                FailedState before = FailedState.of(job);
                int admittedAttempt = ++job.attempt;
                job.status = JobStatus.QUEUED;
                job.startedAt = null;
                job.completedAt = null;
                job.error = null;
                // A retried attempt continues from the durable checkpoint prefix: it resumes at the
                // next uncommitted index and the prefix is preserved here (a rejected retry therefore
                // has nothing to undo in it). Only the published results are cleared.
                job.results = null;
                job.lastHeartbeatAt = null; // the new attempt stamps its own lease at admission
                job.executionLeaseExpiresAt = null;
                job.attemptRequestIds.put(admittedAttempt, requestId);
                try {
                    executor.execute(() -> executeAttempt(job.jobId, admittedAttempt));
                } catch (RejectedExecutionException e) {
                    before.restore(job); // attempt decremented, status FAILED, error/completedAt back
                    job.attemptRequestIds.remove(admittedAttempt); // the attempt never started
                    throw new JobQueueFullException("Hash job queue is full (worker-count=" + workerCount
                            + ", queue-capacity=" + queueCapacity + "); retry after capacity frees");
                }
                keepReservation = true; // only an accepted requeue keeps the slot
                return snapshot(job); // QUEUED, attempt = 2, attempt-1 entry retained
            }
        } finally {
            if (!keepReservation) {
                // A refused (409/404/429) retry must restore the client's count: the claim is
                // dropped and the decrement runs here, outside every job monitor (REQ-08).
                job.quotaReserved.set(false);
                releaseQuota(job.clientKey);
            }
        }
    }

    /**
     * Reserves one slot for {@code job}'s client. Called only under {@code registryLock}; throws
     * {@link JobQuotaExceededException} (touching nothing) when the client is at its ceiling.
     */
    private void reserveQuota(MutableJob job) {
        synchronized (registryLock) {
            if (jobs.get(job.jobId) != job) {
                throw new JobNotFoundException(job.jobId);
            }
            // I-02: a job holds at most one live reservation. A concurrent retry that already
            // reserved this job is about to requeue it, so this caller is answered with the same
            // conflict it would observe after that requeue instead of stealing the live claim.
            if (!job.quotaReserved.compareAndSet(false, true)) {
                JobStatus observed;
                int observedAttempt;
                synchronized (job) { // registryLock -> job monitor is the only legal order
                    observed = job.status;
                    observedAttempt = job.attempt;
                }
                throw new JobRetryConflictException(job.jobId, observed, observedAttempt, job.maxAttempts);
            }
            int active = activeJobsByClient.getOrDefault(job.clientKey, 0);
            if (active >= maxActiveJobsPerClient) {
                job.quotaReserved.set(false); // nothing was reserved, so give the claim back
                throw new JobQuotaExceededException(maxActiveJobsPerClient, active);
            }
            activeJobsByClient.merge(job.clientKey, 1, Integer::sum);
        }
    }

    /**
     * Requests cancellation of a job: {@code QUEUED -> CANCELLED} withdraws it outright, while
     * {@code RUNNING -> CANCELLING} only records the request and lets the worker honour it at its next
     * checkpoint.
     *
     * <p>Both transitions happen under the job's own monitor, so they cannot interleave with the
     * worker's admission check: either the worker has not started (and will see CANCELLED and return
     * without hashing), or it is already RUNNING and turns CANCELLING — the writer that observes
     * CANCELLING is the one that terminalizes CANCELLED exactly once ({@code INV-CAN-2}). A
     * cancellation request deliberately releases nothing: the per-client quota slot and the execution
     * lease stay with the in-flight worker, because the job is still non-terminal and still hashing.</p>
     *
     * <p>Calling this again on a CANCELLING or CANCELLED job returns the current snapshot and writes
     * nothing ({@code INV-CAN-3}); COMPLETED and FAILED remain a conflict and leave the snapshot
     * untouched.</p>
     *
     * @throws JobNotFoundException              when the id is unknown or was already evicted
     * @throws JobCancellationConflictException  when the job is COMPLETED or FAILED
     */
    public BatchHashJobResponse cancel(String jobId) {
        MutableJob job = requireJob(jobId);
        boolean released = false;
        BatchHashJobResponse cancelled;
        synchronized (job) {
            if (job.status == JobStatus.CANCELLING || job.status == JobStatus.CANCELLED) {
                return snapshot(job); // idempotent: nothing is written
            }
            if (job.status == JobStatus.RUNNING) {
                // Records the request only: the worker (or the reaper / drain, if it stalls) still has
                // to write the terminal CANCELLED, so the reservation and the lease stay untouched.
                job.status = JobStatus.CANCELLING;
                return snapshot(job);
            }
            if (job.status != JobStatus.QUEUED) {
                throw new JobCancellationConflictException(job.jobId, job.status);
            }
            job.status = JobStatus.CANCELLED;
            job.completedAt = Instant.now(clock);
            job.results = null; // explicit: nothing was hashed
            job.checkpoint.clear(); // a withdrawn job retains no partial digests
            job.error = null;
            job.lastHeartbeatAt = null;
            job.executionLeaseExpiresAt = null;
            // Same monitor section as the terminal write: exactly one caller can claim the
            // reservation, so a repeated cancel (or a later eviction) cannot release it again.
            released = job.quotaReserved.compareAndSet(true, false);
            cancelled = snapshot(job);
        }
        if (released) {
            releaseQuota(job.clientKey);
            // Bounded retention on the terminal write itself: a fast submit/complete churn would
            // otherwise let terminal rows pile up between two scheduled cleanup passes.
            enforceRetainedJobCap();
        }
        return cancelled;
    }

    /**
     * Writes the single {@code CANCELLING -> CANCELLED} transition. The caller must already hold the
     * job monitor and must have re-checked that this generation still owns a CANCELLING job, so the
     * terminal write, the attempt upsert, the audit close and the reservation claim can never be split
     * by a concurrent writer ({@code INV-CAN-2}).
     *
     * <p>Clearing the checkpoint on the way out is what makes cancellation a withdrawal rather than a
     * failure: a retry resumes an empty prefix, exactly as {@code QUEUED -> CANCELLED} does, and no
     * partial digest survives in memory.</p>
     *
     * @return {@code true} when this call claimed the quota reservation; the caller releases the slot
     *         after leaving the monitor section
     */
    private boolean finalizeCancelledLocked(MutableJob job, int generation) {
        job.status = JobStatus.CANCELLED;
        job.completedAt = Instant.now(clock);
        job.results = null; // explicit: a cancelled batch never publishes results
        job.checkpoint.clear(); // a cancelled job retains no partial digests
        job.error = null;
        job.lastHeartbeatAt = null;
        job.executionLeaseExpiresAt = null;
        recordAttempt(job, generation, JobStatus.CANCELLED, job.startedAt, job.completedAt, null);
        // Closes the entry the attempt opened and drops its id, so the worker's own outcome and every
        // recovery path find nothing left to close and the row is journalled exactly once.
        succeedQuietly(job, generation, "CANCELLED");
        return job.quotaReserved.compareAndSet(true, false);
    }

    /**
     * Terminalizes a CANCELLING job outside the monitor, releasing the quota slot after the section.
     *
     * @return {@code true} when this call wrote the terminal status, {@code false} when another writer
     *         had already terminalized the attempt
     */
    private boolean finalizeCancelled(MutableJob job, int generation) {
        boolean released;
        synchronized (job) {
            if (job.status != JobStatus.CANCELLING) {
                return false; // a concurrent reaper or drain already settled this attempt
            }
            released = finalizeCancelledLocked(job, generation);
        }
        if (released) {
            releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor, never reverse
        }
        return true;
    }

    /**
     * Renews the execution lease of the attempt that is currently RUNNING.
     *
     * <p>A worker calls this to say "still working": the check and both lease writes happen inside a
     * single monitor section, so the only attempt a heartbeat can extend is the one that owns the
     * job at that instant ({@code INV-HB-1}). The new expiry is derived from
     * {@link Instant#now(Clock)}, never from the stored expiry, and an expired lease is refused
     * instead of revived ({@code INV-HB-2}), so a heartbeat arriving after
     * {@link #reapExpiredExecutions(Instant)} claimed the attempt cannot resurrect it. Nothing but
     * {@code lastHeartbeatAt} and {@code executionLeaseExpiresAt} is written ({@code INV-HB-3}).</p>
     *
     * <p>The {@code HEARTBEAT} journal row is opened and closed within this call and its id is never
     * stored in {@code attemptAuditEntryIds}, so the attempt's own entry keeps closing with the
     * attempt's outcome ({@code INV-HB-4}).</p>
     *
     * @param jobId the job whose lease should be renewed
     * @return the post-renewal snapshot
     * @throws JobNotFoundException      when the id is unknown or was already evicted
     * @throws JobRetryConflictException when the job exists but is not RUNNING
     * @throws IllegalArgumentException  when the job is RUNNING but holds no live lease
     */
    public BatchHashJobResponse heartbeat(String jobId) {
        MutableJob job = requireJob(jobId); // order 1: unknown/evicted -> 404 JOB_NOT_FOUND
        synchronized (job) {
            if (job.status != JobStatus.RUNNING) { // order 2: QUEUED/COMPLETED/FAILED/CANCELLED -> 409
                throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts,
                        "renew its execution lease");
            }
            Instant now = Instant.now(clock);
            Instant current = job.executionLeaseExpiresAt;
            if (current == null || !current.isAfter(now)) { // order 3: dead lease -> 400
                throw new IllegalArgumentException("Execution lease for job " + jobId
                        + " has expired; the attempt is reaped and can be retried from attempt "
                        + (job.attempt + 1));
            }
            job.lastHeartbeatAt = now;
            job.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
            recordHeartbeatQuietly(job, job.attempt, job.executionLeaseExpiresAt);
        }
        return snapshot(job);
    }

    @Scheduled(fixedDelayString = "${utiltools.jobs.cleanup-interval-ms:60000}")
    public void evictExpiredJobs() {
        evictExpiredJobs(Instant.now(clock).minusSeconds(retentionSeconds));
    }

    /**
     * Removes terminal jobs (COMPLETED, FAILED, CANCELLED) whose completion instant is older than
     * {@code cutoff}, together with their idempotency entries. QUEUED and RUNNING jobs are never
     * evicted. Public and clock-free so tests can advance the cutoff instead of waiting.
     *
     * <p>The whole pass holds {@code registryLock}, so a removal and the idempotency-index update
     * that mirrors it are one atomic step for {@link #submit}. The scheduled pass therefore enforces
     * time-based retention and then count-based retention ({@link #enforceRetainedJobCapLocked()}) in
     * the same section.</p>
     *
     * @return the number of evicted jobs
     */
    public int evictExpiredJobs(Instant cutoff) {
        int evicted = 0;
        synchronized (registryLock) {
            for (MutableJob job : jobs.values()) {
                synchronized (job) {
                    // Terminality and removal share one monitor section with retry's FAILED -> QUEUED
                    // transition, so a retry can neither slip between the check and the remove nor
                    // target a job this pass has unregistered: either the retry wins (the job is
                    // QUEUED here and is not evicted) or this pass wins (retry re-checks the registry
                    // under the same monitor and answers 404).
                    boolean evictable = isTerminal(job.status)
                            && job.completedAt != null
                            && job.completedAt.isBefore(cutoff);
                    if (evictable && jobs.remove(job.jobId, job)) {
                        evicted++;
                        if (job.idempotencyKey != null) {
                            idempotencyIndex.remove(job.idempotencyKey, job.jobId);
                        }
                        // No quota release here on purpose: every terminal status above was written
                        // together with the reservation claim, so a terminal job holds no slot.
                        // Releasing on eviction would under-count and over-admit this client later.
                    }
                }
            }
            // Count-based retention rides the same pass, under the same lock: an idle registry still
            // converges on the cap even if no terminal transition happens to run enforcement.
            enforceRetainedJobCapLocked();
        }
        return evicted;
    }

    /**
     * Enforces the retained-row cap for a caller that holds no monitor yet: a terminal transition
     * calls this after it has left every job monitor, so the {@code registryLock} it takes here can
     * never be acquired in the reverse order.
     */
    private void enforceRetainedJobCap() {
        synchronized (registryLock) {
            enforceRetainedJobCapLocked();
        }
    }

    /** Enforces the retained-row cap; the caller must already hold {@code registryLock}. */
    private void enforceRetainedJobCapLocked() {
        List<MutableJob> terminal = new ArrayList<>();
        for (MutableJob job : jobs.values()) {
            if (isTerminal(job.status)) {
                terminal.add(job);
            }
        }
        int excess = terminal.size() - maxRetainedJobs;
        if (excess <= 0) {
            return; // the common case: a snapshot of the registry only, never a write
        }
        terminal.sort(EVICTION_ORDER); // oldest completedAt first, jobId ascending on ties
        int evicted = 0;
        for (int i = 0; i < excess; i++) {
            MutableJob victim = terminal.get(i);
            synchronized (victim) {
                // Re-checked under the monitor: a victim is only removed while it is still terminal
                // and still the row this pass selected, so a concurrent retry cannot be unmapped.
                if (!isTerminal(victim.status) || !jobs.remove(victim.jobId, victim)) {
                    continue;
                }
                if (victim.idempotencyKey != null) {
                    // Conditional on purpose: a same-key resubmission may already have repointed the
                    // index at a newer job, and eviction must never drop that newer mapping.
                    idempotencyIndex.remove(victim.idempotencyKey, victim.jobId);
                }
                // No quota release, no surviving-job mutation: a terminal row holds no slot and the
                // snapshots, attempt histories, checkpoints and audit entries of other rows stay put.
            }
            evicted++;
        }
        if (evicted > 0) {
            log.debug("batch-hash retention: evicted={} terminal jobs beyond the cap of {}", evicted,
                    maxRetainedJobs);
        }
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    @Scheduled(fixedDelayString = "${utiltools.jobs.execution-reaper-interval-ms:5000}")
    public void reapExpiredExecutions() {
        reapExpiredExecutions(Instant.now(clock));
    }

    /**
     * Fails every RUNNING attempt whose execution lease expired at or before {@code now}, exactly
     * once per attempt. QUEUED jobs hold no lease, and terminal jobs (including an already reaped
     * FAILED job) are skipped, so a second pass over the same {@code now} reaps nothing and writes
     * nothing. The reaped attempt keeps its attempt number, so the worker that stalled inside
     * {@code hash} fails its status fence when it finally returns and cannot overwrite this
     * snapshot; {@link #retry(String)} then starts {@code attempt + 1}.
     *
     * <p>The pass is clock-free, holds one job monitor at a time and never removes a job, so monitor
     * hold time stays a function of the scalar writes below and the pass cannot deadlock with the
     * worker or with {@link #evictExpiredJobs(Instant)}.</p>
     *
     * @param now the instant lease expiry is compared against
     * @return the number of attempts reaped by this pass
     */
    public int reapExpiredExecutions(Instant now) {
        int reaped = 0;
        for (MutableJob job : jobs.values()) {
            boolean released = false;
            synchronized (job) {
                if (job.executionLeaseExpiresAt == null || job.executionLeaseExpiresAt.isAfter(now)) {
                    continue; // no lease at all, or the lease is still live
                }
                if (job.status == JobStatus.CANCELLING) {
                    // A pending cancellation whose worker stalled: honour the request rather than
                    // overwriting it with FAILED, so the caller-visible outcome stays CANCELLED.
                    released = finalizeCancelledLocked(job, job.attempt);
                    reaped++;
                } else if (job.status == JobStatus.RUNNING) {
                    job.status = JobStatus.FAILED;
                    job.error = EXECUTION_LEASE_EXPIRED;
                    job.completedAt = now;
                    job.results = null;
                    job.executionLeaseExpiresAt = null;
                    job.lastHeartbeatAt = null;
                    recordAttempt(job, job.attempt, JobStatus.FAILED, job.startedAt, now,
                            EXECUTION_LEASE_EXPIRED);
                    // Close the audit entry the stalled worker opened: the fence keeps that worker from
                    // writing its own (late) outcome, so this is the only terminal write for the attempt.
                    failQuietly(job, job.attempt, EXECUTION_LEASE_EXPIRED);
                    // Claimed in the same section as the terminal write; a late worker fenced by this
                    // reap never reaches its own claim, so the slot is released exactly once.
                    released = job.quotaReserved.compareAndSet(true, false);
                    reaped++;
                } else {
                    continue; // QUEUED holds no lease; terminal jobs are already settled
                }
            }
            if (released) {
                releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            }
        }
        return reaped;
    }

    /**
     * Terminalizes the service: in-flight work gets a bounded grace window, everything still
     * non-terminal afterwards is failed with {@link #JOB_SHUTDOWN}.
     *
     * <p>Two phases. Phase one calls {@link ThreadPoolExecutor#shutdown()}, so queued work still
     * runs while {@link #submit} and {@link #retry} are refused through their existing
     * {@link RejectedExecutionException} rollback, and waits up to the configured grace window for
     * the pool to terminate. Phase two interrupts whatever is still running and sweeps the registry.
     * The sweep fails only {@code QUEUED}/{@code RUNNING} jobs, keeps their attempt number and their
     * checkpoint, and closes each open audit entry exactly once, so a worker that ignores
     * interruption and returns later writes nothing: the existing status/attempt fence rejects it.
     * </p>
     *
     * <p>Idempotent by construction rather than by flag: a second call re-runs no-op
     * {@code shutdown()}/{@code shutdownNow()} and finds no non-terminal job, so it adds no attempt,
     * no audit row and no quota release.</p>
     */
    @PreDestroy
    public void shutdown() {
        synchronized (shutdownMonitor) {
            executor.shutdown(); // phase 1: queued work still runs, new work is refused
            boolean terminated = awaitTerminationBounded(shutdownGraceSeconds);
            List<Runnable> discarded = executor.shutdownNow(); // phase 2: interrupt what is still running
            int failed = failNonTerminalJobs(JOB_SHUTDOWN);
            log.info("batch-hash drain: terminated={}, forced={}, failed={}", terminated, discarded.size(),
                    failed);
        }
    }

    /**
     * Waits at most {@code graceSeconds} for the pool to terminate.
     *
     * @return {@code true} when every task finished, {@code false} on timeout or interruption
     */
    private boolean awaitTerminationBounded(long graceSeconds) {
        if (graceSeconds <= 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(graceSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Never swallow the caller's interrupt: the drain proceeds to the forced phase.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Fails every non-terminal job with {@code error}, mirroring {@link #reapExpiredExecutions(Instant)}
     * line for line: one job monitor at a time, terminal write + attempt upsert + audit close +
     * reservation claim in that section, quota release outside it.
     *
     * @return the number of jobs failed by this pass
     */
    private int failNonTerminalJobs(String error) {
        int failed = 0;
        for (MutableJob job : jobs.values()) {
            boolean released = false;
            synchronized (job) {
                if (job.status == JobStatus.CANCELLING) {
                    // The caller already asked for CANCELLED: a drain settles the request instead of
                    // turning it into a JOB_SHUTDOWN failure the caller never asked for.
                    released = finalizeCancelledLocked(job, job.attempt);
                    failed++;
                } else if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) {
                    Instant now = Instant.now(clock);
                    job.status = JobStatus.FAILED; // the fence's status half now fails for a late worker
                    job.error = error;
                    job.completedAt = now;
                    job.results = null; // unpublished results are never published by a drain
                    job.executionLeaseExpiresAt = null;
                    job.lastHeartbeatAt = null;
                    // The checkpoint prefix is deliberately kept: it is the evidence a retry resumes from.
                    recordAttempt(job, job.attempt, JobStatus.FAILED, job.startedAt, now, error);
                    // Closes the entry this attempt opened and drops its id, so a late worker's own
                    // close finds nothing and a second drain cannot close the row again.
                    failQuietly(job, job.attempt, error);
                    // Claimed in the same section as the terminal write: the slot is released exactly once.
                    released = job.quotaReserved.compareAndSet(true, false);
                    failed++;
                } else {
                    continue; // terminal jobs (including one that completed inside the grace) untouched
                }
            }
            if (released) {
                releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            }
        }
        return failed;
    }

    private MutableJob requireJob(String jobId) {
        MutableJob job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    private BatchHashJobResponse snapshot(MutableJob job) {
        synchronized (job) {
            // Lease state is a property of RUNNING alone: admission always stamps both instants, and
            // every non-RUNNING status projects null regardless of any leftover field.
            boolean running = job.status == JobStatus.RUNNING;
            // The checkpoint projection is derived, never stored: a second counter could drift across
            // the reap / retry / cancel edges.
            int completedItems = switch (job.status) {
                case COMPLETED -> job.items.size(); // the prefix was cleared when results were published
                case CANCELLING, CANCELLED -> 0; // never expose a cancelled job's prefix
                default -> job.checkpoint.size(); // QUEUED / RUNNING / FAILED
            };
            return new BatchHashJobResponse(job.jobId, job.idempotencyKey, job.status, job.items.size(),
                    job.results, job.error, job.createdAt, job.startedAt, job.completedAt,
                    job.attempt, job.maxAttempts, List.copyOf(job.attemptHistory),
                    running ? job.executionLeaseExpiresAt : null,
                    running ? job.lastHeartbeatAt : null,
                    completedItems);
        }
    }

    /**
     * Worker entry point. Package-private like {@link #evictExpiredJobs(Instant)} so tests can replay
     * a superseded generation. An evicted job resolves to {@code null} and is simply not executed,
     * rather than throwing inside the pool.
     */
    void executeAttempt(String jobId, int generation) {
        MutableJob job = jobs.get(jobId);
        if (job != null) {
            executeJob(job, generation);
        }
    }

    private void executeJob(MutableJob job, int generation) {
        try {
            synchronized (job) {
                if (job.status != JobStatus.QUEUED || job.attempt != generation) {
                    return; // fenced: a superseded generation (or a cancelled/retried job) writes nothing
                }
                Instant now = Instant.now(clock);
                job.status = JobStatus.RUNNING;
                job.startedAt = now;
                job.lastHeartbeatAt = now;
                job.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
                // The attempt continues at the checkpoint's next index; recorded before the audit
                // descriptor is built so both attempt history and journal agree on where it started.
                int resumedFrom = job.checkpoint.size();
                job.attemptResumedFrom.put(generation, resumedFrom);
                // Admission is the moment the attempt "starts": install the submitter's correlation
                // id on this (possibly reused) pool thread and open exactly one audit entry here.
                RequestContext.set(job.attemptRequestIds.get(generation));
                String entryId = beginQuietly(job, generation);
                if (entryId != null) {
                    job.attemptAuditEntryIds.put(generation, entryId);
                }
                recordAttempt(job, generation, JobStatus.RUNNING, job.startedAt, null, null);
            }
            try {
                int resumedFrom = job.attemptResumedFrom.getOrDefault(generation, 0);
                for (int i = resumedFrom; i < job.items.size(); i++) {
                    if (!renewLease(job, generation)) {
                        return; // fenced: the attempt was reaped, retried or cancelled while hashing
                    }
                    HashResponse hashed = cryptoService.hash(
                            new HashRequest(job.algorithm, job.items.get(i), job.secretKey));
                    if (!commitCheckpoint(job, generation, i, hashed)) {
                        return; // fenced mid-hash: nothing is written, not even the item just hashed
                    }
                }
                boolean released;
                synchronized (job) {
                    if (job.attempt != generation) {
                        // The generation half covers an attempt superseded by retry.
                        return; // fenced: the attempt was requeued while hashing
                    }
                    if (job.status == JobStatus.CANCELLING) {
                        // The caller cancelled while the last item was hashing: its CANCELLED wins over
                        // this COMPLETED, so the results just computed are discarded with the prefix.
                        released = finalizeCancelledLocked(job, generation);
                    } else {
                        if (job.status != JobStatus.RUNNING) {
                            // Load-bearing: a reaped attempt keeps its attempt number but is FAILED, so
                            // the status half of this fence is what stops a late COMPLETED write.
                            return; // fenced: the attempt was reaped while hashing
                        }
                        if (job.checkpoint.size() != job.items.size()) {
                            // Unreachable through input; surfaces as an ordinary FAILED job rather than
                            // publishing a short result list.
                            throw new IllegalStateException("checkpoint prefix does not cover the batch");
                        }
                        job.results = List.copyOf(job.checkpoint); // the full ordered prefix, input order
                        job.checkpoint.clear(); // results now own the values; retained state stays bounded
                        job.error = null;
                        job.status = JobStatus.COMPLETED;
                        job.completedAt = Instant.now(clock);
                        job.lastHeartbeatAt = null;
                        job.executionLeaseExpiresAt = null;
                        recordAttempt(job, generation, JobStatus.COMPLETED, job.startedAt, job.completedAt,
                                null);
                        // Same section that wrote the terminal status: the audit outcome can never
                        // disagree with the snapshot, and a journal fault cannot change it.
                        succeedQuietly(job, generation, "COMPLETED");
                        // Claimed in the same section as the terminal write: the slot covering this
                        // attempt is released exactly once, by the writer that terminated it.
                        released = job.quotaReserved.compareAndSet(true, false);
                    }
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                    enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor
                }
            } catch (Throwable t) {
                // Intentional: an Error must not wedge the pool, later jobs still run.
                boolean released = false;
                synchronized (job) {
                    if (job.attempt != generation) {
                        // A stale failure must not overwrite an attempt superseded by retry.
                        return; // fenced: the attempt was requeued while hashing
                    }
                    if (job.status == JobStatus.CANCELLING) {
                        // Cancellation beats a concurrent fault: the caller asked to stop, so the job
                        // ends CANCELLED with no error rather than FAILED.
                        released = finalizeCancelledLocked(job, generation);
                    } else if (job.status == JobStatus.RUNNING) {
                        job.error = describe(t);
                        job.status = JobStatus.FAILED;
                        job.completedAt = Instant.now(clock);
                        job.lastHeartbeatAt = null;
                        job.executionLeaseExpiresAt = null;
                        recordAttempt(job, generation, JobStatus.FAILED, job.startedAt, job.completedAt,
                                job.error);
                        failQuietly(job, generation, job.error);
                        released = job.quotaReserved.compareAndSet(true, false);
                    } else {
                        // Load-bearing, same reasoning as the COMPLETED fence above: a stale failure
                        // must not overwrite a reaped attempt.
                        return; // fenced: a superseded failure must not overwrite the reaped attempt
                    }
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                    enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor
                }
            }
        } finally {
            // Runs on the pool thread: the next job admitted on it must never inherit this id.
            RequestContext.clear();
        }
    }

    /**
     * Renews the execution lease of {@code generation} before the next item is hashed.
     *
     * <p>Under the job monitor it re-checks {@code job.attempt == generation && status == RUNNING},
     * so an attempt that was reaped, retried or cancelled while hashing is never re-armed: the lease
     * cannot be revived, and the worker stops hashing instead of burning CPU for a job it can no
     * longer complete.</p>
     *
     * <p>This is the cancellation checkpoint: a CANCELLING job is terminalized here, between two items,
     * so the batch stops within one item of the {@code DELETE} and the worker never resumes a lease for
     * a job the caller withdrew ({@code INV-CAN-2}).</p>
     *
     * @return {@code true} when the lease was renewed, {@code false} when the attempt was fenced
     */
    private boolean renewLease(MutableJob job, int generation) {
        boolean cancelPending = false;
        synchronized (job) {
            if (job.attempt != generation) {
                return false;
            }
            if (job.status == JobStatus.CANCELLING) {
                cancelPending = true; // terminalized below, outside this monitor section
            } else if (job.status != JobStatus.RUNNING) {
                return false;
            } else {
                job.lastHeartbeatAt = Instant.now(clock);
                job.executionLeaseExpiresAt = job.lastHeartbeatAt.plusSeconds(executionLeaseSeconds);
                return true;
            }
        }
        if (cancelPending) {
            finalizeCancelled(job, generation);
        }
        return false;
    }

    /**
     * Commits one hashed item to the job's checkpoint prefix.
     *
     * <p>The fence is deliberately re-checked <em>after</em> the hash: {@code CryptoService.hash}
     * can outlive the attempt (reaped, retried or superseded while the worker was inside it), and a
     * pre-hash-only fence would let a stale worker splice item {@code index} into a fresh
     * generation's prefix. The {@code checkpoint.size() == index} guard additionally guarantees an
     * attempt can only continue the prefix, never append out of order.</p>
     *
     * <p>A CANCELLING job is terminalized instead of appended to: the item just hashed is dropped with
     * the rest of the prefix, so a cancelled job never reports progress it did not publish
     * ({@code INV-CAN-2}).</p>
     *
     * @return {@code true} when the item was committed, {@code false} when this generation no longer
     *         owns the job and nothing at all was written
     */
    private boolean commitCheckpoint(MutableJob job, int generation, int index, HashResponse hashed) {
        boolean cancelPending = false;
        synchronized (job) {
            if (job.attempt != generation) {
                return false; // fenced, cancelled, reaped or superseded
            }
            if (job.status == JobStatus.CANCELLING) {
                cancelPending = true; // terminalized below, outside this monitor section
            } else if (job.status != JobStatus.RUNNING) {
                return false; // fenced, reaped or superseded
            } else if (job.checkpoint.size() != index) {
                return false; // drifted prefix: never append at the wrong index
            } else {
                job.checkpoint.add(hashed);
                return true;
            }
        }
        if (cancelPending) {
            finalizeCancelled(job, generation);
        }
        return false;
    }

    /**
     * Upserts the single history entry for {@code attempt}: it first appears as RUNNING and is then
     * replaced in place by its terminal status, so the history stays bounded by {@code maxAttempts}
     * and no entry is ever duplicated.
     */
    private void recordAttempt(MutableJob job, int attempt, JobStatus status, Instant startedAt,
            Instant completedAt, String error) {
        JobAttempt entry = new JobAttempt(attempt, status, startedAt, completedAt, error,
                job.attemptAuditEntryIds.get(attempt), job.attemptResumedFrom.getOrDefault(attempt, 0));
        for (int i = 0; i < job.attemptHistory.size(); i++) {
            if (job.attemptHistory.get(i).attempt() == attempt) {
                job.attemptHistory.set(i, entry);
                return;
            }
        }
        job.attemptHistory.add(entry);
    }

    /**
     * Opens the {@code BATCH_HASH} audit entry for {@code generation} at worker admission.
     *
     * <p>Best effort by contract: an audit failure must never change whether an attempt starts, so
     * every throwable is swallowed and a null id simply means "this attempt has no journal entry".
     * The descriptor is bounded and carries no payload: {@code items} and {@code secretKey} never
     * leave the job.</p>
     */
    private String beginQuietly(MutableJob job, int generation) {
        AuditJournalService audit = this.journal;
        if (audit == null) {
            return null;
        }
        try {
            return audit.begin(OperationType.BATCH_HASH, BatchHashAuditDescriptor.of(job, generation),
                    RequestContext.get()).id();
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit begin failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
            return null;
        }
    }

    /** Closes the attempt's entry as SUCCEEDED; a missing or already-terminal entry is ignored. */
    private void succeedQuietly(MutableJob job, int generation, String outcome) {
        AuditJournalService audit = this.journal;
        String entryId = job.attemptAuditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            audit.succeed(entryId, Map.of("outcome", outcome, "resultCount", job.items.size(),
                    "resumedFrom", job.attemptResumedFrom.getOrDefault(generation, 0),
                    "completedItems", completedItems(job)));
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit succeed failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
        }
    }

    /** Closes the attempt's entry as FAILED with a bounded code; a missing entry is ignored. */
    private void failQuietly(MutableJob job, int generation, String error) {
        AuditJournalService audit = this.journal;
        String entryId = job.attemptAuditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            audit.fail(entryId, boundedAuditText(error, BATCH_HASH_FAILED));
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit fail failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
        }
    }

    /**
     * Opens and immediately closes one {@code HEARTBEAT} row for the renewed attempt.
     *
     * <p>Best effort by contract, exactly like {@link #beginQuietly}: a journal fault must never
     * change the heartbeat outcome, so every throwable is swallowed and the lease extension stands
     * ({@code INV-HB-7}). The descriptor and the result payload carry no item contents, no secret key
     * and no caller-controlled text; {@code leaseExpiresAt} is journaled as an ISO-8601
     * {@code String} because the journal's {@code ObjectMapper} has no {@code JavaTimeModule}
     * ({@code INV-HB-5}, {@code INV-HB-6}).</p>
     */
    private void recordHeartbeatQuietly(MutableJob job, int generation, Instant expiresAt) {
        AuditJournalService audit = this.journal;
        if (audit == null) {
            return;
        }
        try {
            String id = audit.begin(OperationType.BATCH_HASH,
                    HeartbeatAuditDescriptor.of(job.jobId, generation, expiresAt),
                    RequestContext.get()).id();
            audit.succeed(id, Map.of(
                    "status", HEARTBEAT_STATUS,
                    "detail", HEARTBEAT_DETAIL,
                    "jobId", job.jobId,
                    "attempt", generation,
                    "leaseExpiresAt", expiresAt.toString()));
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH heartbeat audit failed for job {} attempt {}: {}",
                    job.jobId, generation, e.toString());
        }
    }

    /** Clamps client-controlled text so a pathological payload cannot bloat the bounded journal. */
    private static String boundedAuditText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= MAX_AUDIT_TEXT ? value : value.substring(0, MAX_AUDIT_TEXT);
    }

    /**
     * The audit descriptor of one attempt. Deliberately payload-free: the job id, attempt number,
     * algorithm, item count and idempotency key describe the work without storing item contents or
     * the secret key in the journal.
     */
    private record BatchHashAuditDescriptor(String jobId, int attempt, String algorithm, int itemCount,
            String idempotencyKey, int resumedFrom, int completedItems) {

        private static BatchHashAuditDescriptor of(MutableJob job, int generation) {
            return new BatchHashAuditDescriptor(job.jobId, generation,
                    boundedAuditText(job.algorithm, "UNKNOWN"), job.items.size(),
                    boundedAuditText(job.idempotencyKey, null),
                    job.attemptResumedFrom.getOrDefault(generation, 0), job.checkpoint.size());
        }
    }

    /**
     * The audit descriptor of one heartbeat. Deliberately payload-free: the job id, attempt number,
     * heartbeat marker and the new expiry describe the renewal without storing item contents or the
     * secret key in the journal.
     */
    private record HeartbeatAuditDescriptor(String jobId, int attempt, String kind, String leaseExpiresAt) {

        private static HeartbeatAuditDescriptor of(String jobId, int generation, Instant expiresAt) {
            return new HeartbeatAuditDescriptor(jobId, generation, HEARTBEAT_STATUS, expiresAt.toString());
        }
    }

    private String describe(Throwable t) {
        return t.getMessage() == null
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** The observable fields of a FAILED job, captured so a rejected requeue can restore them exactly. */
    private static final class FailedState {

        private final int attempt;
        private final Instant startedAt;
        private final Instant completedAt;
        private final String error;
        private final List<HashResponse> results;
        private final List<HashResponse> checkpoint;
        private final Instant lastHeartbeatAt;
        private final Instant executionLeaseExpiresAt;

        private FailedState(int attempt, Instant startedAt, Instant completedAt, String error,
                List<HashResponse> results, List<HashResponse> checkpoint, Instant lastHeartbeatAt,
                Instant executionLeaseExpiresAt) {
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.error = error;
            this.results = results;
            this.checkpoint = checkpoint;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.executionLeaseExpiresAt = executionLeaseExpiresAt;
        }

        private static FailedState of(MutableJob job) {
            return new FailedState(job.attempt, job.startedAt, job.completedAt, job.error, job.results,
                    List.copyOf(job.checkpoint), job.lastHeartbeatAt, job.executionLeaseExpiresAt);
        }

        private void restore(MutableJob job) {
            job.attempt = attempt;
            job.status = JobStatus.FAILED;
            job.startedAt = startedAt;
            job.completedAt = completedAt;
            job.error = error;
            job.results = results;
            job.checkpoint.clear();
            job.checkpoint.addAll(checkpoint);
            job.lastHeartbeatAt = lastHeartbeatAt;
            job.executionLeaseExpiresAt = executionLeaseExpiresAt;
        }
    }

    /**
     * Canonical fingerprint of a request payload: the normalised algorithm, the ordered item list
     * and the secret key. Only the SHA-256 digest leaves this method, so no pre-image (and no
     * secret) is ever stored on a job, logged, or returned.
     *
     * <p>Fields are encoded unambiguously — explicit item count plus byte-length-prefixed UTF-8
     * values — so {@code ["ab","c"]} and {@code ["a","bc"]} cannot collide, and input order is
     * significant. The normalisation mirrors {@code CryptoService.hash}: trim, upper-case, then the
     * {@code SHA256}/{@code SHA-256} style aliases, with {@code null} and blank secrets treated as
     * equivalent. An unsupported algorithm is <em>not</em> rejected here — it keeps its trimmed
     * upper-cased spelling so it still reaches the worker and becomes a {@code FAILED} job.</p>
     */
    private static String fingerprint(String algorithm, List<String> items, String secretKey) {
        String canonicalAlgorithm = normalizeAlgorithmForFingerprint(algorithm);
        boolean secretPresent = secretKey != null && !secretKey.isBlank();
        StringBuilder canonical = new StringBuilder(96 + items.size() * 24);
        appendField(canonical, canonicalAlgorithm);
        canonical.append(items.size()).append('|');
        for (String item : items) {
            appendField(canonical, item);
        }
        canonical.append(secretPresent ? '1' : '0');
        appendField(canonical, secretPresent ? secretKey : "");
        byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(sha256(canonicalBytes));
    }

    private static void appendField(StringBuilder out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append(bytes.length).append(':').append(value).append(';');
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec but unavailable", e);
        }
    }

    private static String normalizeAlgorithmForFingerprint(String algorithm) {
        String upper = algorithm.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SHA256", "SHA-256" -> "SHA-256";
            case "SHA512", "SHA-512" -> "SHA-512";
            case "SHA1", "SHA-1" -> "SHA-1";
            case "MD5" -> "MD5";
            default -> upper;
        };
    }

    private static final class MutableJob {

        private final String jobId;
        private final String idempotencyKey;
        private final String algorithm;
        private final List<String> items;
        private final String secretKey;
        private final String fingerprint;
        /** Client identity the job's reservation is charged to; never copied into a snapshot or journal. */
        private final String clientKey;
        private final Instant createdAt;
        private final int maxAttempts;
        /**
         * True while this job holds a slot in {@code activeJobsByClient}. Flipped to true when the
         * reservation is taken and claimed ({@code compareAndSet(true, false)}) in the same monitor
         * section that writes the terminal status, so a slot is released exactly once.
         */
        private final AtomicBoolean quotaReserved = new AtomicBoolean(false);
        private final List<JobAttempt> attemptHistory = new ArrayList<>();
        /**
         * Per-attempt correlation id captured on the submit/retry caller thread. Kept per attempt
         * (not per job) so a retry is correlated to the retry caller, and only for the attempts
         * this instance admitted.
         */
        private final Map<Integer, String> attemptRequestIds = new HashMap<>();
        /** Audit entry id of every attempt that actually started, keyed by attempt number. */
        private final Map<Integer, String> attemptAuditEntryIds = new HashMap<>();
        /** Durable ordered prefix of this job: entries 0..size-1 are hashed and committed. */
        private final List<HashResponse> checkpoint = new ArrayList<>();
        /** Where each admitted attempt started, keyed by attempt number. */
        private final Map<Integer, Integer> attemptResumedFrom = new HashMap<>();

        private int attempt = 1;
        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant completedAt;
        private Instant lastHeartbeatAt;
        private Instant executionLeaseExpiresAt;
        private List<HashResponse> results;
        private String error;

        private MutableJob(String jobId, String idempotencyKey, String algorithm, List<String> items,
                String secretKey, String fingerprint, Instant createdAt, String clientKey, int maxAttempts) {
            this.jobId = jobId;
            this.idempotencyKey = idempotencyKey;
            this.algorithm = algorithm;
            this.items = List.copyOf(items);
            this.secretKey = secretKey;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
            this.clientKey = clientKey;
            this.maxAttempts = maxAttempts;
        }
    }
}
