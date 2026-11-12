package com.example.utiltools.scheduling;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOD-03: proves the scheduler's graceful-shutdown contract.
 *
 * <ul>
 *   <li>{@code shutdown()} waits for an in-flight pass up to the configured
 *       {@code await-termination-seconds} grace period before forcing termination;</li>
 *   <li>no maintenance threads are left behind, and all maintenance threads are daemons, so a
 *       missed or forced termination can never hang the JVM.</li>
 * </ul>
 *
 * <p>Plain JUnit against a {@link MaintenanceTaskScheduler}; the app's shutdown path closes the
 * context (and therefore this scheduler) via the usual Spring lifecycle.
 */
@DisplayName("Scheduler graceful shutdown (SchedulerLifecycleTest)")
final class SchedulerLifecycleTest {

    /** 4 lanes, {@code maintenance-} naming, 2s shutdown grace. */
    private final MaintenanceTaskScheduler scheduler =
            new MaintenanceTaskScheduler(new MaintenanceSchedulingProperties(4, "maintenance-", 2));
    private final String prefix = "maintenance-";

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    @DisplayName("shutdown waits for an in-flight pass up to the configured grace period")
    void shutdownWaitsForInFlightPass() throws Exception {
        scheduler.initialize();

        // 2s grace: an in-flight pass that finishes in ~1s must complete inside shutdown().
        CountDownLatch inFlightDone = new CountDownLatch(1);
        AtomicBoolean forcedTermination = new AtomicBoolean();
        long taskStart = System.nanoTime();
        long shutdownStart = 0L;
        long shutdownEnd = 0L;

        scheduler.schedule(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                forcedTermination.set(true);
                Thread.currentThread().interrupt();
                return;
            }
            inFlightDone.countDown();
        }, Instant.now().plusMillis(1));

        shutdownStart = System.nanoTime();
        scheduler.shutdown();
        shutdownEnd = System.nanoTime();

        assertThat(inFlightDone.await(5, TimeUnit.SECONDS))
                .as("the in-flight pass finished within the 2s await-termination window")
                .isTrue();
        assertThat(forcedTermination.get())
                .as("the in-flight pass completed on its own, not by forced termination")
                .isFalse();
        long shutdownMs = TimeUnit.NANOSECONDS.toMillis(shutdownEnd - shutdownStart);
        long inFlightMs = TimeUnit.NANOSECONDS.toMillis(shutdownEnd - taskStart);
        assertThat(shutdownMs)
                .as("shutdown() held the scheduler open for the in-flight pass (%d ms of %d ms)",
                        inFlightMs, shutdownMs)
                .isGreaterThanOrEqualTo(900);
    }

    @Test
    @DisplayName("shutdown leaves no maintenance threads behind")
    void shutdownLeavesNoMaintenanceThreads() throws Exception {
        scheduler.initialize();
        scheduler.schedule(() -> { }, Instant.now().plusMillis(1));
        scheduler.shutdown();

        // All maintenance threads are daemons; give the pool a moment to drain, then confirm
        // none remain (and that none was ever a non-daemon).
        long deadline = System.currentTimeMillis() + 5_000;
        while (aliveMaintenanceThreads().size() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(aliveMaintenanceThreads())
                .as("no %s* maintenance threads are left after shutdown", prefix)
                .isEmpty();
    }

    private java.util.List<Thread> aliveMaintenanceThreads() {
        java.util.List<Thread> alive = new java.util.ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix) && thread.isAlive()) {
                alive.add(thread);
            }
        }
        return alive;
    }
}
