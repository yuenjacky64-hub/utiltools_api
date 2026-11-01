package com.example.utiltools.json.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record JsonPatchRequest(
        @NotBlank(message = "json document is required")
        String json,
        @NotEmpty(message = "operations list is required")
        List<@Valid JsonPatchOperation> operations,
        Boolean dryRun
) {}
