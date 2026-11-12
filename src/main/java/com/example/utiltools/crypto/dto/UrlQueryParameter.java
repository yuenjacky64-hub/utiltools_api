package com.example.utiltools.crypto.dto;

/**
 * One decoded query-string parameter. {@code value} is the decoded text after the first {@code =};
 * a pair written without {@code =} (e.g. {@code ?flag}) or with nothing after it (e.g. {@code ?a=})
 * keeps the empty string.
 */
public record UrlQueryParameter(
        String name,
        String value
) {}
