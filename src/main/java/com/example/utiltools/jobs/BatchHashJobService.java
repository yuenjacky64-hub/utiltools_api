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
 * QUEUED -> CANCELLED when it is withdrawn before a worker admits it, an optional idempotency key
 * maps to exactly one job and one execution, and stored results keep the input item order.</p>
 */
@Service
public class BatchHashJobService {

    private final CryptoService cryptoService;
    private final ThreadPoolExecutor executor;
    private final int workerCount;
    private final int queueCapacity;
    private final long retentionSeconds;

    private final ConcurrentHashMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds) {
        this.cryptoService = cryptoService;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.retentionSeconds = retentionSeconds;
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
                    request.algorithm(), request.items(), request.secretKey(), fingerprint, Instant.now());
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
                executor.execute(() -> executeJob(job));
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
            boolean evictable;
            synchronized (job) {
                evictable = (job.status == JobStatus.COMPLETED
                        || job.status == JobStatus.FAILED
                        || job.status == JobStatus.CANCELLED)
                        && job.completedAt != null
                        && job.completedAt.isBefore(cutoff);
            }
            if (!evictable) {
                continue;
            }
            if (jobs.remove(job.jobId, job)) {
                evicted++;
                if (job.idempotencyKey != null) {
                    idempotencyIndex.remove(job.idempotencyKey, job.jobId);
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
                    job.results, job.error, job.createdAt, job.startedAt, job.completedAt);
        }
    }

    private void executeJob(MutableJob job) {
        synchronized (job) {
            if (job.status != JobStatus.QUEUED) {
                return; // a second execution is a no-op
            }
            job.status = JobStatus.RUNNING;
            job.startedAt = Instant.now();
        }
        try {
            List<HashResponse> results = new ArrayList<>(job.items.size());
            for (String item : job.items) {
                results.add(cryptoService.hash(new HashRequest(job.algorithm, item, job.secretKey)));
            }
            synchronized (job) {
                job.results = List.copyOf(results);
                job.error = null;
                job.status = JobStatus.COMPLETED;
                job.completedAt = Instant.now();
            }
        } catch (Throwable t) {
            // Intentional: an Error must not wedge the pool, later jobs still run.
            synchronized (job) {
                job.error = describe(t);
                job.status = JobStatus.FAILED;
                job.completedAt = Instant.now();
            }
        }
    }

    private String describe(Throwable t) {
        return t.getMessage() == null
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
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

        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant completedAt;
        private List<HashResponse> results;
        private String error;

        private MutableJob(String jobId, String idempotencyKey, String algorithm, List<String> items,
                String secretKey, String fingerprint, Instant createdAt) {
            this.jobId = jobId;
            this.idempotencyKey = idempotencyKey;
            this.algorithm = algorithm;
            this.items = List.copyOf(items);
            this.secretKey = secretKey;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
        }
    }
}
