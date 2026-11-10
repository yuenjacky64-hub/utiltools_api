package com.example.utiltools.audit.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Internal seam that runs the replay lease heartbeat: one periodic {@code tick} per attempt.
 *
 * <p>The production implementation is a lazily created single daemon thread, so a short replay
 * never creates a thread and a leaked request cannot keep the JVM alive. Tests inject a manual
 * implementation and drive the tick by hand, so no test sleeps for the lease duration. A
 * scheduler is never a Spring bean: the {@code @WebMvcTest} slice that imports
 * {@link AuditReplayService} must stay satisfiable without one.</p>
 */
public interface ReplayHeartbeatScheduler {

    /**
     * Schedules {@code tick} every {@code period}. The first run is one full period after the
     * call, so a replay shorter than one period never renews.
     */
    Handle schedule(Runnable tick, Duration period);

    /** Cancels one schedule; cancelling twice is a no-op. */
    interface Handle {
        void cancel();
    }

    /** A scheduler that schedules nothing and never runs a tick (the 3-arg constructor seam). */
    static ReplayHeartbeatScheduler disabled() {
        return (tick, period) -> () -> { };
    }

    /**
     * The production scheduler: a single daemon thread, created on the first {@link #schedule}
     * call and shut down by {@link AuditReplayService} when the context closes.
     */
    static ReplayHeartbeatScheduler daemonThreadPool() {
        return new DaemonThreadPoolScheduler();
    }

    /** Lazy single daemon thread; the pool exists only while at least one replay is running. */
    final class DaemonThreadPoolScheduler implements ReplayHeartbeatScheduler, AutoCloseable {

        private ScheduledExecutorService executor;

        @Override
        public Handle schedule(Runnable tick, Duration period) {
            long millis = Math.max(1L, period.toMillis());
            ScheduledFuture<?> future = executor().scheduleAtFixedRate(tick, millis, millis,
                    TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        private synchronized ScheduledExecutorService executor() {
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "audit-replay-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return executor;
        }

        @Override
        public void close() {
            ScheduledExecutorService current;
            synchronized (this) {
                current = executor;
                executor = null;
            }
            if (current != null) {
                current.shutdownNow();
            }
        }
    }
}
