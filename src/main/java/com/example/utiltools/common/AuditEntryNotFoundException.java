package com.example.utiltools.common;

/** Raised when an audit entry id cannot be found in the (bounded) journal. */
public class AuditEntryNotFoundException extends RuntimeException {

    public AuditEntryNotFoundException(String id) {
        super("Audit entry not found: " + id);
    }
}
