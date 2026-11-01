package com.example.utiltools.common;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Holds the correlation id ({@code X-Request-Id}) of the request currently being
 * handled by this thread.
 *
 * <p>The ThreadLocal is written and cleared exclusively by {@link RequestIdFilter}.</p>
 */
public final class RequestContext {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "utiltools.requestId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    static Optional<String> normalize(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String value = candidate.trim();
        return VALID.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    public static void set(String id) {
        CURRENT.set(id);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
