package com.example.utiltools.datetime.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record DateTimeShiftRequest(
        @NotBlank(message = "input datetime or timestamp is required")
        String input,
        String targetTimeZone,
        @NotNull(message = "amount is required")
        Long amount,
        @NotBlank(message = "unit is required")
        @Pattern(regexp = "(?i)YEARS|MONTHS|WEEKS|DAYS|HOURS|MINUTES|SECONDS",
                 message = "unit must be one of YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES, SECONDS")
        String unit,
        String customPattern
) {

    @AssertTrue(message = "amount must not be zero")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isAmountNonZero() {
        return amount == null || amount != 0L;
    }
}
