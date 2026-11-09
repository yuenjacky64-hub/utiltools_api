package com.example.utiltools.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RouteCostResolver")
class RouteCostResolverTest {

    private static final RateLimitCostProperties DEFAULTS =
            new RateLimitCostProperties(1, 5, 3, 2);

    private final RouteCostResolver resolver = new RouteCostResolver(DEFAULTS);

    @Test
    @DisplayName("configured routes keep their costs and every other route falls back to 1")
    void resolvesConfiguredRoutesAndFallsBackToDefault() {
        assertThat(resolver.resolve("POST", "/api/v1/utils/audit/verify")).isEqualTo(5);
        assertThat(resolver.resolve("POST", "/api/v1/utils/jobs/hash")).isEqualTo(3);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query")).isEqualTo(2);

        assertThat(resolver.resolve("GET", "/api/v1/utils/rate-limit/status")).isEqualTo(1);
        assertThat(resolver.resolve("GET", "/api/v1/utils/jobs/abc-123")).isEqualTo(1);
        assertThat(resolver.resolve("POST", "/api/v1/utils/not/a/route")).isEqualTo(1);
        assertThat(resolver.defaultCost()).isEqualTo(1);

        // Method sensitivity: the same path under another verb is not the costed route.
        assertThat(resolver.resolve("GET", "/api/v1/utils/json/query")).isEqualTo(1);
        assertThat(resolver.resolve("PUT", "/api/v1/utils/audit/verify")).isEqualTo(1);
        // Lower-case verbs resolve like the container's upper-case ones.
        assertThat(resolver.resolve("post", "/api/v1/utils/jobs/hash")).isEqualTo(3);

        // Configured values, not the class defaults, drive the mapping.
        RouteCostResolver custom = new RouteCostResolver(new RateLimitCostProperties(4, 9, 7, 6));
        assertThat(custom.resolve("POST", "/api/v1/utils/audit/verify")).isEqualTo(9);
        assertThat(custom.resolve("POST", "/api/v1/utils/jobs/hash")).isEqualTo(7);
        assertThat(custom.resolve("POST", "/api/v1/utils/json/query")).isEqualTo(6);
        assertThat(custom.resolve("GET", "/api/v1/utils/probe")).isEqualTo(4);
        assertThat(custom.defaultCost()).isEqualTo(4);
    }

    @Test
    @DisplayName("trailing slash, matrix parameters and percent-encoding still match the costed route")
    void normalisesTrailingSlashMatrixParamsAndEncoding() {
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query;x=1")).isEqualTo(2);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query;jsessionid=abc")).isEqualTo(2);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query/")).isEqualTo(2);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/%71uery")).isEqualTo(2);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query?x=1")).isEqualTo(2);
        assertThat(resolver.resolve("POST", "/api/v1/utils/audit/verify/")).isEqualTo(5);
        assertThat(resolver.resolve("POST", "/api/v1/utils/jobs/hash;attempt=2")).isEqualTo(3);

        // A different path stays at the default even when it is spelled unusually.
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query/extra")).isEqualTo(1);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/%zzuery")).isEqualTo(1);
        assertThat(resolver.resolve("POST", "/")).isEqualTo(1);
    }

    @Test
    @DisplayName("null and unknown input resolve to the default cost and never throw")
    void isTotalForNullAndUnknownInput() {
        assertThat(resolver.resolve(null, "/api/v1/utils/json/query")).isEqualTo(1);
        assertThat(resolver.resolve("POST", null)).isEqualTo(1);
        assertThat(resolver.resolve("POST", "")).isEqualTo(1);
        assertThat(resolver.resolve(null, null)).isEqualTo(1);
        assertThat(resolver.resolve("", "")).isEqualTo(1);
        assertThat(resolver.resolve("POST", "/api/v1/utils/json/query\u0000")).isEqualTo(1);
    }

    @Test
    @DisplayName("non-positive configured costs are rejected at construction")
    void rejectsNonPositiveConfiguredCost() {
        assertThatThrownBy(() -> new RateLimitCostProperties(0, 5, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.ratelimit.costs.default");
        assertThatThrownBy(() -> new RateLimitCostProperties(1, -5, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.ratelimit.costs.audit-verify");
        assertThatThrownBy(() -> new RateLimitCostProperties(1, 5, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.ratelimit.costs.job-submit");
        assertThatThrownBy(() -> new RateLimitCostProperties(1, 5, 3, -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("utiltools.ratelimit.costs.json-query");
    }
}
