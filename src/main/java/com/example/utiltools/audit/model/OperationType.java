package com.example.utiltools.audit.model;

/** The journalled utility operations and whether they are deterministic. */
public enum OperationType {

    DATETIME_CONVERT(true),
    DATETIME_DIFF(true),
    DATETIME_SHIFT(true),
    DATETIME_DURATION(true),
    DATETIME_BUSINESS_DAYS(true),
    DATETIME_RANGE(true),
    DATETIME_CURRENT(false),
    CRYPTO_HASH(true),
    CRYPTO_BASE64(true),
    CRYPTO_BASE32(true),
    CRYPTO_URL(true),
    CRYPTO_JWT_DECODE(true),
    CRYPTO_UUID(false),
    JSON_FORMAT(true),
    JSON_VALIDATE(true),
    JSON_QUERY(true),
    // An opt-in stale answer is a cached last-known-good result, not a fresh deterministic
    // evaluation: non-deterministic so it can never replay as if it were a live query.
    JSON_QUERY_FALLBACK(false),
    JSON_FLATTEN(true),
    JSON_UNFLATTEN(true),
    JSON_MERGE_PATCH(true),
    JSON_TO_CSV(true),
    JSON_FROM_CSV(true),
    JSON_CSV_TO_JSON(true),
    JSON_TO_NDJSON(true),
    JSON_FROM_NDJSON(true),
    JSON_DIFF(true),
    JSON_POINTER(true),
    TEXT_TRANSFORM(true),
    TEXT_STATS(true),
    TEXT_REPLACE(true),
    TEXT_HTML_ENTITIES(true),
    TEXT_TRUNCATE(true),
    BATCH_HASH(false),
    // A contact submission's dispatch attempt is non-deterministic end to end: delivery has a
    // wall-clock lease, a retry budget, and an out-of-scope dispatcher seam, so the entry never
    // replays as if it were a live evaluation (like BATCH_HASH and DATETIME_CURRENT).
    CONTACT_SUBMIT(false);

    private final boolean deterministic;

    OperationType(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public boolean isDeterministic() {
        return deterministic;
    }
}
