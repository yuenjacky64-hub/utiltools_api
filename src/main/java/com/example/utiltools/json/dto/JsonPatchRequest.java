package com.example.utiltools.json.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record JsonPatchRequest(
        @NotBlank(message = "json document is required")
        String json,
        @NotEmpty(message = "operations list is required")
        @Size(max = 100, message = "operations must contain at most 100 entries")
        List<@Valid JsonPatchOperation> operations,
        Boolean dryRun
) {}
