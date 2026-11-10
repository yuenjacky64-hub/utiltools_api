package com.example.utiltools.jobs;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.ratelimit.ClientKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // ---------------------------------------------------------------------------------------------
    // Per-client fair admission (UTILTOOLS-65): round-robin lanes, rollback at saturation,
    // cancel/drain slot release, and retry re-queue without lane bypass.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("fair admission - with another client queued, its job dispatches before the busy client's next")
    void shouldPreferOtherClientWhenAllLanesHaveWork() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("a1", "a2", "b");
        service = new BatchHashJobService(crypto, 2, 8, 300, 3);

        // Both workers park inside client alice's first two jobs.
        BatchHashJobResponse a1 = service.submit(new BatchHashRequest("SHA-256", List.of("a1"), null, null), "alice");
        assertThat(crypto.awaitEntered("a1")).isTrue();
        BatchHashJobResponse a2 = service.submit(new BatchHashRequest("SHA-256", List.of("a2"), null, null), "alice");
        assertThat(crypto.awaitEntered("a2")).isTrue();

        // Three more of alice's jobs sit behind her lane, plus one queued for bob.
        BatchHashJobResponse a3 = service.submit(new BatchHashRequest("SHA-256", List.of("a3"), null, null), "alice");
        BatchHashJobResponse a4 = service.submit(new BatchHashRequest("SHA-256", List.of("a4"), null, null), "alice");
        BatchHashJobResponse a5 = service.submit(new BatchHashRequest("SHA-256", List.of("a5"), null, null), "alice");
        BatchHashJobResponse b1 = service.submit(new BatchHashRequest("SHA-256", List.of("b"), null, null), "bob");

        // Freeing one worker must admit bob's job before any of alice's queued jobs.
        crypto.unblock("a1");
        assertThat(crypto.awaitEntered("b")).isTrue();
        assertThat(crypto.entriesBefore("b")).containsExactly("a1", "a2");
        crypto.unblock("a2");
        crypto.unblock("b");

        for (BatchHashJobResponse job : List.of(a1, a2, a3, a4, a5, b1)) {
            assertThat(awaitStatus(job.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        }
        assertThat(service.queuedJobsForClient("alice")).isZero();
        assertThat(service.queuedJobsForClient("bob")).isZero();
    }

    @Test
    @DisplayName("fair admission - three queued clients interleave instead of draining one lane")
    void shouldInterleaveThreeClientsRoundRobin() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("a1");
        service = new BatchHashJobService(crypto, 1, 5, 300, 3);

        // The single worker parks inside alice's job; one queued job per other client, one more for alice.
        BatchHashJobResponse a1 = service.submit(new BatchHashRequest("SHA-256", List.of("a1"), null, null), "alice");
        assertThat(crypto.awaitEntered("a1")).isTrue();
        BatchHashJobResponse b1 = service.submit(new BatchHashRequest("SHA-256", List.of("b"), null, null), "bob");
        BatchHashJobResponse c1 = service.submit(new BatchHashRequest("SHA-256", List.of("c"), null, null), "carol");
        BatchHashJobResponse a2 = service.submit(new BatchHashRequest("SHA-256", List.of("a2"), null, null), "alice");

        crypto.unblock("a1");
        awaitStatus(a1.jobId(), JobStatus.COMPLETED);
        awaitStatus(a2.jobId(), JobStatus.COMPLETED);

        // a2's hash may have entered only after both b and c had: no lane was drained before the others.
        assertThat(crypto.entriesBefore("a2")).containsExactlyInAnyOrder("a1", "b", "c");
        assertThat(awaitStatus(b1.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        assertThat(awaitStatus(c1.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        assertThat(crypto.count("a2")).isEqualTo(1);
    }

    @Test
    @DisplayName("fair admission - a client with a long service history still gets its turn before a deeper lane")
    void shouldNotLetADeeperLaneJumpAheadOfAPreviouslyServedClient() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("a4", "a5");
        service = new BatchHashJobService(crypto, 1, 8, 300, 4);

        // alice is served three times on her own before bob exists at all: any accumulated
        // per-client service counter therefore diverges sharply in alice's disfavour.
        for (String item : List.of("a1", "a2", "a3")) {
            BatchHashJobResponse historical = service.submit(new BatchHashRequest("SHA-256", List.of(item), null, null), "alice");
            awaitStatus(historical.jobId(), JobStatus.COMPLETED);
        }

        // Now park the single worker inside a fourth alice job and queue a shallow alice lane
        // (one more job) behind a deeper bob lane (three jobs).
        BatchHashJobResponse a4 = service.submit(new BatchHashRequest("SHA-256", List.of("a4"), null, null), "alice");
        assertThat(crypto.awaitEntered("a4")).isTrue();
        BatchHashJobResponse a5 = service.submit(new BatchHashRequest("SHA-256", List.of("a5"), null, null), "alice");
        List<BatchHashJobResponse> bobJobs = new ArrayList<>();
        for (String item : List.of("b1", "b2", "b3")) {
            bobJobs.add(service.submit(new BatchHashRequest("SHA-256", List.of(item), null, null), "bob"));
        }
        assertThat(service.queuedJobsForClient("bob")).isEqualTo(3);

        crypto.unblock("a4");
        awaitStatus(a4.jobId(), JobStatus.COMPLETED);

        // Round barrier: bob is served once in the new round and then alice's turn comes back, so
        // bob's deeper lane cannot consume her turn even though her service history is longer.
        assertThat(crypto.awaitEntered("a5")).isTrue();
        assertThat(crypto.entriesBefore("a5")).containsExactly("a1", "a2", "a3", "a4", "b1");
        crypto.unblock("a5");
        awaitStatus(a5.jobId(), JobStatus.COMPLETED);
        for (BatchHashJobResponse bobJob : bobJobs) {
            awaitStatus(bobJob.jobId(), JobStatus.COMPLETED);
        }
        assertThat(service.queuedJobsForClient("alice")).isZero();
        assertThat(service.queuedJobsForClient("bob")).isZero();
    }

    @Test
    @DisplayName("fair admission - a submission over global capacity is rejected and rolled back with no residue")
    void shouldRejectAndRollbackCleanlyWhenGlobalCapacityFullAcrossClients() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("a", "b", "c");
        service = new BatchHashJobService(crypto, 1, 2, 300, 3); // capacity = 1 worker + 2 queue slots

        BatchHashJobResponse a = service.submit(new BatchHashRequest("SHA-256", List.of("a"), null, null), "alice");
        BatchHashJobResponse b = service.submit(new BatchHashRequest("SHA-256", List.of("b"), null, null), "bob");
        BatchHashJobResponse c = service.submit(new BatchHashRequest("SHA-256", List.of("c"), null, null), "carol");
        assertThat(crypto.awaitEntered("a")).isTrue(); // a owns the worker; b and c hold the queue slots

        Set<String> registeredBefore = registryKeys("jobs");
        assertThat(registeredBefore).containsExactlyInAnyOrder(a.jobId(), b.jobId(), c.jobId());
        int admittedBefore = service.reservedCapacityForTests(); // 1 running + 2 queued = full

        // A fourth submission from a new client exceeds worker + queue capacity: rejected, rolled back.
        assertThatThrownBy(() -> service.submit(new BatchHashRequest("SHA-256", List.of("d"), null, "dave-key"), "dave"))
                .isInstanceOf(JobQueueFullException.class);
        assertThat(registryKeys("jobs")).isEqualTo(registeredBefore);
        assertThat(registryKeys("idempotencyIndex")).doesNotContain("dave-key");
        assertThat(service.reservedCapacityForTests())
                .as("the rejected submission released its global capacity unit")
                .isEqualTo(admittedBefore);
        assertThat(service.activeJobsForClient("dave")).isZero();
        assertThat(service.queuedJobsForClient("dave")).isZero();

        // After the drain the rejected key is reusable as a brand-new job.
        crypto.unblock("a");
        crypto.unblock("b");
        crypto.unblock("c");
        awaitStatus(a.jobId(), JobStatus.COMPLETED);
        awaitStatus(b.jobId(), JobStatus.COMPLETED);
        awaitStatus(c.jobId(), JobStatus.COMPLETED);

        BatchHashJobResponse d = service.submit(new BatchHashRequest("SHA-256", List.of("d"), null, "dave-key"), "dave");
        assertThat(d.jobId()).isNotIn(a.jobId(), b.jobId(), c.jobId());
        assertThat(awaitStatus(d.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
    }

    @Test
    @DisplayName("fair admission - cancelling a queued job releases its slot exactly once and it never runs")
    void shouldReleaseSlotOnceAndNeverRunAJobRemovedFromItsLane() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("a");
        service = new BatchHashJobService(crypto, 1, 5, 300, 3);

        // The worker parks inside alice's first job; two more of her jobs queue behind the lane.
        BatchHashJobResponse a1 = service.submit(new BatchHashRequest("SHA-256", List.of("a"), null, null), "alice");
        assertThat(crypto.awaitEntered("a")).isTrue();
        BatchHashJobResponse a2 = service.submit(new BatchHashRequest("SHA-256", List.of("a2"), null, null), "alice");
        BatchHashJobResponse a3 = service.submit(new BatchHashRequest("SHA-256", List.of("a3"), null, null), "alice");
        assertThat(service.activeJobsForClient("alice")).isEqualTo(3);
        assertThat(service.queuedJobsForClient("alice")).isEqualTo(2);

        BatchHashJobResponse cancelled = service.cancel(a2.jobId());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        // One release, no double: the running and the remaining queued job still hold their slots.
        assertThat(service.activeJobsForClient("alice")).isEqualTo(2);

        crypto.unblock("a");
        awaitStatus(a1.jobId(), JobStatus.COMPLETED);
        assertThat(awaitStatus(a3.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        assertThat(crypto.count("a2")).isZero(); // the cancelled job never entered the worker
        assertThat(service.activeJobsForClient("alice")).isZero();
        assertThat(service.queuedJobsForClient("alice")).isZero();
    }

    @Test
    @DisplayName("fair admission - a retry re-enters its own lane without jumping other clients' queues")
    void shouldRequeueRetryIntoOwnClientLaneWithoutBypassingOthers() throws Exception {
        OrderingCryptoService crypto = new OrderingCryptoService("blocked", "c");
        crypto.failOnce("flaky", 1);
        service = new BatchHashJobService(crypto, 1, 5, 300, 3);

        // bob's gated job owns the only worker; alice's flaky job and carol's gated job queue in their lanes.
        BatchHashJobResponse b1 = service.submit(new BatchHashRequest("SHA-256", List.of("blocked"), null, null), "bob");
        assertThat(crypto.awaitEntered("blocked")).isTrue();
        BatchHashJobResponse a1 = service.submit(new BatchHashRequest("SHA-256", List.of("flaky"), null, null), "alice");
        BatchHashJobResponse c1 = service.submit(new BatchHashRequest("SHA-256", List.of("c"), null, null), "carol");

        crypto.unblock("blocked");
        awaitStatus(b1.jobId(), JobStatus.COMPLETED);
        // flaky's first attempt ran and failed the job; carol's c is now parked on its gate.
        BatchHashJobResponse failed = awaitStatus(a1.jobId(), JobStatus.FAILED);
        assertThat(failed.error()).contains("transient");
        assertThat(crypto.awaitEntered("c")).isTrue();

        // The explicit retry re-enters alice's lane behind carol's queued work, not ahead of it.
        BatchHashJobResponse requeued = service.retry(a1.jobId());
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(crypto.count("flaky")).isEqualTo(1); // the retried generation has not run yet
        assertThat(crypto.entriesBefore("c")).containsExactly("blocked", "flaky");

        crypto.unblock("c");
        awaitStatus(c1.jobId(), JobStatus.COMPLETED);
        BatchHashJobResponse completed = awaitStatus(a1.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(crypto.count("flaky")).isEqualTo(2);
        assertThat(crypto.count("c")).isEqualTo(1);
        assertThat(service.queuedJobsForClient("alice")).isZero();
        assertThat(service.queuedJobsForClient("carol")).isZero();
    }

    @Test
    @DisplayName("worker - a Throwable marks the job FAILED and leaves the pool usable")
    void shouldMarkJobFailedWhenWorkerThrowsAndKeepPoolUsable() {
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3);

        BatchHashJobResponse submitted =
                service.submit(new BatchHashRequest("NOPE-256", List.of("payload"), null, null));
        BatchHashJobResponse failed = awaitStatus(submitted.jobId(), JobStatus.FAILED);

        // INV-PI-6: the job-level code is exactly BATCH_ITEM_HASH_FAILED; the cause rides the per-item
        // tracking instead of being appended to the code clients switch on.
        assertThat(failed.error()).isEqualTo("BATCH_ITEM_HASH_FAILED");
        assertThat(failed.failedItems()).containsExactly(0);
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

    /**
     * DOD-01 / REQ-02 / REQ-03: cancelling a RUNNING job records a cooperative request instead of
     * failing it. The snapshot turns CANCELLING while the worker is still inside {@code hash}, the
     * client keeps its quota slot, a repeat DELETE returns the same snapshot without writing, and the
     * worker terminalizes CANCELLED exactly once at its next checkpoint - no results, no prefix, one
     * audit close carrying the CANCELLED outcome and one quota release.
     */
    @Test
    @DisplayName("cancel - a RUNNING job turns CANCELLING and its worker finalizes CANCELLED at the checkpoint")
    void shouldMoveRunningJobToCancellingThenCancelled() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        String client = "198.51.100.31";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock, journal);
        assertThat(service.activeJobsForClient(client)).isZero(); // the pre-submit count

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocked", "later"), null, null), client);
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);

        BatchHashJobResponse cancelling = service.cancel(accepted.jobId());
        assertThat(cancelling.status()).isEqualTo(JobStatus.CANCELLING);
        assertThat(cancelling.error()).isNull();
        assertThat(cancelling.results()).isNull();
        assertThat(cancelling.completedItems()).isZero();
        assertThat(cancelling.completedAt()).isNull();
        // Only RUNNING projects lease state, so the snapshot hides it - but the request released
        // nothing: a reaper pass one second inside the lease finds no expired execution, and the
        // client's quota slot still belongs to the in-flight worker.
        assertThat(cancelling.executionLeaseExpiresAt()).isNull();
        assertThat(cancelling.lastHeartbeatAt()).isNull();
        clock.advanceSeconds(LEASE_SECONDS - 1);
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        // CANCELLING is non-terminal for listing: filterable as itself, never as CANCELLED yet.
        assertThat(service.listJobs(JobStatus.CANCELLING, 10))
                .extracting(BatchHashJobResponse::jobId)
                .containsExactly(accepted.jobId());
        assertThat(service.listJobs(JobStatus.CANCELLED, 10)).isEmpty();
        // A repeat DELETE is idempotent and writes nothing.
        assertThat(service.cancel(accepted.jobId())).isEqualTo(cancelling);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
        assertThat(batchHashEntries(journal, accepted.jobId()).get(0).state())
                .isEqualTo(JournalState.PENDING);

        releaseHash.countDown();
        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(cancelled.error()).isNull();
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.completedItems()).isZero();
        assertThat(cancelled.completedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.executionLeaseExpiresAt()).isNull();
        assertThat(cancelled.lastHeartbeatAt()).isNull();
        assertThat(cancelled.attempts()).hasSize(1);
        assertThat(cancelled.attempts().get(0).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.attempts().get(0).completedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.attempts().get(0).error()).isNull();
        // The batch stopped at the checkpoint: the next item was never hashed.
        assertThat(crypto.count("later")).isZero();
        // Exactly one quota release, so a third DELETE still answers the same snapshot.
        awaitActiveJobs(client, 0);
        assertThat(service.cancel(accepted.jobId())).isEqualTo(cancelled);
        assertThat(service.getJob(accepted.jobId())).isEqualTo(cancelled);
        // Exactly one audit close, journalled as the cancelled outcome.
        List<AuditEntry> entries = batchHashEntries(journal, accepted.jobId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entries.get(0).result().path("outcome").asText()).isEqualTo("CANCELLED");
    }

    /**
     * DOD-03 / REQ-02: a repeated DELETE on a CANCELLING job returns the current snapshot and writes
     * nothing - the second request neither bumps an attempt nor closes the audit entry - and once the
     * worker terminalizes the job a third DELETE returns that CANCELLED snapshot unchanged.
     */
    @Test
    @DisplayName("cancel - repeated DELETE on a CANCELLING job is idempotent and writes nothing")
    void shouldRepeatCancelOnCancellingIdempotently() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        CountDownLatch enteredHash = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountingCryptoService crypto = new CountingCryptoService(enteredHash, releaseHash, "blocked");
        String client = "198.51.100.35";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocked", "later"), null, null), client);
        assertThat(enteredHash.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);

        BatchHashJobResponse first = service.cancel(accepted.jobId());
        BatchHashJobResponse second = service.cancel(accepted.jobId());
        assertThat(first.status()).isEqualTo(JobStatus.CANCELLING);
        assertThat(second.status()).isEqualTo(JobStatus.CANCELLING);
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(second.completedAt()).isNull();
        assertThat(second).isEqualTo(first); // identical snapshot, nothing written
        assertThat(service.getJob(accepted.jobId())).isEqualTo(first);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1); // the slot is still claimed
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
        assertThat(batchHashEntries(journal, accepted.jobId()).get(0).state())
                .isEqualTo(JournalState.PENDING);

        releaseHash.countDown();
        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        BatchHashJobResponse third = service.cancel(accepted.jobId());
        assertThat(third.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(third.jobId()).isEqualTo(cancelled.jobId());
        assertThat(third.completedAt()).isEqualTo(cancelled.completedAt());
        assertThat(third).isEqualTo(cancelled);
        // REQ-08: the worker releases the quota after the monitor block that wrote CANCELLED, so the
        // counter may still read 1 when awaitStatus returns - wait for the release, then assert it.
        awaitActiveJobs(client, 0);
        assertThat(service.activeJobsForClient(client)).isZero(); // exactly one release

        // The repeats added no attempt and no second audit close.
        assertThat(service.getJob(accepted.jobId()).attempts()).hasSize(1);
        List<AuditEntry> entries = batchHashEntries(journal, accepted.jobId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entries.get(0).result().path("outcome").asText()).isEqualTo("CANCELLED");
    }

    /**
     * DOD-02 / REQ-03 / REQ-04: a CANCELLING job whose execution lease lapses is terminalized
     * CANCELLED by the reaper - once, with one attempt-history entry, one audit close and one quota
     * release - and the worker that ignored the lapse writes nothing when it finally returns.
     */
    @Test
    @DisplayName("reap - a CANCELLING job with a lapsed lease is finalized CANCELLED once and ignores the late worker")
    void shouldReapCancellingJobWithLapsedLease() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stall");
        String client = "198.51.100.32";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stall", "tail"), null, null), client);
        assertThat(crypto.awaitEntered("stall")).isTrue();
        assertThat(service.cancel(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);

        // A live lease and a non-terminal status: neither eviction nor the reaper touches it.
        assertThat(service.evictExpiredJobs(clock.instant().plusSeconds(86_400))).isZero();
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

        BatchHashJobResponse cancelled = service.getJob(accepted.jobId());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.error()).isNull();
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.completedItems()).isZero();
        assertThat(cancelled.completedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.executionLeaseExpiresAt()).isNull();
        assertThat(cancelled.lastHeartbeatAt()).isNull();
        assertThat(cancelled.attempt()).isEqualTo(1); // the fence's other half is untouched
        assertThat(cancelled.attempts()).hasSize(1);
        assertThat(cancelled.attempts().get(0).status()).isEqualTo(JobStatus.CANCELLED);
        List<AuditEntry> entries = batchHashEntries(journal, accepted.jobId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entries.get(0).result().path("outcome").asText()).isEqualTo("CANCELLED");
        awaitActiveJobs(client, 0);

        // The late worker returns after the terminal write and changes nothing.
        crypto.unblock("stall");
        BatchHashJobResponse marker = service.submit(
                new BatchHashRequest("SHA-256", List.of("marker"), null, null), client);
        assertThat(awaitStatus(marker.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        assertThat(service.getJob(accepted.jobId())).isEqualTo(cancelled);
        assertThat(crypto.count("tail")).isZero();
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-03 / REQ-04: CANCELLING is not retryable and not renewable - both answer 409 with that very
     * status and leave the snapshot, the quota and the audit trail untouched.
     */
    @Test
    @DisplayName("cancelling - heartbeat and retry are refused with the CANCELLING status and write nothing")
    void shouldRejectHeartbeatAndRetryOnCancellingJob() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stall");
        String client = "198.51.100.33";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stall"), null, null), client);
        assertThat(crypto.awaitEntered("stall")).isTrue();
        BatchHashJobResponse cancelling = service.cancel(accepted.jobId());
        assertThat(cancelling.status()).isEqualTo(JobStatus.CANCELLING);

        assertThatThrownBy(() -> service.heartbeat(accepted.jobId()))
                .isInstanceOfSatisfying(JobRetryConflictException.class,
                        e -> assertThat(e.status()).isEqualTo(JobStatus.CANCELLING));
        assertThatThrownBy(() -> service.retry(accepted.jobId()))
                .isInstanceOfSatisfying(JobRetryConflictException.class,
                        e -> assertThat(e.status()).isEqualTo(JobStatus.CANCELLING));
        assertThat(service.getJob(accepted.jobId())).isEqualTo(cancelling);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
        assertThat(heartbeatEntries(journal, accepted.jobId())).isEmpty();

        crypto.unblock("stall");
        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.attempts()).hasSize(1); // the refused retry added no attempt
        awaitActiveJobs(client, 0);
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
    }

    /**
     * DOD-02 / REQ-04: the shutdown drain settles a CANCELLING job as CANCELLED (not as a
     * JOB_SHUTDOWN failure the caller never asked for) while QUEUED and RUNNING jobs keep the
     * pre-existing drain semantics, and a late worker from the cancelled attempt writes nothing.
     */
    @Test
    @DisplayName("drain - a CANCELLING job is settled CANCELLED while QUEUED and RUNNING keep JOB_SHUTDOWN")
    void shouldCancelCancellingJobDuringShutdownDrain() {
        AuditJournalService journal = newJournal();
        StallingCryptoService crypto = new StallingCryptoService("stuck", "busy");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        String client = "198.51.100.34";
        service = new BatchHashJobService(crypto, 2, 10, 300, 3, LEASE_SECONDS, 5000, 0, 20, clock, journal);

        BatchHashJobResponse cancelling = service.submit(
                new BatchHashRequest("SHA-256", List.of("stuck"), null, null), client);
        assertThat(crypto.awaitEntered("stuck")).isTrue();
        BatchHashJobResponse running = service.submit(
                new BatchHashRequest("SHA-256", List.of("busy"), null, null), client);
        assertThat(crypto.awaitEntered("busy")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null), client);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.cancel(cancelling.jobId()).status()).isEqualTo(JobStatus.CANCELLING);

        try {
            service.shutdown(); // zero grace: the forced phase runs immediately

            BatchHashJobResponse cancelled = service.getJob(cancelling.jobId());
            assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(cancelled.error()).isNull();
            assertThat(cancelled.results()).isNull();
            assertThat(cancelled.completedItems()).isZero();
            assertThat(cancelled.completedAt()).isEqualTo(clock.instant());
            assertThat(cancelled.executionLeaseExpiresAt()).isNull();
            assertThat(cancelled.lastHeartbeatAt()).isNull();
            assertThat(cancelled.attempts()).hasSize(1);
            assertThat(cancelled.attempts().get(0).status()).isEqualTo(JobStatus.CANCELLED);

            BatchHashJobResponse failedRunning = service.getJob(running.jobId());
            assertThat(failedRunning.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedRunning.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            BatchHashJobResponse failedQueued = service.getJob(queued.jobId());
            assertThat(failedQueued.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedQueued.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);

            assertThat(service.listJobs(null, 100))
                    .extracting(BatchHashJobResponse::status)
                    .containsExactlyInAnyOrder(JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.FAILED);
            assertThat(service.activeJobsForClient(client)).isZero();
            assertThat(journal.list(500, JournalState.PENDING)).isEmpty();
            List<AuditEntry> entries = batchHashEntries(journal, cancelling.jobId());
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).state()).isEqualTo(JournalState.SUCCEEDED);
            assertThat(entries.get(0).result().path("outcome").asText()).isEqualTo("CANCELLED");

            // The workers were interrupted and ignored it; they return and hit the fence.
            crypto.release("stuck");
            crypto.release("busy");
            awaitExecutorTerminated();
            assertThat(service.getJob(cancelling.jobId())).isEqualTo(cancelled);
            assertThat(batchHashEntries(journal, cancelling.jobId())).hasSize(1);
            assertThat(service.activeJobsForClient(client)).isZero();
            service.shutdown(); // idempotent: every job is terminal, nothing is written
            assertThat(service.getJob(cancelling.jobId())).isEqualTo(cancelled);
        } finally {
            crypto.releaseAll();
        }
    }

    /**
     * DOD-08 / REQ-01: a CANCELLING job holds a live checkpoint prefix (item 1 of four is committed
     * while the worker blocks on item 2), yet the projection exposes neither that prefix nor any
     * results, eviction skips it because it is non-terminal, and it never reaches COMPLETED.
     */
    @Test
    @DisplayName("cancelling - no partial digest is exposed, eviction skips it and it never completes")
    void shouldNotExposeCheckpointForCancellingJob() throws Exception {
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of(), "bravo");
        String client = "198.51.100.36";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC());

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null), client);
        assertThat(crypto.awaitGate()).isTrue(); // item 1 committed, item 2 is inside hash()

        BatchHashJobResponse cancelling = service.cancel(accepted.jobId());
        assertThat(cancelling.status()).isEqualTo(JobStatus.CANCELLING);
        assertThat(cancelling.completedItems()).isZero(); // the durable prefix is hidden
        assertThat(cancelling.results()).isNull();
        assertThat(cancelling.completedAt()).isNull();
        assertThat(service.getJob(accepted.jobId())).isEqualTo(cancelling);
        // Eviction requires a terminal status with a completedAt, so CANCELLING is never evicted.
        assertThat(service.evictExpiredJobs(Instant.now().plusSeconds(86_400L * 365))).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);

        crypto.releaseGate();
        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(cancelled.status()).isNotEqualTo(JobStatus.COMPLETED);
        assertThat(cancelled.completedItems()).isZero(); // the prefix was discarded
        assertThat(cancelled.results()).isNull();
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isZero(); // nothing past the checkpoint was hashed
        assertThat(crypto.count("delta")).isZero();
        awaitActiveJobs(client, 0);
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
     * DOD-01 / REQ-02: the newest job by {@code createdAt} is first, oldest last.
     */
    @Test
    @DisplayName("listJobs - returns the registry newest-first by createdAt")
    void shouldListJobsNewestFirst() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse oldest = service.submit(new BatchHashRequest("SHA-256", List.of("alpha"), null, null));
        clock.advanceSeconds(1);
        BatchHashJobResponse middle = service.submit(new BatchHashRequest("SHA-256", List.of("beta"), null, null));
        clock.advanceSeconds(1);
        BatchHashJobResponse newest = service.submit(new BatchHashRequest("SHA-256", List.of("gamma"), null, null));

        List<String> listed = service.listJobs(null, 20).stream().map(BatchHashJobResponse::jobId).toList();
        assertThat(listed).containsExactly(newest.jobId(), middle.jobId(), oldest.jobId());

        List<Instant> timestamps = service.listJobs(null, 20).stream().map(BatchHashJobResponse::createdAt).toList();
        assertThat(timestamps).containsExactly(LEASE_START.plusSeconds(2), LEASE_START.plusSeconds(1), LEASE_START);
    }

    /**
     * DOD-01 / REQ-02: equal {@code createdAt} stamps are ordered by {@code jobId} ascending, so the
     * order is deterministic rather than dependent on map iteration order.
     */
    @Test
    @DisplayName("listJobs - breaks createdAt ties by jobId ascending")
    void shouldBreakCreatedAtTiesByJobId() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        List<String> submitted = new ArrayList<>();
        for (String item : List.of("alpha", "beta", "gamma")) {
            submitted.add(service.submit(new BatchHashRequest("SHA-256", List.of(item), null, null)).jobId());
        }

        List<BatchHashJobResponse> listed = service.listJobs(null, 20);
        assertThat(listed).hasSize(3);
        assertThat(listed).allSatisfy(job -> assertThat(job.createdAt()).isEqualTo(LEASE_START));
        assertThat(listed.stream().map(BatchHashJobResponse::jobId).toList())
                .containsExactlyElementsOf(submitted.stream().sorted().toList());
    }

    /**
     * DOD-02 / REQ-02 / REQ-04: a supplied status keeps only matching jobs, and a status with no
     * match yields an empty list instead of an error.
     */
    @Test
    @DisplayName("listJobs - filters by status and returns an empty list when nothing matches")
    void shouldFilterJobsByStatus() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("running");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse running = service.submit(new BatchHashRequest("SHA-256", List.of("running"), null, null));
        assertThat(crypto.awaitEntered("running")).isTrue();
        BatchHashJobResponse cancelled = service.submit(new BatchHashRequest("SHA-256", List.of("cancel-me"), null, null));
        service.cancel(cancelled.jobId());

        assertThat(idsOf(service.listJobs(JobStatus.CANCELLED, 20))).containsExactly(cancelled.jobId());
        assertThat(idsOf(service.listJobs(JobStatus.RUNNING, 20))).containsExactly(running.jobId());
        assertThat(service.listJobs(JobStatus.COMPLETED, 20)).isEmpty();
        assertThat(service.listJobs(JobStatus.FAILED, 20)).isEmpty();

        crypto.unblock("running");
        awaitStatus(running.jobId(), JobStatus.COMPLETED);
        assertThat(idsOf(service.listJobs(JobStatus.COMPLETED, 20))).containsExactly(running.jobId());
        assertThat(service.listJobs(JobStatus.QUEUED, 20)).isEmpty();
        assertThat(service.listJobs(null, 20)).hasSize(2);
    }

    /**
     * DOD-02 / REQ-02: {@code limit} is clamped to {@code [1, 100]} — a non-positive value still
     * answers the newest single job and an oversized value is capped at the ceiling.
     */
    @Test
    @DisplayName("listJobs - clamps limit to 1..100")
    void shouldClampLimitToBoundedRange() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);
        for (String item : List.of("alpha", "beta", "gamma")) {
            service.submit(new BatchHashRequest("SHA-256", List.of(item), null, null));
            clock.advanceSeconds(1);
        }
        assertThat(service.listJobs(null, 0)).hasSize(1);
        assertThat(service.listJobs(null, -5)).hasSize(1);
        assertThat(service.listJobs(null, 2)).hasSize(2);

        // A registry larger than the ceiling: one gated job occupies the worker, the rest stay QUEUED.
        service.shutdown();
        LeaseCryptoService crypto = new LeaseCryptoService("blocking");
        // The registry has to exceed the listing ceiling (100) while staying inside the per-client
        // admission quota, so this slice raises the quota above the number of submitted jobs.
        service = new BatchHashJobService(crypto, 1, 200, 300, 3,
                30, 5000, 200, Clock.systemUTC(), null);
        service.submit(new BatchHashRequest("SHA-256", List.of("blocking"), null, null));
        assertThat(crypto.awaitEntered("blocking")).isTrue();
        for (int i = 0; i < 105; i++) {
            service.submit(new BatchHashRequest("SHA-256", List.of("queued-" + i), null, null));
        }

        List<BatchHashJobResponse> capped = service.listJobs(null, 500);
        assertThat(capped).hasSize(100);
        assertThat(service.listJobs(null, 101)).hasSize(100);
        assertThat(service.listJobs(null, 500)).isEqualTo(capped);

        List<BatchHashJobResponse> twenty = service.listJobs(null, 20);
        assertThat(twenty).hasSize(20);
        assertThat(idsOf(twenty)).containsExactlyElementsOf(idsOf(capped).subList(0, 20));

        crypto.unblock("blocking");
    }

    /**
     * DOD-01 / REQ-04: an empty registry is a successful empty list, for both the filtered and the
     * unfiltered call.
     */
    @Test
    @DisplayName("listJobs - returns an empty list for an empty registry")
    void shouldReturnEmptyListWhenRegistryEmpty() {
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3);

        assertThat(service.listJobs(null, 20)).isEmpty();
        assertThat(service.listJobs(JobStatus.COMPLETED, 20)).isEmpty();
        assertThat(service.listJobs(JobStatus.QUEUED, 0)).isEmpty();
    }

    /**
     * DOD-03 / REQ-02 / REQ-03: listing is read-only — registry membership and every job snapshot are
     * identical before and after a list call.
     */
    @Test
    @DisplayName("listJobs - never mutates the registry or a job")
    void shouldNotMutateJobsWhenListing() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("running");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse running = service.submit(new BatchHashRequest("SHA-256", List.of("running"), null, null));
        assertThat(crypto.awaitEntered("running")).isTrue();
        BatchHashJobResponse queued = service.submit(new BatchHashRequest("SHA-256", List.of("waiting"), null, null));

        Set<String> registryBefore = registryKeys("jobs");
        BatchHashJobResponse runningBefore = service.getJob(running.jobId());
        BatchHashJobResponse queuedBefore = service.getJob(queued.jobId());

        service.listJobs(null, 100);
        service.listJobs(JobStatus.RUNNING, 100);

        assertThat(registryKeys("jobs")).isEqualTo(registryBefore);
        assertThat(service.getJob(running.jobId())).isEqualTo(runningBefore);
        assertThat(service.getJob(queued.jobId())).isEqualTo(queuedBefore);

        crypto.unblock("running");
    }

    /**
     * DOD-01 / DOD-03 / REQ-03: each element is the {@code GET /{jobId}} projection — lease fields
     * appear only for RUNNING and the record carries no secret key or raw item component.
     */
    @Test
    @DisplayName("listJobs - every element equals the poll snapshot and exposes no secret")
    void shouldListJobsProjectionMatchesSnapshot() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("running");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse running = service.submit(
                new BatchHashRequest("SHA-256", List.of("running"), "s3cret", null));
        assertThat(crypto.awaitEntered("running")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), "s3cret", null));

        Map<String, BatchHashJobResponse> listed = new ConcurrentHashMap<>();
        for (BatchHashJobResponse job : service.listJobs(null, 20)) {
            listed.put(job.jobId(), job);
        }
        assertThat(listed.keySet()).containsExactlyInAnyOrder(running.jobId(), queued.jobId());

        BatchHashJobResponse listedRunning = listed.get(running.jobId());
        assertThat(listedRunning).isEqualTo(service.getJob(running.jobId()));
        assertThat(listedRunning.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(listedRunning.executionLeaseExpiresAt()).isNotNull();
        assertThat(listedRunning.lastHeartbeatAt()).isNotNull();
        assertThat(listedRunning.executionLeaseExpiresAt())
                .isEqualTo(listedRunning.lastHeartbeatAt().plusSeconds(LEASE_SECONDS));

        BatchHashJobResponse listedQueued = listed.get(queued.jobId());
        assertThat(listedQueued).isEqualTo(service.getJob(queued.jobId()));
        assertThat(listedQueued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(listedQueued.executionLeaseExpiresAt()).isNull();
        assertThat(listedQueued.lastHeartbeatAt()).isNull();
        assertThat(listedQueued.itemCount()).isEqualTo(1);

        Set<String> components = new HashSet<>();
        for (java.lang.reflect.RecordComponent component : BatchHashJobResponse.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertThat(components).doesNotContain("secretKey", "items", "contents");

        crypto.unblock("running");
    }

    @Test
    @DisplayName("retention cap - terminal rows beyond max-retained-jobs are evicted oldest completedAt first")
    void shouldCapRetainedTerminalJobsAndEvictOldestFirst() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        int cap = 3;
        service = cappedService(clock, new CryptoService(), 1, 20, cap);

        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < cap + 2; i++) {
            BatchHashJobResponse accepted = service.submit(
                    new BatchHashRequest("SHA-256", List.of("item-" + i), null, null));
            awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
            jobIds.add(accepted.jobId());
            // A distinct completion instant per row makes the eviction order observable.
            clock.advanceSeconds(1);
        }

        // Exactly the cap survives, and the survivors are the newest rows: no surviving row is
        // re-registered or mutated by the eviction pass. The last completion's cap pass runs after
        // its job-monitor section, so await convergence rather than sampling mid-pass.
        awaitRegistryKeys(jobIds.subList(2, cap + 2).toArray(new String[0]));
        assertThat(registryKeys("jobs")).containsExactlyInAnyOrderElementsOf(jobIds.subList(2, cap + 2));
        assertThatThrownBy(() -> service.getJob(jobIds.get(0))).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> service.getJob(jobIds.get(1))).isInstanceOf(JobNotFoundException.class);
        BatchHashJobResponse newest = service.getJob(jobIds.get(cap + 1));
        assertThat(newest.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(newest.results()).hasSize(1);
    }

    @Test
    @DisplayName("retention cap - QUEUED, RUNNING and CANCELLING rows survive any count pressure")
    void shouldNeverEvictNonTerminalJobsUnderCapPressure() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        StallingCryptoService crypto = new StallingCryptoService("hold");
        int cap = 2;
        service = cappedService(clock, crypto, 2, 20, cap);

        BatchHashJobResponse held = service.submit(
                new BatchHashRequest("SHA-256", List.of("hold"), null, "held-key"));
        assertThat(crypto.awaitEntered("hold")).isTrue();
        assertThat(service.getJob(held.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        // The second worker keeps completing rows, so the terminal count is pushed past the cap
        // repeatedly while a non-terminal row is registered.
        for (int i = 0; i < cap + 3; i++) {
            BatchHashJobResponse accepted = service.submit(
                    new BatchHashRequest("SHA-256", List.of("quick-" + i), null, null));
            awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
            clock.advanceSeconds(1);
        }

        assertThat(awaitRegistrySize(cap + 1)).contains(held.jobId());
        assertThat(service.getJob(held.jobId()).status()).isEqualTo(JobStatus.RUNNING);

        // A time-based pass past retention-seconds still removes every eligible terminal row...
        assertThat(service.evictExpiredJobs(clock.instant().plusSeconds(3600))).isEqualTo(cap);
        // ...and leaves the running row alone.
        assertThat(registryKeys("jobs")).containsExactly(held.jobId());

        crypto.releaseAll();
        BatchHashJobResponse completed = awaitStatus(held.jobId(), JobStatus.COMPLETED);
        assertThat(completed.results()).hasSize(1);
        assertThat(registryKeys("jobs")).containsExactly(held.jobId()); // still under the cap
    }

    @Test
    @DisplayName("retention cap - an evicted idempotency key starts exactly one new job")
    void shouldStartNewJobWhenIdempotencyKeyOfEvictedJobIsReused() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        int cap = 1;
        service = cappedService(clock, new CryptoService(), 1, 20, cap);

        BatchHashRequest keyed = new BatchHashRequest("SHA-256", List.of("reuse"), null, "reuse-key");
        BatchHashJobResponse first = service.submit(keyed);
        awaitStatus(first.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(1);

        // One more terminal row is one row more than the cap allows, so the keyed row is the victim.
        BatchHashJobResponse second = service.submit(
                new BatchHashRequest("SHA-256", List.of("other"), null, null));
        awaitStatus(second.jobId(), JobStatus.COMPLETED);

        awaitRegistryKeys(second.jobId()); // the cap pass for the keyed row runs after its completion
        assertThatThrownBy(() -> service.getJob(first.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThat(registryKeys("jobs")).containsExactly(second.jobId());
        assertThat(registryKeys("idempotencyIndex")).isEmpty(); // the mapping went with the row

        clock.advanceSeconds(1);
        BatchHashJobResponse fresh = service.submit(keyed);
        assertThat(fresh.jobId()).isNotEqualTo(first.jobId());
        awaitStatus(fresh.jobId(), JobStatus.COMPLETED); // the new row ages out second, not first
        awaitRegistryKeys(fresh.jobId());
        assertThat(idempotencyIndexView()).containsOnlyKeys("reuse-key");
        assertThat(idempotencyIndexView()).containsEntry("reuse-key", fresh.jobId());
        // ...and the duplicate path now answers with the new row, not the evicted one.
        assertThat(service.submit(keyed).jobId()).isEqualTo(fresh.jobId());
    }

    @Test
    @DisplayName("retention cap - a non-positive max-retained-jobs is refused at construction")
    void shouldRejectNonPositiveMaxRetainedJobsAtConstruction() {
        for (int invalid : new int[] {0, -1}) {
            assertThatThrownBy(() -> new BatchHashJobService(new CryptoService(), 1, 10, 300, 3,
                    30, 5000, 0, 20, invalid, Clock.systemUTC(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("utiltools.jobs.max-retained-jobs");
        }

    }

    @Test
    @DisplayName("retention cap - the pre-cap constructor overloads keep the documented default")
    void shouldApplyDefaultMaxRetainedJobsToLegacyConstructors() {
        // Every pre-existing overload stays source-compatible and applies the same default cap,
        // so direct-construction callers (and the tests above) compile unchanged.
        List<BatchHashJobService> legacy = List.of(
                new BatchHashJobService(new CryptoService(), 1, 10, 300, 3),
                new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, Clock.systemUTC()),
                new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, 30, 5000, Clock.systemUTC()));
        try {
            for (BatchHashJobService defaults : legacy) {
                assertThat(intValue(defaults, "maxRetainedJobs"))
                        .isEqualTo(BatchHashJobService.DEFAULT_MAX_RETAINED_JOBS)
                        .isEqualTo(1000);
            }
        } finally {
            legacy.forEach(BatchHashJobService::shutdown);
        }
    }

    @Test
    @DisplayName("retention cap - concurrent duplicate submit versus eviction never dangles a mapping")
    void shouldKeepDuplicateSubmitAndEvictionLinearizable() throws Exception {
        int cap = 2;
        int workers = 2;
        int queueCapacity = 30;
        int rounds = 60;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger unresolvableNonTerminal = new AtomicInteger();
        AtomicInteger danglingMappings = new AtomicInteger();
        AtomicInteger aboveBound = new AtomicInteger();
        Set<String> returnedIds = ConcurrentHashMap.newKeySet();

        service = new BatchHashJobService(new CryptoService(), workers, queueCapacity, 300, 3,
                30, 5000, 0, 1000, cap, Clock.systemUTC(), null);
        BatchHashRequest duplicate = new BatchHashRequest("SHA-256", List.of("dupe"), null, "race-key");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> duplicateSubmitter = pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    BatchHashJobResponse accepted = submitEventually(duplicate);
                    returnedIds.add(accepted.jobId());
                    // A non-terminal row is never evicted, so its 202 must stay resolvable; a
                    // terminal row may legitimately be reaped right after the response is built.
                    if (accepted.status() == JobStatus.QUEUED || accepted.status() == JobStatus.RUNNING
                            || accepted.status() == JobStatus.CANCELLING) {
                        try {
                            service.getJob(accepted.jobId());
                        } catch (JobNotFoundException e) {
                            unresolvableNonTerminal.incrementAndGet();
                        }
                    }
                }
            });
            Future<?> churn = pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    submitEventually(new BatchHashRequest("SHA-256", List.of("churn-" + i), null, null));
                }
            });
            Future<?> evictor = pool.submit(() -> {
                while (!stop.get()) {
                    service.evictExpiredJobs(Instant.now().plusSeconds(86_400));
                }
            });
            Future<?> sampler = pool.submit(() -> {
                while (!stop.get()) {
                    // Reading both registries under the production monitor yields one atomic view:
                    // this is exactly the invariant a same-key submission relies on.
                    synchronized (registryLock()) {
                        Set<String> rows = registryKeys("jobs");
                        if (rows.size() > cap + workers + queueCapacity) {
                            aboveBound.incrementAndGet();
                        }
                        for (String jobId : idempotencyIndexView().values()) {
                            if (!rows.contains(jobId)) {
                                danglingMappings.incrementAndGet();
                            }
                        }
                    }
                }
            });
            duplicateSubmitter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            churn.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stop.set(true);
            evictor.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sampler.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        assertThat(returnedIds).isNotEmpty();
        assertThat(unresolvableNonTerminal.get()).isZero();
        assertThat(danglingMappings.get()).isZero();
        assertThat(aboveBound.get()).isZero();

        // Quiesced: the key answers with a live row, and never with an unregistered id.
        // The churn thread's accepted jobs may still occupy the bounded queue right after
        // shutdownNow (it only interrupts, it does not drain), so wait for capacity instead of
        // assuming the queue is already empty.
        BatchHashJobResponse after = submitEventually(duplicate);
        assertThat(service.getJob(after.jobId()).jobId()).isEqualTo(after.jobId());
        synchronized (registryLock()) {
            Set<String> rows = registryKeys("jobs");
            assertThat(rows.size()).isLessThanOrEqualTo(cap + workers + queueCapacity);
            String target = idempotencyIndexView().get("race-key");
            if (target != null) {
                assertThat(rows).contains(target);
                assertThat(target).isEqualTo(after.jobId());
            }
        }
    }

    @Test
    @DisplayName("retention cap - time-based eviction still applies when the cap is not binding")
    void shouldKeepTimeBasedEvictionWithCountCap() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = cappedService(clock, new CryptoService(), 1, 20, 1000);

        BatchHashJobResponse first = service.submit(
                new BatchHashRequest("SHA-256", List.of("time-1"), null, null));
        awaitStatus(first.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(1);
        BatchHashJobResponse second = service.submit(
                new BatchHashRequest("SHA-256", List.of("time-2"), null, null));
        awaitStatus(second.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(1);

        // Inside the retention window and far below the cap: a pass removes nothing. The scheduled
        // caller passes the retention cutoff (now - retention-seconds), not the current instant.
        Instant cutoff = clock.instant().minusSeconds(300);
        assertThat(service.evictExpiredJobs(cutoff)).isZero();
        assertThat(registryKeys("jobs")).containsExactlyInAnyOrder(first.jobId(), second.jobId());

        // Past retention-seconds the time-based pass still removes both rows: the cap only adds a
        // second reason to evict, it does not replace the retention window.
        clock.advanceSeconds(3600);
        assertThat(service.evictExpiredJobs(clock.instant().minusSeconds(300))).isEqualTo(2);
        assertThat(registryKeys("jobs")).isEmpty();
    }

    @Test
    @DisplayName("retention cap - a retention pass cannot run while another thread holds registryLock")
    void shouldBlockEvictionWhileRegistryLockIsHeld() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = cappedService(clock, new CryptoService(), 1, 20, 10);
        BatchHashJobResponse expired = service.submit(
                new BatchHashRequest("SHA-256", List.of("locked"), null, null));
        awaitStatus(expired.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(3600); // the row is evictable, so the pass has real work to do

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Object lock = registryLock();
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "registry-lock-holder");
        holder.start();
        try {
            assertThat(holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            CountDownLatch passDone = new CountDownLatch(1);
            Thread evictor = new Thread(() -> {
                service.evictExpiredJobs(clock.instant());
                passDone.countDown();
            }, "retention-pass");
            evictor.start();
            // The pass must still be blocked on the registry monitor: this is the lock
            // participation the duplicate-submit-versus-eviction race depends on.
            assertThat(passDone.await(300, TimeUnit.MILLISECONDS)).isFalse();

            release.countDown();
            assertThat(passDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            evictor.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }

        assertThat(registryKeys("jobs")).isEmpty();
    }

    @Test
    @DisplayName("retention cap - eviction mutates no survivor and releases no quota slot")
    void shouldNotMutateSurvivorsOrReleaseQuotaWhenEvictingUnderCap() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        StallingCryptoService crypto = new StallingCryptoService("hold");
        AuditJournalService journal = newJournal();
        // One retained terminal row and one non-terminal job per client: the tightest legal setup.
        service = new BatchHashJobService(crypto, 2, 10, 300, 3, 30, 5000, 0, 1, 1, clock, journal);

        String victimClient = "198.51.100.10";
        String survivorClient = "198.51.100.11";
        BatchHashJobResponse victim = service.submit(
                new BatchHashRequest("SHA-256", List.of("victim"), null, null), victimClient);
        awaitStatus(victim.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(victimClient, 0);
        clock.advanceSeconds(1);

        BatchHashJobResponse survivor = service.submit(
                new BatchHashRequest("SHA-256", List.of("hold"), null, "survivor-key"), survivorClient);
        assertThat(crypto.awaitEntered("hold")).isTrue();
        BatchHashJobResponse before = service.getJob(survivor.jobId());
        assertThat(before.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(before.attempt()).isEqualTo(1);
        List<AuditEntry> auditBefore = batchHashEntries(journal, survivor.jobId());
        assertThat(auditBefore).isNotEmpty();

        // A third identity completes a row, so the terminal count exceeds the cap by exactly one and
        // the oldest terminal row (the victim) is the eviction target.
        clock.advanceSeconds(1);
        BatchHashJobResponse pressure = service.submit(
                new BatchHashRequest("SHA-256", List.of("pressure"), null, null), "198.51.100.12");
        awaitStatus(pressure.jobId(), JobStatus.COMPLETED);

        awaitRegistryKeys(survivor.jobId(), pressure.jobId());
        assertThatThrownBy(() -> service.getJob(victim.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThat(registryKeys("jobs")).containsExactlyInAnyOrder(survivor.jobId(), pressure.jobId());

        // The survivor is byte-identical: identity fields, attempt history and audit rows untouched.
        BatchHashJobResponse after = service.getJob(survivor.jobId());
        assertThat(after.jobId()).isEqualTo(before.jobId());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.attempt()).isEqualTo(before.attempt());
        assertThat(after.attempts()).isEqualTo(before.attempts());
        assertThat(after.completedItems()).isEqualTo(before.completedItems());
        assertThat(after.startedAt()).isEqualTo(before.startedAt());
        assertThat(batchHashEntries(journal, survivor.jobId())).isEqualTo(auditBefore);

        // No quota slot was released by the eviction: the survivor's client is still at its limit.
        assertThat(service.activeJobsForClient(survivorClient)).isEqualTo(1);
        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("again"), null, null), survivorClient))
                .isInstanceOfSatisfying(JobQuotaExceededException.class, refused -> {
                    assertThat(refused.limit()).isEqualTo(1);
                    assertThat(refused.activeJobs()).isEqualTo(1);
                });
        assertThat(service.activeJobsForClient(survivorClient)).isEqualTo(1);

        // Letting the gated worker finish keeps no thread behind; the survivor itself may be the next
        // count-pressure victim (the clock is frozen, so both rows share a completedAt), which is
        // exactly why the assertions above ran while it was still non-terminal.
        crypto.releaseAll();
        awaitActiveJobs(survivorClient, 0);
    }

    @Test
    @DisplayName("retention cap - reaping a stalled execution trims the row it terminalised")
    void shouldEnforceRetainedJobCapWhenReaperTerminalisesRow() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        StallingCryptoService crypto = new StallingCryptoService("reaped");
        int cap = 1;
        service = cappedService(clock, crypto, 2, 20, cap);

        // The cap is already full with one completed row before the reaper publishes its own.
        BatchHashJobResponse settled = service.submit(
                new BatchHashRequest("SHA-256", List.of("settled"), null, null));
        awaitStatus(settled.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(1);

        BatchHashJobResponse stalled = service.submit(
                new BatchHashRequest("SHA-256", List.of("reaped"), null, null));
        try {
            assertThat(crypto.awaitEntered("reaped")).isTrue();
            clock.advanceSeconds(LEASE_SECONDS + 1);

            // The reap writes the second terminal row (Plan §4.3 site #4): enforcement runs there too,
            // so the reaped status never leaves the registry one row above the cap.
            assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

            awaitRegistryKeys(stalled.jobId());
            assertThat(registryKeys("jobs")).containsExactly(stalled.jobId());
            assertThatThrownBy(() -> service.getJob(settled.jobId()))
                    .isInstanceOf(JobNotFoundException.class);
            assertThat(service.getJob(stalled.jobId()).status()).isEqualTo(JobStatus.FAILED);
            assertThat(service.getJob(stalled.jobId()).error())
                    .isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        } finally {
            // The fenced worker must not resurrect the reaped attempt, nor add a row.
            crypto.releaseAll();
        }
        awaitStatus(stalled.jobId(), JobStatus.FAILED);
        assertThat(registryKeys("jobs")).containsExactly(stalled.jobId());
    }

    @Test
    @DisplayName("retention cap - the drain sweep trims the rows it terminalises")
    void shouldEnforceRetainedJobCapWhenDrainTerminalisesRows() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        StallingCryptoService crypto = new StallingCryptoService("held-a", "held-b");
        int cap = 1;
        service = cappedService(clock, crypto, 2, 20, cap);

        BatchHashJobResponse settled = service.submit(
                new BatchHashRequest("SHA-256", List.of("settled"), null, null));
        awaitStatus(settled.jobId(), JobStatus.COMPLETED);
        clock.advanceSeconds(1);

        BatchHashJobResponse heldA = service.submit(
                new BatchHashRequest("SHA-256", List.of("held-a"), null, null));
        BatchHashJobResponse heldB = service.submit(
                new BatchHashRequest("SHA-256", List.of("held-b"), null, null));
        assertThat(crypto.awaitEntered("held-a")).isTrue();
        assertThat(crypto.awaitEntered("held-b")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null));
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);

        try {
            // The forced phase publishes three FAILED rows at once (Plan §4.3 site #6). Each settled
            // row runs the cap pass, so the sweep cannot leave the registry above the cap.
            service.shutdown();

            assertThat(registryKeys("jobs")).hasSize(cap);
            assertThat(registryKeys("jobs")).doesNotContain(settled.jobId());
            assertThatThrownBy(() -> service.getJob(settled.jobId()))
                    .isInstanceOf(JobNotFoundException.class);
            // The drained rows are terminal and resolvable: only the oldest row was dropped.
            Set<String> drained = Set.of(heldA.jobId(), heldB.jobId(), queued.jobId());
            assertThat(registryKeys("jobs")).isSubsetOf(drained);
            assertThat(service.getJob(registryKeys("jobs").iterator().next()).status())
                    .isEqualTo(JobStatus.FAILED);
        } finally {
            crypto.releaseAll();
        }
    }

    @Test
    @DisplayName("retention cap - the retention pass reports its count-pressure removals")
    void shouldCountCountPressureRemovalsInRetentionPassReturnValue() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        StallingCryptoService crypto = new StallingCryptoService("pile-0", "pile-1", "pile-2", "pile-3");
        int cap = 1;
        service = cappedService(clock, crypto, 4, 20, cap);

        List<String> accepted = new ArrayList<>();
        // Holding the registry monitor makes the pile-up deterministic: every completing worker
        // publishes its terminal status and then blocks in its own cap pass, so four terminal rows are
        // registered above a cap of one when the pass below runs. submit() and the pass both
        // synchronize on the same (re-entrant) monitor, so both run on this thread.
        synchronized (registryLock()) {
            for (int i = 0; i < 4; i++) {
                BatchHashJobResponse job = service.submit(
                        new BatchHashRequest("SHA-256", List.of("pile-" + i), null, null));
                accepted.add(job.jobId());
            }
            for (int i = 0; i < 4; i++) {
                assertThat(crypto.awaitEntered("pile-" + i)).isTrue();
            }
            crypto.releaseAll();
            for (String jobId : accepted) {
                awaitStatus(jobId, JobStatus.COMPLETED);
            }
            assertThat(registryKeys("jobs")).hasSize(4); // blocked hooks, no trim yet

            // Nothing is past retention-seconds here, so all three removals are count-pressure ones
            // and the pass must report them (Plan §4.5: evicted + enforced).
            assertThat(service.evictExpiredJobs(clock.instant().minusSeconds(300))).isEqualTo(3);
            assertThat(registryKeys("jobs")).hasSize(cap);
        }

        awaitRegistrySize(cap);
        assertThat(registryKeys("jobs")).hasSize(cap);
    }

    /** A service whose retained-row cap is small enough for a handful of jobs to exceed it. */
    private BatchHashJobService cappedService(MutableJobClock clock, CryptoService crypto, int workers,
            int queueCapacity, int maxRetainedJobs) {
        return new BatchHashJobService(crypto, workers, queueCapacity, 300, 3,
                30, 5000, 0, 1000, maxRetainedJobs, clock, null);
    }

    /** Submits, retrying while the bounded queue is full: these tests are about retention, not queueing. */
    private BatchHashJobResponse submitEventually(BatchHashRequest request) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                return service.submit(request);
            } catch (JobQueueFullException e) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying a submission", interrupted);
                }
            }
        }
        throw new AssertionError("The bounded queue never accepted " + request);
    }

    /** The production registry monitor, so a test can read an atomic view of both maps. */
    private Object registryLock() {
        try {
            Field field = BatchHashJobService.class.getDeclaredField("registryLock");
            field.setAccessible(true);
            return field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the batch hash registry lock", e);
        }
    }

    /** Snapshot of the {@code idempotencyIndex} (key -> jobId), read like {@link #registryKeys(String)}. */
    private Map<String, String> idempotencyIndexView() {
        try {
            Field field = BatchHashJobService.class.getDeclaredField("idempotencyIndex");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) field.get(service);
            return Map.copyOf(map);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read BatchHashJobService.idempotencyIndex", e);
        }
    }

    /** Reads an {@code int} tuning field, so a test can prove the default cap is applied. */
    private int intValue(Object target, String fieldName) {
        try {
            Field field = BatchHashJobService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read BatchHashJobService." + fieldName, e);
        }
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

    /** Projection of a list result onto job ids, keeping the list order intact. */
    private static List<String> idsOf(List<BatchHashJobResponse> jobs) {
        List<String> ids = new ArrayList<>(jobs.size());
        for (BatchHashJobResponse job : jobs) {
            ids.add(job.jobId());
        }
        return ids;
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

    /**
     * Waits until the {@code jobs} registry converges on exactly {@code expectedIds}, then returns the
     * observed set.
     *
     * <p>A terminal transition publishes its status under the job monitor and only then runs the cap
     * pass, which must take {@code registryLock} <em>after</em> leaving that monitor
     * ({@code registryLock -> job monitor} is the only legal order). A row that has just become
     * terminal is therefore still registered for the few microseconds the completing worker needs to
     * re-acquire the registry lock, so these tests await convergence instead of sampling the registry
     * in the middle of an enforcement pass.</p>
     */
    private Set<String> awaitRegistryKeys(String... expectedIds) {
        Set<String> expected = Set.of(expectedIds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        Set<String> observed = Set.of();
        while (System.nanoTime() < deadline) {
            observed = registryKeys("jobs");
            if (observed.equals(expected)) {
                return observed;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting the jobs registry", e);
            }
        }
        throw new AssertionError("jobs registry did not converge on " + expected
                + "; last observed " + observed);
    }

    /** Waits until the {@code jobs} registry holds exactly {@code expectedSize} rows (see above). */
    private Set<String> awaitRegistrySize(int expectedSize) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        Set<String> observed = Set.of();
        while (System.nanoTime() < deadline) {
            observed = registryKeys("jobs");
            if (observed.size() == expectedSize) {
                return observed;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting the jobs registry", e);
            }
        }
        throw new AssertionError("jobs registry did not converge on " + expectedSize
                + " rows; last observed " + observed);
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
     * Hashing stub for the fair-admission tests: records every item's entry into {@link #hash} in
     * order, lets one designated item fail its first N hashes (a transient fault that forces a
     * retry), and can gate any designated item on its own latch so a worker can be held
     * deterministically. Every other item delegates to real hashing.
     */
    private static final class OrderingCryptoService extends CryptoService {

        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> entryOrder = new ConcurrentLinkedQueue<>();

        OrderingCryptoService(String... gatedItems) {
            for (String item : gatedItems) {
                gates.put(item, new Gate());
            }
        }

        /**
         * The next {@code times} hashes of {@code item} abort the whole attempt with a transient
         * {@link WholeAttemptFault}: an item-level {@link RuntimeException} would instead be recorded
         * as that item's own failure and never retried (REQ-04), which is not what the retry-lane
         * fixture below models.
         */
        void failOnce(String item, int times) {
            remainingFailures.put(item, new AtomicInteger(times));
        }

        @Override
        public HashResponse hash(HashRequest request) {
            String content = request.content();
            invocations.computeIfAbsent(content, c -> new AtomicInteger()).incrementAndGet();
            entryOrder.add(content);
            AtomicInteger remaining = remainingFailures.get(content);
            if (remaining != null && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new WholeAttemptFault("transient hashing failure for " + content);
            }
            Gate gate = gates.get(content);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    gate.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.hash(request);
        }

        boolean awaitEntered(String item) throws Exception {
            return gates.get(item).entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void unblock(String item) {
            gates.get(item).released.countDown();
        }

        int count(String item) {
            AtomicInteger counter = invocations.get(item);
            return counter == null ? 0 : counter.get();
        }

        /** Items whose hash started before the first entry of {@code item} (in entry order). */
        List<String> entriesBefore(String item) {
            List<String> before = new ArrayList<>();
            for (String entry : entryOrder) {
                if (entry.equals(item)) {
                    break;
                }
                before.add(entry);
            }
            return before;
        }

        private static final class Gate {
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
        }
    }

    /**
     * Hashing stub with per-item gates that <em>ignore interruption</em>: the worker parks on a latch
     * until {@link #release(String)} is called, deliberately swallowing the {@code InterruptedException}
     * the forced drain raises. That is the drain's hard case — a worker that outlives the sweep — so
     * the tests can prove the fence, not the interrupt, is what keeps the terminal snapshot intact.
     */
    private static final class StallingCryptoService extends CryptoService {

        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final AtomicInteger interrupts = new AtomicInteger();

        private StallingCryptoService(String... gatedItems) {
            for (String item : gatedItems) {
                gates.put(item, new Gate());
            }
        }

        @Override
        public HashResponse hash(HashRequest request) {
            invocations.computeIfAbsent(request.content(), key -> new AtomicInteger()).incrementAndGet();
            Gate gate = gates.get(request.content());
            if (gate == null) {
                return super.hash(request);
            }
            gate.entered.countDown();
            while (true) {
                try {
                    // No timeout: the test always releases from its finally block.
                    gate.released.await();
                    break;
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet(); // load-bearing: the drain's interrupt is ignored
                }
            }
            return super.hash(request);
        }

        private boolean awaitEntered(String item) {
            Gate gate = gates.get(item);
            try {
                return gate != null && gate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void release(String item) {
            Gate gate = gates.get(item);
            if (gate != null) {
                gate.released.countDown();
            }
        }

        private void releaseAll() {
            gates.values().forEach(gate -> gate.released.countDown());
        }

        private int count(String item) {
            AtomicInteger count = invocations.get(item);
            return count == null ? 0 : count.get();
        }

        private int interrupts() {
            return interrupts.get();
        }

        private static final class Gate {
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
        }
    }

    /**
     * The whole-attempt fault the retry and checkpoint fixtures inject. A {@link RuntimeException} a
     * single item's hash raises is now that item's own outcome - recorded, isolated and deliberately
     * never retried (REQ-01, REQ-04) - so the long-standing fixtures that model an attempt which
     * *aborts mid-batch* (retry bookkeeping, attempt fencing, eviction races, queue-full rollback and
     * the fenced-checkpoint resume) inject this unchecked {@link Error} instead. It reaches the
     * worker's outer {@code catch (Throwable)} fence, which still fails the whole attempt and leaves
     * every remaining item unprocessed: exactly the pre-existing state those tests assert on.
     */
    private static final class WholeAttemptFault extends Error {

        private WholeAttemptFault(String message) {
            super(message);
        }
    }

    /**
     * Stub whose designated item fails the whole attempt (see {@link WholeAttemptFault}) a bounded
     * number of times, optionally blocking on latches so the attempt can be held mid-hash.
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
                throw new WholeAttemptFault("transient hashing failure for " + request.content());
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
    // Execution-lease heartbeat (REQ-01..REQ-07, INV-HB-1..INV-HB-7)
    // ---------------------------------------------------------------------------------------------

    /**
     * DOD-02 / REQ-03, REQ-04: a RUNNING job's heartbeat derives the new expiry from the clock (never
     * from the stored expiry) and stamps {@code lastHeartbeatAt} with the same instant, while the
     * attempt's own projection stays RUNNING with no results and no completion instant.
     */
    @Test
    @DisplayName("heartbeat - a RUNNING job renews its lease and stamps lastHeartbeatAt from the clock")
    void shouldExtendLeaseAndStampHeartbeatAtWhenRunningJobHeartbeats() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("b");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("a", "b"), null, null));
        assertThat(crypto.awaitEntered("b")).isTrue(); // "a" is committed, the worker is inside "b"
        try {
            clock.advanceSeconds(5);

            BatchHashJobResponse renewed = service.heartbeat(accepted.jobId());

            assertThat(renewed.jobId()).isEqualTo(accepted.jobId());
            assertThat(renewed.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(renewed.lastHeartbeatAt()).isEqualTo(clock.instant());
            assertThat(renewed.executionLeaseExpiresAt())
                    .isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));
            assertThat(renewed.results()).isNull();
            assertThat(renewed.completedAt()).isNull();
            assertThat(renewed.attempt()).isEqualTo(1);
            // The renewal is what the reaper reads: the extended lease is live at the new instant.
            assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
            assertThat(service.getJob(accepted.jobId()).executionLeaseExpiresAt())
                    .isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));
        } finally {
            crypto.unblock("b");
        }
        assertThat(awaitStatus(accepted.jobId(), JobStatus.COMPLETED).results()).hasSize(2);
    }

    /**
     * DOD-02 / REQ-02: an unknown or already-evicted id answers like every other job route — a
     * {@link JobNotFoundException} with nothing registered, journaled or mutated.
     */
    @Test
    @DisplayName("heartbeat - an unknown job id is a 404-worthy JobNotFoundException and writes nothing")
    void shouldRejectHeartbeatForUnknownJob() {
        AuditJournalService journal = newJournal();
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);
        Set<String> registeredBefore = registryKeys("jobs");
        int journalSizeBefore = journal.list(500, null).size();

        assertThatThrownBy(() -> service.heartbeat("unknown-job-id"))
                .isInstanceOf(JobNotFoundException.class);

        assertThat(registryKeys("jobs")).isEqualTo(registeredBefore);
        assertThat(journal.list(500, null)).hasSize(journalSizeBefore);
    }

    /**
     * DOD-02 / REQ-02, REQ-07: only a RUNNING attempt may renew. A QUEUED, COMPLETED, CANCELLED or
     * already reaped FAILED job answers 409 through {@link JobRetryConflictException}, and every one
     * of those snapshots is byte-identical afterwards with no {@code HEARTBEAT} row written.
     */
    @Test
    @DisplayName("heartbeat - QUEUED, COMPLETED, CANCELLED and reaped FAILED jobs answer 409 unchanged")
    void shouldRejectHeartbeatWhenJobIsNotRunning() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock, journal);

        BatchHashJobResponse completed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("done"), null, null)).jobId(),
                JobStatus.COMPLETED);
        BatchHashJobResponse stalled = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null));
        BatchHashJobResponse cancelled = service.cancel(service.submit(
                new BatchHashRequest("SHA-256", List.of("withdrawn"), null, null)).jobId());
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);

        for (BatchHashJobResponse notRunning : List.of(queued, completed, cancelled)) {
            BatchHashJobResponse before = service.getJob(notRunning.jobId());
            assertThatThrownBy(() -> service.heartbeat(notRunning.jobId()))
                    .isInstanceOfSatisfying(JobRetryConflictException.class,
                            e -> assertThat(e.status()).isEqualTo(notRunning.status()));
            assertThat(service.getJob(notRunning.jobId())).isEqualTo(before);
            assertThat(heartbeatEntries(journal, notRunning.jobId())).isEmpty();
        }

        // The reaped attempt is FAILED, not RUNNING: renewal is refused after the reaper claimed it.
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse reaped = service.getJob(stalled.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThatThrownBy(() -> service.heartbeat(stalled.jobId()))
                .isInstanceOfSatisfying(JobRetryConflictException.class,
                        e -> assertThat(e.status()).isEqualTo(JobStatus.FAILED));
        assertThat(service.getJob(stalled.jobId())).isEqualTo(reaped);
        assertThat(heartbeatEntries(journal, stalled.jobId())).isEmpty();

        crypto.unblock("stalled");
    }

    /**
     * DOD-02 / REQ-02, INV-HB-2: renewal is refused once the lease has lapsed — the last live
     * millisecond still renews, the expiry instant itself does not, and a refused call leaves both
     * the lease and the status untouched (it never revives an attempt the reaper may claim).
     */
    @Test
    @DisplayName("heartbeat - renewal succeeds one millisecond before expiry and is refused at it")
    void shouldRejectHeartbeatWhenLeaseHasExpired() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        try {
            clock.advanceMillis(LEASE_SECONDS * 1000 - 1); // 1 ms before the original expiry
            BatchHashJobResponse renewed = service.heartbeat(accepted.jobId());
            assertThat(renewed.executionLeaseExpiresAt())
                    .isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));

            BatchHashJobResponse live = service.getJob(accepted.jobId());
            Instant expiry = live.executionLeaseExpiresAt();
            clock.advanceSeconds(LEASE_SECONDS); // the clock now sits exactly on the expiry instant
            assertThat(clock.instant()).isEqualTo(expiry);

            assertThatThrownBy(() -> service.heartbeat(accepted.jobId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("has expired");
            BatchHashJobResponse refused = service.getJob(accepted.jobId());
            assertThat(refused).isEqualTo(live);
            assertThat(refused.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(refused.executionLeaseExpiresAt()).isEqualTo(expiry);
            assertThat(refused.lastHeartbeatAt()).isEqualTo(live.lastHeartbeatAt());
        } finally {
            crypto.unblock("stalled");
        }
        awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
    }

    /**
     * DOD-02 / REQ-05, INV-HB-4, INV-HB-5: each successful renewal opens and closes exactly one
     * bounded {@code HEARTBEAT} row, and the attempt's own entry keeps closing with the attempt's
     * outcome — the heartbeat never steals it.
     */
    @Test
    @DisplayName("heartbeat - every renewal journals one bounded HEARTBEAT row and never closes the attempt row")
    void shouldJournalHeartbeatEntryWithBoundedDetail() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock, journal);

        RequestContext.set("req-heartbeat1");
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();

        service.heartbeat(accepted.jobId());
        clock.advanceSeconds(5);
        service.heartbeat(accepted.jobId());
        RequestContext.clear();

        List<AuditEntry> rows = heartbeatEntries(journal, accepted.jobId());
        assertThat(rows).hasSize(2);
        for (AuditEntry row : rows) {
            assertThat(row.operation()).isEqualTo(OperationType.BATCH_HASH);
            assertThat(row.state()).isEqualTo(JournalState.SUCCEEDED);
            assertThat(row.requestId()).isEqualTo("req-heartbeat1");
            assertThat(row.request().path("attempt").asInt()).isEqualTo(1);
            assertThat(row.request().path("leaseExpiresAt").asText()).isNotBlank();
            assertThat(row.result().path("status").asText()).isEqualTo("HEARTBEAT");
            assertThat(row.result().path("detail").asText()).isEqualTo("lease renewed via heartbeat");
            assertThat(row.result().path("detail").asText().length())
                    .isLessThanOrEqualTo(ReapRecord.MAX_DETAIL_LENGTH);
        }
        String newestExpiry = rows.get(0).result().path("leaseExpiresAt").asText();
        assertThat(newestExpiry).isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS).toString());

        // The attempt's own entry is still open (PENDING) and is not one of the heartbeat rows.
        List<AuditEntry> attemptRows = attemptEntries(journal, accepted.jobId());
        assertThat(attemptRows).hasSize(1);
        AuditEntry attemptRow = attemptRows.get(0);
        assertThat(attemptRow.state()).isEqualTo(JournalState.PENDING);
        assertThat(rows).noneMatch(row -> row.id().equals(attemptRow.id()));
        assertThat(service.getJob(accepted.jobId()).attempts().get(0).auditEntryId())
                .isEqualTo(attemptRow.id());

        crypto.unblock("stalled");
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        AuditEntry closedAttemptRow = journal.find(attemptRow.id()).orElseThrow();
        assertThat(closedAttemptRow.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(closedAttemptRow.result().path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(completed.attempts().get(0).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(heartbeatEntries(journal, accepted.jobId())).hasSize(2); // renewal rows unaffected
    }

    /**
     * DOD-05 / REQ-06, INV-HB-1, INV-HB-2: the fence is the attempt generation. A heartbeat after
     * the reaper claimed attempt 1 is refused and journals nothing; once the job is retried the
     * renewal belongs to attempt 2, and replaying the superseded attempt-1 worker changes neither
     * the snapshot nor the journal.
     */
    @Test
    @DisplayName("heartbeat - renewal is fenced to the current attempt across reap, retry and a late worker")
    void shouldFenceHeartbeatToCurrentAttemptAfterReapAndRetry() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        // Two workers: the retried attempt 2 starts on the free thread while the stale attempt-1
        // worker is still parked inside hash("stalled") -- that is exactly the late-worker race.
        service = new BatchHashJobService(crypto, 2, 10, 300, 3, LEASE_SECONDS, 5000, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stalled"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue();
        try {
            clock.advanceSeconds(LEASE_SECONDS);
            assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
            assertThatThrownBy(() -> service.heartbeat(accepted.jobId()))
                    .isInstanceOf(JobRetryConflictException.class);
            assertThat(heartbeatEntries(journal, accepted.jobId())).isEmpty();

            BatchHashJobResponse retried = service.retry(accepted.jobId());
            assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(retried.attempt()).isEqualTo(2);

            awaitRunningAttempt(accepted.jobId(), 2);
            clock.advanceSeconds(5);
            BatchHashJobResponse renewed = service.heartbeat(accepted.jobId());
            assertThat(renewed.attempt()).isEqualTo(2);
            assertThat(renewed.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(renewed.lastHeartbeatAt()).isEqualTo(clock.instant());
            assertThat(renewed.executionLeaseExpiresAt())
                    .isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));

            List<AuditEntry> rows = heartbeatEntries(journal, accepted.jobId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).request().path("attempt").asInt()).isEqualTo(2);

            // The superseded attempt-1 worker is replayed: the generation fence drops it entirely.
            BatchHashJobResponse before = service.getJob(accepted.jobId());
            int rowsBefore = batchHashEntries(journal, accepted.jobId()).size();
            service.executeAttempt(accepted.jobId(), 1);
            assertThat(service.getJob(accepted.jobId())).isEqualTo(before);
            assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(rowsBefore);
        } finally {
            crypto.unblock("stalled");
        }
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.attempts()).hasSize(2);
    }

    /**
     * DOD-02 / REQ-07, INV-HB-3: a mid-batch renewal writes only the lease record and the heartbeat
     * stamp. The status, the checkpoint prefix, the attempt history, the started/completed instants
     * and the (still unpublished) results are all untouched.
     */
    @Test
    @DisplayName("heartbeat - only the lease and lastHeartbeatAt change; the attempt outcome does not")
    void shouldNotMutateJobOutcomeOrCheckpointOnHeartbeat() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("c");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("a", "b", "c"), null, "heartbeat-key"));
        assertThat(crypto.awaitEntered("c")).isTrue(); // two items committed, the worker is inside "c"
        try {
            BatchHashJobResponse before = service.getJob(accepted.jobId());
            assertThat(before.completedItems()).isEqualTo(2);
            clock.advanceSeconds(5);

            BatchHashJobResponse after = service.heartbeat(accepted.jobId());

            assertThat(after.status()).isEqualTo(before.status());
            assertThat(after.itemCount()).isEqualTo(before.itemCount());
            assertThat(after.completedItems()).isEqualTo(before.completedItems());
            assertThat(after.attempt()).isEqualTo(before.attempt());
            assertThat(after.attempts()).isEqualTo(before.attempts());
            assertThat(after.results()).isNull();
            assertThat(after.completedAt()).isNull();
            assertThat(after.startedAt()).isEqualTo(before.startedAt());
            assertThat(after.createdAt()).isEqualTo(before.createdAt());
            assertThat(after.idempotencyKey()).isEqualTo("heartbeat-key");
            assertThat(after.error()).isNull();
            assertThat(after.lastHeartbeatAt()).isEqualTo(clock.instant());
            assertThat(after.executionLeaseExpiresAt())
                    .isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));
            // Field by field above, and as a whole: rebuilding the pre-heartbeat snapshot with only
            // the two lease stamps taken from the post-heartbeat one reproduces it exactly.
            assertThat(after).isEqualTo(new BatchHashJobResponse(before.jobId(), before.idempotencyKey(),
                    before.status(), before.itemCount(), before.results(), before.error(),
                    before.createdAt(), before.startedAt(), before.completedAt(), before.attempt(),
                    before.maxAttempts(), before.attempts(), after.executionLeaseExpiresAt(),
                    after.lastHeartbeatAt(), before.completedItems(), before.deadlineAt(),
                    before.succeededItems(), before.failedItems()));
        } finally {
            crypto.unblock("c");
        }
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.results()).hasSize(3);
        assertThat(completed.completedAt()).isNotNull();
    }

    /** Blocks until the job's given attempt is the one actually executing (RUNNING). */
    private BatchHashJobResponse awaitRunningAttempt(String jobId, int attempt) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            BatchHashJobResponse snapshot = service.getJob(jobId);
            if (snapshot.status() == JobStatus.RUNNING && snapshot.attempt() == attempt) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job " + jobId + " attempt " + attempt
                + " did not start; last=" + service.getJob(jobId));
    }

    /** HEARTBEAT rows recorded for one job, newest first; identified by the descriptor's own kind. */
    private static List<AuditEntry> heartbeatEntries(AuditJournalService journal, String jobId) {
        return batchHashEntries(journal, jobId).stream()
                .filter(entry -> entry.request() != null && entry.request().has("kind"))
                .toList();
    }

    /** The attempt's own entry for one job (no {@code kind} marker), newest first. */
    private static List<AuditEntry> attemptEntries(AuditJournalService journal, String jobId) {
        return batchHashEntries(journal, jobId).stream()
                .filter(entry -> entry.request() == null || !entry.request().has("kind"))
                .toList();
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

    // ---------------------------------------------------------------------------------------------
    // Per-item failure isolation (REQ-01..REQ-05)
    // ---------------------------------------------------------------------------------------------

    /** A three-item batch whose middle item is the one the fault stub fails. */
    private static final List<String> PARTIAL_ITEMS = List.of("alpha", "bravo", "charlie");

    /**
     * DOD-01 / DOD-02 / REQ-01: one item's hash exception neither aborts the batch nor loses the items
     * already hashed - every remaining item is still hashed, the batch terminalizes FAILED with
     * {@code BATCH_ITEM_HASH_FAILED}, publishes no results and keeps the two successes in its prefix.
     */
    @Test
    @DisplayName("per-item failure - one item's hash exception does not abort the batch")
    void shouldContinueBatchWhenOneItemHashThrows() {
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("bravo"), Set.of());
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null)).jobId(),
                JobStatus.FAILED);

        // The loop carried on past the throw: the item after the failing one was hashed too.
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(1);
        assertThat(failed.results()).isNull();
        // Exactly the documented code - never augmented with the cause (INV-PI-6 / DOD-02).
        assertThat(failed.error()).isEqualTo("BATCH_ITEM_HASH_FAILED");
        assertThat(failed.error()).doesNotContain("bravo"); // the item text stays out of the snapshot
        assertThat(failed.completedItems()).isEqualTo(2);
        assertThat(failed.succeededItems()).containsExactly(0, 2);
        assertThat(failed.failedItems()).containsExactly(1);
    }

    /**
     * DOD-02 / REQ-01: a batch in which every item's hash throws still terminalizes as a per-item
     * failure - FAILED with {@code BATCH_ITEM_HASH_FAILED}, no results published and nothing to keep
     * - while each item is still attempted exactly once.
     */
    @Test
    @DisplayName("per-item failure - a batch whose items all throw fails with BATCH_ITEM_HASH_FAILED")
    void shouldFailJobWithPerItemErrorWhenEveryItemHashThrows() {
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("alpha", "bravo", "charlie"), Set.of());
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null)).jobId(),
                JobStatus.FAILED);

        // No success to lose: every item was attempted once and recorded as failed.
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(1);
        assertThat(failed.results()).isNull();
        assertThat(failed.error()).isEqualTo("BATCH_ITEM_HASH_FAILED");
        assertThat(failed.completedItems()).isZero();
        assertThat(failed.succeededItems()).isEmpty();
        assertThat(failed.failedItems()).containsExactly(0, 1, 2);
    }

    /**
     * DOD-03 / REQ-02: the per-item tracking is observable on both surfaces - ascending
     * {@code succeededItems}/{@code failedItems} on the snapshot and the matching index lists on the
     * attempt-history row of the failing attempt.
     */
    @Test
    @DisplayName("per-item failure - the attempt row carries the same index lists as the snapshot")
    void shouldExposePerItemTrackingOnSnapshotAndAttemptRow() {
        service = new BatchHashJobService(new ItemFaultCryptoService(Set.of("bravo"), Set.of()), 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null)).jobId(),
                JobStatus.FAILED);

        assertThat(failed.attempts()).hasSize(1);
        JobAttempt row = failed.attempts().get(0);
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.status()).isEqualTo(JobStatus.FAILED);
        assertThat(row.error()).isEqualTo(failed.error());
        assertThat(row.succeededItems()).isEqualTo(failed.succeededItems());
        assertThat(row.failedItems()).isEqualTo(failed.failedItems());
        assertThat(row.resumedFrom()).isZero();
        // A terminal batch keeps the per-item projections of the attempt that produced them.
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(failed.completedItems()).isEqualTo(2);
    }

    /**
     * DOD-06 / REQ-03: the attempt's closing journal entry replays the partial outcome in the plan's
     * schema - {@code checkpointItems}, {@code itemSuccesses}, {@code itemFailures} and the bounded
     * {@code itemFailureErrors} - with the same job-wide projections the snapshot publishes, and
     * discloses neither the item pre-image nor the secret.
     */
    @Test
    @DisplayName("per-item failure - the attempt journal entry records per-item outcomes")
    void shouldRecordPerItemOutcomesInAttemptJournalEntry() {
        AuditJournalService journal = newJournal();
        service = new BatchHashJobService(new ItemFaultCryptoService(Set.of("bravo"), Set.of()), 1, 10, 300, 3,
                LEASE_SECONDS, 5000, 1, new MutableJobClock(LEASE_START), journal);

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", PARTIAL_ITEMS, "s3cret", null)).jobId(), JobStatus.FAILED);

        List<AuditEntry> entries = batchHashEntries(journal, failed.jobId());
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.state()).isEqualTo(JournalState.FAILED);
        assertThat(entry.errorCode()).isEqualTo(failed.error());
        assertThat(entry.errorCode()).isEqualTo("BATCH_ITEM_HASH_FAILED");
        assertThat(entry.result().path("outcome").asText()).isEqualTo("FAILED");
        assertThat(entry.result().path("resultCount").asInt()).isEqualTo(PARTIAL_ITEMS.size());
        assertThat(entry.result().path("completedItems").asInt()).isEqualTo(2);
        assertThat(entry.result().path("resumedFrom").asInt()).isZero();
        assertThat(entry.result().path("checkpointItems").asInt()).isEqualTo(2);
        assertThat(indexList(entry.result().path("itemSuccesses"))).containsExactly(0, 2);
        assertThat(indexList(entry.result().path("itemFailures"))).containsExactly(1);
        assertThat(entry.result().path("itemFailureErrors").get(0).path("index").asInt()).isEqualTo(1);
        assertThat(entry.result().path("itemFailureErrors").get(0).path("error").asText())
                .startsWith("IllegalStateException");
        // The journal row and the snapshot report the very same projections (no scope skew).
        assertThat(indexList(entry.result().path("itemSuccesses")))
                .isEqualTo(failed.succeededItems());
        assertThat(indexList(entry.result().path("itemFailures")))
                .isEqualTo(failed.failedItems());
        // Bounded, content-free detail: no item text and no secret key anywhere in the payload.
        assertThat(entry.result().toString()).doesNotContain("bravo").doesNotContain("s3cret");
    }

    /**
     * DOD-05 / INV-PI-9 / REQ-04: a retry of a batch whose every index is already recorded is admitted
     * as a new attempt and immediately terminalized FAILED - the requeued worker finds no remaining
     * index, hashes nothing, and the retained per-item state is unchanged.
     */
    @Test
    @DisplayName("retry - a batch whose every index is already recorded terminalizes without hashing")
    void shouldTerminalizeRetryWithoutHashingWhenNothingRemains() {
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("bravo"), Set.of());
        service = new BatchHashJobService(crypto, 1, 10, 300, 3);

        BatchHashJobResponse failed = awaitStatus(
                service.submit(new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null)).jobId(),
                JobStatus.FAILED);

        assertThat(service.retry(failed.jobId()).status()).isEqualTo(JobStatus.QUEUED);

        // Every index was hashed or failed, so the requeued attempt hashes nothing and goes straight
        // to FAILED: zero new hash calls, and the retained per-item state is unchanged.
        BatchHashJobResponse terminal = awaitStatus(failed.jobId(), JobStatus.FAILED);
        assertThat(terminal.attempt()).isEqualTo(2);
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(1);
        assertThat(terminal.succeededItems()).containsExactly(0, 2);
        assertThat(terminal.failedItems()).containsExactly(1);
        assertThat(terminal.results()).isNull();
        assertThat(terminal.attempts()).hasSize(2);
    }

    /**
     * DOD-04 / REQ-04: a retry resumes at the first unprocessed index - the item hashed before the
     * stalled worker was reaped is never hashed twice, and only the remaining ones are hashed.
     */
    @Test
    @DisplayName("retry - resume starts at the first unprocessed index and never re-hashes a processed item")
    void shouldResumeOnlyUnprocessedItemsOnRetry() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("faulty"), Set.of("stall"));
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha", "stall", "tail"), null, null));
        assertThat(crypto.awaitEntered("stall")).isTrue();

        // The worker stalls inside item 1 with item 0 already committed; the reaper fails the attempt.
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse reaped = service.getJob(accepted.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.completedItems()).isEqualTo(1);
        assertThat(reaped.succeededItems()).containsExactly(0);
        assertThat(reaped.failedItems()).isEmpty();

        crypto.unblock("stall");
        assertThat(service.retry(accepted.jobId()).attempt()).isEqualTo(2);
        BatchHashJobResponse resumed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);

        assertThat(resumed.results()).hasSize(3);
        assertThat(resumed.attempts()).hasSize(2);
        assertThat(resumed.attempts().get(1).resumedFrom()).isEqualTo(1);
        assertThat(crypto.count("alpha")).isEqualTo(1); // the committed prefix was not re-hashed
        assertThat(crypto.count("stall")).isEqualTo(2); // once before the reap, once on the resume
        assertThat(crypto.count("tail")).isEqualTo(1);
    }

    /**
     * DOD-06 / REQ-03 (F3): a resumed attempt's journal row reports the job-wide outcome - the prefix it
     * inherited plus the indices it hashed itself - so the row and the snapshot agree on both the
     * success side and the failure side instead of mixing attempt scope with job scope.
     */
    @Test
    @DisplayName("per-item outcomes - a resumed attempt's journal row includes the inherited prefix")
    void shouldReportJobWideItemOutcomesOnResumedAttempt() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("charlie"), Set.of("bravo"));
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock, journal);

        // Item 0 commits, then item 1 stalls: the reap keeps index 0 as the durable prefix.
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null));
        assertThat(crypto.awaitEntered("bravo")).isTrue();
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

        // The resume starts at index 1 and fails on index 2: index 1 succeeds on this attempt only.
        crypto.unblock("bravo");
        assertThat(service.retry(accepted.jobId()).attempt()).isEqualTo(2);
        BatchHashJobResponse failed = awaitStatus(accepted.jobId(), JobStatus.FAILED);
        assertThat(failed.failedItems()).containsExactly(2);
        assertThat(failed.succeededItems()).containsExactly(0, 1);

        AuditEntry retryEntry = batchHashEntries(journal, accepted.jobId()).stream()
                .filter(entry -> entry.request().path("attempt").asInt() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(retryEntry.state()).isEqualTo(JournalState.FAILED);
        assertThat(retryEntry.request().path("resumedFrom").asInt()).isEqualTo(1);
        assertThat(retryEntry.result().path("resumedFrom").asInt()).isEqualTo(1);
        assertThat(retryEntry.result().path("checkpointItems").asInt()).isEqualTo(2);
        // Job-wide on both sides: index 0 came from the inherited prefix, not from this attempt.
        assertThat(indexList(retryEntry.result().path("itemSuccesses"))).containsExactly(0, 1);
        assertThat(indexList(retryEntry.result().path("itemFailures"))).containsExactly(2);
        assertThat(indexList(retryEntry.result().path("itemSuccesses")))
                .isEqualTo(failed.succeededItems());
        assertThat(indexList(retryEntry.result().path("itemFailures")))
                .isEqualTo(failed.failedItems());
    }

    /**
     * REQ-02 / INV-CAN-2 (F1): a cancel that lands while a <em>failing</em> item is in flight still
     * wins - the item-failure writer sees {@code CANCELLING}, terminalizes the job CANCELLED instead of
     * recording the failure, withdraws the per-item state and stops the loop, so the remaining item is
     * never hashed.
     */
    @Test
    @DisplayName("cancel - a cancel that lands while a failing item is in flight wins over the failure")
    void shouldCancelWhenFailingItemIsInFlight() throws Exception {
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("bravo"), Set.of("bravo"));
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC());

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", PARTIAL_ITEMS, null, null));
        assertThat(crypto.awaitEntered("bravo")).isTrue(); // item 0 committed, item 1 in flight
        assertThat(service.cancel(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);
        crypto.unblock("bravo"); // the in-flight hash now throws: the writer sees CANCELLING

        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(cancelled.error()).isNull(); // a withdrawal, never BATCH_ITEM_HASH_FAILED
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.completedItems()).isZero();
        assertThat(cancelled.succeededItems()).isEmpty();
        assertThat(cancelled.failedItems()).isEmpty();
        assertThat(cancelled.attempts()).hasSize(1);
        assertThat(cancelled.attempts().get(0).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.attempts().get(0).succeededItems()).isEmpty();
        assertThat(cancelled.attempts().get(0).failedItems()).isEmpty();
        // The loop stopped at the cancellation: the remaining item was never hashed.
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isZero();
    }

    /**
     * DOD-08 / REQ-05: the lease and heartbeat keep working across item failures - the lease expires
     * afresh for every remaining item hash, a heartbeat still extends a job that already has a failed
     * item, and an expired lease is still reaped to FAILED with {@code EXECUTION_LEASE_EXPIRED}.
     */
    @Test
    @DisplayName("per-item failure - the lease is renewed per item and the reaper still fails a lapsed lease")
    void shouldRenewLeaseAndHeartbeatAcrossItemFailures() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("faulty"), Set.of("stall"));
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        // Item 0 fails, item 1 stalls: the batch survives the failure with the lease still live.
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("faulty", "stall", "tail"), null, null));
        assertThat(crypto.awaitEntered("stall")).isTrue();
        BatchHashJobResponse running = service.getJob(accepted.jobId());
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.failedItems()).containsExactly(0);
        assertThat(running.executionLeaseExpiresAt()).isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));

        // A heartbeat after the item failure still extends the running attempt.
        clock.advanceSeconds(LEASE_SECONDS - 1);
        BatchHashJobResponse renewed = service.heartbeat(accepted.jobId());
        assertThat(renewed.lastHeartbeatAt()).isEqualTo(clock.instant());
        assertThat(renewed.executionLeaseExpiresAt()).isEqualTo(clock.instant().plusSeconds(LEASE_SECONDS));
        assertThat(renewed.failedItems()).containsExactly(0);

        // Let the lease lapse: the reaper still moves the job to FAILED with the unchanged error code.
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse reaped = service.getJob(accepted.jobId());
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(reaped.results()).isNull();
        assertThat(crypto.count("tail")).isZero();
    }

    /**
     * DOD-09 / REQ-02: a cancellation withdraws the whole batch, so the CANCELLED snapshot reports
     * empty per-item lists and discloses no partial outcome.
     */
    @Test
    @DisplayName("cancel - a job with per-item outcomes reports empty index lists once cancelled")
    void shouldDiscardPerItemOutcomesOnCancel() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        ItemFaultCryptoService crypto = new ItemFaultCryptoService(Set.of("faulty"), Set.of("stall"));
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("faulty", "stall", "tail"), null, null));
        assertThat(crypto.awaitEntered("stall")).isTrue();
        assertThat(service.getJob(accepted.jobId()).failedItems()).containsExactly(0);

        BatchHashJobResponse cancelling = service.cancel(accepted.jobId());
        assertThat(cancelling.failedItems()).isEmpty();
        assertThat(cancelling.succeededItems()).isEmpty();

        crypto.unblock("stall");
        BatchHashJobResponse cancelled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.succeededItems()).isEmpty();
        assertThat(cancelled.failedItems()).isEmpty();
        assertThat(cancelled.completedItems()).isZero();
        assertThat(cancelled.attempts()).hasSize(1);
        assertThat(cancelled.attempts().get(0).succeededItems()).isEmpty();
        assertThat(cancelled.attempts().get(0).failedItems()).isEmpty();
    }

    /**
     * Hashing stub for the per-item failure tests: counts invocations per item, throws a content-free
     * {@link IllegalStateException} for every item of {@code failingItems} and blocks the worker on a
     * latch for every item of {@code gatedItems} until {@link #unblock(String)} releases it. Every
     * other item delegates to real hashing, so digests stay comparable with {@link CryptoService}.
     */
    private static final class ItemFaultCryptoService extends CryptoService {

        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final Set<String> failingItems;

        private ItemFaultCryptoService(Set<String> failingItems, Set<String> gatedItems) {
            this.failingItems = Set.copyOf(failingItems);
            for (String item : gatedItems) {
                gates.put(item, new Gate());
            }
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
            // The gate is consulted before the fault, so an item can be both gated and failing: the
            // worker parks inside the hash with the caller in control of when it throws.
            Gate gate = gates.get(request.content());
            if (gate != null) {
                gate.entered.countDown();
                try {
                    gate.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failingItems.contains(request.content())) {
                throw new IllegalStateException("synthetic hash fault");
            }
            return super.hash(request);
        }

        private static final class Gate {
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
        }
    }

    private static AuditJournalService newJournal() {
        return new AuditJournalService(new ObjectMapper(), 200, 65536, 300L);
    }

    /** The integer values of a JSON array node (item index lists in the journal payload). */
    private static List<Integer> indexList(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(child -> out.add(child.asInt()));
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Fenced checkpoint resume (REQ-01..REQ-06)
    // ---------------------------------------------------------------------------------------------

    /** A four-item batch: big enough that losing the prefix would be visible in the counters. */
    private static final List<String> CHECKPOINT_ITEMS = List.of("alpha", "bravo", "charlie", "delta");

    /**
     * DOD-01 / REQ-01..REQ-03: a transient fault on item 2 leaves the two committed digests in the
     * private prefix, reports them as {@code completedItems} and publishes nothing as results.
     */
    @Test
    @DisplayName("checkpoint - a transient failure keeps the durable prefix and leaves results null")
    void shouldExposeCheckpointPrefixOnFailureAndKeepResultsNull() {
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of("charlie", 1), null);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, Clock.systemUTC());

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null)).jobId(), JobStatus.FAILED);

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.itemCount()).isEqualTo(4);
        assertThat(failed.completedItems()).isEqualTo(2); // the two hashes that succeeded
        assertThat(failed.results()).isNull(); // a prefix is never disclosed as results
        assertThat(failed.error()).contains("charlie");
        assertThat(failed.attempts()).hasSize(1);
        assertThat(failed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts().get(0).resumedFrom()).isZero();

        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(1);
        assertThat(crypto.count("delta")).isZero(); // never reached
    }

    /**
     * DOD-02 / REQ-02, REQ-03: the retry resumes at the prefix's next index, so only the suffix is
     * hashed and the completed results equal the full batch in input order.
     */
    @Test
    @DisplayName("checkpoint - retry resumes at the prefix's next index and publishes the full ordered batch")
    void shouldResumeAtCheckpointIndexOnRetryWithoutRehashingCompletedItems() {
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of("charlie", 1), null);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, Clock.systemUTC());

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null)).jobId(), JobStatus.FAILED);
        assertThat(failed.completedItems()).isEqualTo(2);

        BatchHashJobResponse requeued = service.retry(failed.jobId());
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(requeued.completedItems()).isEqualTo(2); // the prefix survives the requeue
        assertThat(requeued.results()).isNull(); // ...but stays unpublished

        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        CryptoService reference = new CryptoService();
        List<HashResponse> expected = new ArrayList<>();
        for (String item : CHECKPOINT_ITEMS) {
            expected.add(reference.hash(new HashRequest("SHA-256", item, null)));
        }
        assertThat(completed.results()).isEqualTo(expected); // whole batch, input order
        assertThat(completed.completedItems()).isEqualTo(4);
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.attempts().get(0).resumedFrom()).isZero();
        assertThat(completed.attempts().get(1).resumedFrom()).isEqualTo(2);
        assertThat(completed.attempts().get(1).status()).isEqualTo(JobStatus.COMPLETED);

        // Only the suffix ran again: the item that threw is retried, nothing before it.
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(2);
        assertThat(crypto.count("delta")).isEqualTo(1);
    }

    /**
     * DOD-03 / REQ-02, REQ-03: a worker whose attempt was reaped cannot advance the checkpoint, and
     * replaying a superseded or already-finished generation writes nothing.
     */
    @Test
    @DisplayName("checkpoint - a stale worker from a reaped or superseded attempt cannot advance it")
    void shouldFenceStaleWorkerFromAdvancingCheckpoint() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stalled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                List.of("first", "stalled", "third", "fourth"), null, null));
        assertThat(crypto.awaitEntered("stalled")).isTrue(); // "first" is hashed and committed
        assertThat(service.getJob(accepted.jobId()).completedItems()).isEqualTo(1);

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse before = service.getJob(accepted.jobId());
        assertThat(before.status()).isEqualTo(JobStatus.FAILED);
        assertThat(before.error()).isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        assertThat(before.completedItems()).isEqualTo(1);
        assertThat(before.results()).isNull();

        // The late worker returns from hash() inside a job it no longer owns: the fenced commit
        // writes nothing - no prefix append, no results, no error, no status.
        crypto.unblock("stalled");
        BatchHashJobResponse probe = service.submit(
                new BatchHashRequest("SHA-256", List.of("probe"), null, null));
        awaitStatus(probe.jobId(), JobStatus.COMPLETED); // the stale worker has returned by now
        assertThat(service.getJob(accepted.jobId())).isEqualTo(before);
        assertThat(service.getJob(accepted.jobId()).completedItems()).isEqualTo(1);

        // Replaying the reaped generation is a no-op: the job is not QUEUED any more.
        service.executeAttempt(accepted.jobId(), 1);
        assertThat(service.getJob(accepted.jobId())).isEqualTo(before);

        // The prefix survived the reap, and the stale worker did not append "stalled" to it.
        service.retry(accepted.jobId());
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempts().get(1).resumedFrom()).isEqualTo(1);
        assertThat(crypto.count("first")).isEqualTo(1); // never rehashed
        assertThat(crypto.count("stalled")).isEqualTo(2); // once for the reaped attempt, once now
        assertThat(crypto.count("third")).isEqualTo(1);
        assertThat(crypto.count("fourth")).isEqualTo(1);

        // ...and replaying a finished generation writes nothing either.
        service.executeAttempt(accepted.jobId(), 1);
        service.executeAttempt(accepted.jobId(), 2);
        assertThat(service.getJob(accepted.jobId())).isEqualTo(completed);
    }

    /**
     * DOD-04 / REQ-04: a queue-full rejection restores the exact pre-retry FAILED snapshot -
     * including the attempt counter and the checkpoint - so the retry can be repeated for free.
     */
    @Test
    @DisplayName("checkpoint - a queue-full retry restores the FAILED snapshot and loses no progress")
    void shouldRestoreCheckpointOnQueueFullRetry() throws Exception {
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of("charlie", 1), "blocker");
        service = new BatchHashJobService(crypto, 1, 1, 300, 3, LEASE_SECONDS, 5000, Clock.systemUTC());

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null)).jobId(), JobStatus.FAILED);
        assertThat(failed.completedItems()).isEqualTo(2);

        // Saturate the single worker and its single queue slot so the retry is rejected.
        BatchHashJobResponse blocker = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocker"), null, null));
        assertThat(crypto.awaitGate()).isTrue();
        BatchHashJobResponse filler = service.submit(
                new BatchHashRequest("SHA-256", List.of("filler"), null, null));
        assertThat(filler.status()).isEqualTo(JobStatus.QUEUED);

        BatchHashJobResponse before = service.getJob(failed.jobId());
        assertThatThrownBy(() -> service.retry(failed.jobId())).isInstanceOf(JobQueueFullException.class);
        BatchHashJobResponse after = service.getJob(failed.jobId());
        assertThat(after).isEqualTo(before); // status, attempt and checkpoint all restored
        assertThat(after.attempt()).isEqualTo(1);
        assertThat(after.completedItems()).isEqualTo(2);

        // Capacity frees, so the same retry now runs - and completes from the checkpoint.
        crypto.releaseGate();
        awaitStatus(blocker.jobId(), JobStatus.COMPLETED);
        awaitStatus(filler.jobId(), JobStatus.COMPLETED);
        service.retry(failed.jobId());
        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.attempts().get(1).resumedFrom()).isEqualTo(2);
        assertThat(completed.results()).hasSize(4);
        assertThat(completed.completedItems()).isEqualTo(4);
        assertThat(crypto.count("alpha")).isEqualTo(1);
        assertThat(crypto.count("bravo")).isEqualTo(1);
        assertThat(crypto.count("charlie")).isEqualTo(2);
        assertThat(crypto.count("delta")).isEqualTo(1);
    }

    /**
     * DOD-07 / REQ-05: attempt history shows {@code resumedFrom}, the journal descriptor and the
     * SUCCEEDED outcome carry {@code resumedFrom}/{@code completedItems}, no item or secret key ever
     * reaches either, and a throwing journal leaves the checkpoint projection untouched.
     */
    @Test
    @DisplayName("checkpoint - attempt history and the BATCH_HASH journal record counts only, never items")
    void shouldRecordResumedFromAndCompletedItemsInAttemptHistoryAndAudit() throws Exception {
        AuditJournalService journal = newJournal();
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of("charlie", 1), null);
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), journal);
        String secretKey = "s3cr3t-key-value";

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, secretKey, null)).jobId(),
                JobStatus.FAILED);
        List<AuditEntry> afterFailure = batchHashEntries(journal, failed.jobId());
        assertThat(afterFailure).hasSize(1);
        AuditEntry firstEntry = afterFailure.get(0);
        assertThat(firstEntry.state()).isEqualTo(JournalState.FAILED);
        assertThat(firstEntry.request().path("resumedFrom").asInt()).isZero();
        assertThat(firstEntry.request().path("completedItems").asInt()).isZero(); // nothing was committed yet

        service.retry(failed.jobId());
        BatchHashJobResponse completed = awaitStatus(failed.jobId(), JobStatus.COMPLETED);
        assertThat(completed.attempts().get(0).resumedFrom()).isZero();
        assertThat(completed.attempts().get(1).resumedFrom()).isEqualTo(2);

        AuditEntry retryEntry = batchHashEntries(journal, failed.jobId()).stream()
                .filter(entry -> !entry.id().equals(firstEntry.id()))
                .findFirst()
                .orElseThrow();
        assertThat(retryEntry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(retryEntry.request().path("attempt").asInt()).isEqualTo(2);
        assertThat(retryEntry.request().path("resumedFrom").asInt()).isEqualTo(2);
        assertThat(retryEntry.request().path("completedItems").asInt()).isEqualTo(2);
        assertThat(retryEntry.result().path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(retryEntry.result().path("resultCount").asInt()).isEqualTo(4);
        assertThat(retryEntry.result().path("resumedFrom").asInt()).isEqualTo(2);
        assertThat(retryEntry.result().path("completedItems").asInt()).isEqualTo(4);
        String serialized = retryEntry.request() + "|" + retryEntry.result();
        assertThat(serialized)
                .doesNotContain("alpha")
                .doesNotContain("bravo")
                .doesNotContain("charlie")
                .doesNotContain("delta")
                .doesNotContain(secretKey);

        // A throwing journal cannot change whether the prefix is retained and reported.
        CheckpointCryptoService faultyCrypto = new CheckpointCryptoService(Map.of("charlie", 1), null);
        service = new BatchHashJobService(faultyCrypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                Clock.systemUTC(), new FailingJournal(false));
        BatchHashJobResponse journalFault = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null)).jobId(), JobStatus.FAILED);
        assertThat(journalFault.status()).isEqualTo(JobStatus.FAILED);
        assertThat(journalFault.results()).isNull();
        assertThat(journalFault.completedItems()).isEqualTo(2);
    }

    /**
     * DOD-08 / REQ-06: withdrawing a QUEUED job that already carries a durable prefix discards the
     * prefix, so a cancelled job never publishes or exposes checkpoint results.
     */
    @Test
    @DisplayName("checkpoint - a cancelled job publishes no checkpoint results")
    void shouldNotExposeCheckpointForCancelledJob() throws Exception {
        CheckpointCryptoService crypto = new CheckpointCryptoService(Map.of("charlie", 1), "blocker");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, Clock.systemUTC());

        BatchHashJobResponse failed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", CHECKPOINT_ITEMS, null, null)).jobId(), JobStatus.FAILED);
        assertThat(failed.completedItems()).isEqualTo(2);

        BatchHashJobResponse blocker = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocker"), null, null));
        assertThat(crypto.awaitGate()).isTrue(); // the only worker is busy, so the retry stays QUEUED
        BatchHashJobResponse requeued = service.retry(failed.jobId());
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.completedItems()).isEqualTo(2); // the prefix is carried into the requeue

        BatchHashJobResponse cancelled = service.cancel(failed.jobId());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.completedItems()).isZero(); // the prefix was discarded
        assertThat(service.getJob(failed.jobId())).isEqualTo(cancelled);

        crypto.releaseGate();
        awaitStatus(blocker.jobId(), JobStatus.COMPLETED);
        assertThat(service.getJob(failed.jobId()).completedItems()).isZero();
        assertThat(service.getJob(failed.jobId()).results()).isNull();
        assertThat(crypto.count("delta")).isZero(); // the cancelled attempt hashed nothing more
    }

    /**
     * Hashing stub for the checkpoint tests: counts invocations per item, fails a designated item a
     * configurable number of times (a transient fault) and optionally blocks the worker inside
     * {@code hash()} on a designated item so a job can be held in QUEUED / RUNNING. Every other item
     * delegates to real hashing so digests stay comparable with {@link CryptoService}.
     */
    private static final class CheckpointCryptoService extends CryptoService {

        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();
        private final CountDownLatch gateEntered = new CountDownLatch(1);
        private final CountDownLatch gateReleased = new CountDownLatch(1);
        private final String gateItem;

        private CheckpointCryptoService(Map<String, Integer> failures, String gateItem) {
            failures.forEach((item, count) -> remainingFailures.put(item, new AtomicInteger(count)));
            this.gateItem = gateItem;
        }

        private boolean awaitGate() throws InterruptedException {
            return gateItem != null && gateEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void releaseGate() {
            gateReleased.countDown();
        }

        private int count(String item) {
            AtomicInteger counter = invocations.get(item);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public HashResponse hash(HashRequest request) {
            invocations.computeIfAbsent(request.content(), key -> new AtomicInteger()).incrementAndGet();
            AtomicInteger remaining = remainingFailures.get(request.content());
            if (remaining != null && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new WholeAttemptFault("transient hashing failure for " + request.content());
            }
            if (gateItem != null && gateItem.equals(request.content())) {
                gateEntered.countDown();
                try {
                    gateReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.hash(request);
        }
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

        @Override
        public AuditEntry fail(String id, String errorCode, Object result) {
            throw new IllegalStateException("journal fail unavailable");
        }
    }
    // ---------------------------------------------------------------------------------------------
    // Per-client active-job quota: admission, release, rollback, retry and concurrency (REQ-01..REQ-08)
    // ---------------------------------------------------------------------------------------------

    /**
     * DOD-21 / REQ-01, REQ-03, REQ-04: a barrier of submitters on one contended client admits exactly
     * the configured limit. The refused submissions carry the bounded {@code limit}/{@code activeJobs}
     * pair, register no job row and no idempotency mapping, and every admitted job executes once.
     */
    @Test
    @DisplayName("quota - a barrier of submitters admits exactly the per-client limit and leaves no residue")
    void shouldAdmitExactlyThePerClientLimitUnderBarrier() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("blocker");
        int limit = 3;
        service = new BatchHashJobService(crypto, 1, 20, 300, 3, LEASE_SECONDS, 5000, limit,
                Clock.systemUTC());
        String client = "203.0.113.61";
        int threads = 12;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService submitters = Executors.newFixedThreadPool(threads);
        List<BatchHashJobResponse> accepted = Collections.synchronizedList(new ArrayList<>());
        List<JobQuotaExceededException> refused = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "barrier-key-" + i;
                futures.add(submitters.submit(() -> {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    try {
                        accepted.add(service.submit(
                                new BatchHashRequest("SHA-256", List.of("blocker"), null, key), client));
                    } catch (JobQuotaExceededException e) {
                        refused.add(e);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            submitters.shutdownNow();
        }

        assertThat(accepted).hasSize(limit);
        assertThat(refused).hasSize(threads - limit);
        for (JobQuotaExceededException e : refused) {
            assertThat(e.limit()).isEqualTo(limit);
            assertThat(e.activeJobs()).isEqualTo(limit);
        }
        assertThat(service.activeJobsForClient(client)).isEqualTo(limit);
        assertThat(registryKeys("jobs")).hasSize(limit);
        assertThat(registryKeys("idempotencyIndex")).hasSize(limit); // no mapping for a refused key

        crypto.unblock("blocker");
        for (BatchHashJobResponse response : List.copyOf(accepted)) {
            assertThat(awaitStatus(response.jobId(), JobStatus.COMPLETED).results()).hasSize(1);
        }
        awaitActiveJobs(client, 0);
        assertThat(crypto.count("blocker")).isEqualTo(limit); // only admitted jobs ever ran
        assertThat(registryKeys("idempotencyIndex")).hasSize(limit); // ...and no orphan entry appeared
    }

    /**
     * DOD-02 / REQ-04: a refused submission registers no job row and no idempotency mapping, and the
     * client's counter is left at the limit it observed.
     */
    @Test
    @DisplayName("quota - a submission at the limit is refused without a job row or idempotency mapping")
    void shouldRejectSubmissionAtQuotaWithoutLeavingJobOrIdempotencyResidue() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("blocker");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String client = "203.0.113.62";

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocker"), null, "admitted-key"), client);
        assertThat(crypto.awaitEntered("blocker")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        Set<String> jobsBefore = registryKeys("jobs");
        Set<String> keysBefore = registryKeys("idempotencyIndex");

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("overflow"), null, "refused-key"), client))
                .isInstanceOf(JobQuotaExceededException.class)
                .hasMessageContaining("limit=1")
                .hasMessageContaining("activeJobs=1");

        assertThat(registryKeys("jobs")).isEqualTo(jobsBefore);
        assertThat(registryKeys("idempotencyIndex")).isEqualTo(keysBefore).containsExactly("admitted-key");
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        assertThatThrownBy(() -> service.getJob("refused-key"))
                .isInstanceOf(JobNotFoundException.class);

        crypto.unblock("blocker");
        awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-03 (COMPLETED half) / REQ-05: the slot held by a COMPLETED job and by a worker-FAILED job is
     * released, so a single-slot quota admits the next job in both cases.
     */
    @Test
    @DisplayName("quota - release on COMPLETED and on worker FAILED frees the slot for the next job")
    void shouldReleaseQuotaOnCompletedAndWorkerFailed() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        crypto.failAfterRelease("boom");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String client = "203.0.113.63";

        BatchHashJobResponse failing = service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null), client);
        assertThat(crypto.awaitEntered("boom")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        crypto.unblock("boom");
        BatchHashJobResponse failed = awaitStatus(failing.jobId(), JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("BATCH_ITEM_HASH_FAILED");
        assertThat(failed.failedItems()).containsExactly(0);
        awaitActiveJobs(client, 0);

        // The released slot admits a second job immediately, and its COMPLETED transition releases it.
        BatchHashJobResponse succeeding = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), client);
        assertThat(crypto.awaitEntered("gate")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        crypto.unblock("gate");
        awaitStatus(succeeding.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-03 (reaper + cancel halves) / REQ-05: the scheduled execution reaper's FAILED write and a
     * QUEUED cancellation each release exactly one slot.
     */
    @Test
    @DisplayName("quota - release on reaper FAILED and on QUEUED cancellation frees one slot each")
    void shouldReleaseQuotaOnReaperFailedAndCancellation() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stall", "cancelled");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock);
        String client = "198.51.100.4";

        BatchHashJobResponse stalled = service.submit(
                new BatchHashRequest("SHA-256", List.of("stall"), null, null), client);
        assertThat(crypto.awaitEntered("stall")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);

        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        assertThat(service.getJob(stalled.jobId()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(service.getJob(stalled.jobId()).error())
                .isEqualTo(BatchHashJobService.EXECUTION_LEASE_EXPIRED);
        awaitActiveJobs(client, 0);

        // The freed slot admits a job while the stale worker is still blocked inside hash(); with one
        // worker it stays QUEUED, so cancellation is the transition that has to release it.
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("cancelled"), null, null), client);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        assertThat(service.cancel(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);
        awaitActiveJobs(client, 0);

        crypto.unblock("stall"); // the fenced worker returns and must not release a second slot
        awaitActiveJobs(client, 0);
        assertThat(service.getJob(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);
    }

    /**
     * DOD-03 (no double release) / REQ-05: a worker that returns after the reaper already failed its
     * attempt cannot decrement the client's counter again — the job it was covering has been replaced
     * by a live reservation.
     */
    @Test
    @DisplayName("quota - a late worker fenced by the reaper cannot decrement the counter a second time")
    void shouldNotReleaseQuotaTwiceWhenLateWorkerReturnsAfterReap() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("stale", "fresh");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, clock);
        String client = "198.51.100.7";

        BatchHashJobResponse stale = service.submit(
                new BatchHashRequest("SHA-256", List.of("stale"), null, null), client);
        assertThat(crypto.awaitEntered("stale")).isTrue();
        clock.advanceSeconds(LEASE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        awaitActiveJobs(client, 0);

        BatchHashJobResponse fresh = service.submit(
                new BatchHashRequest("SHA-256", List.of("fresh"), null, null), client);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);

        crypto.unblock("stale"); // release the stale worker: it must fail its fence and release nothing
        assertThat(crypto.awaitEntered("fresh")).isTrue(); // ...and the new attempt takes the worker
        assertThat(service.getJob(stale.jobId()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1); // no second decrement

        crypto.unblock("fresh");
        awaitStatus(fresh.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-04 / REQ-05: terminal jobs have already released their slot, so a repeated cancellation and
     * the eviction pass are both quota-neutral.
     */
    @Test
    @DisplayName("quota - repeated cancellation and eviction release nothing further")
    void shouldNotReleaseQuotaAgainOnEvictionOrRepeatedCancel() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("blocker");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 2, clock);
        String holder = "198.51.100.8";
        String client = "198.51.100.9";

        service.submit(new BatchHashRequest("SHA-256", List.of("blocker"), null, null), holder);
        assertThat(crypto.awaitEntered("blocker")).isTrue(); // the only worker is occupied

        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("victim"), null, "evict-key"), client);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);

        assertThat(service.cancel(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED);
        awaitActiveJobs(client, 0);
        assertThat(service.cancel(queued.jobId()).status()).isEqualTo(JobStatus.CANCELLED); // idempotent
        assertThat(service.activeJobsForClient(client)).isZero();
        assertThat(service.activeJobsForClient(holder)).isEqualTo(1);

        assertThat(service.evictExpiredJobs(clock.instant().plusSeconds(301))).isEqualTo(1);
        assertThatThrownBy(() -> service.getJob(queued.jobId())).isInstanceOf(JobNotFoundException.class);
        assertThat(service.activeJobsForClient(client)).isZero(); // eviction released nothing
        assertThat(service.activeJobsForClient(holder)).isEqualTo(1); // RUNNING job keeps its slot

        crypto.unblock("blocker");
    }

    /**
     * DOD-05 (both halves) / REQ-06: a retry re-acquires a slot before FAILED -> QUEUED; when the
     * client is at its limit the job stays FAILED with its attempt count untouched.
     */
    @Test
    @DisplayName("quota - retry re-acquires a slot and leaves the FAILED snapshot intact when exhausted")
    void shouldReacquireQuotaOnRetryAndLeaveFailedSnapshotWhenExhausted() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        crypto.failAfterRelease("boom");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String client = "192.0.2.10";

        BatchHashJobResponse submitted = service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null), client);
        assertThat(crypto.awaitEntered("boom")).isTrue();
        crypto.unblock("boom");
        BatchHashJobResponse failed = awaitStatus(submitted.jobId(), JobStatus.FAILED);
        awaitActiveJobs(client, 0);

        BatchHashJobResponse blocking = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), client);
        assertThat(crypto.awaitEntered("gate")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);

        BatchHashJobResponse atLimit = service.getJob(failed.jobId());
        assertThatThrownBy(() -> service.retry(failed.jobId()))
                .isInstanceOf(JobQuotaExceededException.class)
                .hasMessageContaining("limit=1")
                .hasMessageContaining("activeJobs=1");
        assertThat(service.getJob(failed.jobId())).isEqualTo(atLimit); // FAILED, attempt unchanged
        assertThat(atLimit.attempt()).isEqualTo(1);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1); // refused retry reserved nothing

        crypto.unblock("gate");
        awaitStatus(blocking.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);

        BatchHashJobResponse requeued = service.retry(failed.jobId());
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempt()).isEqualTo(2);
        // The retry reserved a slot before FAILED -> QUEUED; the single worker may already have
        // re-failed the attempt (releasing it again) by the time we look, so the observation is
        // bounded rather than exactly one.
        assertThat(service.activeJobsForClient(client)).isBetween(0, 1);

        awaitStatus(failed.jobId(), JobStatus.FAILED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-05 (rejected requeue) / REQ-06: when the executor rejects the requeued attempt the previous
     * FAILED snapshot is restored, the attempt count is not consumed, and the reservation is released.
     */
    @Test
    @DisplayName("quota - a requeued attempt rejected by the executor restores FAILED and releases the slot")
    void shouldRestoreFailedSnapshotAndReleaseOnRejectedRetry() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        crypto.failAfterRelease("boom");
        service = new BatchHashJobService(crypto, 1, 1, 300, 3, LEASE_SECONDS, 5000, 3,
                Clock.systemUTC());
        String client = "192.0.2.11";

        BatchHashJobResponse submitted = service.submit(
                new BatchHashRequest("SHA-256", List.of("boom"), null, null), client);
        assertThat(crypto.awaitEntered("boom")).isTrue();
        crypto.unblock("boom");
        BatchHashJobResponse failed = awaitStatus(submitted.jobId(), JobStatus.FAILED);
        awaitActiveJobs(client, 0);

        service.submit(new BatchHashRequest("SHA-256", List.of("gate"), null, null), client);
        assertThat(crypto.awaitEntered("gate")).isTrue(); // occupies the only worker
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("queued"), null, null), client);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED); // ...and the single queue slot
        assertThat(service.activeJobsForClient(client)).isEqualTo(2);

        BatchHashJobResponse before = service.getJob(failed.jobId());
        assertThatThrownBy(() -> service.retry(failed.jobId()))
                .isInstanceOf(JobQueueFullException.class)
                .hasMessageContaining("queue-capacity=1");

        assertThat(service.getJob(failed.jobId())).isEqualTo(before); // FAILED + attempt 1 restored
        assertThat(before.attempt()).isEqualTo(1);
        assertThat(service.activeJobsForClient(client)).isEqualTo(2); // the retry's slot was released

        crypto.unblock("gate");
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-07 (duplicate half) / REQ-03: at the quota boundary a same-key/same-payload duplicate returns
     * the existing job instead of a quota error, and consumes no second slot.
     */
    @Test
    @DisplayName("quota - a duplicate submission at the boundary returns the existing job")
    void shouldReturnExistingJobForDuplicateSubmissionAtQuotaBoundary() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String client = "203.0.113.20";
        BatchHashRequest request = new BatchHashRequest("SHA-256", List.of("gate"), null, "boundary-key");

        BatchHashJobResponse first = service.submit(request, client);
        assertThat(crypto.awaitEntered("gate")).isTrue();
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);

        BatchHashJobResponse duplicate = service.submit(request, client);
        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(service.activeJobsForClient(client)).isEqualTo(1); // no second slot
        assertThat(registryKeys("jobs")).hasSize(1);

        crypto.unblock("gate");
        awaitStatus(first.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /** DOD-07 (independent clients) / REQ-01: distinct client keys hold distinct counters. */
    @Test
    @DisplayName("quota - different clients are admitted independently of each other's limit")
    void shouldAdmitDifferentClientsIndependently() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        service = new BatchHashJobService(crypto, 2, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String first = "203.0.113.31";
        String second = "203.0.113.32";

        BatchHashJobResponse firstJob = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), first);
        BatchHashJobResponse secondJob = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), second);
        assertThat(service.activeJobsForClient(first)).isEqualTo(1);
        assertThat(service.activeJobsForClient(second)).isEqualTo(1);

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("other"), null, null), first))
                .isInstanceOf(JobQuotaExceededException.class);
        assertThat(service.activeJobsForClient(second)).isEqualTo(1); // untouched by the other client

        crypto.unblock("gate");
        awaitStatus(firstJob.jobId(), JobStatus.COMPLETED);
        awaitStatus(secondJob.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(first, 0);
        awaitActiveJobs(second, 0);
    }

    /**
     * DOD-07 (conflict half) / REQ-03: the idempotency conflict is decided before any quota is read, so
     * a conflicting submission at the limit answers CONFLICT rather than a quota error.
     */
    @Test
    @DisplayName("quota - an idempotency conflict is reported before quota is consumed")
    void shouldRejectIdempotencyConflictBeforeConsumingQuota() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String client = "203.0.113.33";

        BatchHashJobResponse admitted = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, "conflict-key"), client);
        assertThat(crypto.awaitEntered("gate")).isTrue();
        BatchHashJobResponse before = service.getJob(admitted.jobId());

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("different"), null, "conflict-key"), client))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(service.getJob(admitted.jobId())).isEqualTo(before);
        assertThat(service.activeJobsForClient(client)).isEqualTo(1);
        assertThat(registryKeys("jobs")).hasSize(1);

        crypto.unblock("gate");
        awaitStatus(admitted.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-06 / REQ-07: a first submission rejected by the executor rolls back the job registration, the
     * idempotency mapping and the quota reservation, keeping the QUEUE_FULL contract intact.
     */
    @Test
    @DisplayName("quota - a rejected first submission rolls back registration, mapping and reservation")
    void shouldRollbackQuotaAndRegistrationsWhenFirstSubmissionIsRejected() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        service = new BatchHashJobService(crypto, 1, 1, 300, 3, LEASE_SECONDS, 5000, 5,
                Clock.systemUTC());
        String client = "203.0.113.40";

        service.submit(new BatchHashRequest("SHA-256", List.of("gate"), null, null), client);
        assertThat(crypto.awaitEntered("gate")).isTrue(); // the only worker is occupied
        service.submit(new BatchHashRequest("SHA-256", List.of("queued"), null, "kept-key"), client);
        assertThat(service.activeJobsForClient(client)).isEqualTo(2);
        assertThat(registryKeys("jobs")).hasSize(2);

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("overflow"), null, "rolled-back-key"), client))
                .isInstanceOf(JobQueueFullException.class)
                .hasMessageContaining("queue-capacity=1");

        assertThat(registryKeys("jobs")).hasSize(2); // no residual job row
        assertThat(registryKeys("idempotencyIndex")).containsExactly("kept-key"); // no residual mapping
        assertThat(service.activeJobsForClient(client)).isEqualTo(2); // no residual active count

        crypto.unblock("gate");
        awaitActiveJobs(client, 0);
    }

    /** DOD-13 (anonymous bucket) / REQ-02: the no-identity overload is charged to the unknown key. */
    @Test
    @DisplayName("quota - submissions without an identity are charged to the unknown key")
    void shouldChargeNoIdentitySubmissionToUnknownKey() throws Exception {
        LeaseCryptoService crypto = new LeaseCryptoService("gate");
        service = new BatchHashJobService(crypto, 2, 10, 300, 3, LEASE_SECONDS, 5000, 1,
                Clock.systemUTC());
        String named = "203.0.113.50";

        BatchHashJobResponse anonymous = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null));
        assertThat(service.activeJobsForClient(ClientKeyResolver.UNKNOWN_CLIENT)).isEqualTo(1);
        assertThat(service.activeJobsForClient(null)).isEqualTo(1); // null is the same bucket
        assertThat(service.activeJobsForClient("   ")).isEqualTo(1); // so is a blank key
        assertThat(service.activeJobsForClient(named)).isZero();

        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), " "))
                .isInstanceOf(JobQuotaExceededException.class); // the anonymous bucket is full

        BatchHashJobResponse identified = service.submit(
                new BatchHashRequest("SHA-256", List.of("gate"), null, null), named);
        assertThat(service.activeJobsForClient(named)).isEqualTo(1);

        crypto.unblock("gate");
        awaitStatus(anonymous.jobId(), JobStatus.COMPLETED);
        awaitStatus(identified.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(ClientKeyResolver.UNKNOWN_CLIENT, 0);
        awaitActiveJobs(named, 0);
    }

    /**
     * DOD-12 / REQ-01: the quota property is validated at startup, and every pre-existing constructor
     * keeps working with the documented default of 20.
     */
    @Test
    @DisplayName("config - a non-positive max-active-jobs-per-client is rejected and defaults are kept")
    void shouldRejectNonPositiveMaxActiveJobsPerClientConfig() {
        CryptoService crypto = new CryptoService();
        Clock clock = Clock.systemUTC();
        assertThat(BatchHashJobService.DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT).isEqualTo(20);

        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.jobs.max-active-jobs-per-client")
                .hasMessageContaining("0");
        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, -2, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.jobs.max-active-jobs-per-client")
                .hasMessageContaining("-2");

        // The legacy constructors still build a service: the default quota applies.
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);
        BatchHashJobResponse accepted = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null)).jobId(), JobStatus.COMPLETED);
        assertThat(accepted.status()).isEqualTo(JobStatus.COMPLETED);
        // The quota counter is released just after the job turns COMPLETED, so drain it deterministically.
        awaitActiveJobs(ClientKeyResolver.UNKNOWN_CLIENT, 0);
        assertThat(service.activeJobsForClient(ClientKeyResolver.UNKNOWN_CLIENT)).isZero();
    }

    /**
     * DOD-13 / REQ-04, REQ-08: neither the job snapshot, the audit journal nor the quota exception
     * carries the client key or any other quota state.
     */
    @Test
    @DisplayName("privacy - the client key and quota state never reach the snapshot, journal or exception")
    void shouldKeepClientKeyOutOfSnapshotAndAuditJournal() throws Exception {
        AuditJournalService journal = newJournal();
        String client = "198.51.100.77";
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000, 20,
                Clock.systemUTC(), journal);

        BatchHashJobResponse completed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null), client).jobId(),
                JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);

        assertThat(completed.toString()).doesNotContain(client);
        assertThat(Arrays.stream(BatchHashJobResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .noneMatch(name -> name.toLowerCase().contains("client"))
                .noneMatch(name -> name.toLowerCase().contains("quota"));

        List<AuditEntry> entries = batchHashEntries(journal, completed.jobId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).toString()).doesNotContain(client);
        assertThat(entries.get(0).request().toString()).doesNotContain(client);

        JobQuotaExceededException refusal = new JobQuotaExceededException(20, 20);
        assertThat(refusal.getMessage())
                .contains("activeJobs=20")
                .contains("limit=20")
                .doesNotContain(client);
    }

    /**
     * DOD-01 / DOD-02 / REQ-01 / REQ-02: the explicit-grace overload drives the two-phase drain
     * without sleeping. The attempt already RUNNING when the drain starts finishes inside the grace
     * window and turns COMPLETED; the job still queued behind it is failed by the forced phase with
     * {@code JOB_SHUTDOWN} instead of being silently dropped, and no entry stays PENDING.
     */
    @Test
    @DisplayName("drain - a running job completes inside the grace window while the job it blocks is failed")
    void shouldCompleteRunningJobAndFailQueuedJobWhenGraceCoversRunningWork() throws Exception {
        AuditJournalService journal = newJournal();
        StallingCryptoService crypto = new StallingCryptoService("blocking", "stuck");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        String client = "c1";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 1, 20, clock, journal);

        BatchHashJobResponse first = service.submit(
                new BatchHashRequest("SHA-256", List.of("blocking"), null, null), client);
        assertThat(crypto.awaitEntered("blocking")).isTrue();
        BatchHashJobResponse second = service.submit(
                new BatchHashRequest("SHA-256", List.of("stuck"), null, null), client);
        assertThat(service.getJob(second.jobId()).status()).isEqualTo(JobStatus.QUEUED);

        ExecutorService drain = Executors.newSingleThreadExecutor();
        try {
            Future<?> drained = drain.submit(() -> {
                service.shutdown();
                return null;
            });
            awaitExecutorShutdown();
            assertThat(service.getJob(first.jobId()).status()).isEqualTo(JobStatus.RUNNING);
            // Released well inside the grace window: the pool drains the first job, then starts the
            // queued one, which stalls and is forced by phase two.
            crypto.release("blocking");
            drained.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            crypto.releaseAll();
            drain.shutdownNow();
        }

        BatchHashJobResponse completed = service.getJob(first.jobId());
        assertThat(completed.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.error()).isNull();
        assertThat(completed.completedAt()).isEqualTo(clock.instant());
        assertThat(completed.attempts().get(0).status()).isEqualTo(JobStatus.COMPLETED);
        List<AuditEntry> completedEntries = batchHashEntries(journal, first.jobId());
        assertThat(completedEntries).hasSize(1);
        assertThat(completedEntries.get(0).state()).isEqualTo(JournalState.SUCCEEDED);

        BatchHashJobResponse failed = service.getJob(second.jobId());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
        assertThat(failed.completedAt()).isEqualTo(clock.instant());
        assertThat(failed.results()).isNull();
        // The attempt it did start is closed as FAILED rather than left PENDING.
        assertThat(batchHashEntries(journal, second.jobId()))
                .allMatch(entry -> entry.state() == JournalState.FAILED);
        assertThat(journal.list(500, JournalState.PENDING)).isEmpty();
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-03 / REQ-03: a stuck RUNNING attempt and a still-QUEUED job are both terminalized by the
     * forced phase with a zero grace budget (no sleeping at all), the attempt's quota slot is
     * released, and the audit entry the attempt opened is closed rather than left PENDING.
     */
    @Test
    @DisplayName("drain - a zero grace budget fails the stuck and queued jobs and closes their audit entries")
    void shouldFailStuckAndQueuedJobsWhenGraceExpires() {
        AuditJournalService journal = newJournal();
        StallingCryptoService crypto = new StallingCryptoService("stuck");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        String client = "198.51.100.42";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 0, 20, clock, journal);

        BatchHashJobResponse running = service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha", "stuck"), null, null), client);
        assertThat(crypto.awaitEntered("stuck")).isTrue();
        BatchHashJobResponse queued = service.submit(
                new BatchHashRequest("SHA-256", List.of("waiting"), null, null), client);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.activeJobsForClient(client)).isEqualTo(2);

        try {
            service.shutdown(); // zero grace: the forced phase runs immediately

            BatchHashJobResponse failedRunning = service.getJob(running.jobId());
            assertThat(failedRunning.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedRunning.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            assertThat(failedRunning.completedAt()).isEqualTo(clock.instant());
            assertThat(failedRunning.results()).isNull();
            assertThat(failedRunning.executionLeaseExpiresAt()).isNull();
            assertThat(failedRunning.lastHeartbeatAt()).isNull();
            assertThat(failedRunning.attempt()).isEqualTo(1); // the fence's other half is untouched
            assertThat(failedRunning.completedItems()).isEqualTo(1); // the checkpoint prefix is kept
            assertThat(failedRunning.attempts()).hasSize(1);
            assertThat(failedRunning.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedRunning.attempts().get(0).error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);

            BatchHashJobResponse failedQueued = service.getJob(queued.jobId());
            assertThat(failedQueued.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedQueued.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            assertThat(failedQueued.startedAt()).isNull(); // never admitted
            // The sweep upserts the attempt it terminalized even though it never started, so the
            // history names the shutdown failure; no audit entry exists for an attempt never admitted.
            assertThat(failedQueued.attempts()).hasSize(1);
            assertThat(failedQueued.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
            assertThat(failedQueued.attempts().get(0).startedAt()).isNull();
            assertThat(failedQueued.attempts().get(0).auditEntryId()).isNull();

            // The invariant: nothing registered is still QUEUED or RUNNING.
            assertThat(service.listJobs(null, 100))
                    .isNotEmpty()
                    .allMatch(job -> job.status() == JobStatus.FAILED);
            assertThat(service.activeJobsForClient(client)).isZero();
            assertThat(journal.list(500, JournalState.PENDING)).isEmpty();
            List<AuditEntry> entries = batchHashEntries(journal, running.jobId());
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).state()).isEqualTo(JournalState.FAILED);
            assertThat(entries.get(0).errorCode()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            assertThat(entries.get(0).completedAt()).isNotNull(); // journal stamps its own wall clock
            assertThat(batchHashEntries(journal, queued.jobId())).isEmpty(); // never started, no row
            // Phase two really interrupted the stuck worker instead of only marking the snapshot.
            awaitInterrupts(crypto);
        } finally {
            crypto.releaseAll(); // let the interrupted worker return so the pool can terminate
        }
    }

    /**
     * DOD-04 / REQ-04: a worker that ignores interruption and returns after the sweep changes nothing
     * — snapshot, results, checkpoint, attempt history and audit entry are the drain's — and a second
     * {@code shutdown()} adds no attempt row, no audit row and no quota release.
     */
    @Test
    @DisplayName("drain - a late worker cannot overwrite the shutdown snapshot and a repeated drain adds nothing")
    void shouldNotLetLateWorkerOverwriteShutdownSnapshot() {
        AuditJournalService journal = newJournal();
        StallingCryptoService crypto = new StallingCryptoService("late");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        String client = "drain-late-client";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 0, 20, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha", "late"), null, null), client);
        assertThat(crypto.awaitEntered("late")).isTrue();
        String entryId = service.getJob(accepted.jobId()).attempts().get(0).auditEntryId();
        assertThat(entryId).isNotNull();
        assertThat(journal.find(entryId).orElseThrow().state()).isEqualTo(JournalState.PENDING);

        try {
            service.shutdown();
            BatchHashJobResponse afterDrain = service.getJob(accepted.jobId());
            assertThat(afterDrain.status()).isEqualTo(JobStatus.FAILED);
            assertThat(afterDrain.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            assertThat(afterDrain.completedItems()).isEqualTo(1);
            List<AuditEntry> drainedEntries = batchHashEntries(journal, accepted.jobId());
            assertThat(drainedEntries).hasSize(1);
            assertThat(drainedEntries.get(0).state()).isEqualTo(JournalState.FAILED);

            // The worker was interrupted and ignored it; it continues hashing and then hits the fence.
            // The interrupt is delivered asynchronously, so wait for it before releasing the gate:
            // releasing first let the release win that race and the worker then left the latch loop
            // without ever observing the interrupt (flaky zero count).
            awaitInterrupts(crypto);
            assertThat(crypto.interrupts()).isPositive();
            crypto.release("late");
            awaitExecutorTerminated(); // the pool terminates only once that worker has returned
            assertThat(crypto.count("late")).isEqualTo(1);

            assertThat(service.getJob(accepted.jobId())).isEqualTo(afterDrain);
            List<AuditEntry> lateEntries = batchHashEntries(journal, accepted.jobId());
            assertThat(lateEntries).hasSize(1);
            assertThat(lateEntries.get(0).id()).isEqualTo(entryId);
            assertThat(lateEntries.get(0).state()).isEqualTo(JournalState.FAILED);
            assertThat(journal.list(500, JournalState.PENDING)).isEmpty();

            service.shutdown(); // idempotent: finds no non-terminal job, writes nothing
            assertThat(service.getJob(accepted.jobId())).isEqualTo(afterDrain);
            assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
            assertThat(service.activeJobsForClient(client)).isZero();
        } finally {
            crypto.releaseAll();
        }
        assertThat(service.activeJobsForClient(client)).isZero();
    }

    /**
     * DOD-02 / REQ-02 / REQ-04: once the drain has returned, both submission and retry reach the
     * closed lanes and are refused through the pre-existing queue-full rollback (the drain sets the
     * lane gate, so {@code RejectedExecutionException} is never reached) — no new error path —
     * leaving no job row, no attempt row, no audit row and no reserved quota slot behind.
     */
    @Test
    @DisplayName("drain - submission and retry are refused after the drain and leave no residue")
    void shouldRejectSubmissionAndRetryAfterShutdown() {
        AuditJournalService journal = newJournal();
        StallingCryptoService crypto = new StallingCryptoService("stuck");
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        String client = "c1";
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 0, 20, clock, journal);

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("stuck"), null, null), client);
        assertThat(crypto.awaitEntered("stuck")).isTrue();
        try {
            service.shutdown(); // zero grace: the forced phase runs immediately
            BatchHashJobResponse swept = service.getJob(accepted.jobId());
            assertThat(swept.status()).isEqualTo(JobStatus.FAILED);
            assertThat(swept.error()).isEqualTo(BatchHashJobService.JOB_SHUTDOWN);
            assertThat(swept.attempt()).isEqualTo(1); // not exhausted, so retry is normally eligible
            Set<String> keysBefore = registryKeys("jobs");

            assertThatThrownBy(() -> service.submit(
                    new BatchHashRequest("SHA-256", List.of("late"), null, null), client))
                    .isInstanceOf(JobQueueFullException.class)
                    .hasMessageContaining("queue-capacity=10");
            // The retry is accepted by the state machine and then refused by the dead executor, which
            // rolls the snapshot back to the drain's FAILED state instead of resurrecting the job.
            assertThatThrownBy(() -> service.retry(accepted.jobId()))
                    .isInstanceOf(JobQueueFullException.class);

            assertThat(service.getJob(accepted.jobId())).isEqualTo(swept);
            assertThat(registryKeys("jobs")).isEqualTo(keysBefore);
            assertThat(service.listJobs(null, 100)).hasSize(1);
            assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);
            assertThat(journal.list(500, JournalState.PENDING)).isEmpty();
            assertThat(service.activeJobsForClient(client)).isZero();
        } finally {
            crypto.releaseAll();
        }
    }

    /**
     * DOD-01 / REQ-01: the grace defaults to 30 seconds, a negative budget is rejected at
     * construction, and zero ("no grace") plus the pre-existing constructors keep working.
     */
    @Test
    @DisplayName("config - the drain grace defaults to 30 seconds and rejects a negative budget")
    void shouldRejectNegativeShutdownGrace() {
        CryptoService crypto = new CryptoService();
        Clock clock = Clock.systemUTC();
        assertThat(BatchHashJobService.DEFAULT_SHUTDOWN_GRACE_SECONDS).isEqualTo(30L);

        assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, -1, 20, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.jobs.shutdown-grace-seconds")
                .hasMessageContaining("-1");

        // Zero is legal: it forces immediately, which is exactly what the forced-phase tests use.
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, 0, 20, clock);
        BatchHashJobResponse completed = awaitStatus(service.submit(
                new BatchHashRequest("SHA-256", List.of("alpha"), null, null)).jobId(), JobStatus.COMPLETED);
        assertThat(completed.status()).isEqualTo(JobStatus.COMPLETED);
        awaitActiveJobs(ClientKeyResolver.UNKNOWN_CLIENT, 0);

        service.shutdown(); // no non-terminal job left: the drain terminates the pool and writes nothing
        assertThat(service.getJob(completed.jobId())).isEqualTo(completed);

        // The legacy overload still builds a service and applies the default 30-second window.
        service = new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000, clock);
        assertThat(awaitStatus(service.submit(new BatchHashRequest("SHA-256", List.of("beta"), null, null)).jobId(),
                JobStatus.COMPLETED).results()).hasSize(1);
    }

    /** Reads the service's pool so the drain's phases can be observed without a sleep. */
    private ThreadPoolExecutor executor() {
        try {
            Field field = BatchHashJobService.class.getDeclaredField("executor");
            field.setAccessible(true);
            return (ThreadPoolExecutor) field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the batch hash executor", e);
        }
    }

    /** Waits until the drain's first phase has started, i.e. the pool refuses new work. */
    private void awaitExecutorShutdown() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (executor().isShutdown()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("The drain never started its first phase");
    }

    /** Waits until every pool task has returned, so a late worker's writes would have landed. */
    private void awaitExecutorTerminated() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (executor().isTerminated()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("The pool did not terminate; a stuck worker may never have returned");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling the batch hash executor", e);
        }
    }

    /** Waits until the forced phase's interrupt has actually been delivered to the gated worker. */
    private void awaitInterrupts(StallingCryptoService crypto) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (crypto.interrupts() >= 1) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("The forced drain never interrupted the stuck worker");
    }

    /** Polls the client's counter: release happens after the terminal monitor block, so it is async. */
    private void awaitActiveJobs(String clientKey, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (service.activeJobsForClient(clientKey) == expected) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting the quota counter", e);
            }
        }
        throw new AssertionError("Client " + clientKey + " active jobs did not reach " + expected
                + "; last observed " + service.activeJobsForClient(clientKey));
    }

    // ---------------------------------------------------------------------------------------------
    // Total-life deadlines: queue wait and execution (REQ-01..REQ-05, DOD-01..DOD-12)
    // ---------------------------------------------------------------------------------------------

    /** The three-item batch whose committed prefix the mid-batch deadline tests assert on. */
    private static final List<String> DEADLINE_ITEMS = List.of("alpha", "bravo", "charlie");

    /** Bound used by the deadline tests: strictly shorter than {@link #LEASE_SECONDS}. */
    private static final long DEADLINE_SECONDS = 5;

    /**
     * Service wired for the deadline tests: one worker, an explicit queue capacity, the test clock and
     * the supplied journal. {@code defaultDeadlineSeconds} is {@code 0} ("no server default") so each
     * test governs its bound through the request itself.
     */
    private BatchHashJobService newDeadlineService(CryptoService crypto, int queueCapacity,
            MutableJobClock clock, AuditJournalService journal) {
        return new BatchHashJobService(crypto, 1, queueCapacity, 300, 3, LEASE_SECONDS, 5000, 0L,
                BatchHashJobService.DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, 0L, clock, journal);
    }

    /**
     * DOD-01 / REQ-01, REQ-04: a job that waits out its bound fails without hashing anything -
     * {@code FAILED} + {@code JOB_DEADLINE_EXCEEDED}, a non-null {@code completedAt}, no item hashed,
     * no published results, and its quota slot released exactly once.
     */
    @Test
    @DisplayName("deadline - a QUEUED job that outlives its bound fails without hashing any item")
    void shouldFailQueuedJobAtDeadlineWithoutHashingAnyItem() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("parked");
        String client = "198.51.100.71";
        service = newDeadlineService(crypto, 1, clock, journal);

        // The only worker is parked inside the first job, so the second job can only ever wait.
        BatchHashJobResponse parked = service.submit(
                new BatchHashRequest("SHA-256", List.of("parked"), null, null), client);
        assertThat(crypto.awaitEntered("parked")).isTrue();
        assertThat(awaitRunningAttempt(parked.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);
        BatchHashJobResponse waiting = service.submit(new BatchHashRequest("SHA-256",
                List.of("waiting-1", "waiting-2"), null, "deadline-queued", (int) DEADLINE_SECONDS),
                client);
        try {
            assertThat(waiting.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(waiting.deadlineAt())
                    .isEqualTo(waiting.createdAt().plusSeconds(DEADLINE_SECONDS));

            clock.advanceSeconds(DEADLINE_SECONDS);
            assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

            BatchHashJobResponse failed = service.getJob(waiting.jobId());
            assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.error()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
            assertThat(failed.completedAt()).isEqualTo(clock.instant());
            assertThat(failed.completedItems()).isZero();
            assertThat(failed.results()).isNull(); // nothing was published
            assertThat(failed.attempts()).hasSize(1);
            assertThat(failed.attempts().get(0).attempt()).isEqualTo(1);
            assertThat(failed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.attempts().get(0).error())
                    .isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
            // The attempt never reached a worker: no item was hashed and no BATCH_HASH row was opened.
            assertThat(crypto.count("waiting-1")).isZero();
            assertThat(crypto.count("waiting-2")).isZero();
            assertThat(batchHashEntries(journal, waiting.jobId())).isEmpty();
            // The slot is freed exactly once: a second pass over the same instant changes nothing.
            awaitActiveJobs(client, 1);
            assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
            assertThat(service.activeJobsForClient(client)).isEqualTo(1);
            assertThat(service.getJob(waiting.jobId()).completedAt()).isEqualTo(clock.instant());
        } finally {
            crypto.unblock("parked");
        }
        awaitStatus(parked.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
        assertThat(crypto.count("waiting-1")).isZero(); // still never hashed once the worker freed up
    }

    /**
     * DOD-02 / REQ-03: a job that runs out of time mid-batch ends {@code FAILED} with
     * {@code completedItems} equal to the committed prefix, {@code results} still null (no partial
     * digest is ever published) and exactly one closed {@code BATCH_HASH} row carrying the deadline
     * error.
     *
     * <p>The gate holds the worker inside the <em>first</em> item's hash, so exactly one item is
     * committed when the bound lapses: the release commits that item (prefix 1) and the renewal
     * before item 2 then trips the bound - the checkpoint the retry test resumes from.</p>
     */
    @Test
    @DisplayName("deadline - a job that runs out of time mid-batch keeps its prefix and publishes nothing")
    void shouldFailMidBatchJobAtDeadlineKeepingCommittedPrefix() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("alpha");
        String client = "198.51.100.72";
        service = newDeadlineService(crypto, 10, clock, journal);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                DEADLINE_ITEMS, null, "deadline-mid-batch", (int) DEADLINE_SECONDS), client);
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(crypto.awaitEntered("alpha")).isTrue(); // the worker is inside the first hash
        clock.advanceSeconds(DEADLINE_SECONDS); // ...and the bound lapses while it is in flight
        crypto.unblock("alpha"); // commit item 1, then the renewal before item 2 trips the bound

        BatchHashJobResponse failed = awaitStatus(accepted.jobId(), JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
        assertThat(failed.completedAt()).isEqualTo(clock.instant());
        assertThat(failed.completedItems()).isEqualTo(1); // the committed prefix, kept for a retry
        assertThat(failed.results()).isNull();
        assertThat(failed.attempts()).hasSize(1);
        assertThat(failed.attempts().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(crypto.count("bravo")).isZero(); // the attempt stopped at the fence, not mid-append
        assertThat(crypto.count("charlie")).isZero();

        List<AuditEntry> entries = batchHashEntries(journal, accepted.jobId());
        assertThat(entries).hasSize(1); // one attempt, one row, closed exactly once
        assertThat(entries.get(0).state()).isEqualTo(JournalState.FAILED);
        assertThat(entries.get(0).errorCode()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
        awaitActiveJobs(client, 0); // the quota slot is released exactly once
    }

    /**
     * DOD-03 / REQ-05: the deadline-failed job is retryable, the retry starts attempt 2 with a fresh
     * window measured from the retry instant and resumes from the retained checkpoint, so it
     * completes with the full ordered digest list.
     */
    @Test
    @DisplayName("deadline - a retry starts a fresh window and resumes from the retained prefix")
    void shouldResumeFromRetainedCheckpointAfterDeadlineFailure() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("alpha");
        String client = "198.51.100.73";
        service = newDeadlineService(crypto, 10, clock, journal);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                DEADLINE_ITEMS, null, "deadline-retry", (int) DEADLINE_SECONDS), client);
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(crypto.awaitEntered("alpha")).isTrue();
        clock.advanceSeconds(DEADLINE_SECONDS);
        crypto.unblock("alpha");
        BatchHashJobResponse failed = awaitStatus(accepted.jobId(), JobStatus.FAILED);
        assertThat(failed.deadlineAt()).isEqualTo(LEASE_START.plusSeconds(DEADLINE_SECONDS));
        awaitActiveJobs(client, 0);

        BatchHashJobResponse retried = service.retry(accepted.jobId());
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        // The retry reuses the same job: the attempt-2 row is written when a worker admits it.
        assertThat(retried.attempts()).hasSize(1); // only attempt 1 has been recorded so far
        // A fresh window measured from the retry instant - not from createdAt, not from the expiry.
        assertThat(retried.deadlineAt()).isEqualTo(clock.instant().plusSeconds(DEADLINE_SECONDS));
        assertThat(retried.deadlineAt()).isAfter(failed.deadlineAt());

        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.completedItems()).isEqualTo(DEADLINE_ITEMS.size());
        assertThat(completed.results()).hasSize(DEADLINE_ITEMS.size());
        CryptoService reference = new CryptoService();
        for (int i = 0; i < DEADLINE_ITEMS.size(); i++) {
            assertThat(completed.results().get(i).digest()).isEqualTo(
                    reference.hash(new HashRequest("SHA-256", DEADLINE_ITEMS.get(i), null)).digest());
        }
        assertThat(completed.attempts()).hasSize(2);
        assertThat(completed.attempts().get(1).attempt()).isEqualTo(2); // the retry is a new attempt
        assertThat(completed.attempts().get(1).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.attempts().get(1).resumedFrom()).isEqualTo(1); // resumed, not restarted
        assertThat(crypto.count("alpha")).isEqualTo(1); // the retained prefix was not re-hashed
        assertThat(batchHashEntries(journal, accepted.jobId(), JournalState.SUCCEEDED)).hasSize(1);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-03 / REQ-05: a retry does not inherit the expired window - the fresh bound is measured from
     * the retry instant (strictly after the instant the failed job recorded) and survives the
     * terminal write.
     */
    @Test
    @DisplayName("deadline - a retry restarts the window instead of inheriting the expired one")
    void shouldRestartDeadlineWindowOnRetry() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("parked");
        String client = "198.51.100.80";
        service = newDeadlineService(crypto, 2, clock, journal);

        BatchHashJobResponse parked = service.submit(
                new BatchHashRequest("SHA-256", List.of("parked"), null, null), client);
        assertThat(crypto.awaitEntered("parked")).isTrue();
        BatchHashJobResponse expiring = service.submit(new BatchHashRequest("SHA-256",
                List.of("window-retry"), null, "deadline-window", (int) DEADLINE_SECONDS), client);
        clock.advanceSeconds(DEADLINE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse failed = service.getJob(expiring.jobId());
        assertThat(failed.error()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);

        clock.advanceSeconds(30); // an arbitrary later instant: the retry measures its window from here
        BatchHashJobResponse retried = service.retry(expiring.jobId());
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.deadlineAt()).isEqualTo(clock.instant().plusSeconds(DEADLINE_SECONDS));
        assertThat(retried.deadlineAt()).isAfter(failed.deadlineAt()); // the old window is not inherited

        crypto.unblock("parked");
        BatchHashJobResponse completed = awaitStatus(expiring.jobId(), JobStatus.COMPLETED);
        assertThat(completed.deadlineAt()).isEqualTo(retried.deadlineAt()); // survives the write
        awaitStatus(parked.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0);
    }

    /**
     * DOD-02 / REQ-03, REQ-04: a late {@code executeAttempt} for an attempt that already failed at its
     * deadline writes nothing - no status flip, no second attempt row, no late audit row and no extra
     * quota release.
     */
    @Test
    @DisplayName("deadline - a late executeAttempt cannot overwrite the FAILED snapshot")
    void shouldFenceLateExecuteAttemptAgainstFailedSnapshot() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("parked");
        String client = "198.51.100.74";
        service = newDeadlineService(crypto, 1, clock, journal);

        BatchHashJobResponse parked = service.submit(
                new BatchHashRequest("SHA-256", List.of("parked"), null, null), client);
        assertThat(crypto.awaitEntered("parked")).isTrue();
        BatchHashJobResponse expiring = service.submit(new BatchHashRequest("SHA-256",
                List.of("late-1"), null, "deadline-late", (int) DEADLINE_SECONDS), client);
        assertThat(expiring.status()).isEqualTo(JobStatus.QUEUED);
        clock.advanceSeconds(DEADLINE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1); // the waiting job only
        BatchHashJobResponse failed = service.getJob(expiring.jobId());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        try {
            // The reaped attempt's worker finally shows up: the status/attempt fence rejects it.
            service.executeAttempt(expiring.jobId(), 1);
            // The slot was released once, by the reaper - the late worker releases nothing.
            assertThat(service.activeJobsForClient(client)).isEqualTo(1); // the parked job only
        } finally {
            crypto.unblock("parked");
        }

        BatchHashJobResponse after = service.getJob(expiring.jobId());
        assertThat(after.status()).isEqualTo(JobStatus.FAILED);
        assertThat(after.error()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
        assertThat(after.completedAt()).isEqualTo(failed.completedAt());
        assertThat(after.completedItems()).isZero();
        assertThat(after.results()).isNull();
        assertThat(after.attempts()).hasSize(1); // no second attempt row
        assertThat(batchHashEntries(journal, expiring.jobId())).isEmpty(); // and no late audit row
        assertThat(crypto.count("late-1")).isZero(); // the late worker hashed nothing
        awaitStatus(parked.jobId(), JobStatus.COMPLETED);
        awaitActiveJobs(client, 0); // and the expired job never released a second slot
    }

    /**
     * DOD-04 / REQ-04: an operator's withdrawal wins at the deadline - a {@code CANCELLING} job past
     * its bound terminalizes as {@code CANCELLED} (never {@code FAILED} /
     * {@code JOB_DEADLINE_EXCEEDED}), releasing its slot exactly once.
     */
    @Test
    @DisplayName("deadline - a CANCELLING job still ends CANCELLED when its bound is reached")
    void shouldHonourCancellingAtDeadline() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("second");
        String client = "198.51.100.75";
        service = newDeadlineService(crypto, 10, clock, journal);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                List.of("first", "second"), null, "deadline-cancel", (int) DEADLINE_SECONDS), client);
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(crypto.awaitEntered("second")).isTrue();
        assertThat(service.cancel(accepted.jobId()).status()).isEqualTo(JobStatus.CANCELLING);

        clock.advanceSeconds(DEADLINE_SECONDS); // the bound is reached while the withdrawal is pending
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse cancelled = service.getJob(accepted.jobId());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.error()).isNull(); // the deadline never overwrites the withdrawal
        assertThat(cancelled.completedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.results()).isNull();
        assertThat(cancelled.completedItems()).isZero();
        assertThat(service.activeJobsForClient(client)).isZero();

        crypto.unblock("second"); // the parked worker cannot revive the settled attempt
        BatchHashJobResponse settled = awaitStatus(accepted.jobId(), JobStatus.CANCELLED);
        assertThat(settled.completedAt()).isEqualTo(clock.instant());
        assertThat(settled.error()).isNull();
    }

    /**
     * DOD-05 / REQ-04: the bound outranks a <em>live</em> execution lease - a worker that keeps
     * renewing can never hash past its deadline.
     */
    @Test
    @DisplayName("deadline - a RUNNING job fails at its bound even while its execution lease is live")
    void shouldFailRunningJobAtDeadlineWithLiveLease() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("second");
        String client = "198.51.100.76";
        service = newDeadlineService(crypto, 10, clock, journal);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                List.of("first", "second"), null, "deadline-live-lease", (int) DEADLINE_SECONDS), client);
        assertThat(awaitRunningAttempt(accepted.jobId(), 1).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(crypto.awaitEntered("second")).isTrue();

        // Past the bound but well inside the lease: only the total-life bound can settle this job.
        clock.advanceSeconds(DEADLINE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);
        BatchHashJobResponse failed = service.getJob(accepted.jobId());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo(BatchHashJobService.JOB_DEADLINE_EXCEEDED);
        assertThat(failed.completedItems()).isEqualTo(1);
        assertThat(failed.results()).isNull();
        awaitActiveJobs(client, 0);

        crypto.unblock("second");
        BatchHashJobResponse settled = awaitStatus(accepted.jobId(), JobStatus.FAILED);
        assertThat(settled.completedAt()).isEqualTo(clock.instant());
        assertThat(settled.completedItems()).isEqualTo(1); // the late worker appended nothing
    }

    /**
     * DOD-06 / REQ-04: every reaper pass is idempotent - a second pass over the same instant returns
     * 0 and writes no attempt, no audit row and no quota release.
     */
    @Test
    @DisplayName("deadline - a second reaper pass over a settled job is a no-op")
    void shouldNoOpSecondDeadlineReaperPass() throws Exception {
        AuditJournalService journal = newJournal();
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        LeaseCryptoService crypto = new LeaseCryptoService("second");
        String client = "198.51.100.77";
        service = newDeadlineService(crypto, 10, clock, journal);

        BatchHashJobResponse accepted = service.submit(new BatchHashRequest("SHA-256",
                List.of("first", "second"), null, "deadline-idempotent", (int) DEADLINE_SECONDS), client);
        assertThat(crypto.awaitEntered("second")).isTrue();
        clock.advanceSeconds(DEADLINE_SECONDS);
        assertThat(service.reapExpiredExecutions(clock.instant())).isEqualTo(1);

        BatchHashJobResponse first = service.getJob(accepted.jobId());
        int journalRows = journal.size();
        int attempts = first.attempts().size();
        assertThat(service.activeJobsForClient(client)).isZero();

        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        BatchHashJobResponse second = service.getJob(accepted.jobId());
        assertThat(second.status()).isEqualTo(JobStatus.FAILED);
        assertThat(second.completedAt()).isEqualTo(first.completedAt());
        assertThat(second.attempts()).hasSize(attempts); // no extra attempt row
        assertThat(journal.size()).isEqualTo(journalRows); // no extra audit row
        assertThat(service.activeJobsForClient(client)).isZero(); // no double release (never negative)
        assertThat(batchHashEntries(journal, accepted.jobId())).hasSize(1);

        crypto.unblock("second");
        awaitStatus(accepted.jobId(), JobStatus.FAILED);
    }

    /**
     * DOD-07 / DOD-11 / REQ-01: the effective bound is part of the idempotency identity - the same key
     * with a different deadline is a 409 conflict, an identical submission is still a replay, and a
     * request without a value fingerprints the configured default.
     */
    @Test
    @DisplayName("deadline - the effective bound is part of the idempotency fingerprint")
    void shouldConflictOnSameKeyWithDifferentDeadline() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = new BatchHashJobService(new CryptoService(), 1, 10, 300, 3, LEASE_SECONDS, 5000, 0L,
                BatchHashJobService.DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, 60L, clock, newJournal());
        String client = "198.51.100.78";
        List<String> items = List.of("fingerprint");

        BatchHashJobResponse first = service.submit(
                new BatchHashRequest("SHA-256", items, null, "fp-key", 30), client);
        assertThat(first.deadlineAt()).isEqualTo(first.createdAt().plusSeconds(30));
        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", items, null, "fp-key", 60), client))
                .isInstanceOf(IdempotencyConflictException.class);
        // A replay of the very same submission is still deduplicated, not conflicted.
        assertThat(service.submit(new BatchHashRequest("SHA-256", items, null, "fp-key", 30), client)
                .jobId()).isEqualTo(first.jobId());

        // No per-request value: the configured default is the effective bound, and it fingerprints the
        // same whether it arrives by default or explicitly.
        BatchHashJobResponse defaulted = service.submit(
                new BatchHashRequest("SHA-256", items, null, "fp-default"), client);
        assertThat(defaulted.deadlineAt()).isEqualTo(defaulted.createdAt().plusSeconds(60));
        assertThat(service.submit(new BatchHashRequest("SHA-256", items, null, "fp-default", 60), client)
                .jobId()).isEqualTo(defaulted.jobId());
        assertThatThrownBy(() -> service.submit(
                new BatchHashRequest("SHA-256", items, null, "fp-default", 5), client))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    /**
     * DOD-08 / REQ-01: {@code 0} disables the server default, so a request without a value stays
     * unbounded exactly as before - no {@code deadlineAt}, and the reaper never fails it for time.
     */
    @Test
    @DisplayName("deadline - a disabled default leaves unbounded jobs on today's behaviour")
    void shouldDisableDeadlineWhenDefaultIsZero() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = newDeadlineService(new CryptoService(), 10, clock, newJournal());

        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("unbounded"), null, "no-deadline-key"));
        assertThat(accepted.deadlineAt()).isNull();
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.deadlineAt()).isNull();

        clock.advanceSeconds(7200); // two hours later the retired job is still not "late"
        assertThat(service.reapExpiredExecutions(clock.instant())).isZero();
        assertThat(service.getJob(accepted.jobId()).status()).isEqualTo(JobStatus.COMPLETED);
        // ...and the duplicate submission still returns the existing job rather than a 409.
        assertThat(service.submit(
                new BatchHashRequest("SHA-256", List.of("unbounded"), null, "no-deadline-key")).jobId())
                .isEqualTo(accepted.jobId());
    }

    /**
     * DOD-09 / REQ-01: the configured default is validated on the same {@code 0..3600} range as the
     * request, so a bad {@code utiltools.jobs.default-deadline-seconds} fails at startup rather than
     * per submission.
     */
    @Test
    @DisplayName("deadline - an out-of-range configured default is rejected at construction")
    void shouldRejectDefaultDeadlineOutsideRange() {
        CryptoService crypto = new CryptoService();
        for (long invalid : new long[] {-1L, 3601L}) {
            assertThatThrownBy(() -> new BatchHashJobService(crypto, 1, 10, 300, 3, LEASE_SECONDS, 5000,
                    0L, BatchHashJobService.DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, invalid,
                    new MutableJobClock(LEASE_START), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("utiltools.jobs.default-deadline-seconds");
        }
    }

    /**
     * DOD-10 / REQ-01: a per-request value outside {@code 1..3600} is rejected even when the service is
     * called directly, so {@code 0} can never be smuggled in as "unbounded": no job row and no quota
     * slot is created.
     */
    @Test
    @DisplayName("deadline - a direct submit with an out-of-range deadline is refused")
    void shouldRejectOutOfRangeRequestDeadlineOnDirectSubmit() {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        service = newDeadlineService(new CryptoService(), 10, clock, newJournal());
        String client = "198.51.100.79";

        for (int invalid : new int[] {0, -5, 3601}) {
            assertThatThrownBy(() -> service.submit(new BatchHashRequest("SHA-256",
                    List.of("invalid"), null, "invalid-deadline-" + invalid, invalid), client))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadlineSeconds");
        }
        assertThat(service.activeJobsForClient(client)).isZero(); // no slot consumed
        assertThat(service.listJobs(null, 20)).isEmpty(); // and no job row registered
    }

    /**
     * DOD-12 / REQ-02: {@code deadlineAt} is an additive snapshot component projected for every status,
     * while the pre-deadline constructors still build a response with no bound.
     */
    @Test
    @DisplayName("deadline - the snapshot exposes deadlineAt and keeps the older constructors")
    void shouldExposeDeadlineAtOnSnapshot() throws Exception {
        MutableJobClock clock = new MutableJobClock(LEASE_START);
        List<JobAttempt> attempts = List.of(new JobAttempt(1, JobStatus.COMPLETED, LEASE_START,
                LEASE_START, null));
        BatchHashJobResponse legacy = new BatchHashJobResponse("job-legacy", null, JobStatus.COMPLETED, 1,
                List.of(), null, LEASE_START, LEASE_START, LEASE_START, 1, 3, attempts,
                LEASE_START.plusSeconds(30), LEASE_START.plusSeconds(10));
        assertThat(legacy.deadlineAt()).isNull(); // 14-component compatibility constructor

        service = newDeadlineService(new CryptoService(), 10, clock, newJournal());
        BatchHashJobResponse accepted = service.submit(
                new BatchHashRequest("SHA-256", List.of("snapshot"), null, null, 60));
        assertThat(accepted.deadlineAt()).isEqualTo(accepted.createdAt().plusSeconds(60));
        BatchHashJobResponse completed = awaitStatus(accepted.jobId(), JobStatus.COMPLETED);
        assertThat(completed.deadlineAt()).isEqualTo(accepted.deadlineAt()); // projected after the write
        assertThat(service.getJob(accepted.jobId()).deadlineAt()).isEqualTo(accepted.deadlineAt());
        assertThat(service.listJobs(null, 20)).extracting(BatchHashJobResponse::deadlineAt)
                .containsExactly(accepted.deadlineAt());
    }
}
