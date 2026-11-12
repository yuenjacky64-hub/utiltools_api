package com.example.utiltools.scheduling;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A {@link ThreadPoolTaskScheduler} that backs the shared {@code taskScheduler} bean with a
 * {@link ScheduledThreadPoolExecutor} built from {@link MaintenanceSchedulingProperties}.
 *
 * <p>Worker threads are named {@code <thread-name-prefix><n>} ({@code n} a 1-based counter,
 * prefix verbatim) and are <strong>daemon</strong>, so the JVM can never hang at exit waiting
 * for a maintenance lane that shutdown missed. The pool is created by overriding the base
 * class's {@code createExecutor} hook with a daemon thread factory, so the base class's own
 * executor creation (whose thread naming/daemon settings are not configurable) is never used.</p>
 *
 * <p>{@code waitForTasksToCompleteOnShutdown} is enabled, so {@code shutdown()} lets in-flight
 * passes finish up to {@code await-termination-seconds} before forcing termination. The
 * {@code *Count}/{@code *Depth} accessors are read-only views of the live executor for the
 * status route; the class exposes no scheduling or cancellation surface.</p>
 */
public class MaintenanceTaskScheduler extends ThreadPoolTaskScheduler {

    private final MaintenanceSchedulingProperties properties;

    public MaintenanceTaskScheduler(MaintenanceSchedulingProperties properties) {
        this.properties = properties;
        setPoolSize(properties.poolSize());
        setThreadNamePrefix(properties.threadNamePrefix());
        setWaitForTasksToCompleteOnShutdown(true);
        setAwaitTerminationSeconds(properties.awaitTerminationSeconds());
        setRemoveOnCancelPolicy(true);
    }

    /** The base class's executor-creation hook: always use the daemon maintenance thread factory. */
    @Override
    protected ScheduledExecutorService createExecutor(int poolSize, ThreadFactory threadFactory,
            RejectedExecutionHandler rejectedExecutionHandler) {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                poolSize, new MaintenanceThreadFactory(properties.threadNamePrefix()), rejectedExecutionHandler);
        return pool;
    }

    // --- read-only config views (status route) ---

    public int poolSize() {
        return properties.poolSize();
    }

    public String threadNamePrefix() {
        return properties.threadNamePrefix();
    }

    public int awaitTerminationSeconds() {
        return properties.awaitTerminationSeconds();
    }

    // --- read-only executor counters (status route) ---

    /** Threads currently running a maintenance pass ({@code >= 0}). */
    public int activeCount() {
        return getScheduledThreadPoolExecutor().getActiveCount();
    }

    /** Passes that finished since the scheduler started. */
    public long completedTaskCount() {
        return getScheduledThreadPoolExecutor().getCompletedTaskCount();
    }

    /** Passes admitted but waiting for a free lane ({@code >= 0}). */
    public int queueDepth() {
        return getScheduledThreadPoolExecutor().getQueue().size();
    }

    /** Named, daemon-thread factory: {@code <prefix>1, <prefix>2, ...}. */
    private static final class MaintenanceThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger nextThreadNumber = new AtomicInteger(1);

        private MaintenanceThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + nextThreadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
