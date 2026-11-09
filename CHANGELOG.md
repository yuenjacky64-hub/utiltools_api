# Changelog

## [v0.71.0] - 2026-11-09

- Add `RouteCostResolver`: method+URI to positive integer cost, default 1, configurable per route via `utiltools.ratelimit.costs.{default,audit-verify,job-submit,json-query}` (5/3/2 out of the box); non-positive values fail startup.
- `SlidingWindowRateLimiter` now tracks a weighted in-window sum with per-admission costs (`tryAcquire(clientKey, cost)`); one-argument overloads charge 1.
- `RateLimitFilter` charges each route once, emits `X-RateLimit-Cost`, keeps `X-RateLimit-Limit`/`X-RateLimit-Remaining` weighted, and returns a cost-aware 429 with `Retry-After` and `details.{cost,limit,windowMs}`.
- Cleanup and cap eviction only remove buckets with zero live weighted charge, decided and removed under the bucket lock; `/rate-limit/status` stays weighted and side-effect free.

