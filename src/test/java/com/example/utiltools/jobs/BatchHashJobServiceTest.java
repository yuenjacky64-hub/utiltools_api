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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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
        service = new BatchHashJobService(crypto, 2, 10, 300, 3);

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
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300, 3);

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
        service = new BatchHashJobService(crypto, 1, 1, 300, 3);

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
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3);

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
        service = new BatchHashJobService(crypto, 2, 10, 300, 3);

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
    @DisplayName("cancel - QUEUED job moves to CANCELLED exactly once with completedAt and no results")
    void shouldCancelQueuedJobExactlyOnce() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        // The single worker is busy, so the second job is guaranteed to still be QUEUED.
        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        BatchHashJobResponse queued =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha", "beta"), null, "cancel-key"));
        assertThat(service.getJob(queued.jobId()).status()).isEqualTo(JobStatus.QUEUED);

        BatchHashJobResponse cancelled = service.cancel(queued.jobId());

        assertThat(cancelled.jobId()).isEqualTo(queued.jobId());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
        assertThat(cancelled.startedAt()).isNull();
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.error()).isNull();
        assertThat(cancelled.itemCount()).isEqualTo(2);
        assertThat(cancelled.idempotencyKey()).isEqualTo("cancel-key");

        // Polling returns the very same cancelled snapshot.
        assertThat(service.getJob(queued.jobId())).isEqualTo(cancelled);

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("cancel - a second cancel on a CANCELLED job returns the identical snapshot")
    void shouldBeIdempotentWhenCancellingTwice() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        BatchHashJobResponse queued =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha"), null, null));

        BatchHashJobResponse first = service.cancel(queued.jobId());
        BatchHashJobResponse second = service.cancel(queued.jobId());
        BatchHashJobResponse third = service.cancel(queued.jobId());

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(second.completedAt()).isEqualTo(first.completedAt());
        assertThat(third.status()).isEqualTo(JobStatus.CANCELLED);

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("cancel - a cancelled queued job never invokes CryptoService.hash and never publishes results")
    void shouldNotHashAfterCancellation() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        BatchHashJobResponse cancelledJob =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha", "beta"), null, null));
        assertThat(service.cancel(cancelledJob.jobId()).status()).isEqualTo(JobStatus.CANCELLED);

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);

        // FIFO queue: this marker only runs after the cancelled task has been dequeued and returned,
        // so by the time it completes the cancelled runnable has provably been executed as a no-op.
        BatchHashJobResponse marker =
                service.submit(new BatchHashRequest("SHA-256", List.of("marker"), null, null));
        assertThat(awaitStatus(marker.jobId(), JobStatus.COMPLETED).results()).hasSize(1);

        assertThat(crypto.count("alpha")).isZero();
        assertThat(crypto.count("beta")).isZero();
        BatchHashJobResponse polled = service.getJob(cancelledJob.jobId());
        assertThat(polled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(polled.results()).isNull();
        assertThat(polled.startedAt()).isNull();
    }

    @Test
    @DisplayName("evictExpiredJobs - CANCELLED job stays observable until the retention cutoff, then is evicted")
    void shouldEvictCancelledJobOnlyAfterRetentionCutoff() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        BatchHashJobResponse queued =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha"), null, "cancelled-key"));
        assertThat(service.cancel(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);

        // The cutoff is the "completed before" bound: a cutoff one second in the past is younger
        // than the job's completedAt, so nothing is evicted and the snapshot is still pollable.
        assertThat(service.evictExpiredJobs(Instant.now().minusSeconds(1))).isZero();
        assertThat(service.getJob(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);

        // A duplicate submission with the same key still resolves to the cancelled snapshot.
        BatchHashJobResponse duplicate =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha"), null, "cancelled-key"));
        assertThat(duplicate.jobId()).isEqualTo(queued.jobId());
        assertThat(duplicate.status()).isEqualTo(JobStatus.CANCELLED);

        // Past the cutoff: the job and its idempotency mapping are retired together.
        assertThat(service.evictExpiredJobs(Instant.now().plusSeconds(300 + 3600))).isEqualTo(1);
        assertThatThrownBy(() -> service.getJob(queued.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThat(registryKeys("idempotencyIndex")).doesNotContain("cancelled-key");

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("cancel - a RUNNING job is rejected with a conflict carrying the job id and status")
    void shouldRejectCancellationOfRunningJob() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        JobCancellationConflictException conflict = conflictFrom(running.jobId());
        assertThat(conflict.jobId()).isEqualTo(running.jobId());
        assertThat(conflict.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(conflict.getMessage()).contains(running.jobId()).contains("RUNNING");
        assertThat(service.getJob(running.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("cancel - COMPLETED and FAILED jobs are rejected with a conflict carrying that status")
    void shouldRejectCancellationOfTerminalJob() {
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300, 3);

        BatchHashJobResponse completed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("done"), null, null)).jobId(),
                JobStatus.COMPLETED);
        JobCancellationConflictException completedConflict = conflictFrom(completed.jobId());
        assertThat(completedConflict.jobId()).isEqualTo(completed.jobId());
        assertThat(completedConflict.status()).isEqualTo(JobStatus.COMPLETED);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("NOPE-256", List.of("payload"), null, null)).jobId(),
                JobStatus.FAILED);
        JobCancellationConflictException failedConflict = conflictFrom(failed.jobId());
        assertThat(failedConflict.jobId()).isEqualTo(failed.jobId());
        assertThat(failedConflict.status()).isEqualTo(JobStatus.FAILED);

        // Unknown ids keep the pre-existing 404 path untouched.
        assertThatThrownBy(() -> service.cancel("does-not-exist")).isInstanceOf(JobNotFoundException.class);
    }

    @Test
    @DisplayName("retry - a FAILED job is requeued as QUEUED, keeps attempt-1 history and reaches COMPLETED")
    void shouldRetryFailedJobAndRetainAttemptHistory() {
        // Transient failure: the designated item fails its first hash only, so attempt 2 succeeds.
        service = new BatchHashJobService(new RetryCryptoService("transient", 1, null, null, null), 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("transient"), null, "retry-key")).jobId(),
                JobStatus.FAILED);
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(failed.maxAttempts()).isEqualTo(3);
        assertThat(failed.attempts()).hasSize(1);
        assertThat(failed.attempts().get(0).attempt()).isEqualTo(1);
        assertThat(failed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts().get(0).error()).isEqualTo(failed.error());
        assertThat(failed.attempts().get(0).completedAt()).isEqualTo(failed.completedAt());

        BatchHashJobResponse requeued = service.retry(failed.jobId());

        // The retried snapshot: same job, QUEUED, attempt 2, previous attempt still observable.
        assertThat(requeued.jobId()).isEqualTo(failed.jobId());
        assertThat(requeued.idempotencyKey()).isEqualTo("retry-key");
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(requeued.maxAttempts()).isEqualTo(3);
        assertThat(requeued.startedAt()).isNull();
        assertThat(requeued.completedAt()).isNull();
        assertThat(requeued.error()).isNull();
        assertThat(requeued.results()).isNull();
        assertThat(requeued.attempts()).hasSize(1);
        assertThat(requeued.attempts().get(0).attempt()).isEqualTo(1);
        assertThat(requeued.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);

        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.results()).hasSize(1);
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.attempts().get(0).attempt()).isEqualTo(1);
        assertThat(completed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(completed.attempts().get(1).attempt()).isEqualTo(2);
        assertThat(completed.attempts().get(1).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.attempts().get(1).completedAt()).isEqualTo(completed.completedAt());

        // The retry rides on the same id and leaves the idempotency mapping untouched.
        assertThat(service.submit(new BatchHashRequest("SHA-256", List.of("transient"), null, "retry-key")).jobId())
                .isEqualTo(failed.jobId());
    }

    @Test
    @DisplayName("retry - QUEUED, RUNNING, COMPLETED and CANCELLED jobs are rejected without mutation")
    void shouldRejectRetryOfNonFailedJob() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        RetryCryptoService crypto = new RetryCryptoService("flaky", 1, enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse completed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("done"), null, null)).jobId(),
                JobStatus.COMPLETED);
        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("flaky"), null, null)).jobId(),
                JobStatus.FAILED);

        // The single worker is busy inside hash(), so one job stays QUEUED and one can be cancelled.
        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        BatchHashJobResponse queued =
                service.submit(new BatchHashRequest("SHA-256", List.of("alpha"), null, null));
        BatchHashJobResponse cancelled = service.cancel(
                service.submit(new BatchHashRequest("SHA-256", List.of("beta"), null, null)).jobId());

        assertRetryConflict(queued, JobStatus.QUEUED);
        assertRetryConflict(running, JobStatus.RUNNING);
        assertRetryConflict(completed, JobStatus.COMPLETED);
        assertRetryConflict(cancelled, JobStatus.CANCELLED);

        // An unknown id keeps the pre-existing 404 path and registers nothing.
        Set<String> keysBefore = registryKeys("jobs");
        assertThatThrownBy(() -> service.retry("does-not-exist")).isInstanceOf(JobNotFoundException.class);
        assertThat(registryKeys("jobs")).isEqualTo(keysBefore);

        // An evicted id is indistinguishable from an unknown one.
        assertThat(service.evictExpiredJobs(Instant.now().plusSeconds(300 + 3600))).isGreaterThan(0);
        assertThatThrownBy(() -> service.retry(completed.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> service.retry(failed.jobId())).isInstanceOf(JobNotFoundException.class);

        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("retry - a job at maxAttempts cannot be retried and its snapshot stays identical")
    void shouldRejectRetryAtAttemptExhaustion() {
        service = new BatchHashJobService(
                new RetryCryptoService("forever", Integer.MAX_VALUE, null, null, null), 1, 10, 300, 2);

        BatchHashJobResponse firstFailure = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("forever"), null, null)).jobId(),
                JobStatus.FAILED);
        assertThat(firstFailure.attempt()).isEqualTo(1);
        assertThat(firstFailure.maxAttempts()).isEqualTo(2);

        BatchHashJobResponse requeued = service.retry(firstFailure.jobId());
        assertThat(requeued.attempt()).isEqualTo(2);

        BatchHashJobResponse exhausted = awaitStatus(firstFailure.jobId(), JobStatus.FAILED);
        assertThat(exhausted.attempt()).isEqualTo(2);
        assertThat(exhausted.attempts()).hasSize(2);
        assertThat(exhausted.attempts()).allSatisfy(entry -> assertThat(entry.status()).isEqualTo(JobStatus.FAILED));

        JobRetryConflictException conflict = retryConflictFrom(firstFailure.jobId());
        assertThat(conflict.jobId()).isEqualTo(firstFailure.jobId());
        assertThat(conflict.status()).isEqualTo(JobStatus.FAILED);
        assertThat(conflict.attempt()).isEqualTo(2);
        assertThat(conflict.maxAttempts()).isEqualTo(2);
        assertThat(conflict.getMessage()).contains("attempt 2 of 2");

        // Rejected before a single field was written.
        assertThat(service.getJob(firstFailure.jobId())).isEqualTo(exhausted);
    }

    @Test
    @DisplayName("retry - a superseded worker generation cannot touch the retried attempt")
    void shouldFenceStaleAttemptGeneration() {
        service = new BatchHashJobService(new RetryCryptoService("transient", 1, null, null, null), 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("transient"), null, null)).jobId(),
                JobStatus.FAILED);
        assertThat(service.retry(failed.jobId()).attempt()).isEqualTo(2);
        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);

        // Replaying the superseded generation 1 must change nothing at all.
        service.executeAttempt(failed.jobId(), 1);

        assertThat(service.getJob(failed.jobId())).isEqualTo(completed);
        assertThat(service.getJob(failed.jobId()).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(service.getJob(failed.jobId()).attempt()).isEqualTo(2);
        assertThat(service.getJob(failed.jobId()).results()).isEqualTo(completed.results());
        assertThat(service.getJob(failed.jobId()).error()).isNull();
        assertThat(service.getJob(failed.jobId()).attempts()).isEqualTo(completed.attempts());
    }

    @Test
    @DisplayName("retry - a pass that saw the job FAILED does not evict it once it is requeued")
    void shouldNotEvictRequeuedJob() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        RetryCryptoService crypto = new RetryCryptoService("transient", 1, enteredHash, releaseHash, "blocked");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("transient"), null, "requeue-key")).jobId(),
                JobStatus.FAILED);

        // Occupy the single worker so the retried attempt provably stays QUEUED, i.e. the job is
        // observable in the non-terminal state that eviction must respect.
        service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        BatchHashJobResponse requeued = service.retry(failed.jobId());
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempt()).isEqualTo(2);

        // The cutoff is past every completion instant, so a stale FAILED verdict would evict; the
        // pass must instead observe the requeued attempt and keep the job pollable.
        assertThat(service.evictExpiredJobs(Instant.now().plusSeconds(300 + 3600))).isZero();
        assertThat(service.getJob(failed.jobId())).isEqualTo(requeued);

        releaseHash.countDown();
        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(completed.attempts().get(1).status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("retry - an accepted retry is never lost to a concurrently running eviction pass")
    void shouldNeverAcceptRetryForAJobTheEvictionPassRemoves() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                CountDownLatch decided = new CountDownLatch(1);
                CountDownLatch releaseHash = new CountDownLatch(1);
                RetryCryptoService crypto = new RetryCryptoService("transient", 1, null, releaseHash, "blocked");
                BatchHashJobService roundService = new BatchHashJobService(crypto, 1, 10, 300, 3);
                service = roundService;
                try {
                    // Attempt 1 fails on "transient" before "blocked" is reached; the retried
                    // attempt passes "transient" and blocks in "blocked", so it stays non-terminal.
                    BatchHashJobResponse failed = awaitStatus(roundService.submit(new BatchHashRequest(
                            "SHA-256", List.of("transient", "blocked"), null, "race-key")).jobId(),
                            JobStatus.FAILED);
                    Instant cutoff = Instant.now().plusSeconds(300 + 3600);

                    Future<BatchHashJobResponse> retryFuture = racers.submit(() -> {
                        decided.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        return roundService.retry(failed.jobId());
                    });
                    Future<Integer> evictFuture = racers.submit(() -> {
                        decided.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        return roundService.evictExpiredJobs(cutoff);
                    });
                    decided.countDown();

                    BatchHashJobResponse requeued = null;
                    try {
                        requeued = retryFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        // The other legal outcome: the pass unregistered the job first, so the
                        // caller gets 404 instead of a 202 for an unpollable job.
                        assertThat(e.getCause()).isInstanceOf(JobNotFoundException.class);
                    }
                    evictFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    if (requeued != null) {
                        BatchHashJobResponse observed = roundService.getJob(failed.jobId());
                        assertThat(observed.attempt()).isEqualTo(2);
                        assertThat(observed.status()).isIn(JobStatus.QUEUED, JobStatus.RUNNING);
                    }
                } finally {
                    releaseHash.countDown();
                    roundService.shutdown();
                }
            }
        } finally {
            racers.shutdownNow();
        }
    }

    @Test
    @DisplayName("retry - queue rejection rolls back to FAILED, keeps the attempt count and is retryable later")
    void shouldRollbackRetryWhenQueueRejects() throws Exception {
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        RetryCryptoService crypto = new RetryCryptoService("transient", 1, enteredHash, releaseHash, "blocked");
        // One worker and a one-slot queue: while the worker is blocked and the slot is taken, a
        // retry is provably rejected by the AbortPolicy.
        service = new BatchHashJobService(crypto, 1, 1, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", List.of("transient"), null, "rollback-key")).jobId(),
                JobStatus.FAILED);

        BatchHashJobResponse running =
                service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null));
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        BatchHashJobResponse filler = service.submit(new BatchHashRequest("SHA-256", List.of("filler"), null, null));
        assertThat(service.getJob(filler.jobId()).status()).isEqualTo(JobStatus.QUEUED);

        Set<String> keysBefore = registryKeys("jobs");
        assertThatThrownBy(() -> service.retry(failed.jobId()))
                .isInstanceOf(JobQueueFullException.class)
                .hasMessageContaining("queue-capacity=1");

        // The rejected retry consumed nothing: same FAILED snapshot, same attempt, no new history.
        BatchHashJobResponse restored = service.getJob(failed.jobId());
        assertThat(restored.status()).isEqualTo(JobStatus.FAILED);
        assertThat(restored.attempt()).isEqualTo(1);
        assertThat(restored.error()).isEqualTo(failed.error());
        assertThat(restored.completedAt()).isEqualTo(failed.completedAt());
        assertThat(restored.attempts()).isEqualTo(failed.attempts());
        assertThat(restored.idempotencyKey()).isEqualTo("rollback-key");
        assertThat(registryKeys("jobs")).isEqualTo(keysBefore);

        // Once capacity frees, the very same job can be retried and completes on attempt 2.
        releaseHash.countDown();
        assertThat(awaitStatus(running.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        assertThat(awaitStatus(filler.jobId(), JobStatus.COMPLETED).results()).hasSize(1);

        BatchHashJobResponse requeued = service.retry(failed.jobId());
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempt()).isEqualTo(2);

        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.results()).hasSize(1);
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

    /** Runs {@code cancel} expecting a conflict and returns the exception for field-level assertions. */
    private JobCancellationConflictException conflictFrom(String jobId) {
        try {
            service.cancel(jobId);
        } catch (JobCancellationConflictException e) {
            return e;
        }
        throw new AssertionError("Expected JobCancellationConflictException for job " + jobId);
    }

    /** Runs {@code retry} expecting a conflict and returns the exception for field-level assertions. */
    private JobRetryConflictException retryConflictFrom(String jobId) {
        try {
            service.retry(jobId);
        } catch (JobRetryConflictException e) {
            return e;
        }
        throw new AssertionError("Expected JobRetryConflictException for job " + jobId);
    }

    /** Asserts that retrying a non-FAILED job conflicts and leaves the polled snapshot identical. */
    private void assertRetryConflict(BatchHashJobResponse expected, JobStatus expectedStatus) {
        BatchHashJobResponse before = service.getJob(expected.jobId());
        JobRetryConflictException conflict = retryConflictFrom(expected.jobId());
        assertThat(conflict.jobId()).isEqualTo(expected.jobId());
        assertThat(conflict.status()).isEqualTo(expectedStatus);
        assertThat(conflict.attempt()).isEqualTo(before.attempt());
        assertThat(conflict.maxAttempts()).isEqualTo(before.maxAttempts());
        assertThat(conflict.getMessage()).contains(expected.jobId()).contains(expectedStatus.name());
        assertThat(service.getJob(expected.jobId())).isEqualTo(before);
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
     * Hashing stub for the retry tests: one designated item fails its first {@code failures} hashes
     * (a transient fault) and, optionally, another designated item blocks the worker on latches so a
     * job can be observed in QUEUED / RUNNING. Every other item delegates to real hashing.
     */
    private static final class RetryCryptoService extends CryptoService {

        private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();
        private final CountDownLatch enteredHash;
        private final CountDownLatch releaseHash;
        private final String blockItem;

        private RetryCryptoService(String failingItem, int failures,
                CountDownLatch enteredHash, CountDownLatch releaseHash, String blockItem) {
            if (failingItem != null) {
                remainingFailures.put(failingItem, new AtomicInteger(failures));
            }
            this.enteredHash = enteredHash;
            this.releaseHash = releaseHash;
            this.blockItem = blockItem;
        }

        @Override
        public HashResponse hash(HashRequest request) {
            AtomicInteger remaining = remainingFailures.get(request.content());
            if (remaining != null && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("transient hashing failure for " + request.content());
            }
            if (blockItem != null && blockItem.equals(request.content())) {
                if (enteredHash != null) {
                    enteredHash.countDown();
                }
                if (releaseHash != null) {
                    try {
                        releaseHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return super.hash(request);
        }
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
