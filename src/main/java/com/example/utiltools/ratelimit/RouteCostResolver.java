package com.example.utiltools.ratelimit;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Maps an HTTP method plus request URI to the number of weighted budget units it charges.
 *
 * <p>Pure and bounded: three immutable route entries, one map lookup on the hit path, no regex, no
 * allocation beyond the normalised path, and no growth keyed by attacker-controlled input. Anything
 * that is not an exact {@code (POST, path)} match falls back to {@code utiltools.ratelimit.costs.default},
 * so a mismatch can only ever over-charge relative to the cheap default.</p>
 *
 * <p>The class is deliberately non-final: tests substitute a throwing subclass to prove the filter's
 * fail-open behaviour is real rather than dead code.</p>
 */
@Component
public class RouteCostResolver {

    /** {@code POST /api/v1/utils/audit/verify} replays up to the journal cap. */
    public static final String AUDIT_VERIFY_PATH = "/api/v1/utils/audit/verify";
    /** {@code POST /api/v1/utils/jobs/hash} hashes up to 1000 items. */
    public static final String JOB_SUBMIT_PATH = "/api/v1/utils/jobs/hash";
    /** {@code POST /api/v1/utils/json/query} evaluates an arbitrary JSONPath. */
    public static final String JSON_QUERY_PATH = "/api/v1/utils/json/query";

    private static final String POST = "POST";

    private final int defaultCost;
    private final Map<Route, Integer> costByRoute;

    public RouteCostResolver(RateLimitCostProperties costs) {
        this.defaultCost = costs.defaultCost();
        this.costByRoute = Map.copyOf(Map.of(
                new Route(POST, AUDIT_VERIFY_PATH), costs.auditVerifyCost(),
                new Route(POST, JOB_SUBMIT_PATH), costs.jobSubmitCost(),
                new Route(POST, JSON_QUERY_PATH), costs.jsonQueryCost()));
    }

    /**
     * Resolves the cost of one request.
     *
     * @param method     HTTP method, may be {@code null}
     * @param requestUri request URI as reported by the container, may be {@code null}
     * @return a positive integer cost; {@code defaultCost()} when nothing matches
     */
    public int resolve(String method, String requestUri) {
        if (method == null || requestUri == null || requestUri.isEmpty()) {
            return defaultCost;
        }
        try {
            Integer cost = costByRoute.get(new Route(method.toUpperCase(Locale.ROOT), normalise(requestUri)));
            return cost != null ? cost : defaultCost;
        } catch (RuntimeException ignored) {
            // Totality: resolution may never break the request path.
            return defaultCost;
        }
    }

    /** Cost charged to every route without a specific entry. */
    public int defaultCost() {
        return defaultCost;
    }

    /**
     * Normalises the raw URI to the path Spring MVC would dispatch: percent-decoded, matrix-parameter
     * suffix and query string removed, one trailing slash dropped. Every step only makes the match
     * more permissive, never less, so a costed route cannot be reached under a cheaper spelling.
     */
    private static String normalise(String requestUri) {
        String path = requestUri;
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            // Malformed escape: keep the raw value and let it fall through to the default cost.
            path = requestUri;
        }
        int matrixParam = path.indexOf(';');
        if (matrixParam >= 0) {
            path = path.substring(0, matrixParam);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** Immutable lookup key: method names are normalised before construction. */
    private record Route(String method, String path) {
    }
}
