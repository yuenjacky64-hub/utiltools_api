package com.example.utiltools.common;

/** Raised when an audit entry exists but cannot be manually reaped (it is already terminal). */
public class ReapConflictException extends RuntimeException {

    public ReapConflictException(String id, String reason) {
        super("Manual reap not allowed for audit entry " + id + ": " + reason);
    }
}
