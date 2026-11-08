package com.example.utiltools.json.dto;

import jakarta.validation.constraints.Size;

/** {@code ndjson} may be null or blank: an empty document is a valid input. */
public record JsonFromNdjsonRequest(
        @Size(max = 1000000, message = "ndjson content must be at most 1000000 characters")
        String ndjson
) {}
