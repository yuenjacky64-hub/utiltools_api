package com.example.utiltools.common;

/** Raised when an audit entry exists but cannot be replayed. */
public class ReplayConflictException extends RuntimeException {

    public ReplayConflictException(String id, String reason) {
        super("Replay not allowed for audit entry " + id + ": " + reason);
    }
}
