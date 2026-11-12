package com.example.utiltools.scheduling;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOD-02: proves the shared bounded pool isolates maintenance passes.
 *
 * <ul>
 *   <li>a pass blocked on a latch does not prevent a different fixed-delay pass from running
 *       repeatedly (DOD-02 / REQ-05 head-of-line blocking regression);</li>
 *   <li>one fixed-delay task never overlaps its own previous execution;</li>
 *   <li>in-flight maintenance passes report {@code maintenance-*} daemon threads.</li>
 * </ul>
 *
 * <p>Plain JUnit against a {@link MaintenanceTaskScheduler} built from the default properties;
 * no Spring context needed. Fixed-delay is asserted with {@code scheduleAtFixedRate} periods in
 * the tens of milliseconds so the suite stays fast.
 */
@DisplayName("Scheduler pool isolation (SchedulingIsolationTest)")
final class SchedulingIsolationTest {

    /** 8 lanes, {@code maintenance-} naming, 5s shutdown grace (fast enough for the suite). */
    private static final MaintenanceSchedulingProperties PROPERTIES =
            new MaintenanceSchedulingProperties(8, "maintenance-", 5);

    private static final long POLL_PERIOD_MS = 50L;

    private final MaintenanceTaskScheduler scheduler = new MaintenanceTaskScheduler(PROPERTIES);
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        poller.shutdownNow();
        scheduler.shutdown();
    }

    @Test
    @DisplayName("blocked pass does not starve a different fixed-delay pass")
    void blockedPassDoesNotStarveDifferentPass() throws Exception {
        scheduler.initialize();

        // Pass A: occupies one lane until released (the "slow journal scan").
        CountDownLatch releasePassA = new CountDownLatch(1);
        AtomicBoolean passAStarted = new AtomicBoolean();
        ScheduledFuture<?> passA = scheduler.schedule(() -> {
            passAStarted.set(true);
            try {
                releasePassA.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now().plusMillis(1));

        // Pass B: independent fixed-delay cadence; cancels itself after 4 cycles.
        AtomicInteger passBCycles = new AtomicInteger();
        ScheduledFuture<?> passB = scheduler.scheduleAtFixedRate(() -> passBCycles.incrementAndGet(),
                POLL_PERIOD_MS);

        try {
            // While pass A is blocked, pass B must still complete at least two fixed-delay cycles.
            awaitTrue(() -> passBCycles.get() >= 2, "pass B reached two cycles", 10_000);
            int cyclesBeforeRelease = passBCycles.get();
            assertThat(cyclesBeforeRelease)
                    .as("pass B kept its fixed-delay cadence while pass A blocked")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            releasePassA.countDown();
            passA.cancel(false);
            passB.cancel(false);
        }
        assertThat(passAStarted).isTrue();
    }

    @Test
    @DisplayName("one fixed-delay task never overlaps its own previous execution")
    void fixedDelayTaskNeverOverlapsItself() throws Exception {
        scheduler.initialize();

        int cycles = 5;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(cycles);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(inFlight.get(), Math::max);
            try {
                Thread.sleep(POLL_PERIOD_MS / 2); // body is shorter than the fixed-delay gap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                done.countDown();
            }
        }, POLL_PERIOD_MS);

        try {
            assertThat(done.await(15, TimeUnit.SECONDS))
                    .as("all %d fixed-delay cycles completed", cycles)
                    .isTrue();
        } finally {
            task.cancel(false);
        }
        assertThat(peakInFlight.get())
                .as("a fixed-delay task must never overlap its own previous execution")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("in-flight maintenance passes run on named daemon threads")
    void inFlightRunsOnNamedDaemonThread() throws Exception {
        scheduler.initialize();

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        scheduler.schedule(() -> {
            Thread current = Thread.currentThread();
            threadName.set(current.getName());
            daemon.set(current.isDaemon());
            done.countDown();
        }, Instant.now().plusMillis(1));

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get())
                .as("maintenance threads are named from the configured prefix")
                .startsWith(PROPERTIES.threadNamePrefix());
        assertThat(daemon.get())
                .as("maintenance threads are daemons so the JVM cannot hang on them")
                .isTrue();
    }

    /** Polls the condition on the poller until true or {@code timeoutMs} elapses. */
    private void awaitTrue(java.util.function.BooleanSupplier condition, String description,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }
}
