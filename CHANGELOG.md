# Changelog

## [v0.56.0] - 2026-11-08

- Add `POST /api/v1/utils/crypto/base32` with deterministic RFC 4648 (A-Z2-7) encode/decode over UTF-8 bytes; encode emits uppercase padded output, decode accepts padded/unpadded input case-insensitively and rejects invalid characters, impossible lengths, misplaced padding and non-zero trailing bits with HTTP 400.
- Register `OperationType.CRYPTO_BASE32` as deterministic and wire it through `AuditedOperations.base32`/`replayInvoke` so Base32 calls are journalled and replay to `VERIFIED`.
- Add `Base32Request`/`Base32Response` DTOs and document Base32 in the `CryptoController` OpenAPI tag.

