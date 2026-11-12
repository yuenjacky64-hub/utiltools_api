# Changelog

## [v0.119.0] - 2026-11-12

- Add `POST /api/v1/utils/crypto/url/parse` returning `scheme`, `userInfo`, `host`, `port`, `path`, raw `query`, `fragment` and an ordered, duplicate-preserving list of decoded query `parameters` (absent components are `null`, no query yields an empty list)
- Add `UrlParseRequest` (non-blank `url`, max 8192 characters), `UrlParseResponse` and `UrlQueryParameter` DTOs; every component except `parameters` is returned still percent-encoded
- Parse via `java.net.URI`: opaque URIs, malformed syntax, illegal ports, malformed percent escapes, invalid UTF-8 and URLs with more than 128 query parameters return the unified `400 BAD_REQUEST` envelope (reject, never truncate)
- Journal successful parses as a deterministic `CRYPTO_URL_PARSE` `OperationType` routed through `AuditedOperations` and its `replayInvoke` switch, so replays settle `VERIFIED`
- Document the route, the raw-vs-decoded component rules, the 128-parameter cap and the verbatim-journalling security note in `README.md`

