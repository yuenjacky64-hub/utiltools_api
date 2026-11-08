package com.example.utiltools.datetime.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DateTimeDurationRequest(
        @NotBlank(message = "action is required")
        @Pattern(regexp = "(?i)PARSE|FORMAT", message = "action must be one of PARSE, FORMAT")
        String action,
        String input,
        Long seconds,
        @Min(value = 0, message = "nanos must be between 0 and 999999999")
        @Max(value = 999999999, message = "nanos must be between 0 and 999999999")
        Integer nanos
) {

    @AssertTrue(message = "seconds is required when action is FORMAT")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isSecondsPresentForFormat() {
        return action == null || !"FORMAT".equalsIgnoreCase(action.trim()) || seconds != null;
    }
}
