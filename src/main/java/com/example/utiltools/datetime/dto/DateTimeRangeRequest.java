package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Requests an ordered, bounded calendar date range from {@code startDate} towards {@code endDate}.
 *
 * <p>Only the request shape is validated here (required dates and their {@code yyyy-MM-dd} form), so a
 * malformed body surfaces as {@code VALIDATION_ERROR}. The semantic checks - {@code step} in 1..1000, the
 * date ordering, the unit name and the 1000-point bound - belong to the service and surface as
 * {@code BAD_REQUEST}.</p>
 *
 * @param startDate first date of the range, ISO-8601 local date ({@code yyyy-MM-dd})
 * @param endDate   last date of the range (inclusive unless {@code inclusive} is {@code false})
 * @param unit      {@code DAYS}/{@code WEEKS}/{@code MONTHS}/{@code YEARS}, case-insensitive; blank means
 *                  {@code DAYS}
 * @param step      positive stride between two dates, 1..1000; {@code null} means 1
 * @param inclusive whether a point equal to {@code endDate} is emitted; {@code null} means {@code true}
 */
public record DateTimeRangeRequest(
        @NotBlank(message = "startDate is required")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
        String startDate,
        @NotBlank(message = "endDate is required")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd")
        String endDate,
        String unit,
        Integer step,
        Boolean inclusive
) {}
