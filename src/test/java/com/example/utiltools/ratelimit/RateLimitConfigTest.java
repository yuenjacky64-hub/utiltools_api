package com.example.utiltools.ratelimit;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.example.utiltools.UtiltoolsApiApplication;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = UtiltoolsApiApplication.class)
@DisplayName("RateLimitConfig")
class RateLimitConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the filter is registered once for /api/v1/utils/* ahead of Spring MVC")
    void filterRegisteredForUtilsPathsBeforeSpringMvc() {
        FilterRegistrationBean<?> registration =
                context.getBean("rateLimitFilterRegistration", FilterRegistrationBean.class);

        assertThat(registration.getFilter()).isInstanceOf(RateLimitFilter.class);
        assertThat(registration.getUrlPatterns()).contains("/api/v1/utils/*");
        assertThat(registration.getOrder()).isEqualTo(RateLimitConfig.RATE_LIMIT_FILTER_ORDER);
        assertThat(registration.getOrder()).isLessThan(0);

        long rateLimitRegistrations = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(bean -> bean.getFilter() instanceof RateLimitFilter)
                .count();
        assertThat(rateLimitRegistrations).isEqualTo(1);

        Map<String, Filter> rawFilterBeans = context.getBeansOfType(Filter.class);
        assertThat(rawFilterBeans.values()).noneMatch(filter -> filter instanceof RateLimitFilter);
    }

    @Test
    @DisplayName("application.properties binds the documented defaults")
    void applicationPropertiesBind() {
        RateLimitProperties properties = context.getBean(RateLimitProperties.class);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.requestsPerWindow()).isEqualTo(60);
        assertThat(properties.windowMs()).isEqualTo(60_000L);
        assertThat(properties.cleanupIntervalMs()).isEqualTo(30_000L);
        assertThat(properties.maxTrackedClients()).isEqualTo(10_000);
        assertThat(properties.trustForwardedHeader()).isFalse();
        assertThat(context.getBean(Clock.class)).isNotNull();
        assertThat(context.getBean(RateLimitConfig.class)).isNotNull();
    }

    @Test
    @DisplayName("class-level defaults apply without application.properties and are overridable")
    void defaultsBindAndOverride() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnlyConfig.class)
                .run(ctx -> {
                    RateLimitProperties properties = ctx.getBean(RateLimitProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.requestsPerWindow()).isEqualTo(60);
                    assertThat(properties.windowMs()).isEqualTo(60_000L);
                    assertThat(properties.cleanupIntervalMs()).isEqualTo(30_000L);
                    assertThat(properties.maxTrackedClients()).isEqualTo(10_000);
                    assertThat(properties.trustForwardedHeader()).isFalse();
                });

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnlyConfig.class)
                .withPropertyValues("utiltools.ratelimit.requests-per-window=5",
                        "utiltools.ratelimit.window-ms=1000",
                        "utiltools.ratelimit.max-tracked-clients=2",
                        "utiltools.ratelimit.trust-forwarded-header=true")
                .run(ctx -> {
                    RateLimitProperties properties = ctx.getBean(RateLimitProperties.class);
                    assertThat(properties.requestsPerWindow()).isEqualTo(5);
                    assertThat(properties.windowMs()).isEqualTo(1000L);
                    assertThat(properties.maxTrackedClients()).isEqualTo(2);
                    assertThat(properties.trustForwardedHeader()).isTrue();
                });
    }

    @Test
    @DisplayName("cleanup is scheduled on the configured cleanup interval and evicts idle keys only")
    void cleanupJobUsesConfiguredInterval() throws Exception {
        Method cleanup = RateLimitConfig.class.getMethod("evictIdleBuckets");
        Scheduled scheduled = cleanup.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${utiltools.ratelimit.cleanup-interval-ms:30000}");

        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimitProperties properties = new RateLimitProperties(true, 1, 1000L, 30_000L, 100, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        RateLimitConfig config = new RateLimitConfig();
        ReflectionTestUtils.setField(config, "limiterProvider",
                new ObjectProvider<SlidingWindowRateLimiter>() {
                    @Override
                    public SlidingWindowRateLimiter getObject() {
                        return limiter;
                    }
                });
        ReflectionTestUtils.setField(config, "properties", properties);

        limiter.tryAcquire("idle");
        clock.advance(Duration.ofMillis(1500));
        assertThat(limiter.tryAcquire("active").allowed()).isTrue();

        config.evictIdleBuckets();
        assertThat(limiter.trackedClients()).isEqualTo(2);

        clock.advance(Duration.ofMillis(600));
        config.evictIdleBuckets();
        assertThat(limiter.trackedClients()).isEqualTo(1);
        assertThat(limiter.tryAcquire("active").allowed()).isFalse();
    }

    @Test
    @DisplayName("a low max-tracked-clients binds through the context and bounds the real limiter")
    void configuredCapAppliesToRealLimiter() {
        new ApplicationContextRunner()
                .withUserConfiguration(LimiterSupport.class, RateLimitConfig.class)
                .withPropertyValues("utiltools.ratelimit.max-tracked-clients=2",
                        "utiltools.ratelimit.requests-per-window=3")
                .run(ctx -> {
                    SlidingWindowRateLimiter limiter = ctx.getBean(SlidingWindowRateLimiter.class);
                    for (int i = 0; i < 50; i++) {
                        limiter.tryAcquire("spoof-" + i);
                        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(2);
                    }
                    assertThat(limiter.properties().maxTrackedClients()).isEqualTo(2);
                });
    }

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertiesOnlyConfig {
    }

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class LimiterSupport {

        @Bean
        SlidingWindowRateLimiter slidingWindowRateLimiter(RateLimitProperties properties) {
            return new SlidingWindowRateLimiter(properties, Clock.systemUTC());
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
