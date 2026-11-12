package com.example.utiltools.text.exception;

/**
 * Raised when the request-scoped wall-clock budget for a {@code text/replace} regex evaluation is
 * exhausted. The message carries only the configured timeout, never caller text, so a 422 body
 * built from it stays bounded.
 */
public final class RegexTimeoutException extends RuntimeException {

    private final int timeoutMs;

    public RegexTimeoutException(int timeoutMs) {
        super("regex evaluation timed out after " + timeoutMs + " ms");
        this.timeoutMs = timeoutMs;
    }

    /** The configured {@code utiltools.text.regex-timeout-ms} value that bound this evaluation. */
    public int timeoutMs() {
        return timeoutMs;
    }
}
