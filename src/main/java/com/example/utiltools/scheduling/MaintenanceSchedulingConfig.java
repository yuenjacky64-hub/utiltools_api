package com.example.utiltools.scheduling;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared bounded maintenance scheduler.
 *
 * <p>The bean is deliberately named {@code taskScheduler}: the {@code @Scheduled} binder
 * resolves a bean of that name before falling back to the default single-thread scheduler, so
 * every maintenance pass in the app executes on this pool. Spring Boot's
 * {@code TaskSchedulingAutoConfiguration} backs off on the bean's type
 * ({@code @ConditionalOnMissingBean(TaskScheduler.class)}), so there is exactly one scheduler
 * pool in the context.</p>
 *
 * <p>The {@link MaintenancePassGuard} bean gives every scheduled pass its own quarantine machine.
 * It builds its {@code resilience.CircuitBreaker} internally and never exposes it, so the context
 * keeps exactly one {@code CircuitBreaker} bean.</p>
 */
@Configuration
@EnableConfigurationProperties(MaintenanceSchedulingProperties.class)
public class MaintenanceSchedulingConfig {

    @Bean
    public MaintenanceTaskScheduler taskScheduler(MaintenanceSchedulingProperties properties) {
        return new MaintenanceTaskScheduler(properties);
    }

    @Bean
    public MaintenancePassGuard maintenancePassGuard(MaintenanceSchedulingProperties properties,
            Clock clock) {
        return new MaintenancePassGuard(properties, clock);
    }
}
