package com.example.utiltools.crypto.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a Base58 encode or decode operation")
public record Base58Response(
        @Schema(description = "Normalised action that produced the result", example = "ENCODE")
        String action,
        @Schema(description = "Base58 text for ENCODE, decoded UTF-8 text for DECODE", example = "2NEpo7TZRRrLZSi2U")
        String result
) {}
