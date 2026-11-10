# Changelog

## [v0.91.0] - 2026-11-10

- `GET /api/v1/utils/crypto/uuid` now accepts `version=V8`: a time-ordered RFC 9562 UUIDv8 whose 48-bit prefix is the unix-millisecond timestamp, version nibble `8`, and whose low 16 bits are the shared per-process monotonic counter.
- `CryptoService.generateUuidV8()` (batch, shared monotonic state) and `generateUuidV8(long counter)` (caller-supplied counter, pure layout) added; V4/V7 output unchanged.
- Version dispatch is now an explicit switch with a throwing `default` (`Invalid version: … Expected V4, V7 or V8.`), so an allow-listed version can never silently return a v4 id.
- V8 requests are journalled under the existing `CRYPTO_UUID` operation with `request.version="V8"`; replay behaviour and the determinism flag are unchanged.
- Tests: UUIDv8 fixed-clock layout, counter modulo 65536, intra-millisecond lexicographic sorting, 8x10 000 concurrent uniqueness, MockMvc list/compact/400/journal-replay; README documents `V4|V7|V8`.

