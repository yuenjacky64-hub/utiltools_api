package com.example.utiltools.json.dto;

public record JsonValidateResponse(
        boolean valid,
        String error,
        Integer line,
        Integer column,
        String type
) {}
