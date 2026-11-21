package com.example.utiltools.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration surface for the shared bounded maintenance scheduler
 * ({@code utiltools.scheduling.*}).
 *
 * <p>One {@code ThreadPoolTaskScheduler} pool owns every {@code @Scheduled} maintenance pass in
 * the app. {@code pool-size} is one lane per pass at the default of {@value #DEFAULT_POOL_SIZE}
 * (the app ships eight passes), bounded to {@code 1..64} so a misconfigured giant pool cannot
 * silently consume the box. Invalid values fail startup with a {@link IllegalArgumentException}
 * naming the offending property.</p>
 *
 * <p>The four {@code pass-*} properties configure the per-pass quarantine implemented by
 * {@link MaintenancePassGuard}: a pass that fails {@code pass-failure-threshold} consecutive ticks
 * is quarantined for {@code pass-cooldown-ms}, and the single recovery probe claims a
 * {@code pass-probe-lease-ms} lease before the pass closes after
 * {@code pass-success-threshold} successful probes.</p>
 */
@ConfigurationProperties(prefix = "utiltools.scheduling")
public record MaintenanceSchedulingProperties(
        @DefaultValue("8") int poolSize,
        @DefaultValue("maintenance-") String threadNamePrefix,
        @DefaultValue("30") int awaitTerminationSeconds,
        @DefaultValue("3") int passFailureThreshold,
        @DefaultValue("1") int passSuccessThreshold,
        @DefaultValue("60000") long passCooldownMs,
        @DefaultValue("10000") long passProbeLeaseMs
) {

    public static final int DEFAULT_POOL_SIZE = 8;
    public static final String DEFAULT_THREAD_NAME_PREFIX = "maintenance-";
    public static final int DEFAULT_AWAIT_TERMINATION_SECONDS = 30;
    public static final int DEFAULT_PASS_FAILURE_THRESHOLD = 3;
    public static final int DEFAULT_PASS_SUCCESS_THRESHOLD = 1;
    public static final long DEFAULT_PASS_COOLDOWN_MS = 60_000L;
    public static final long DEFAULT_PASS_PROBE_LEASE_MS = 10_000L;

    @ConstructorBinding
    public MaintenanceSchedulingProperties {
        if (poolSize < 1 || poolSize > 64) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.pool-size must be between 1 and 64, got " + poolSize);
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.thread-name-prefix must be a non-blank string");
        }
        if (awaitTerminationSeconds < 0) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.await-termination-seconds must be >= 0, got "
                            + awaitTerminationSeconds);
        }
        if (passFailureThreshold < 1 || passFailureThreshold > 100) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.pass-failure-threshold must be between 1 and 100, got "
                            + passFailureThreshold);
        }
        if (passSuccessThreshold < 1 || passSuccessThreshold > 10) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.pass-success-threshold must be between 1 and 10, got "
                            + passSuccessThreshold);
        }
        if (passCooldownMs < 0) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.pass-cooldown-ms must be >= 0, got " + passCooldownMs);
        }
        if (passProbeLeaseMs <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.scheduling.pass-probe-lease-ms must be > 0, got " + passProbeLeaseMs);
        }
    }

    /**
     * Source-compatible constructor for the pre-quarantine surface: pool shape only, with the
     * documented {@code pass-*} defaults.
     */
    public MaintenanceSchedulingProperties(int poolSize, String threadNamePrefix,
            int awaitTerminationSeconds) {
        this(poolSize, threadNamePrefix, awaitTerminationSeconds, DEFAULT_PASS_FAILURE_THRESHOLD,
                DEFAULT_PASS_SUCCESS_THRESHOLD, DEFAULT_PASS_COOLDOWN_MS,
                DEFAULT_PASS_PROBE_LEASE_MS);
    }

    /**
     * The documented defaults: 8 lanes, {@code maintenance-} naming, 30s shutdown grace, and a
     * quarantine of 3 failures / 1 success / 60s cooldown / 10s probe lease.
     */
    public static MaintenanceSchedulingProperties defaults() {
        return new MaintenanceSchedulingProperties(DEFAULT_POOL_SIZE,
                DEFAULT_THREAD_NAME_PREFIX, DEFAULT_AWAIT_TERMINATION_SECONDS);
    }
}
