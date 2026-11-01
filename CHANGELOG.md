# Changelog

## [v0.3.0] - 2026-11-01

- Add `utiltools.ratelimit` configuration (enabled, requests-per-window, window-ms, cleanup-interval-ms, max-tracked-clients, trust-forwarded-header) with safe defaults documented in `application.properties` and README.
- Add `SlidingWindowRateLimiter`: Clock-driven, thread-safe per-client ring buffer; admission atomic, `remaining` never negative, eviction atomic with admission.
- Add `RateLimitFilter` (`OncePerRequestFilter` via `FilterRegistrationBean`, order `HIGHEST_PRECEDENCE+10`) for `/api/v1/utils/*`; rejects excess traffic with 429 `RATE_LIMITED` ApiResponse + `Retry-After`, `X-RateLimit-Limit/Remaining/Reset`; excluded actuator/docs/swagger.
- Add `@Scheduled` idle eviction and hard `max-tracked-clients` cap so spoofed client identities cannot grow limiter memory.
- Add 25 tests: concurrency at exact cap, deterministic rollover via mutable Clock, denial-consumes-nothing, per-client isolation, idle eviction preserving active counters, cap enforcement under spoofed keys, MockMvc envelope/header/no-controller proof.

