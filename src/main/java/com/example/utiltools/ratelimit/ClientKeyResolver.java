package com.example.utiltools.ratelimit;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single source of truth for the client identity used by the rate limiter.
 *
 * <p>Both the admission gate ({@link RateLimitFilter}) and the read-only status endpoint
 * ({@link RateLimitStatusController}) resolve their key here, so the bucket a caller is limited by
 * and the bucket a caller is shown are always the same one. A second copy of the trust rule would
 * let a caller read a bucket it is not being limited by.</p>
 *
 * <p>{@code X-Forwarded-For} is only honoured when
 * {@code utiltools.ratelimit.trust-forwarded-header} is enabled, and only the first entry is
 * considered, and only when it looks like an address.</p>
 */
public final class ClientKeyResolver {

    /** Bucket key used when the servlet request carries no usable remote address. */
    public static final String UNKNOWN_CLIENT = "unknown";

    /** Header carrying the originating client address behind a trusted proxy. */
    public static final String FORWARDED_HEADER = "X-Forwarded-For";

    private static final Pattern WELL_FORMED_ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private ClientKeyResolver() {
    }

    /**
     * Resolves the rate-limit key for {@code request}.
     *
     * @param request    the current servlet request
     * @param properties the limiter configuration (only {@code trustForwardedHeader} is read)
     * @return the client key, never {@code null} or blank
     */
    public static String resolve(HttpServletRequest request, RateLimitProperties properties) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            remoteAddress = UNKNOWN_CLIENT;
        }
        if (!properties.trustForwardedHeader()) {
            return remoteAddress;
        }
        String forwarded = request.getHeader(FORWARDED_HEADER);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        String candidate = forwarded.split(",", 2)[0].trim();
        return WELL_FORMED_ADDRESS.matcher(candidate).matches() ? candidate : remoteAddress;
    }
}
