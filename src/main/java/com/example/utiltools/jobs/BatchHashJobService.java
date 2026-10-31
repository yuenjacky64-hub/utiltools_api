package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * the heap. Every accepted job moves QUEUED -> RUNNING -> COMPLETED|FAILED exactly once, an
 * optional idempotency key maps to exactly one job and one execution, and stored results keep the
 * input item order.</p>
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

        synchronized (registryLock) {
            if (key != null) {
                String existingJobId = idempotencyIndex.get(key);
                if (existingJobId != null) {
                    MutableJob existing = jobs.get(existingJobId);
                    if (existing != null) {
                        return snapshot(existing); // duplicate submission: same jobId, no second execution
                    }
                }
            }

            MutableJob job = new MutableJob(UUID.randomUUID().toString(), key,
                    request.algorithm(), request.items(), request.secretKey(), Instant.now());
            if (key != null) {
                idempotencyIndex.put(key, job.jobId);
            }
            jobs.put(job.jobId, job);

            // Snapshot taken at registration, before enqueue, so the 202 body always reports the
            // accepted state (QUEUED) even if a worker thread starts and finishes immediately.
            BatchHashJobResponse accepted = snapshot(job);

            try {
                executor.execute(() -> executeJob(job));
            } catch (RejectedExecutionException e) {
                jobs.remove(job.jobId, job);
                if (key != null) {
                    idempotencyIndex.remove(key, job);
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

    @Scheduled(fixedDelayString = "${utiltools.jobs.cleanup-interval-ms:60000}")
    public void evictExpiredJobs() {
        evictExpiredJobs(Instant.now().minusSeconds(retentionSeconds));
    }

    /**
     * Removes terminal jobs whose completion instant is older than {@code cutoff}, together with
     * their idempotency entries. QUEUED and RUNNING jobs are never evicted. Public and clock-free
     * so tests can advance the cutoff instead of waiting.
     *
     * @return the number of evicted jobs
     */
    public int evictExpiredJobs(Instant cutoff) {
        int evicted = 0;
        for (MutableJob job : jobs.values()) {
            boolean evictable;
            synchronized (job) {
                evictable = (job.status == JobStatus.COMPLETED || job.status == JobStatus.FAILED)
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

    private static final class MutableJob {

        private final String jobId;
        private final String idempotencyKey;
        private final String algorithm;
        private final List<String> items;
        private final String secretKey;
        private final Instant createdAt;

        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant completedAt;
        private List<HashResponse> results;
        private String error;

        private MutableJob(String jobId, String idempotencyKey, String algorithm, List<String> items,
                String secretKey, Instant createdAt) {
            this.jobId = jobId;
            this.idempotencyKey = idempotencyKey;
            this.algorithm = algorithm;
            this.items = List.copyOf(items);
            this.secretKey = secretKey;
            this.createdAt = createdAt;
        }
    }
}
