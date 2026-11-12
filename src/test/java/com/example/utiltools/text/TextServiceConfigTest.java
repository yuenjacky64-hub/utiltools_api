package com.example.utiltools.text;

import com.example.utiltools.text.service.TextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup-time validation of {@code utiltools.text.regex-timeout-ms} (REQ-01, DOD-04). A value
 * outside 1..60000 fails application startup with {@link IllegalArgumentException}; in-range and
 * default values start cleanly. Mirrors the {@code RateLimitConfigTest} {@link ApplicationContextRunner}
 * binding precedent — no running server, just the bean-creation seam.
 */
class TextServiceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TextServiceConfig.class);

    @Test
    @DisplayName("a value below 1 fails application startup (REQ-01)")
    void belowRangeFailsStartup() {
        runner.withPropertyValues("utiltools.text.regex-timeout-ms=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("utiltools.text.regex-timeout-ms");
        });
    }

    @Test
    @DisplayName("a value above 60000 fails application startup (REQ-01)")
    void aboveRangeFailsStartup() {
        runner.withPropertyValues("utiltools.text.regex-timeout-ms=60001").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("utiltools.text.regex-timeout-ms");
        });
    }

    @Test
    @DisplayName("the in-range boundary 60000 starts cleanly (REQ-01)")
    void inRangeBoundaryStarts() {
        runner.withPropertyValues("utiltools.text.regex-timeout-ms=60000")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("the absence of the property starts on the 200 ms default (REQ-01)")
    void absentPropertyStartsOnDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TextService.class);
        });
    }

    @Configuration
    static class TextServiceConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        TextService textService(@Value("${utiltools.text.regex-timeout-ms:200}") int regexTimeoutMs,
                                Clock clock) {
            return new TextService(regexTimeoutMs, clock);
        }
    }
}
