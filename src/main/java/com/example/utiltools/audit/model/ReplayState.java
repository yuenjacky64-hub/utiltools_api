package com.example.utiltools.audit.model;

/** Outcome of replaying a stored request against the live service. */
public enum ReplayState {
    VERIFIED,
    MISMATCH
}
