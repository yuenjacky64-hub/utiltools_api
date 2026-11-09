package com.example.utiltools.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Weighted admission costs per route, bound from {@code utiltools.ratelimit.costs.*}.
 *
 * <p>Every cost is expressed in the same unit as {@code utiltools.ratelimit.requests-per-window}:
 * an admitted request charges its route cost against the caller's windowed budget. A non-positive
 * value is rejected here, at binding time, so a misconfiguration fails startup instead of surfacing
 * as a runtime error.</p>
 *
 * @param defaultCost     cost charged to every route without a specific entry
 * @param auditVerifyCost cost of {@code POST /api/v1/utils/audit/verify}
 * @param jobSubmitCost   cost of {@code POST /api/v1/utils/jobs/hash}
 * @param jsonQueryCost   cost of {@code POST /api/v1/utils/json/query}
 */
@ConfigurationProperties(prefix = "utiltools.ratelimit.costs")
public record RateLimitCostProperties(
        @Name("default") @DefaultValue("1") int defaultCost,
        @Name("audit-verify") @DefaultValue("5") int auditVerifyCost,
        @Name("job-submit") @DefaultValue("3") int jobSubmitCost,
        @Name("json-query") @DefaultValue("2") int jsonQueryCost) {

    public RateLimitCostProperties {
        requirePositive(defaultCost, "utiltools.ratelimit.costs.default");
        requirePositive(auditVerifyCost, "utiltools.ratelimit.costs.audit-verify");
        requirePositive(jobSubmitCost, "utiltools.ratelimit.costs.job-submit");
        requirePositive(jsonQueryCost, "utiltools.ratelimit.costs.json-query");
    }

    private static void requirePositive(int cost, String property) {
        if (cost < 1) {
            throw new IllegalArgumentException(property + " must be >= 1 (was " + cost + ")");
        }
    }
}
