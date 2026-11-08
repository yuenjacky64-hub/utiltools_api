package com.example.utiltools.json.dto;

/** One structural difference: its rendered path, both compact JSON values and the change kind. */
public record JsonDiffDetail(
        String path,
        String oldValue,
        String newValue,
        String changeType
) {

    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED = "CHANGED";
}
