# Changelog

## [v0.88.0] - 2026-11-10

- Add POST /api/v1/utils/crypto/jwt/decode: strict, decode-only compact JWT inspection returning algorithm, typ, header, claims, signature presence and byte length with verified=false.
- Journal every JWT decode as replayable CRYPTO_JWT_DECODE (eighteen journalled operations); raw token and signature never echoed.
- Document the new route, curl example and decode-only limitation in README.md; no new dependency.

