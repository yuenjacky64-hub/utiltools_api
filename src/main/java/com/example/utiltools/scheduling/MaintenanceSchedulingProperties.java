package com.example.utiltools.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 */
@ConfigurationProperties(prefix = "utiltools.scheduling")
public record MaintenanceSchedulingProperties(
        @DefaultValue("8") int poolSize,
        @DefaultValue("maintenance-") String threadNamePrefix,
        @DefaultValue("30") int awaitTerminationSeconds
) {

    public static final int DEFAULT_POOL_SIZE = 8;
    public static final String DEFAULT_THREAD_NAME_PREFIX = "maintenance-";
    public static final int DEFAULT_AWAIT_TERMINATION_SECONDS = 30;

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
    }

    /** The documented defaults: 8 lanes, {@code maintenance-} naming, 30s shutdown grace. */
    public static MaintenanceSchedulingProperties defaults() {
        return new MaintenanceSchedulingProperties(DEFAULT_POOL_SIZE,
                DEFAULT_THREAD_NAME_PREFIX, DEFAULT_AWAIT_TERMINATION_SECONDS);
    }
}
