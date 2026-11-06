package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
 */
@Service
public class BatchHashJobService {

    private final CryptoService cryptoService;
    private final ThreadPoolExecutor executor;
    private final int workerCount;
    private final int queueCapacity;
    private final long retentionSeconds;
    private final int maxAttempts;

    private final ConcurrentHashMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.jobs.max-attempts:3}") int maxAttempts) {
        this.cryptoService = cryptoService;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.retentionSeconds = retentionSeconds;
        // A non-positive configuration must not authorise unlimited retries.
        this.maxAttempts = Math.max(1, maxAttempts);
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
                    request.algorithm(), request.items(), request.secretKey(), fingerprint, Instant.now(),
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
            job.completedAt = Instant.now();
            job.results = null; // explicit: nothing was hashed
            job.error = null;
            return snapshot(job);
        }
    }

    @Scheduled(fixedDelayString = "${utiltools.jobs.cleanup-interval-ms:60000}")
    public void evictExpiredJobs() {
        evictExpiredJobs(Instant.now().minusSeconds(retentionSeconds));
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
            return new BatchHashJobResponse(job.jobId, job.idempotencyKey, job.status, job.items.size(),
                    job.results, job.error, job.createdAt, job.startedAt, job.completedAt,
                    job.attempt, job.maxAttempts, List.copyOf(job.attemptHistory));
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
            job.status = JobStatus.RUNNING;
            job.startedAt = Instant.now();
            recordAttempt(job, generation, JobStatus.RUNNING, job.startedAt, null, null);
        }
        try {
            List<HashResponse> results = new ArrayList<>(job.items.size());
            for (String item : job.items) {
                results.add(cryptoService.hash(new HashRequest(job.algorithm, item, job.secretKey)));
            }
            synchronized (job) {
                if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                    // Defence-in-depth only: the load-bearing fence is the admission check above,
                    // because RUNNING jobs are neither retryable nor evictable today.
                    return; // fenced: the attempt was requeued or reaped while hashing
                }
                job.results = List.copyOf(results);
                job.error = null;
                job.status = JobStatus.COMPLETED;
                job.completedAt = Instant.now();
                recordAttempt(job, generation, JobStatus.COMPLETED, job.startedAt, job.completedAt, null);
            }
        } catch (Throwable t) {
            // Intentional: an Error must not wedge the pool, later jobs still run.
            synchronized (job) {
                if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                    // Defence-in-depth only, same reasoning as the COMPLETED fence above.
                    return; // fenced: a superseded failure must not overwrite the retried attempt
                }
                job.error = describe(t);
                job.status = JobStatus.FAILED;
                job.completedAt = Instant.now();
                recordAttempt(job, generation, JobStatus.FAILED, job.startedAt, job.completedAt, job.error);
            }
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

        private FailedState(int attempt, Instant startedAt, Instant completedAt, String error,
                List<HashResponse> results) {
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.error = error;
            this.results = results;
        }

        private static FailedState of(MutableJob job) {
            return new FailedState(job.attempt, job.startedAt, job.completedAt, job.error, job.results);
        }

        private void restore(MutableJob job) {
            job.attempt = attempt;
            job.status = JobStatus.FAILED;
            job.startedAt = startedAt;
            job.completedAt = completedAt;
            job.error = error;
            job.results = results;
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
