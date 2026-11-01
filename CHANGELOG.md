# Changelog

## [v0.15.0] - 2026-11-01

- Add POST /api/v1/utils/datetime/shift applying a signed amount+unit inside a target IANA zone (ZonedDateTime arithmetic, month-end clamping, DST-aware).
- Add DateTimeShiftRequest/DateTimeShiftResponse DTOs with @NotBlank input, @NotNull amount/unit, case-insensitive unit whitelist, and zero-amount rejection.
- Guard epoch-millis conversion so out-of-range shifts return 400 BAD_REQUEST instead of 500.
- Add service/controller tests for positive/negative shifts, month-end clamp, DST spring-forward/fall-back, zero amount, unknown zone, invalid pattern, malformed JSON; document the endpoint in README.

