package com.example.utiltools.audit.model;

/**
 * Outcome of replaying a stored request against the live service.
 *
 * <p>{@code IN_PROGRESS} is a claim: exactly one replier owns the entry while it has a live lease.
 * The only legal replay transitions are {@code IN_PROGRESS -> VERIFIED}, {@code IN_PROGRESS ->
 * MISMATCH} and {@code IN_PROGRESS -> FAILED}; the terminal states are never left.
 */
public enum ReplayState {
    IN_PROGRESS,
    VERIFIED,
    MISMATCH,
    FAILED
}
