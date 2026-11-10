# Changelog

## [v0.84.0] - 2026-11-10

- Add POST /api/v1/utils/text/html-entities with ENCODE (ampersand-first escaping of `&`, `<`, `>`, `"`, `'`) and DECODE (five named entities plus decimal `&#NN;` and hex `&#xHH;` numeric references; unknown or malformed references preserved verbatim).
- Journal and replay every call as `TEXT_HTML_ENTITIES`, bounded by the existing 1000000-code-point result limit (400 `BAD_REQUEST` when exceeded).
- Document the route in the README endpoint table and a detailed endpoint guide, including the 'DECODE is not a sanitiser' rendering-safety rule.

