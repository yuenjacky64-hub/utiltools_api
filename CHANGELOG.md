# Changelog

## [v0.110.0] - 2026-11-12

- Add REVERSE text transformation operation to POST /api/v1/utils/text/transform (UTILTOOLS-113)
- Code-point reversal (reverseByCodePoints): Unicode/emoji-safe, no surrogate-pair splitting
- REVERSE accepted in ALLOWED_OPERATIONS and @Pattern validation; unknown operations still rejected (9-op contract)
- 78 tests green incl. surrogate-pair preservation; README op lists updated

