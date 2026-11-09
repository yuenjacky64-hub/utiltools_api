package com.example.utiltools.ratelimit;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires the rate limiter: the shared {@link Clock}, the single filter registration, and the
 * scheduled idle-bucket cleanup. The per-route {@link RateLimitCostProperties} are enabled here so
 * the {@link RouteCostResolver} bean and the filter registration see the same bound values.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, RateLimitCostProperties.class})
@EnableScheduling
public class RateLimitConfig {

    /** After Boot's encoding filter, before Spring MVC dispatch. */
    public static final int RATE_LIMIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    // Provider, not the bean itself: the limiter (a scanned @Component) depends on the Clock bean
    // declared below, so eagerly injecting the limiter here would make config <-> limiter circular.
    @Autowired
    private ObjectProvider<SlidingWindowRateLimiter> limiterProvider;

    @Autowired
    private RateLimitProperties properties;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            SlidingWindowRateLimiter limiter, RateLimitProperties properties,
            RouteCostResolver costResolver, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new RateLimitFilter(limiter, properties, costResolver, objectMapper));
        registration.addUrlPatterns("/api/v1/utils/*");
        registration.setOrder(RATE_LIMIT_FILTER_ORDER);
        registration.setName("rateLimitFilter");
        return registration;
    }

    @Scheduled(fixedDelayString = "${utiltools.ratelimit.cleanup-interval-ms:30000}")
    public void evictIdleBuckets() {
        if (!properties.enabled()) {
            return;
        }
        int removed = limiterProvider.getObject().evictIdleBuckets();
        if (removed > 0) {
            log.debug("rate-limit cleanup removed {} idle buckets", removed);
        }
    }
}
