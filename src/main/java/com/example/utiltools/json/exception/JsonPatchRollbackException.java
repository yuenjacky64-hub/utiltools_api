package com.example.utiltools.json.exception;

import com.example.utiltools.json.dto.JsonPatchOperation;

import java.util.List;

public class JsonPatchRollbackException extends RuntimeException {

    private final int failedIndex;
    private final String failedOperation;
    private final String failedPath;
    private final int operationsRolledBack;
    private final List<JsonPatchOperation> audit;
    private final String reason;

    public JsonPatchRollbackException(int failedIndex, String failedOperation, String failedPath,
                                      int operationsRolledBack, List<JsonPatchOperation> audit,
                                      String reason) {
        super(String.format(
                "JSON Patch failed at operation %d (%s %s): %s; %d previously applied operation(s) were rolled back",
                failedIndex, failedOperation, failedPath, reason, operationsRolledBack));
        this.failedIndex = failedIndex;
        this.failedOperation = failedOperation;
        this.failedPath = failedPath;
        this.operationsRolledBack = operationsRolledBack;
        this.audit = List.copyOf(audit);
        this.reason = reason;
    }

    public int failedIndex() {
        return failedIndex;
    }

    public String failedOperation() {
        return failedOperation;
    }

    public String failedPath() {
        return failedPath;
    }

    public int operationsRolledBack() {
        return operationsRolledBack;
    }

    public List<JsonPatchOperation> audit() {
        return audit;
    }

    public String reason() {
        return reason;
    }
}
