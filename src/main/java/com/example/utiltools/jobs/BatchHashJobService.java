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

    /** Per-client ceiling on non-terminal jobs when the property is not configured. */
    static final int DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT = 20;

    private static final long DEFAULT_EXECUTION_LEASE_SECONDS = 30;
    private static final long DEFAULT_EXECUTION_REAPER_INTERVAL_MS = 5000;
    private static final String BATCH_HASH_FAILED = "BATCH_HASH_FAILED";
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

    private final CryptoService cryptoService;
    private final ThreadPoolExecutor executor;
    private final int workerCount;
    private final int queueCapacity;
    private final long retentionSeconds;
    private final int maxAttempts;
    private final long executionLeaseSeconds;
    private final long executionReaperIntervalMs;
    private final int maxActiveJobsPerClient;
    private final Clock clock;

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
            case CANCELLED -> 0;
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

    @Autowired
    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.jobs.max-attempts:3}") int maxAttempts,
            @Value("${utiltools.jobs.execution-lease-seconds:30}") long executionLeaseSeconds,
            @Value("${utiltools.jobs.execution-reaper-interval-ms:5000}") long executionReaperIntervalMs,
            @Value("${utiltools.jobs.max-active-jobs-per-client:20}") int maxActiveJobsPerClient,
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
        // A non-positive quota would refuse every submission, so it is rejected loudly at startup.
        if (maxActiveJobsPerClient <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.max-active-jobs-per-client must be positive, was " + maxActiveJobsPerClient);
        }
        this.executionLeaseSeconds = executionLeaseSeconds;
        this.executionReaperIntervalMs = executionReaperIntervalMs;
        this.maxActiveJobsPerClient = maxActiveJobsPerClient;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
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
                }
            }

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
     * Withdraws a job that is still QUEUED. The QUEUED -> CANCELLED transition happens exactly once,
     * under the job's own monitor, so it cannot interleave with the worker's admission check: either
     * the worker has not started (and will see CANCELLED and return without hashing) or the job is
     * already RUNNING and this call reports a conflict. A repeated call on a CANCELLED job returns the
     * same snapshot, because CANCELLED is terminal and no field is written again.
     *
     * @throws JobNotFoundException              when the id is unknown or was already evicted
     * @throws JobCancellationConflictException  when the job is RUNNING, COMPLETED or FAILED
     */
    public BatchHashJobResponse cancel(String jobId) {
        MutableJob job = requireJob(jobId);
        boolean released = false;
        BatchHashJobResponse cancelled;
        synchronized (job) {
            if (job.status == JobStatus.CANCELLED) {
                return snapshot(job); // idempotent: nothing is written
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
        }
        return cancelled;
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
     * @return the number of evicted jobs
     */
    public int evictExpiredJobs(Instant cutoff) {
        int evicted = 0;
        for (MutableJob job : jobs.values()) {
            synchronized (job) {
                // Terminality and removal share one monitor section with retry's FAILED -> QUEUED
                // transition, so a retry can neither slip between the check and the remove nor
                // target a job this pass has unregistered: either the retry wins (the job is
                // QUEUED here and is not evicted) or this pass wins (retry re-checks the registry
                // under the same monitor and answers 404).
                boolean evictable = (job.status == JobStatus.COMPLETED
                        || job.status == JobStatus.FAILED
                        || job.status == JobStatus.CANCELLED)
                        && job.completedAt != null
                        && job.completedAt.isBefore(cutoff);
                if (evictable && jobs.remove(job.jobId, job)) {
                    evicted++;
                    if (job.idempotencyKey != null) {
                        idempotencyIndex.remove(job.idempotencyKey, job.jobId);
                    }
                    // No quota release here on purpose: every terminal status above was written
                    // together with the reservation claim, so a terminal job holds no slot. Releasing
                    // on eviction would under-count and over-admit this client later.
                }
            }
        }
        return evicted;
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
                if (job.status != JobStatus.RUNNING || job.executionLeaseExpiresAt == null) {
                    continue; // QUEUED, terminal, or (defensively) a RUNNING job with no lease
                }
                if (job.executionLeaseExpiresAt.isAfter(now)) {
                    continue; // lease still live
                }
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
            }
            if (released) {
                releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            }
        }
        return reaped;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
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
                case CANCELLED -> 0; // never expose a withdrawn job's prefix
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
                    if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                        // Load-bearing: a reaped attempt keeps its attempt number but is FAILED, so the
                        // status half of this fence is what stops a late COMPLETED write; the generation
                        // half covers an attempt superseded by retry.
                        return; // fenced: the attempt was requeued or reaped while hashing
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
                    recordAttempt(job, generation, JobStatus.COMPLETED, job.startedAt, job.completedAt, null);
                    // Same section that wrote the terminal status: the audit outcome can never
                    // disagree with the snapshot, and a journal fault cannot change it.
                    succeedQuietly(job, generation, "COMPLETED");
                    // Claimed in the same section as the terminal write: the slot covering this
                    // attempt is released exactly once, by the writer that terminated it.
                    released = job.quotaReserved.compareAndSet(true, false);
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                }
            } catch (Throwable t) {
                // Intentional: an Error must not wedge the pool, later jobs still run.
                boolean released = false;
                synchronized (job) {
                    if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                        // Load-bearing, same reasoning as the COMPLETED fence above: a stale failure must
                        // not overwrite a reaped or retried attempt.
                        return; // fenced: a superseded failure must not overwrite the retried attempt
                    }
                    job.error = describe(t);
                    job.status = JobStatus.FAILED;
                    job.completedAt = Instant.now(clock);
                    job.lastHeartbeatAt = null;
                    job.executionLeaseExpiresAt = null;
                    recordAttempt(job, generation, JobStatus.FAILED, job.startedAt, job.completedAt, job.error);
                    failQuietly(job, generation, job.error);
                    released = job.quotaReserved.compareAndSet(true, false);
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
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
     * @return {@code true} when the lease was renewed, {@code false} when the attempt was fenced
     */
    private boolean renewLease(MutableJob job, int generation) {
        synchronized (job) {
            if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                return false;
            }
            job.lastHeartbeatAt = Instant.now(clock);
            job.executionLeaseExpiresAt = job.lastHeartbeatAt.plusSeconds(executionLeaseSeconds);
            return true;
        }
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
     * @return {@code true} when the item was committed, {@code false} when this generation no longer
     *         owns the job and nothing at all was written
     */
    private static boolean commitCheckpoint(MutableJob job, int generation, int index, HashResponse hashed) {
        synchronized (job) {
            if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                return false; // fenced, cancelled, reaped or superseded
            }
            if (job.checkpoint.size() != index) {
                return false; // drifted prefix: never append at the wrong index
            }
            job.checkpoint.add(hashed);
            return true;
        }
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
