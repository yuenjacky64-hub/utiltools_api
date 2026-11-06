# Changelog

## [v0.27.0] - 2026-11-06

- Add `POST /api/v1/utils/crypto/url` with `UrlRequest`/`UrlResponse`, `COMPONENT`/`PATH`/`FORM` modes and RFC 3986 unreserved passthrough
- Decode symmetrically: `+` -> space in `FORM` only, `%2B` -> `+` in every mode; malformed escapes and non-UTF-8 bytes return 400 `BAD_REQUEST`
- Journal `OperationType.CRYPTO_URL` through `AuditedOperations` with deterministic `VERIFIED` replay and no duplicate entry
- Document the route, mode semantics and replay behaviour in `README.md`

