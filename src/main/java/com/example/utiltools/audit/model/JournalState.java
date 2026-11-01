package com.example.utiltools.audit.model;

/** Lifecycle of a journalled operation: PENDING -> SUCCEEDED | FAILED. */
public enum JournalState {
    PENDING,
    SUCCEEDED,
    FAILED
}
