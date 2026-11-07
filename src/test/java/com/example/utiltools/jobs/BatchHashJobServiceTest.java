package com.example.utiltools.jobs;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
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

    /** Fixed clock origin and lease length for the execution-lease tests. */
    private static final Instant LEASE_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 30;

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
    @DisplayName("submit - same key with a different item list conflicts and leaves no residue")
    void shouldRejectConflictWhenItemListChangesUnderSameKey() {
        CountingCryptoService crypto = new CountingCryptoService(null, null, null);
        service = new BatchHashJobService(crypto, 2, 10, 300, 3);

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
        service = new BatchHashJobService(crypto, 2, 10, 300, 3);

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
        service = new BatchHashJobService(crypto, 2, 10, 300, 3);

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
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300, 3);

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
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300, 3);

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
     * DOD-01 / REQ-03 / REQ-04: a worker blocked inside {@code hash()} is reaped exactly once at the
     * inclusive lease boundary, and the snapshot it would have written afterwards never lands.
     */
    @Test
    @DisplayName("execution lease - a stalled worker is reaped exactly once and never resurrects the job")
    void shouldReapStalledWorkerExactlyOnceAndNeverResurrectIt() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(accepted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(crypto.awaitEntered("stalled")).isTrue(); // the worker is now inside hash()

        BatchHashJobResponse running = service.getJob(accepted.jobId());
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.lastHeartbeatAt()).isEqualTo(LEASE_START);
        assertThat(running.executionLeaseExpiresAt()).isEqualTo(LEASE_START.plusSeconds(LEASE_SECONDS));

        // One millisecond before expiry the lease is still live; the boundary instant itself is expired.
        clock.advanceMillis(LEASE_SECONDS * 1000 - 1);
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        clock.advanceMillis(1);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

        BatchHashJobResponse reaped = service.getJob(accepted.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(reaped.completedAt()).isEqualTo(clock.instant());
        assertThat(reaped.results()).isNull();
        assertThat(reaped.executionLeaseExpiresAt()).isNull();
        assertThat(reaped.lastHeartbeatAt()).isNull();
        assertThat(reaped.attempts()).hasSize(1);
        assertThat(reaped.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.attempts().get(0).error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);

        // Release the stalled worker: its late COMPLETED write must be fenced out.
        clock.advanceSeconds(LEASE_SECONDS);
        crypto.unblock("stalled");
        BatchHashJobResponse probe = service.submit(
                new BatchHashRequest("SHA-256", List.of("probe"), null, null));
        awaitStatus(probe.jobId(), JobStatus.COMPLETED); // the single worker returned from hash()

        assertThat(service.getJob(accepted.jobId())).isEqualTo(reaped); // FAILED snapshot unchanged
        assertThat(crypto.count("stalled")).isEqualTo(1);
    }

    /**
     * DOD-02 / REQ-02: heartbeat renewal keeps a healthy multi-item attempt alive across clock
     * advances that are each shorter than the lease.
     */
    @Test
    @DisplayName("execution lease - a healthy worker renews the lease between items and is not reaped")
    void shouldRenewLeaseBetweenItemsAndCompleteWithoutBeingReaped() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("b", "c");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("a", "b", "c"), null, null));
        assertThat(crypto.awaitEntered("b")).isTrue(); // "a" is hashed, the worker is inside "b"

        clock.advanceSeconds(20);
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        clock.advanceSeconds(LEASE_SECONDS - 20 - 1); // start + 29: still inside the lease
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();

        crypto.unblock("b");
        assertThat(crypto.awaitEntered("c")).isTrue(); // the renewal before "c" already happened

        BatchHashJobResponse renewed = service.getJob(accepted.jobId());
        assertThat(renewed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(renewed.lastHeartbeatAt()).isEqualTo(LEASE_START.plusSeconds(LEASE_SECONDS - 1));
        assertThat(renewed.executionLeaseExpiresAt())
                .isEqualTo(LEASE_START.plusSeconds(2 * LEASE_SECONDS - 1));

        // Without the renewal the lease would have expired at start+30; it now runs to start+59.
        clock.advanceSeconds(25);
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        crypto.unblock("c");
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.results()).hasSize(3);
        assertThat(completed.executionLeaseExpiresAt()).isNull();
        assertThat(completed.lastHeartbeatAt()).isNull();
    }

    /**
     * DOD-03 / REQ-03 / REQ-04 / REQ-05: the reaper is idempotent, skips QUEUED and terminal jobs,
     * and a reaped attempt is retryable into a clean {@code attempt + 1} that completes.
     */
    @Test
    @DisplayName("execution lease - reaping is idempotent, skips QUEUED/terminal jobs, and retry reaches attempt 2")
    void shouldReapIdempotentlySkipQueuedAndTerminalJobsAndRetryToAttemptTwo() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse terminal = service.submit(
                new BatchHashRequest("SHA-256", List.of("first"), null, null));
        BatchHashJobResponse completed = awaitStatus(terminal.jobId(), JobStatus.COMPLETED);
        BatchHashJobResponse stalled = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null));
        BatchHashJobResponse cancelled = service.cancel(service.submit(
                new BatchHashRequest("SHA-256", List.of("withdrawn"), null, null)).jobId());
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        BatchHashJobResponse queuedBefore = service.getJob(queued.jobId());
        BatchHashJobResponse cancelledBefore = service.getJob(cancelled.jobId());

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1); // the stalled attempt only

        BatchHashJobResponse reaped = service.getJob(stalled.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(service.getJob(completed.jobId())).isEqualTo(completed);
        assertThat(service.getJob(queued.jobId())).isEqualTo(queuedBefore);
        assertThat(service.getJob(cancelled.jobId())).isEqualTo(cancelledBefore);

        // Idempotency: the same instant reaps nothing and rewrites nothing.
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(stalled.jobId())).isEqualTo(reaped);

        // A reaped FAILED job is retryable: attempt + 1 with the error, results and lease cleared.
        BatchHashJobResponse retried = service.retry(stalled.jobId());
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.error()).isNull();
        assertThat(retried.results()).isNull();
        assertThat(retried.executionLeaseExpiresAt()).isNull();
        assertThat(retried.lastHeartbeatAt()).isNull();

        clock.advanceSeconds(5);
        crypto.unblock("stalled"); // the stale attempt-1 worker returns and is fenced out
        BatchHashJobResponse done = awaitStatus(stalled.jobId(), JobStatus.COMPLETED);
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.error()).isNull();
        assertThat(done.results()).hasSize(1);
        assertThat(done.attempts()).hasSize(2);
        assertThat(done.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.attempts().get(0).error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(done.attempts().get(1).status()).isEqualTo(JobStatus.COMPLETED);
    }

    /**
     * DOD-04 / REQ-06: lease state is projected only while RUNNING.
     */
    @Test
    @DisplayName("execution lease - polling exposes the lease fields only while RUNNING")
    void shouldExposeLeaseFieldsOnlyWhileRunning() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("running");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse submitted = service.submit(
                new BatchHashRequest("SHA-256", List.of("running"), null, null));
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(submitted.executionLeaseExpiresAt()).isNull();
        assertThat(submitted.lastHeartbeatAt()).isNull();
        assertThat(crypto.awaitEntered("running")).isTrue();

        BatchHashJobResponse running = service.getJob(submitted.jobId());
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.executionLeaseExpiresAt()).isNotNull();
        assertThat(running.lastHeartbeatAt()).isNotNull();
        assertThat(running.executionLeaseExpiresAt())
                .isEqualTo(running.lastHeartbeatAt().plusSeconds(LEASE_SECONDS));

        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null));
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(queued.executionLeaseExpiresAt()).isNull();
        assertThat(queued.lastHeartbeatAt()).isNull();

        crypto.unblock("running");
        BatchHashJobResponse completed = awaitStatus(submitted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.executionLeaseExpiresAt()).isNull();
        assertThat(completed.lastHeartbeatAt()).isNull();

        // The 12-argument compatibility constructor leaves both lease stamps unset.
        BatchHashJobResponse legacy = new BatchHashJobResponse("legacy", "key", JobStatus.COMPLETED, 1,
                List.of(), null, LEASE_START, LEASE_START, LEASE_START, 1, 3, List.of());
        assertThat(legacy.executionLeaseExpiresAt()).isNull();
        assertThat(legacy.lastHeartbeatAt()).isNull();
    }

    /**
     * DOD-05 / REQ-04: once a lease is reaped, a worker that later succeeds, later fails, or is
     * replayed by generation cannot overwrite the reaped FAILED snapshot.
     */
    @Test
    @DisplayName("execution lease - a worker that finishes or fails after the reap cannot resurrect the job")
    void shouldFenceStaleWorkerAfterReap() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled", "stalled-failing");
        crypto.failAfterRelease("stalled-failing");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        // Scenario 1: the stale worker would have written COMPLETED.
        BatchHashJobResponse reapedSuccess = reapStalledJob(clock, crypto, "stalled");
        service.executeAttempt(reapedSuccess.jobId(), 1); // a generation replay is fenced just as well
        assertThat(service.getJob(reapedSuccess.jobId())).isEqualTo(reapedSuccess);
        clock.advanceSeconds(LEASE_SECONDS);
        crypto.unblock("stalled");
        BatchHashJobResponse probe = service.submit(
                new BatchHashRequest("SHA-256", List.of("probe-1"), null, null));
        awaitStatus(probe.jobId(), JobStatus.COMPLETED); // the single worker has returned from hash()
        assertThat(service.getJob(reapedSuccess.jobId())).isEqualTo(reapedSuccess);

        // Scenario 2: the stale worker throws instead; the reaped error must survive untouched.
        BatchHashJobResponse reapedFailure = reapStalledJob(clock, crypto, "stalled-failing");
        clock.advanceSeconds(LEASE_SECONDS);
        crypto.unblock("stalled-failing");
        BatchHashJobResponse probe2 = service.submit(
                new BatchHashRequest("SHA-256", List.of("probe-2"), null, null));
        awaitStatus(probe2.jobId(), JobStatus.COMPLETED);

        BatchHashJobResponse fenced = service.getJob(reapedFailure.jobId());
        assertThat(fenced).isEqualTo(reapedFailure);
        assertThat(fenced.status()).isEqualTo(JobStatus.FAILED);
        assertThat(fenced.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(fenced.results()).isNull();
    }

    /**
     * DOD-06 / REQ-01: non-positive lease/reaper configuration is rejected, a null clock falls back
     * to the system clock, and the five-argument constructor keeps the 30-second default lease.
     */
    @Test
    @DisplayName("execution lease - non-positive config is rejected and the five-argument constructor still works")
    void shouldRejectNonPositiveLeaseConfigAndKeepFiveArgumentConstructor() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("blocking");
        MutableJobClock clock = new MutableJobClock(LEASE_START);

        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, 0, 5000, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution-lease-seconds");
        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, -1, 5000, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution-lease-seconds");
        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution-reaper-interval-ms");

        BatchHashJobService systemClockService =
                new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, null);
        systemClockService.shutdown();

        service = new BatchHashJobService(crypto, 1, 10, 300, 3); // system clock, default 30 s lease
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocking"), null, null));
        assertThat(crypto.awaitEntered("blocking")).isTrue();
        BatchHashJobResponse running = service.getJob(accepted.jobId());
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.executionLeaseExpiresAt()).isNotNull();
        assertThat(running.executionLeaseExpiresAt()).isEqualTo(running.lastHeartbeatAt().plusSeconds(30));
        crypto.unblock("blocking");
    }

    /**
     * Submits a single-item job, lets the worker stall inside {@code hash()}, advances the clock past
     * the lease and reaps it, returning the reaped snapshot.
     */
    private BatchHashJobResponse reapStalledJob(MutableJobClock clock, LeaseCryptoService crypto, String item)
            throws InterruptedException {
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of(item), null, null));
        assertThat(crypto.awaitEntered(item)).isTrue();
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse reaped = service.getJob(accepted.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        return reaped;
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

    /**
     * Hashing stub for the execution-lease tests: every item named as a gate blocks the worker inside
     * {@code hash()} on its own latches until {@link #unblock(String)} releases it, so a job can be
     * stalled across clock advances. A gated item optionally throws after release (a stale worker
     * whose failure must not overwrite a reaped snapshot); every other item delegates to real hashing.
     */
    private static final class LeaseCryptoService extends CryptoService {

        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final Set<String> failAfterRelease = ConcurrentHashMap.newKeySet();

        private LeaseCryptoService(String... gatedItems) {
            for (String item : gatedItems) {
                gates.put(item, new Gate());
            }
        }

        private void failAfterRelease(String item) {
            failAfterRelease.add(item);
            gates.computeIfAbsent(item, key -> new Gate());
        }

        private boolean awaitEntered(String item) throws InterruptedException {
            Gate gate = gates.get(item);
            return gate != null && gate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void unblock(String item) {
            Gate gate = gates.get(item);
            if (gate != null) {
                gate.released.countDown();
            }
        }

        private int count(String item) {
            AtomicInteger counter = invocations.get(item);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public HashResponse hash(HashRequest request) {
            invocations.computeIfAbsent(request.content(), key -> new AtomicInteger()).incrementAndGet();
            Gate gate = gates.get(request.content());
            if (gate != null) {
                gate.entered.countDown();
                try {
                    gate.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (failAfterRelease.contains(request.content())) {
                    throw new IllegalStateException("stale worker failure for " + request.content());
                }
            }
            return super.hash(request);
        }

        private static final class Gate {
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per-attempt BATCH_HASH audit journaling (REQ-01..REQ-06)
    // ---------------------------------------------------------------------------------------------

    /**
     * DOD-01 / REQ-02: two consecutive jobs on a single reused pool thread open BATCH_HASH entries
     * whose {@code requestId} matches their own submit call; each attempt scrubs the context in a
     * {@code finally} block, so a later job never inherits the previous submitter's id.
     */
    @Test
    @DisplayName("audit - one worker, successive jobs: each attempt sees its own request id and the context is cleared")
    void shouldCorrelateEachAttemptWithItsOwnRequestId() throws Exception {
        AuditJournalService journal = newJournal();
        RequestRecordingCryptoService crypto = new RequestRecordingCryptoService();
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);

        RequestContext.set("req-submit-aaaa1");
        BatchHashJobResponse first = service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null));
        BatchHashJobResponse firstDone = awaitStatus(first.jobId(), JobStatus.COMPLETED);

        RequestContext.set("req-submit-bbbb2");
        BatchHashJobResponse second = service.submit(
                new BatchHashRequest("SHA-256", List.of("beta"), null, null));
        BatchHashJobResponse secondDone = awaitStatus(second.jobId(), JobStatus.COMPLETED);

        // No caller id at all: the third attempt must observe an empty context, proving the pool
        // thread was scrubbed by the previous attempt's finally block.
        RequestContext.clear();
        BatchHashJobResponse third = service.submit(
                new BatchHashRequest("SHA-256", List.of("gamma"), null, null));
        BatchHashJobResponse thirdDone = awaitStatus(third.jobId(), JobStatus.COMPLETED);

        assertThat(crypto.idsObservedFor("alpha")).containsExactly("req-submit-aaaa1");
        assertThat(crypto.idsObservedFor("beta")).containsExactly("req-submit-bbbb2");
        assertThat(crypto.idsObservedFor("gamma")).hasSize(1).containsNull();

        List<AuditEntry> firstEntries = batchHashEntries(journal, first.jobId());
        List<AuditEntry> secondEntries = batchHashEntries(journal, second.jobId());
        assertThat(firstEntries).hasSize(1);
        assertThat(secondEntries).hasSize(1);
        assertThat(firstEntries.get(0).requestId()).isEqualTo("req-submit-aaaa1");
        assertThat(secondEntries.get(0).requestId()).isEqualTo("req-submit-bbbb2");
        assertThat(firstDone.attempts().get(0).auditEntryId()).isEqualTo(firstEntries.get(0).id());
        assertThat(secondDone.attempts().get(0).auditEntryId()).isEqualTo(secondEntries.get(0).id());
        assertThat(thirdDone.attempts().get(0).auditEntryId()).isNotNull();
    }

    /** DOD-02 / REQ-03: a successful attempt leaves exactly one SUCCEEDED BATCH_HASH entry, with a payload-free descriptor. */
    @Test
    @DisplayName("audit - a successful attempt has exactly one SUCCEEDED BATCH_HASH entry without payload")
    void shouldJournalOneSucceededEntryPerAttempt() throws Exception {
        AuditJournalService journal = newJournal();
        service = new BatchHashJobService(new CryptoService(), 2, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);

        RequestContext.set("req-succeed01");
        BatchHashJobResponse done = awaitStatus(service.submit(new BatchHashRequest("SHA-256",
                List.of("alpha", "beta"), "s3cr3t-key-value", "idem-key-1")).jobId(), JobStatus.COMPLETED);
        RequestContext.clear();

        List<AuditEntry> entries = batchHashEntries(journal, done.jobId());
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.requestId()).isEqualTo("req-succeed01");
        assertThat(entry.request().path("attempt").asInt()).isEqualTo(1);
        assertThat(entry.request().path("algorithm").asText()).isEqualTo("SHA-256");
        assertThat(entry.request().path("itemCount").asInt()).isEqualTo(2);
        assertThat(entry.request().path("idempotencyKey").asText()).isEqualTo("idem-key-1");
        assertThat(entry.request().toString()).doesNotContain("alpha").doesNotContain("s3cr3t-key-value");
        assertThat(entry.result()).isNotNull();
        assertThat(done.attempts()).hasSize(1);
        assertThat(done.attempts().get(0).auditEntryId()).isEqualTo(entry.id());
        assertThat(journal.list(200, JournalState.PENDING)).isEmpty();
    }

    /** DOD-03 / REQ-04: a hashing failure closes the entry as FAILED with a bounded code and leaves no PENDING sibling. */
    @Test
    @DisplayName("audit - a failing attempt has exactly one FAILED BATCH_HASH entry with a bounded code")
    void shouldJournalFailedEntryWithBoundedCode() throws Exception {
        AuditJournalService journal = newJournal();
        RetryCryptoService crypto = new RetryCryptoService("boom", Integer.MAX_VALUE, null, null, null);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);

        RequestContext.set("req-failure01");
        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null)).jobId(), JobStatus.FAILED);
        RequestContext.clear();

        List<AuditEntry> entries = batchHashEntries(journal, failed.jobId());
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.FAILED);
        assertThat(entry.errorCode()).isNotBlank();
        assertThat(entry.errorCode().length()).isLessThanOrEqualTo(200);
        assertThat(entry.requestId()).isEqualTo("req-failure01");
        assertThat(failed.attempts()).hasSize(1);
        assertThat(failed.attempts().get(0).auditEntryId()).isEqualTo(entry.id());
        assertThat(batchHashEntries(journal, failed.jobId(), JournalState.PENDING)).isEmpty();
    }

    /**
     * DOD-04 / REQ-05: when a stalled RUNNING attempt is reaped, the job and its BATCH_HASH entry
     * both read FAILED with EXECUTION_LEASE_EXPIRED and the attempt history exposes that entry id;
     * a second reaper pass writes nothing.
     */
    @Test
    @DisplayName("audit - the reaper and the journal agree on EXECUTION_LEASE_EXPIRED")
    void shouldAgreeWithReaperOnLeaseExpiry() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock, journal);

        RequestContext.set("req-reaper001");
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        RequestContext.clear();

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse reaped = service.getJob(accepted.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);

        List<AuditEntry> reapedEntries = batchHashEntries(journal, accepted.jobId());
        assertThat(reapedEntries).hasSize(1);
        AuditEntry reapedEntry = reapedEntries.get(0);
        assertThat(reapedEntry.state()).isEqualTo(JournalState.FAILED);
        assertThat(reapedEntry.errorCode()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(reapedEntry.requestId()).isEqualTo("req-reaper001");
        assertThat(reaped.attempts()).hasSize(1);
        assertThat(reaped.attempts().get(0).auditEntryId()).isEqualTo(reapedEntry.id());

        // A second pass reaps and journals nothing: the FAILED fence already closed this attempt.
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
    }

    /**
     * DOD-05 / REQ-05: a retry after a reaped attempt opens a separate BATCH_HASH entry correlated
     * to the retry caller's request id, and the late worker from the reaped attempt cannot rewrite
     * the entry the reaper closed.
     */
    @Test
    @DisplayName("audit - the retried attempt journals its own entry under the retry caller's id")
    void shouldCorrelateRetryAttemptWithRetryRequest() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock, journal);

        RequestContext.set("req-reaper001");
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        RequestContext.clear();

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        AuditEntry reapedEntry = batchHashEntries(journal, accepted.jobId()).get(0);
        assertThat(reapedEntry.state()).isEqualTo(JournalState.FAILED);
        assertThat(reapedEntry.errorCode()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);

        // The retry is its own audited request: attempt 2 is correlated to *this* caller's id.
        RequestContext.set("req-retry0002");
        BatchHashJobResponse retried = service.retry(accepted.jobId());
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(retried.attempt()).isEqualTo(2);
        RequestContext.clear();

        // Releasing the stalled worker lets the late attempt-1 thread finish; it is fenced by the
        // generation check and must not touch the reaped entry, and attempt 2 then runs to completion.
        crypto.unblock("stalled");
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);

        List<AuditEntry> allEntries = batchHashEntries(journal, accepted.jobId());
        assertThat(allEntries).hasSize(2); // the retry opened a second entry; the reaped one survived
        AuditEntry reapedAfterLateWorker = journal.find(reapedEntry.id()).orElseThrow();
        assertThat(reapedAfterLateWorker.state()).isEqualTo(JournalState.FAILED);
        assertThat(reapedAfterLateWorker.errorCode()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(reapedAfterLateWorker.requestId()).isEqualTo("req-reaper001");
        AuditEntry retryEntry = allEntries.stream()
                .filter(entry -> !entry.id().equals(reapedEntry.id()))
                .findFirst()
                .orElseThrow();
        assertThat(retryEntry.requestId()).isEqualTo("req-retry0002");
        assertThat(retryEntry.request().path("attempt").asInt()).isEqualTo(2);
        assertThat(retryEntry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.attempts().get(0).auditEntryId()).isEqualTo(reapedEntry.id());
        assertThat(completed.attempts().get(1).auditEntryId()).isEqualTo(retryEntry.id());
    }

    /**
     * DOD-06 / REQ-03: a saturated-queue rejection and a retry refused by the exhausted attempt
     * budget each create no job row and no BATCH_HASH audit entry.
     */
    @Test
    @DisplayName("audit - rejected submissions and retries open no BATCH_HASH entries")
    void shouldNotCreateAuditEntryForRejectedSubmissionOrRetry() throws Exception {
        // A saturated-queue rejection registers no job row and opens no journal entry.
        AuditJournalService journal = newJournal();
        LeaseCryptoService crypto = new LeaseCryptoService("blocker");
        service = new BatchHashJobService(crypto, 1, 1, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);

        RequestContext.set("req-reject001");
        service.submit(new BatchHashRequest("SHA-256", List.of("blocker"), null, null));
        assertThat(crypto.awaitEntered("blocker")).isTrue(); // the only worker is occupied
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("queued"), null, null));
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED); // ...and the single queue slot is taken

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("overflow"), null, null)))
                .isInstanceOf(JobQueueFullException.class);
        RequestContext.clear();

        assertThat(registryKeys("jobs")).hasSize(2); // the rejected submission registered nothing
        assertThat(journal.list(200, null))
                .filteredOn(entry -> entry.operation() == OperationType.BATCH_HASH)
                .hasSize(1); // only the running attempt opened an entry

        // A retry refused by the exhausted attempt budget writes no entry and leaves the failed
        // attempt's single entry untouched.
        AuditJournalService retryJournal = newJournal();
        RetryCryptoService failingCrypto = new RetryCryptoService("boom", Integer.MAX_VALUE, null, null, null);
        service = new BatchHashJobService(failingCrypto, 1, 10, 300, 1, LEASE_SECONDS, 5000,
                Clock.systemUTC(), retryJournal);

        RequestContext.set("req-budget001");
        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null)).jobId(), JobStatus.FAILED);

        assertThatThrownBy(() -> service.retry(failed.jobId()))
                .isInstanceOf(JobRetryConflictException.class);
        RequestContext.clear();

        assertThat(batchHashEntries(retryJournal, failed.jobId())).hasSize(1);
        assertThat(service.getJob(failed.jobId()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(service.getJob(failed.jobId()).attempt()).isEqualTo(1);
    }

    /**
     * DOD-08 / REQ-03: the attempt history exposes the entry id of the BATCH_HASH audit entry, and
     * the 5-arg backward-compatible JobAttempt constructor still compiles and yields a null
     * {@code auditEntryId} that is omitted from the snapshot JSON.
     */
    @Test
    @DisplayName("audit - the attempt history exposes the entry id, and the legacy constructor yields null")
    void shouldExposeAuditEntryIdOnAttemptHistory() throws Exception {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        Instant finished = Instant.parse("2026-01-01T00:00:01Z");

        // Pre-journalling call sites keep compiling: the 5-arg constructor yields a null entry id.
        JobAttempt legacy = new JobAttempt(1, JobStatus.COMPLETED, started, finished, null);
        assertThat(legacy.auditEntryId()).isNull();

        // With a journal wired, the started attempt's history entry carries the entry's id.
        AuditJournalService journal = newJournal();
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);
        RequestContext.set("req-history01");
        BatchHashJobResponse done = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null)).jobId(), JobStatus.COMPLETED);
        RequestContext.clear();
        AuditEntry entry = batchHashEntries(journal, done.jobId()).get(0);
        assertThat(done.attempts().get(0).auditEntryId()).isEqualTo(entry.id());

        // Without a journal the field stays null and the snapshot omits it entirely.
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC());
        BatchHashJobResponse unjournalled = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("beta"), null, null)).jobId(), JobStatus.COMPLETED);
        assertThat(unjournalled.attempts().get(0).auditEntryId()).isNull();
    }

    /**
     * DOD-11 / REQ-03: the bounded descriptor carries the job id, attempt, algorithm, item count
     * and truncated idempotency key - never the item contents or the secret key.
     */
    @Test
    @DisplayName("audit - items and secretKey never reach the BATCH_HASH descriptor")
    void shouldNotLeakItemsOrSecretKeyIntoAuditEntry() throws Exception {
        AuditJournalService journal = newJournal();
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);

        String longKey = "idem".repeat(75); // 300 chars: exceeds the 200-char audit bound
        RequestContext.set("req-noleak01");
        BatchHashJobResponse done = awaitStatus(service.submit(new BatchHashRequest("SHA-256",
                List.of("alpha", "bravo", "charlie"), "s3cr3t-key-value", longKey)).jobId(),
                JobStatus.COMPLETED);
        RequestContext.clear();

        AuditEntry entry = batchHashEntries(journal, done.jobId()).get(0);
        assertThat(entry.request().path("jobId").asText()).isEqualTo(done.jobId());
        assertThat(entry.request().path("attempt").asInt()).isEqualTo(1);
        assertThat(entry.request().path("algorithm").asText()).isEqualTo("SHA-256");
        assertThat(entry.request().path("itemCount").asInt()).isEqualTo(3);
        assertThat(entry.request().path("idempotencyKey").asText()).isEqualTo(longKey.substring(0, 200));
        String requestTree = entry.request().toString();
        assertThat(requestTree)
                .doesNotContain("alpha")
                .doesNotContain("bravo")
                .doesNotContain("charlie")
                .doesNotContain("s3cr3t-key-value");
    }

    /**
     * DOD-07 / REQ-04: journal begin/succeed/fail faults never change job status or result, and a
     * failed {@code begin} simply leaves the attempt unjournalled.
     */
    @Test
    @DisplayName("audit - journal faults are isolated from job status, result and error text")
    void shouldKeepJobStatusWhenJournalFails() throws Exception {
        // begin() throws: the attempt still runs and completes, it just has no entry id.
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), new FailingJournal(true));
        BatchHashJobResponse noEntry = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null)).jobId(), JobStatus.COMPLETED);
        assertThat(noEntry.results()).hasSize(1);
        assertThat(noEntry.attempts().get(0).auditEntryId()).isNull();

        // succeed()/fail() throw: the terminal snapshot is written regardless of the journal fault.
        AuditJournalService closedFaults = new FailingJournal(false);
        RetryCryptoService crypto = new RetryCryptoService("boom", Integer.MAX_VALUE, null, null, null);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), closedFaults);

        BatchHashJobResponse succeeded = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null)).jobId(), JobStatus.COMPLETED);
        assertThat(succeeded.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(succeeded.results()).hasSize(1);
        assertThat(succeeded.attempts().get(0).auditEntryId()).isNotNull(); // begin() worked
        assertThat(batchHashEntries(closedFaults, succeeded.jobId()).get(0).state())
                .isEqualTo(JournalState.PENDING); // succeed() threw, so the entry stays open

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null)).jobId(), JobStatus.FAILED);
        assertThat(failed.error()).contains("boom");
        assertThat(failed.results()).isNull();
        assertThat(batchHashEntries(closedFaults, failed.jobId()).get(0).state())
                .isEqualTo(JournalState.PENDING); // fail() threw, so the entry stays open
    }

    private static AuditJournalService newJournal() {
        return new AuditJournalService(new ObjectMapper(), 200, 65536, 300L);
    }

    /** BATCH_HASH entries recorded for one job, newest first (the journal itself is newest-first). */
    private static List<AuditEntry> batchHashEntries(AuditJournalService journal, String jobId) {
        return batchHashEntries(journal, jobId, null);
    }

    private static List<AuditEntry> batchHashEntries(AuditJournalService journal, String jobId,
            JournalState state) {
        return journal.list(500, state).stream()
                .filter(entry -> entry.operation() == OperationType.BATCH_HASH)
                .filter(entry -> entry.request() != null
                        && jobId.equals(entry.request().path("jobId").asText()))
                .toList();
    }

    /** Records, per item, which request id the pool thread carried while hashing it. */
    private static final class RequestRecordingCryptoService extends CryptoService {

        private final Map<String, List<String>> observed = new ConcurrentHashMap<>();

        @Override
        public HashResponse hash(HashRequest request) {
            observed.computeIfAbsent(request.content(),
                            key -> Collections.synchronizedList(new ArrayList<>()))
                    .add(RequestContext.get());
            return super.hash(request);
        }

        private List<String> idsObservedFor(String item) {
            List<String> ids = observed.get(item);
            return ids == null ? List.of() : new ArrayList<>(ids);
        }
    }

    /**
     * Journal stub for the fault-isolation test: optionally throws from {@code begin}, and always
     * throws from {@code succeed}/{@code fail} once an entry exists.
     */
    private static final class FailingJournal extends AuditJournalService {

        private final boolean failBegin;

        private FailingJournal(boolean failBegin) {
            super(new ObjectMapper(), 200, 65536, 300L);
            this.failBegin = failBegin;
        }

        @Override
        public AuditEntry begin(OperationType operation, Object request, String requestId) {
            if (failBegin) {
                throw new IllegalStateException("journal begin unavailable");
            }
            return super.begin(operation, request, requestId);
        }

        @Override
        public AuditEntry succeed(String id, Object result) {
            throw new IllegalStateException("journal succeed unavailable");
        }

        @Override
        public AuditEntry fail(String id, String errorCode) {
            throw new IllegalStateException("journal fail unavailable");
        }
    }
}
