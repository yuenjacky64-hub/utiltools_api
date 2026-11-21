# Changelog

## [v0.128.0] - 2026-11-21

- Add `POST /api/v1/utils/crypto/base58` with Bitcoin-alphabet ENCODE/DECODE, leading-zero preservation and strict invalid-alphabet 400s (REQ-01..REQ-04).
- Add `OperationType.CRYPTO_BASE58` + `AuditedOperations.base58` replay branch so Base58 calls are journalled and replay as VERIFIED (REQ-03).
- Document the new route, crypto examples and thirty-five-route inventory in README (REQ-05).

