package com.example.utiltools.audit.model;

/** The journalled utility operations and whether they are deterministic. */
public enum OperationType {

    DATETIME_CONVERT(true),
    DATETIME_DIFF(true),
    DATETIME_SHIFT(true),
    DATETIME_CURRENT(false),
    CRYPTO_HASH(true),
    CRYPTO_BASE64(true),
    CRYPTO_URL(true),
    CRYPTO_UUID(false),
    JSON_FORMAT(true),
    JSON_VALIDATE(true),
    JSON_QUERY(true),
    JSON_FLATTEN(true),
    JSON_UNFLATTEN(true),
    TEXT_TRANSFORM(true),
    TEXT_STATS(true),
    TEXT_REPLACE(true);

    private final boolean deterministic;

    OperationType(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public boolean isDeterministic() {
        return deterministic;
    }
}
