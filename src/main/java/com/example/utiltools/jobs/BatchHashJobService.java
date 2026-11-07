package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import jakarta.annotation.PreDestroy;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 */
@Service
public class BatchHashJobService {

    /** Error recorded on a RUNNING attempt whose execution lease lapsed without a renewal. */
    public static final String EXECUTION_LEASE_EXPIRED = "EXECUTION_LEASE_EXPIRED";

    private static final long DEFAULT_EXECUTION_LEASE_SECONDS = 30;
    private static final long DEFAULT_EXECUTION_REAPER_INTERVAL_MS = 5000;

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
    private final Clock clock;

    private final ConcurrentHashMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    /**
     * Compatibility overload for callers (and tests) that build the service directly: the system
     * clock and the default lease and reaper cadence are used.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                DEFAULT_EXECUTION_LEASE_SECONDS, DEFAULT_EXECUTION_REAPER_INTERVAL_MS, Clock.systemUTC());
    }

    @Autowired
    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.jobs.max-attempts:3}") int maxAttempts,
            @Value("${utiltools.jobs.execution-lease-seconds:30}") long executionLeaseSeconds,
            @Value("${utiltools.jobs.execution-reaper-interval-ms:5000}") long executionReaperIntervalMs,
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
        this.executionLeaseSeconds = executionLeaseSeconds;
        this.executionReaperIntervalMs = executionReaperIntervalMs;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Registers the job, enqueues it, and returns its snapshot. Registration, {@code execute} and
     * the rejection rollback share one critical section, so "remove the job and its idempotency
     * mapping atomically" is literally true: no concurrent submitter can ever observe a job id
     * whose job was rolled back.
     */
    public BatchHashJobResponse submit(BatchHashRequest request) {
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

            MutableJob job = new MutableJob(UUID.randomUUID().toString(), key,
                    request.algorithm(), request.items(), request.secretKey(), fingerprint, Instant.now(clock),
                    maxAttempts);
            if (key != null) {
                idempotencyIndex.put(key, job.jobId);
            }
            jobs.put(job.jobId, job);

            // Snapshot taken at registration, before enqueue, so the 202 body of a *first*
            // submission always reports the accepted state (QUEUED) even if a worker thread starts
            // and finishes immediately. A duplicate submission (fast path above) instead returns the
            // current live snapshot of the already-registered job, which may already be COMPLETED.
            BatchHashJobResponse accepted = snapshot(job);

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
                throw new JobQueueFullException("Hash job queue is full (worker-count=" + workerCount
                        + ", queue-capacity=" + queueCapacity + "); retry after capacity frees");
            }

            return accepted;
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
     * @throws JobQueueFullException       when the bounded executor rejects the requeued attempt
     */
    public BatchHashJobResponse retry(String jobId) {
        MutableJob job = requireJob(jobId); // unknown/evicted -> JobNotFoundException (404 JOB_NOT_FOUND)
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
            job.results = null; // a retried attempt starts from nothing
            job.lastHeartbeatAt = null; // the new attempt stamps its own lease at admission
            job.executionLeaseExpiresAt = null;
            try {
                executor.execute(() -> executeAttempt(job.jobId, admittedAttempt));
            } catch (RejectedExecutionException e) {
                before.restore(job); // attempt decremented, status FAILED, error/completedAt back
                throw new JobQueueFullException("Hash job queue is full (worker-count=" + workerCount
                        + ", queue-capacity=" + queueCapacity + "); retry after capacity frees");
            }
            return snapshot(job); // QUEUED, attempt = 2, attempt-1 entry retained
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
            job.error = null;
            job.lastHeartbeatAt = null;
            job.executionLeaseExpiresAt = null;
            return snapshot(job);
        }
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
                reaped++;
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
            return new BatchHashJobResponse(job.jobId, job.idempotencyKey, job.status, job.items.size(),
                    job.results, job.error, job.createdAt, job.startedAt, job.completedAt,
                    job.attempt, job.maxAttempts, List.copyOf(job.attemptHistory),
                    running ? job.executionLeaseExpiresAt : null,
                    running ? job.lastHeartbeatAt : null);
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
        synchronized (job) {
            if (job.status != JobStatus.QUEUED || job.attempt != generation) {
                return; // fenced: a superseded generation (or a cancelled/retried job) writes nothing
            }
            Instant now = Instant.now(clock);
            job.status = JobStatus.RUNNING;
            job.startedAt = now;
            job.lastHeartbeatAt = now;
            job.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
            recordAttempt(job, generation, JobStatus.RUNNING, job.startedAt, null, null);
        }
        try {
            List<HashResponse> results = new ArrayList<>(job.items.size());
            for (String item : job.items) {
                if (!renewLease(job, generation)) {
                    return; // fenced: the attempt was reaped, retried or cancelled while hashing
                }
                results.add(cryptoService.hash(new HashRequest(job.algorithm, item, job.secretKey)));
            }
            synchronized (job) {
                if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                    // Load-bearing: a reaped attempt keeps its attempt number but is FAILED, so the
                    // status half of this fence is what stops a late COMPLETED write; the generation
                    // half covers an attempt superseded by retry.
                    return; // fenced: the attempt was requeued or reaped while hashing
                }
                job.results = List.copyOf(results);
                job.error = null;
                job.status = JobStatus.COMPLETED;
                job.completedAt = Instant.now(clock);
                job.lastHeartbeatAt = null;
                job.executionLeaseExpiresAt = null;
                recordAttempt(job, generation, JobStatus.COMPLETED, job.startedAt, job.completedAt, null);
            }
        } catch (Throwable t) {
            // Intentional: an Error must not wedge the pool, later jobs still run.
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
            }
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
     * Upserts the single history entry for {@code attempt}: it first appears as RUNNING and is then
     * replaced in place by its terminal status, so the history stays bounded by {@code maxAttempts}
     * and no entry is ever duplicated.
     */
    private void recordAttempt(MutableJob job, int attempt, JobStatus status, Instant startedAt,
            Instant completedAt, String error) {
        JobAttempt entry = new JobAttempt(attempt, status, startedAt, completedAt, error);
        for (int i = 0; i < job.attemptHistory.size(); i++) {
            if (job.attemptHistory.get(i).attempt() == attempt) {
                job.attemptHistory.set(i, entry);
                return;
            }
        }
        job.attemptHistory.add(entry);
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
        private final Instant lastHeartbeatAt;
        private final Instant executionLeaseExpiresAt;

        private FailedState(int attempt, Instant startedAt, Instant completedAt, String error,
                List<HashResponse> results, Instant lastHeartbeatAt, Instant executionLeaseExpiresAt) {
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.error = error;
            this.results = results;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.executionLeaseExpiresAt = executionLeaseExpiresAt;
        }

        private static FailedState of(MutableJob job) {
            return new FailedState(job.attempt, job.startedAt, job.completedAt, job.error, job.results,
                    job.lastHeartbeatAt, job.executionLeaseExpiresAt);
        }

        private void restore(MutableJob job) {
            job.attempt = attempt;
            job.status = JobStatus.FAILED;
            job.startedAt = startedAt;
            job.completedAt = completedAt;
            job.error = error;
            job.results = results;
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
        private final Instant createdAt;
        private final int maxAttempts;
        private final List<JobAttempt> attemptHistory = new ArrayList<>();

        private int attempt = 1;
        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant completedAt;
        private Instant lastHeartbeatAt;
        private Instant executionLeaseExpiresAt;
        private List<HashResponse> results;
        private String error;

        private MutableJob(String jobId, String idempotencyKey, String algorithm, List<String> items,
                String secretKey, String fingerprint, Instant createdAt, int maxAttempts) {
            this.jobId = jobId;
            this.idempotencyKey = idempotencyKey;
            this.algorithm = algorithm;
            this.items = List.copyOf(items);
            this.secretKey = secretKey;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
            this.maxAttempts = maxAttempts;
        }
    }
}
