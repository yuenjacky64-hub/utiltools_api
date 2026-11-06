package com.example.utiltools.jobs;

import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant tests for {@link BatchHashJobService}. Plain JUnit 5 + AssertJ, no Spring context:
 * fast, no scheduler, no port binding, and no non-daemon threads left behind.
 */
class BatchHashJobServiceTest {

    private static final long TIMEOUT_SECONDS = 10;

    private BatchHashJobService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("submit - concurrent same idempotency key yields one jobId and one execution per item")
    void shouldDeduplicateConcurrentSubmissionsWithSameIdempotencyKey() throws Exception {
        CountingCryptoService crypto = new CountingCryptoService(null, null, null);
        service = new BatchHashJobService(crypto, 2, 10, 300);

        BatchHashRequest request = new BatchHashRequest("SHA-256", List.of("alpha", "beta"), null, "dedup-key");
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService submitter = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(submitter.submit(() -> {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return service.submit(request).jobId();
                }));
            }
            Set<String> jobIds = new HashSet<>();
            for (Future<String> future : futures) {
                jobIds.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertThat(jobIds).hasSize(1);

            String jobId = jobIds.iterator().next();
            BatchHashJobResponse completed = awaitStatus(jobId, JobStatus.COMPLETED);
            assertThat(completed.itemCount()).isEqualTo(2);
            assertThat(completed.idempotencyKey()).isEqualTo("dedup-key");
            assertThat(crypto.count("alpha")).isEqualTo(1);
            assertThat(crypto.count("beta")).isEqualTo(1);
        } finally {
            submitter.shutdownNow();
        }
    }

    @Test
    @DisplayName("getJob - results keep input order and equal CryptoService output per item")
    void shouldPreserveInputOrderInResults() {
        List<String> items = List.of("alpha", "beta", "gamma");
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300);

        BatchHashJobResponse submitted = service.submit(new BatchHashRequest("SHA-256", items, null, null));
        BatchHashJobResponse completed = awaitStatus(submitted.jobId(), JobStatus.COMPLETED);

        assertThat(completed.results()).hasSize(items.size());
        CryptoService reference = new CryptoService();
        for (int i = 0; i < items.size(); i++) {
            HashResponse expected = reference.hash(new HashRequest("SHA-256", items.get(i), null));
            assertThat(completed.results().get(i).digest()).isEqualTo(expected.digest());
            assertThat(completed.results().get(i).algorithm()).isEqualTo(expected.algorithm());
            assertThat(completed.results().get(i).isHmac()).isFalse();
        }
        assertThat(completed.createdAt()).isNotNull();
        assertThat(completed.startedAt()).isNotNull();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.error()).isNull();
    }

    @Test
    @DisplayName("submit - saturated queue is rejected with no residue and the same key is accepted after drain")
    void shouldRejectOnSaturatedQueueAndAcceptRetryAfterDrain() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "a");
        service = new BatchHashJobService(crypto, 1, 1, 300);

        // Job A occupies the single worker thread.
        BatchHashJobResponse jobA = service.submit(new BatchHashRequest("SHA-256", List.of("a"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // Job B fills the single queue slot.
        BatchHashJobResponse jobB = service.submit(new BatchHashRequest("SHA-256", List.of("b"), null, null));

        Set<String> registeredBefore = registryKeys("jobs");
        assertThat(registeredBefore).containsExactlyInAnyOrder(jobA.jobId(), jobB.jobId());

        // Job C must be rejected by the AbortPolicy; no jobId is ever handed out.
        assertThatThrownBy(() -> service.submit(new BatchHashRequest("SHA-256", List.of("c"), null, "retry-key")))
                .isInstanceOf(JobQueueFullException.class)
                .hasMessageContaining("queue-capacity=1");

        // No residue: the rejected submission left neither a job row nor its idempotency mapping.
        assertThat(registryKeys("jobs")).isEqualTo(registeredBefore);
        assertThat(registryKeys("idempotencyIndex")).doesNotContain("retry-key");

        // Drain job A and job B, then the same key must be accepted as a brand-new job.
        releaseHash.countDown();
        awaitStatus(jobA.jobId(), JobStatus.COMPLETED);
        awaitStatus(jobB.jobId(), JobStatus.COMPLETED);

        BatchHashJobResponse retried = service.submit(new BatchHashRequest("SHA-256", List.of("c"), null, "retry-key"));
        assertThat(retried.jobId()).isNotBlank().isNotIn(jobA.jobId(), jobB.jobId());
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        BatchHashJobResponse retriedCompleted = awaitStatus(retried.jobId(), JobStatus.COMPLETED);
        assertThat(retriedCompleted.results()).hasSize(1);
        assertThat(retriedCompleted.idempotencyKey()).isEqualTo("retry-key");
        assertThat(registryKeys("idempotencyIndex")).contains("retry-key");
    }

    @Test
    @DisplayName("worker - a Throwable marks the job FAILED and leaves the pool usable")
    void shouldMarkJobFailedWhenWorkerThrowsAndKeepPoolUsable() {
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300);

        BatchHashJobResponse submitted =
                service.submit(new BatchHashRequest("NOPE-256", List.of("payload"), null, null));
        BatchHashJobResponse failed = awaitStatus(submitted.jobId(), JobStatus.FAILED);

        assertThat(failed.error()).isNotNull().isNotBlank();
        assertThat(failed.error()).contains("NOPE-256");
        assertThat(failed.startedAt()).isNotNull();
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.results()).isNull();

        // Same single-threaded pool must still execute later jobs.
        BatchHashJobResponse healthy = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("payload"), null, null)).jobId(),
                JobStatus.COMPLETED);
        assertThat(healthy.results()).hasSize(1);
        assertThat(healthy.error()).isNull();
    }

    @Test
    @DisplayName("evictExpiredJobs - only stale terminal jobs are evicted, RUNNING jobs survive")
    void shouldEvictOnlyStaleTerminalJobs() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 2, 10, 300);

        // A job that stays RUNNING while blocked inside hash().
        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, "running-key"));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // A job that reaches a terminal state.
        BatchHashJobResponse completed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("done"), null, "completed-key")).jobId(),
                JobStatus.COMPLETED);

        // Advance the cutoff well past the retention window.
        int evicted = service.evictExpiredJobs(Instant.now().plusSeconds(300 + 3600));
        assertThat(evicted).isEqualTo(1);

        assertThatThrownBy(() -> service.getJob(completed.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThat(service.getJob(running.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        // The evicted job's idempotency mapping is gone too: resubmitting runs a brand-new job.
        BatchHashJobResponse resubmitted =
                service.submit(new BatchHashRequest("SHA-256", List.of("done"), null, "completed-key"));
        assertThat(resubmitted.jobId()).isNotEqualTo(completed.jobId());

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("submit - same key with a different item list conflicts and leaves no residue")
    void shouldRejectConflictWhenItemListChangesUnderSameKey() {
        CountingCryptoService crypto = new CountingCryptoService(null, null, null);
        service = new BatchHashJobService(crypto, 2, 10, 300);

        BatchHashJobResponse first = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("a"), null, "seq-key")).jobId(),
                JobStatus.COMPLETED);

        assertThatThrownBy(() -> service.submit(new BatchHashRequest("SHA-256", List.of("b"), null, "seq-key")))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("seq-key")
                .hasMessageContaining(first.jobId());

        // The conflicting payload never reached CryptoService; the first job is untouched.
        assertThat(crypto.count("b")).isZero();
        assertThat(crypto.count("a")).isEqualTo(1);
        BatchHashJobResponse unchanged = service.getJob(first.jobId());
        assertThat(unchanged.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(unchanged.itemCount()).isEqualTo(1);
        assertThat(unchanged.idempotencyKey()).isEqualTo("seq-key");
        assertThat(unchanged.results()).hasSize(1);

        // No residue: exactly one job and one idempotency mapping remain.
        assertThat(registryKeys("jobs")).containsExactly(first.jobId());
        assertThat(registryKeys("idempotencyIndex")).containsExactly("seq-key");
    }

    @Test
    @DisplayName("submit - concurrent same key with different payloads yields one accepted job and one conflict per loser")
    void shouldRejectConcurrentConflictingPayloadsWithOneWinner() throws Exception {
        CountingCryptoService crypto = new CountingCryptoService(null, null, null);
        service = new BatchHashJobService(crypto, 2, 10, 300);

        String key = "race-key";
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService submitter = Executors.newFixedThreadPool(threads);
        List<String> acceptedJobIds = Collections.synchronizedList(new ArrayList<>());
        List<IdempotencyConflictException> conflicts = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String item = (i % 2 == 0) ? "a" : "b";
                futures.add(submitter.submit(() -> {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    try {
                        acceptedJobIds.add(
                                service.submit(new BatchHashRequest("SHA-256", List.of(item), null, key)).jobId());
                    } catch (IdempotencyConflictException e) {
                        conflicts.add(e);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            submitter.shutdownNow();
        }

        // Which payload wins is not asserted: the split is deterministic only in size.
        assertThat(acceptedJobIds).hasSize(threads / 2);
        assertThat(new HashSet<>(acceptedJobIds)).hasSize(1);
        assertThat(conflicts).hasSize(threads / 2);
        String winnerJobId = acceptedJobIds.get(0);
        assertThat(conflicts).allSatisfy(conflict -> {
            assertThat(conflict.idempotencyKey()).isEqualTo(key);
            assertThat(conflict.existingJobId()).isEqualTo(winnerJobId);
        });

        // Wait for the winner's single execution to reach a terminal state BEFORE counting, so the
        // hash-count assertions observe a finished job instead of racing the worker thread.
        assertThat(awaitStatus(winnerJobId, JobStatus.COMPLETED).itemCount()).isEqualTo(1);

        // Only the winning payload ran, exactly once.
        int hashedA = crypto.count("a");
        int hashedB = crypto.count("b");
        assertThat(hashedA + hashedB).isEqualTo(1);
        assertThat(Math.max(hashedA, hashedB)).isEqualTo(1);
        assertThat(Math.min(hashedA, hashedB)).isZero();
        assertThat(registryKeys("jobs")).containsExactly(winnerJobId);
        assertThat(registryKeys("idempotencyIndex")).containsExactly(key);
    }

    @Test
    @DisplayName("submit - identical key and payload returns the same jobId without a second execution")
    void shouldReturnSameJobIdForIdenticalRetryWithoutSecondExecution() {
        CountingCryptoService crypto = new CountingCryptoService(null, null, null);
        service = new BatchHashJobService(crypto, 2, 10, 300);

        BatchHashRequest request = new BatchHashRequest("SHA-256", List.of("alpha"), "s3cret", "identical-key");
        BatchHashJobResponse first = service.submit(request);
        BatchHashJobResponse duplicate = service.submit(request);

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        awaitStatus(first.jobId(), JobStatus.COMPLETED);

        // A retry after completion still resolves to the same job, never a second execution.
        assertThat(service.submit(request).jobId()).isEqualTo(first.jobId());
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(registryKeys("jobs")).containsExactly(first.jobId());
        assertThat(registryKeys("idempotencyIndex")).containsExactly("identical-key");
    }

    @Test
    @DisplayName("submit - algorithm and secretKey spellings normalise like CryptoService")
    void shouldTreatAlgorithmAndSecretKeySpellingsAsEquivalent() {
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300);

        BatchHashJobResponse first =
                service.submit(new BatchHashRequest("sha-256", List.of("alpha"), null, "norm-key"));
        BatchHashJobResponse aliased =
                service.submit(new BatchHashRequest("SHA256", List.of("alpha"), "   ", "norm-key"));
        BatchHashJobResponse padded =
                service.submit(new BatchHashRequest("  SHA-256  ", List.of("alpha"), null, "norm-key"));

        assertThat(aliased.jobId()).isEqualTo(first.jobId());
        assertThat(padded.jobId()).isEqualTo(first.jobId());
        assertThat(registryKeys("jobs")).containsExactly(first.jobId());
    }

    @Test
    @DisplayName("submit - reordered or boundary-shifted items conflict under the same key")
    void shouldRejectConflictWhenItemsAreReordered() {
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300);

        BatchHashJobResponse first =
                service.submit(new BatchHashRequest("SHA-256", List.of("ab", "c"), null, "boundary-key"));

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("a", "bc"), null, "boundary-key")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("c", "ab"), null, "boundary-key")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(registryKeys("jobs")).containsExactly(first.jobId());
        assertThat(registryKeys("idempotencyIndex")).containsExactly("boundary-key");
    }

    /**
     * Snapshot of the keys of a private registry map ({@code jobs} / {@code idempotencyIndex}).
     * Reflection keeps the production class free of test-only accessors while still letting the
     * saturation test prove that a rejected submission leaves no job or idempotency residue.
     */
    private Set<String> registryKeys(String fieldName) {
        try {
            Field field = BatchHashJobService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) field.get(service);
            return Set.copyOf(map.keySet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read BatchHashJobService." + fieldName, e);
        }
    }

    private BatchHashJobResponse awaitStatus(String jobId, JobStatus expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            BatchHashJobResponse snapshot = service.getJob(jobId);
            if (snapshot.status() == expected) {
                return snapshot;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting job " + jobId, e);
            }
        }
        throw new AssertionError("Job " + jobId + " did not reach " + expected
                + "; last observed status=" + service.getJob(jobId).status());
    }

    /**
     * Hashing stub that counts invocations per item and, for one designated item only, blocks the
     * worker on a latch. Delegates to real hashing so digests stay comparable with
     * {@link CryptoService}.
     */
    private static final class CountingCryptoService extends CryptoService {

        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final CountDownLatch enteredHash;
        private final CountDownLatch releaseHash;
        private final String blockItem;

        private CountingCryptoService(CountDownLatch enteredHash, CountDownLatch releaseHash, String blockItem) {
            this.enteredHash = enteredHash;
            this.releaseHash = releaseHash;
            this.blockItem = blockItem;
        }

        @Override
        public HashResponse hash(HashRequest request) {
            invocations.computeIfAbsent(request.content(), key -> new AtomicInteger()).incrementAndGet();
            if (blockItem != null && blockItem.equals(request.content())) {
                if (enteredHash != null) {
                    enteredHash.countDown();
                }
            }
            if (releaseHash != null && blockItem != null && blockItem.equals(request.content())) {
                try {
                    releaseHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.hash(request);
        }

        private int count(String item) {
            AtomicInteger counter = invocations.get(item);
            return counter == null ? 0 : counter.get();
        }
    }
}
