package com.example.utiltools.scheduling;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOD-04: {@link MaintenanceSchedulingProperties} validates at startup.
 *
 * <p>Both the compact-constructor guards and the Spring Boot {@code Binder} startup path are
 * covered: invalid {@code utiltools.scheduling.*} values (pool-size=0, pool-size=65, blank
 * thread-name-prefix, negative await-termination-seconds) fail with a clear configuration error
 * naming the offending property, and the documented defaults (8 / {@code maintenance-} / 30)
 * bind when nothing is set.
 */
@DisplayName("Maintenance scheduling properties validation (MaintenanceSchedulingPropertiesTest)")
final class MaintenanceSchedulingPropertiesTest {

    @Test
    @DisplayName("documented defaults: 8 lanes, maintenance- prefix, 30s shutdown grace")
    void defaults() {
        MaintenanceSchedulingProperties properties = MaintenanceSchedulingProperties.defaults();
        assertThat(properties.poolSize()).isEqualTo(8);
        assertThat(properties.threadNamePrefix()).isEqualTo("maintenance-");
        assertThat(properties.awaitTerminationSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("pool-size below the bound fails startup")
    void poolSizeBelowBoundFails() {
        assertThatThrownBy(() -> new MaintenanceSchedulingProperties(0, "maintenance-", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.scheduling.pool-size must be between 1 and 64");
    }

    @Test
    @DisplayName("pool-size above the bound fails startup")
    void poolSizeAboveBoundFails() {
        assertThatThrownBy(() -> new MaintenanceSchedulingProperties(65, "maintenance-", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.scheduling.pool-size must be between 1 and 64")
                .hasMessageContaining("got 65");
    }

    @Test
    @DisplayName("blank or null thread-name-prefix fails startup")
    void blankThreadNamePrefixFails() {
        assertThatThrownBy(() -> new MaintenanceSchedulingProperties(8, "  ", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.scheduling.thread-name-prefix must be a non-blank");
        assertThatThrownBy(() -> new MaintenanceSchedulingProperties(8, null, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.scheduling.thread-name-prefix must be a non-blank");
    }

    @Test
    @DisplayName("negative await-termination-seconds fails startup")
    void negativeAwaitTerminationSecondsFails() {
        assertThatThrownBy(() -> new MaintenanceSchedulingProperties(8, "maintenance-", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.scheduling.await-termination-seconds must be >= 0");
    }

    @Test
    @DisplayName("boundary values (1 lane, await-termination-seconds 0) are accepted")
    void boundaryValuesAccepted() {
        MaintenanceSchedulingProperties properties =
                new MaintenanceSchedulingProperties(1, "maintenance-", 0);
        assertThat(properties.poolSize()).isEqualTo(1);
        assertThat(properties.awaitTerminationSeconds()).isZero();
    }

    @Test
    @DisplayName("binding with no utiltools.scheduling.* set yields the documented defaults")
    void bindDefaultsWhenUnset() {
        MaintenanceSchedulingProperties properties =
                bind(Collections.emptyMap(), 8, "maintenance-", 30, null);
        assertThat(properties.poolSize()).isEqualTo(8);
        assertThat(properties.threadNamePrefix()).isEqualTo("maintenance-");
        assertThat(properties.awaitTerminationSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("binding invalid pool-size fails startup with a clear configuration error")
    void bindInvalidPoolSizeFails() {
        Map<String, Object> source =
                Map.of("utiltools.scheduling.pool-size", Collections.singletonList("65"));
        assertThatThrownBy(() -> bind(source, -1, null, 0, "pool-size"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("utiltools.scheduling.pool-size must be between 1 and 64");
    }

    @Test
    @DisplayName("binding blank thread-name-prefix fails startup")
    void bindBlankThreadNamePrefixFails() {
        Map<String, Object> source =
                Map.of("utiltools.scheduling.thread-name-prefix", Collections.singletonList(""));
        assertThatThrownBy(() -> bind(source, -1, null, 0, "thread-name-prefix"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("utiltools.scheduling.thread-name-prefix must be a non-blank");
    }

    @Test
    @DisplayName("binding negative await-termination-seconds fails startup")
    void bindNegativeAwaitTerminationSecondsFails() {
        Map<String, Object> source =
                Map.of("utiltools.scheduling.await-termination-seconds",
                        Collections.singletonList("-1"));
        assertThatThrownBy(() -> bind(source, -1, null, 0, "await-termination-seconds"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("utiltools.scheduling.await-termination-seconds must be >= 0");
    }

    @Test
    @DisplayName("binding valid custom values passes through")
    void bindValidCustomValues() {
        Map<String, Object> source = Map.of(
                "utiltools.scheduling.pool-size", Collections.singletonList("16"),
                "utiltools.scheduling.thread-name-prefix", Collections.singletonList("ops-"),
                "utiltools.scheduling.await-termination-seconds", Collections.singletonList("0"));
        MaintenanceSchedulingProperties properties =
                bind(source, 16, "ops-", 0, null);
        assertThat(properties.poolSize()).isEqualTo(16);
        assertThat(properties.threadNamePrefix()).isEqualTo("ops-");
        assertThat(properties.awaitTerminationSeconds()).isZero();
    }

    /**
     * Binds the record the way the application startup does; {@code expect*} are assertion
     * targets checked only when no {@code failingProperty} is expected.
     */
    private static MaintenanceSchedulingProperties bind(Map<String, Object> source, int expectPoolSize,
            String expectPrefix, int expectAwait, String failingProperty) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        MaintenanceSchedulingProperties properties = binder
                .bind("utiltools.scheduling", MaintenanceSchedulingProperties.class,
                        MaintenanceSchedulingProperties.defaults())
                .get();
        if (failingProperty == null) {
            assertThat(properties.poolSize()).isEqualTo(expectPoolSize);
            assertThat(properties.threadNamePrefix()).isEqualTo(expectPrefix);
            assertThat(properties.awaitTerminationSeconds()).isEqualTo(expectAwait);
        }
        return properties;
    }
}
