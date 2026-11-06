# Changelog

## [v0.30.0] - 2026-11-06

- Fails open: `utiltools.ratelimit.enabled=false` reports the full budget with a live `resetAt`, and a limiter exception is answered with the full budget instead of a 5xx.

