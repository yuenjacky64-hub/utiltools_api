package com.example.utiltools.contact;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.service.AuditJournalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant tests for {@link ContactSubmissionService}: plain JUnit 5 + AssertJ, no Spring
 * context. A controllable dispatcher decides per generation whether to fail, hold, or complete,
 * so fencing, retry, idempotency, and reaper semantics are asserted deterministically against a
 * mutable clock.
 */
class ContactSubmissionServiceTest {

    private static final int LEASE_SECONDS = 30;

    private final MutableClock clock = new MutableClock();
    private ControllableDispatcher dispatcher;
    private ContactSubmissionService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private ContactSubmissionService startService(int maxAttempts) {
        dispatcher = new ControllableDispatcher(new CountDownLatch(1));
        service = new ContactSubmissionService(dispatcher, 1, 1, 0L, maxAttempts, LEASE_SECONDS,
                clock);
        return service;
    }

    /** startService plus explicit backoff base/cap (seconds), for the automatic-retry scans. */
    private ContactSubmissionService startServiceWithBackoff(int maxAttempts, int baseSeconds,
            int capSeconds) {
        dispatcher = new ControllableDispatcher(new CountDownLatch(1));
        service = new ContactSubmissionService(dispatcher, 1, 1, 0L, maxAttempts, LEASE_SECONDS,
                1000, 0, clock, baseSeconds * 1000, capSeconds * 1000, 1000);
        return service;
    }

    private static ContactSubmissionRequest request(String key, String message) {
        return new ContactSubmissionRequest("Ada", "ada@example.com", message, key);
    }

    private ContactSubmissionResponse pollUntil(String id,
            java.util.function.Predicate<ContactStatus> match) {
        long deadline = System.currentTimeMillis() + 5_000;
        ContactSubmissionResponse snapshot;
        while (true) {
            snapshot = service.get(id);
            if (match.test(snapshot.status())) {
                return snapshot;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("never matched for " + id + ", saw " + snapshot.status());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private ContactSubmissionResponse pollUntil(String id, ContactStatus status) {
        return pollUntil(id, candidate -> candidate == status);
    }

    /** Polls an arbitrary condition on the same 5s budget as {@link #pollUntil(String, ContactStatus)}. */
    private static void awaitCondition(java.util.function.Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("condition never matched");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("DOD-02: a failed dispatch ends FAILED with the bounded code, preserves the payload, and retry starts attempt+1")
    void failedDispatchPreservesPayloadAndRetryStartsNextAttempt() {
        startService(3);
        dispatcher.failGenerations.add(1);
        ContactSubmissionResponse submitted = service.submit(request(null, "hello there"));
        assertThat(submitted.status()).isIn(ContactStatus.QUEUED, ContactStatus.DISPATCHING,
                ContactStatus.DELIVERED, ContactStatus.FAILED);

        ContactSubmissionResponse failed = pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(failed.message()).isEqualTo("hello there");
        assertThat(failed.currentAttempt().error())
                .isEqualTo(ContactSubmissionService.CONTACT_DISPATCH_FAILED);

        ContactSubmissionResponse retrying = service.retry(submitted.id());
        assertThat(retrying.attempt()).isEqualTo(2);
        assertThat(retrying.message()).isEqualTo("hello there");
        ContactSubmissionResponse delivered = pollUntil(retrying.id(), ContactStatus.DELIVERED);
        assertThat(delivered.attempt()).isEqualTo(2);
        // the dispatcher was driven for generation 2: the retried attempt.
        assertThat(dispatcher.calls()).contains(2);
        assertThat(delivered.currentAttempt().error()).isNull();
    }

    @Test
    @DisplayName("DOD-04: the lease reaper terminalizes an expired DISPATCHING attempt and a late worker from it cannot overwrite the newer attempt")
    void reaperTerminalizesStaleAttemptAndFencesLateWorker() throws Exception {
        startService(3);
        dispatcher.holdGenerations.add(1);
        ContactSubmissionResponse submitted = service.submit(request(null, "stalled"));
        pollUntil(submitted.id(), ContactStatus.DISPATCHING);

        clock.advance(LEASE_SECONDS + 1L);
        assertThat(service.reapStaleDispatching()).isEqualTo(1);
        ContactSubmissionResponse reaped = service.get(submitted.id());
        assertThat(reaped.status()).isEqualTo(ContactStatus.FAILED);
        assertThat(reaped.currentAttempt().error())
                .isEqualTo(ContactSubmissionService.CONTACT_DELIVERY_LEASE_EXPIRED);
        Instant reapedCompletedAt = reaped.currentAttempt().completedAt();
        assertThat(reapedCompletedAt).isEqualTo(clock.instant());

        ContactSubmissionResponse retrying = service.retry(submitted.id());
        assertThat(retrying.attempt()).isEqualTo(2);
        // attempt 2 is admitted at reservation time: DISPATCHING even though worker 1 still holds
        // attempt 1, with the execution lease re-stamped.
        assertThat(retrying.status()).isEqualTo(ContactStatus.DISPATCHING);
        Instant startedAt2 = retrying.currentAttempt().startedAt();

        // The held generation-1 worker now returns: its completion must be fenced out, and
        // attempt 2 then completes on the freed worker.
        dispatcher.release().countDown();
        ContactSubmissionResponse delivered = pollUntil(retrying.id(), ContactStatus.DELIVERED);
        Instant deliveredAt = delivered.currentAttempt().completedAt();

        ContactSubmissionResponse intact = service.get(submitted.id());
        assertThat(intact.status()).isEqualTo(ContactStatus.DELIVERED);
        assertThat(intact.attempt()).isEqualTo(2);
        assertThat(intact.currentAttempt().completedAt()).isEqualTo(deliveredAt);
        assertThat(intact.currentAttempt().startedAt()).isEqualTo(startedAt2);
        assertThat(intact.currentAttempt().error()).isNull();
        assertThat(service.reapStaleDispatching()).isZero();
    }

    @Test
    @DisplayName("DOD-03: an idempotent duplicate returns the same id without a second dispatch")
    void idempotentDuplicateReturnsSameSubmission() {
        startService(3);
        ContactSubmissionResponse first = service.submit(request("k-key", "same payload"));
        pollUntil(first.id(), ContactStatus.DELIVERED);
        int dispatches = dispatcher.calls().size();
        assertThat(dispatches).isEqualTo(1);

        ContactSubmissionResponse duplicate = service.submit(request("k-key", "same payload"));
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(dispatcher.calls()).hasSize(dispatches);
        // INV-13: a duplicate never consumes a retry.
        assertThat(duplicate.attempt()).isEqualTo(1);
        assertThat(duplicate.status()).isEqualTo(ContactStatus.DELIVERED);
    }

    @Test
    @DisplayName("DOD-03: the same key with a different payload answers CONTACT_IDEMPOTENCY_CONFLICT and leaves the original untouched")
    void conflictingPayloadKeepsOriginalSubmission() {
        startService(3);
        ContactSubmissionResponse original = service.submit(request("k-conflict", "original text"));
        pollUntil(original.id(), ContactStatus.DELIVERED);
        int dispatches = dispatcher.calls().size();

        assertThatThrownBy(() -> service.submit(request("k-conflict", "different text")))
                .isInstanceOf(ContactIdempotencyConflictException.class)
                .satisfies(ex -> {
                    ContactIdempotencyConflictException conflict =
                            (ContactIdempotencyConflictException) ex;
                    assertThat(conflict.idempotencyKey()).isEqualTo("k-conflict");
                    assertThat(conflict.existingSubmissionId()).isEqualTo(original.id());
                });

        assertThat(service.get(original.id()).message()).isEqualTo("original text");
        assertThat(dispatcher.calls()).hasSize(dispatches);
    }

    @Test
    @DisplayName("DOD-03: a saturated bounded pool answers CONTACT_QUEUE_FULL and rolls back atomically")
    void queueFullLeavesNoResidue() throws Exception {
        startService(3);
        dispatcher.holdAll = true; // every attempt occupies its slot until released
        ContactSubmissionResponse a = service.submit(request(null, "first"));
        // Same deterministic-saturation guard: only once the worker has picked up `a`'s task
        // (one dispatcher call recorded) is the single queue slot free, so `b` fills it for
        // sure instead of racing the worker and hitting the AbortPolicy under load.
        awaitCondition(() -> dispatcher.calls().size() >= 1);
        ContactSubmissionResponse b = service.submit(request(null, "second"));
        pollUntil(a.id(), ContactStatus.DISPATCHING);
        pollUntil(b.id(), ContactStatus.DISPATCHING);

        // workerCount + queueCapacity == 2 slots: the third admission must 429.
        assertThatThrownBy(() -> service.submit(request("k-new", "third")))
                .isInstanceOf(ContactQueueFullException.class);

        // INV-C4: no row, no idempotency mapping, no pending reservation behind the 429.
        Map<String, ?> submissions = readField(service, "submissions");
        Map<String, String> index = readField(service, "idempotencyIndex");
        assertThat(submissions).hasSize(2);
        assertThat(submissions.keySet()).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(index).doesNotContainKey("k-new");
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(2);
    }

    @Test
    @DisplayName("DOD-03/INV-03: retry from a non-FAILED state answers CONTACT_TRANSITION_CONFLICT without mutation")
    void illegalTransitionLeavesSnapshotUntouched() throws Exception {
        startService(3);
        dispatcher.holdGenerations.add(1);
        ContactSubmissionResponse dispatched = service.submit(request(null, "still going"));
        pollUntil(dispatched.id(), ContactStatus.DISPATCHING);
        // Admission stamps DISPATCHING at reservation time, before the worker thread reaches
        // the dispatch seam — wait on the call itself, not the status.
        long deadline = System.currentTimeMillis() + 5_000;
        while (dispatcher.calls().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        int dispatches = dispatcher.calls().size();
        assertThat(dispatches).isOne();

        assertThatThrownBy(() -> service.retry(dispatched.id()))
                .isInstanceOf(ContactTransitionConflictException.class)
                .satisfies(ex -> {
                    ContactTransitionConflictException conflict =
                            (ContactTransitionConflictException) ex;
                    assertThat(conflict.submissionId()).isEqualTo(dispatched.id());
                    assertThat(conflict.status()).isEqualTo(ContactStatus.DISPATCHING);
                    assertThat(conflict.attempt()).isEqualTo(1);
                    assertThat(conflict.maxAttempts()).isEqualTo(3);
                    assertThat(conflict.reason()).contains("FAILED");
                });

        ContactSubmissionResponse intact = service.get(dispatched.id());
        assertThat(intact.status()).isEqualTo(ContactStatus.DISPATCHING);
        assertThat(intact.attempt()).isEqualTo(1);
        assertThat(dispatcher.calls()).hasSize(dispatches);
    }

    @Test
    @DisplayName("INV-04: retry after the attempt budget is exhausted answers CONTACT_TRANSITION_CONFLICT")
    void exhaustedBudgetRefusesRetry() {
        startService(1);
        dispatcher.failGenerations.add(1);
        ContactSubmissionResponse failed = pollUntil(
                service.submit(request(null, "doomed")).id(), ContactStatus.FAILED);
        assertThat(failed.attempt()).isEqualTo(1);

        assertThatThrownBy(() -> service.retry(failed.id()))
                .isInstanceOf(ContactTransitionConflictException.class)
                .satisfies(ex -> {
                    ContactTransitionConflictException conflict =
                            (ContactTransitionConflictException) ex;
                    assertThat(conflict.status()).isEqualTo(ContactStatus.FAILED);
                    assertThat(conflict.reason()).contains("exhausted");
                });
        assertThat(service.get(failed.id()).attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-06/DOD-01: a due scan dispatches exactly one new generation, never before nextRetryAt, and repeats admit nothing")
    void dueScanDispatchesExactlyOneGeneration() {
        startServiceWithBackoff(3, 2, 8);
        dispatcher.failGenerations.add(1);
        Instant t0 = clock.instant();
        ContactSubmissionResponse submitted = service.submit(request(null, "auto retry"));
        ContactSubmissionResponse failed = pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(failed.currentAttempt().nextRetryAt()).isEqualTo(t0.plusSeconds(2));

        // DOD-02: the scan admits nothing while the schedule is in the future.
        assertThat(service.retryDueSubmissions(clock.instant())).isZero();
        assertThat(dispatcher.calls()).containsExactly(1);

        // DOD-02: exactly one dispatch when due; a repeated scan at the same instant adds none.
        clock.advance(2L);
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        pollUntil(submitted.id(), ContactStatus.DELIVERED);
        assertThat(dispatcher.calls()).containsExactly(1, 2);
        assertThat(service.retryDueSubmissions(clock.instant())).isZero();
    }

    @Test
    @DisplayName("REQ-06/DOD-03: automatic backoff grows base, base*2, base*4 up to the configured cap")
    void backoffGrowsExponentiallyUpToCap() {
        startServiceWithBackoff(4, 2, 3);
        dispatcher.failGenerations.add(1);
        dispatcher.failGenerations.add(2);
        dispatcher.failGenerations.add(3);
        Instant t0 = clock.instant();
        ContactSubmissionResponse submitted = service.submit(request(null, "capped"));
        pollUntil(submitted.id(), ContactStatus.FAILED); // wait for the terminal handler

        clock.advance(2L); // due at t0 + 2s (base)
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(service.get(submitted.id()).currentAttempt().nextRetryAt()).isEqualTo(t0.plusSeconds(5));

        clock.advance(3L); // due at t0 + 5s (base*2 clamped to the 3s cap)
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(service.get(submitted.id()).currentAttempt().nextRetryAt()).isEqualTo(t0.plusSeconds(8));

        clock.advance(3L); // still capped
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        ContactSubmissionResponse delivered = pollUntil(submitted.id(), ContactStatus.DELIVERED);
        assertThat(delivered.attempt()).isEqualTo(4);
        assertThat(dispatcher.calls()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("REQ-06/DOD-03: at the attempt ceiling no schedule is set and the scan never dispatches")
    void noDispatchAtAttemptCeiling() {
        startServiceWithBackoff(3, 2, 8);
        dispatcher.failGenerations.add(1);
        dispatcher.failGenerations.add(2);
        dispatcher.failGenerations.add(3);
        ContactSubmissionResponse submitted = service.submit(request(null, "doomed auto"));
        ContactSubmissionResponse failed1 = pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(failed1.currentAttempt().nextRetryAt()).isNotNull();

        clock.advance(2L);
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        pollUntil(submitted.id(), ContactStatus.FAILED);

        clock.advance(4L);
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        ContactSubmissionResponse failed3 = pollUntil(submitted.id(), ContactStatus.FAILED);
        assertThat(failed3.attempt()).isEqualTo(3);
        // DOD-01: the attempt budget is exhausted, so no automatic schedule survives.
        assertThat(failed3.currentAttempt().nextRetryAt()).isNull();
        assertThat(failed3.currentAttempt().status()).isEqualTo(ContactStatus.FAILED);

        clock.advance(60L);
        assertThat(service.retryDueSubmissions(clock.instant())).isZero();
        assertThat(dispatcher.calls()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("REQ-06/DOD-04: a manual retry before the schedule clears it, and the due scan admits no second attempt")
    void manualRetryBeforeScheduleWinsWithoutDoubleDispatch() {
        startServiceWithBackoff(3, 2, 8);
        dispatcher.failGenerations.add(1);
        ContactSubmissionResponse submitted = service.submit(request(null, "raced"));
        ContactSubmissionResponse failed = pollUntil(submitted.id(), ContactStatus.FAILED);
        Instant scheduled = failed.currentAttempt().nextRetryAt();
        assertThat(scheduled).isNotNull();

        // DOD-04: the manual retry clears the pending schedule before admission.
        clock.advance(2L);
        ContactSubmissionResponse retried = service.retry(submitted.id());
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(service.get(submitted.id()).currentAttempt().nextRetryAt()).isNull();
        pollUntil(submitted.id(), ContactStatus.DELIVERED);

        // The scan that would have due-admitted generation 2 now finds nothing to admit.
        assertThat(service.retryDueSubmissions(clock.instant())).isZero();
        assertThat(dispatcher.calls()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("REQ-04/DOD-05: a saturated pool preserves the FAILED row and a later scan succeeds once capacity frees")
    void saturatedPoolPreservesFailedRowAndRetriesLater() throws Exception {
        startServiceWithBackoff(3, 2, 8);
        dispatcher.failGenerations.add(1);
        Instant t0 = clock.instant();
        ContactSubmissionResponse x = service.submit(request(null, "queued for later"));
        ContactSubmissionResponse failed = pollUntil(x.id(), ContactStatus.FAILED);
        assertThat(failed.currentAttempt().nextRetryAt()).isEqualTo(t0.plusSeconds(2));

        // Saturate the pool: both slots busy with held attempts.
        dispatcher.failGenerations.clear();
        dispatcher.holdAll = true;
        ContactSubmissionResponse a = service.submit(request(null, "hold one"));
        // Synchronize saturation deterministically: `x`'s failed attempt 1 already recorded one
        // dispatcher call, so two calls mean the worker picked up `a`'s task and the single
        // queue slot is free — `b` fills it for sure instead of racing the worker and hitting
        // the AbortPolicy under load.
        awaitCondition(() -> dispatcher.calls().size() >= 2);
        ContactSubmissionResponse b = service.submit(request(null, "hold two"));
        pollUntil(a.id(), ContactStatus.DISPATCHING);
        pollUntil(b.id(), ContactStatus.DISPATCHING);

        clock.advance(2L);
        // The scan cannot reserve capacity: the row stays FAILED with attempt and error intact
        // and its schedule pushed to the next backoff slot.
        assertThat(service.retryDueSubmissions(clock.instant())).isZero();
        ContactSubmissionResponse preserved = service.get(x.id());
        assertThat(preserved.status()).isEqualTo(ContactStatus.FAILED);
        assertThat(preserved.attempt()).isEqualTo(1);
        assertThat(preserved.currentAttempt().error())
                .isEqualTo(ContactSubmissionService.CONTACT_DISPATCH_FAILED);
        assertThat(preserved.currentAttempt().nextRetryAt()).isEqualTo(t0.plusSeconds(4));
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(2);

        // Free a slot: the scan that pushed the schedule now finds it due and admits the
        // pending automatic retry.
        dispatcher.release().countDown();
        pollUntil(a.id(), ContactStatus.DELIVERED);
        pollUntil(b.id(), ContactStatus.DELIVERED);
        clock.advance(2L); // due at the pushed slot t0 + 4s
        assertThat(service.retryDueSubmissions(clock.instant())).isEqualTo(1);
        pollUntil(x.id(), ContactStatus.DELIVERED);
        assertThat(service.get(x.id()).attempt()).isEqualTo(2);
        assertThat(dispatcher.calls()).contains(2);
    }

    @Test
    @DisplayName("REQ-08: every started attempt gets exactly one journal entry, closed exactly once")
    void journalEntriesCloseExactlyOncePerAttempt() throws Exception {
        AuditJournalService journal =
                new AuditJournalService(new ObjectMapper(), 5_000, 10_000_000, 60L);
        startService(2);
        service.setAuditJournal(journal);
        dispatcher.holdGenerations.add(1);
        ContactSubmissionResponse submitted = service.submit(request(null, "audit me"));
        pollUntil(submitted.id(), ContactStatus.DISPATCHING);
        String entry1 = service.get(submitted.id()).currentAttempt().auditEntryId();
        assertThat(entry1).isNotBlank();

        clock.advance(LEASE_SECONDS + 1L);
        assertThat(service.reapStaleDispatching()).isEqualTo(1);
        AuditEntry closed1 = journal.find(entry1).orElseThrow();
        assertThat(closed1.state()).isEqualTo(JournalState.FAILED);
        assertThat(closed1.errorCode())
                .isEqualTo(ContactSubmissionService.CONTACT_DELIVERY_LEASE_EXPIRED);
        assertThat(service.get(submitted.id()).currentAttempt().auditEntryId()).isNull();

        // Retry as attempt 2: admitted DISPATCHING, then the freed worker delivers it.
        service.retry(submitted.id());
        dispatcher.release().countDown();
        pollUntil(submitted.id(), ContactStatus.DELIVERED);
        assertThat(service.get(submitted.id()).currentAttempt().auditEntryId()).isNull();
        AuditEntry succeeded2 = journal.list(10, JournalState.SUCCEEDED).stream()
                .filter(entry -> entry.id().equals(entry1)).findAny().orElse(null);
        assertThat(succeeded2).isNull(); // attempt 1's entry is FAILED, not SUCCEEDED
        assertThat(journal.list(10, JournalState.SUCCEEDED)).hasSize(1);
        assertThat(journal.list(10, JournalState.FAILED)).hasSize(1);
        assertThat(journal.list(10, JournalState.PENDING)).isEmpty();
    }

    /**
     * REQ-01/REQ-02/REQ-03 (DOD-01/03): a queued attempt is cancelled before its worker enters
     * the dispatcher; the repeat cancel is idempotent with exactly-once journal closure; the
     * late worker that later dequeues the task is fenced out and never touches the dispatcher
     * or the counters.
     */
    @Test
    @DisplayName("DOD-01/03: pre-dispatch cancel terminalizes CANCELLED, closes the journal once, and fences the late worker")
    void preDispatchCancelFencesLateWorker() throws Exception {
        AuditJournalService journal =
                new AuditJournalService(new ObjectMapper(), 5_000, 10_000_000, 60L);
        startService(2);
        service.setAuditJournal(journal);
        dispatcher.holdGenerations.add(1); // the lone worker holds generation 1
        ContactSubmissionResponse a = service.submit(request(null, "worker holder"));
        pollUntil(a.id(), ContactStatus.DISPATCHING);
        awaitCondition(() -> dispatcher.calls().size() >= 1);

        // `b` is admitted while the worker is held: it sits in the executor queue, stamped
        // DISPATCHING at reservation but not yet inside the dispatcher (fence not passed).
        ContactSubmissionResponse b = service.submit(request(null, "withdraw me"));
        pollUntil(b.id(), ContactStatus.DISPATCHING);
        String bEntry = service.get(b.id()).currentAttempt().auditEntryId();
        assertThat(bEntry).isNotBlank();
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(2);

        // The cancellation wins the fence race: terminal CANCELLED, code stamped, schedule
        // cleared, and the pending slot released exactly once (2 -> 1).
        ContactSubmissionResponse cancelled = service.cancel(b.id());
        assertThat(cancelled.status()).isEqualTo(ContactStatus.CANCELLED);
        assertThat(cancelled.currentAttempt().error())
                .isEqualTo(ContactSubmissionService.CONTACT_SUBMISSION_CANCELLED);
        assertThat(cancelled.currentAttempt().nextRetryAt()).isNull();
        assertThat(cancelled.currentAttempt().completedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.message()).isEqualTo("withdraw me");
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(1);
        AuditEntry closed = journal.find(bEntry).orElseThrow();
        assertThat(closed.state()).isEqualTo(JournalState.FAILED);
        assertThat(closed.errorCode())
                .isEqualTo(ContactSubmissionService.CONTACT_SUBMISSION_CANCELLED);
        assertThat(service.get(b.id()).currentAttempt().auditEntryId()).isNull();

        // Repeat cancel: idempotent 200 with the same snapshot and zero further writes —
        // exactly one journal entry exists for `b`, still in its closed state.
        ContactSubmissionResponse repeat = service.cancel(b.id());
        assertThat(repeat.status()).isEqualTo(ContactStatus.CANCELLED);
        assertThat(repeat.currentAttempt().error())
                .isEqualTo(ContactSubmissionService.CONTACT_SUBMISSION_CANCELLED);
        assertThat(journal.find(bEntry).orElseThrow().state()).isEqualTo(JournalState.FAILED);
        long matchingFailed = journal.list(10, JournalState.FAILED).stream()
                .filter(entry -> entry.id().equals(bEntry))
                .count();
        assertThat(matchingFailed).isOne();

        // Release the worker: `a` delivers; the late `b` task is dequeued and fenced out.
        dispatcher.release().countDown();
        pollUntil(a.id(), ContactStatus.DELIVERED);
        ThreadPoolExecutor pool = readField(service, "executor");
        awaitCondition(pool.getQueue()::isEmpty); // `b`'s late task was consumed and dropped
        assertThat(dispatcher.calls()).containsExactly(1); // never driven for `b`
        assertThat(service.get(b.id()).status()).isEqualTo(ContactStatus.CANCELLED);
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(0);
        assertThat(journal.list(10, JournalState.SUCCEEDED)).hasSize(1); // only `a`
        assertThat(journal.list(10, JournalState.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("DOD-02: cancelling while the dispatcher is running is 409 and the attempt still terminalizes normally")
    void inFlightCancelRefusedAndDispatchCompletes() throws Exception {
        AuditJournalService journal =
                new AuditJournalService(new ObjectMapper(), 5_000, 10_000_000, 60L);
        startService(2);
        service.setAuditJournal(journal);
        dispatcher.holdGenerations.add(1);
        ContactSubmissionResponse submitted = service.submit(request(null, "in flight"));
        pollUntil(submitted.id(), ContactStatus.DISPATCHING);
        // The dispatcher call itself is the proof the worker passed the execution-start
        // fence: from this point cancellation must be refused, not re-queued.
        awaitCondition(() -> dispatcher.calls().size() >= 1);
        String entry = service.get(submitted.id()).currentAttempt().auditEntryId();
        assertThat(journal.find(entry).orElseThrow().state()).isEqualTo(JournalState.PENDING);

        assertThatThrownBy(() -> service.cancel(submitted.id()))
                .isInstanceOf(ContactTransitionConflictException.class)
                .satisfies(ex -> {
                    ContactTransitionConflictException conflict =
                            (ContactTransitionConflictException) ex;
                    assertThat(conflict.submissionId()).isEqualTo(submitted.id());
                    assertThat(conflict.status()).isEqualTo(ContactStatus.DISPATCHING);
                    assertThat(conflict.reason()).contains("in flight");
                });

        // The refusal left the row, its counters, and its journal byte-identical.
        ContactSubmissionResponse intact = service.get(submitted.id());
        assertThat(intact.status()).isEqualTo(ContactStatus.DISPATCHING);
        assertThat((Object) readField(service, "pendingAttempts")).isEqualTo(1);
        assertThat(journal.find(entry).orElseThrow().state()).isEqualTo(JournalState.PENDING);

        // When the dispatcher returns, the attempt still terminalizes normally.
        dispatcher.release().countDown();
        ContactSubmissionResponse delivered = pollUntil(submitted.id(), ContactStatus.DELIVERED);
        assertThat(delivered.currentAttempt().error()).isNull();
        assertThat(journal.find(entry).orElseThrow().state()).isEqualTo(JournalState.SUCCEEDED);
    }

    @Test
    @DisplayName("DOD-04: DELIVERED and FAILED submissions refuse cancellation, row byte-identical")
    void terminalStatesRefuseCancel() {
        startService(1);
        ContactSubmissionResponse delivered = pollUntil(
                service.submit(request(null, "done")).id(), ContactStatus.DELIVERED);
        Instant deliveredAt = delivered.currentAttempt().completedAt();
        assertThatThrownBy(() -> service.cancel(delivered.id()))
                .isInstanceOf(ContactTransitionConflictException.class)
                .satisfies(ex -> {
                    ContactTransitionConflictException conflict =
                            (ContactTransitionConflictException) ex;
                    assertThat(conflict.status()).isEqualTo(ContactStatus.DELIVERED);
                    assertThat(conflict.reason()).contains("terminal");
                });
        assertThat(service.get(delivered.id())).isEqualTo(delivered);
        assertThat(service.get(delivered.id()).currentAttempt().completedAt()).isEqualTo(deliveredAt);
        assertThat(service.get(delivered.id()).currentAttempt().error()).isNull();

        dispatcher.failGenerations.add(1);
        ContactSubmissionResponse failed = pollUntil(
                service.submit(request(null, "doomed")).id(), ContactStatus.FAILED);
        String failedError = failed.currentAttempt().error();
        Instant failedAt = failed.currentAttempt().completedAt();
        assertThatThrownBy(() -> service.cancel(failed.id()))
                .isInstanceOf(ContactTransitionConflictException.class)
                .satisfies(ex -> {
                    ContactTransitionConflictException conflict =
                            (ContactTransitionConflictException) ex;
                    assertThat(conflict.status()).isEqualTo(ContactStatus.FAILED);
                    assertThat(conflict.reason()).contains("terminal");
                });
        ContactSubmissionResponse unchanged = service.get(failed.id());
        assertThat(unchanged.status()).isEqualTo(ContactStatus.FAILED);
        assertThat(unchanged.currentAttempt().error()).isEqualTo(failedError);
        assertThat(unchanged.currentAttempt().completedAt()).isEqualTo(failedAt);
    }

    @Test
    @DisplayName("REQ-02: cancelling an unknown id answers CONTACT_SUBMISSION_NOT_FOUND")
    void cancelUnknownIdNotFound() {
        startService(1);
        assertThatThrownBy(() -> service.cancel("never-submitted"))
                .isInstanceOf(ContactSubmissionNotFoundException.class);
    }

    /** Per-generation dispatcher behaviour: hold, fail, or complete; counts every call. */
    private static final class ControllableDispatcher implements ContactDispatcher {
        final ConcurrentLinkedQueue<Integer> calls = new ConcurrentLinkedQueue<>();
        final Set<Integer> holdGenerations = ConcurrentHashMap.newKeySet();
        final Set<Integer> failGenerations = ConcurrentHashMap.newKeySet();
        private final CountDownLatch release;
        volatile boolean holdAll;

        ControllableDispatcher(CountDownLatch release) {
            this.release = release;
        }

        CountDownLatch release() {
            return release;
        }

        java.util.List<Integer> calls() {
            return java.util.List.copyOf(calls);
        }

        @Override
        public void dispatch(String submissionId, int generation) throws Exception {
            calls.add(generation);
            if (holdAll || holdGenerations.contains(generation)) {
                release.await(60, TimeUnit.SECONDS);
            }
            if (failGenerations.contains(generation)) {
                throw new IllegalStateException("dispatcher failure for generation " + generation);
            }
        }
    }

    /** Mutable clock: the service stamps every lease and heartbeat from it. */
    private static final class MutableClock extends Clock {
        private final AtomicLong epochNanos = new AtomicLong(
                Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() * 1_000_000L);

        MutableClock advance(long seconds) {
            epochNanos.addAndGet(seconds * 1_000_000_000L);
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochNanos.get() / 1_000_000L);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
