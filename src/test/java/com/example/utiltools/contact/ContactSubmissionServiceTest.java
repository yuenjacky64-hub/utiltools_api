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
