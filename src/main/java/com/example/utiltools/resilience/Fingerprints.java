package com.example.utiltools.resilience;

import java.util.regex.Pattern;

/**
 * Single source of truth for the caller-supplied circuit breaker fingerprint.
 *
 * <p>The rule lives here for the same reason {@code ratelimit.ClientKeyResolver} documents one:
 * a controller copy and a breaker copy would drift, and a caller could then be quarantined under a
 * key it is never shown under. Anything that is null, blank or not
 * {@code [A-Za-z0-9_.:-]{1,64}} maps to {@link #DEFAULT}, which is the single global machine the
 * breaker used before fingerprints existed.</p>
 */
public final class Fingerprints {

    /** The legacy single-machine key: absent, blank or malformed fingerprints land here. */
    public static final String DEFAULT = "default";

    /** Request header that carries the fingerprint on the resilience endpoints. */
    public static final String HEADER = "X-Client-Fingerprint";

    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    private Fingerprints() {
    }

    /** @return the normalised key; never {@code null} and never blank. */
    public static String normalize(String candidate) {
        if (candidate == null) {
            return DEFAULT;
        }
        String trimmed = candidate.trim();
        return WELL_FORMED.matcher(trimmed).matches() ? trimmed : DEFAULT;
    }
}
