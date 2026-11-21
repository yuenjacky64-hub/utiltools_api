# Changelog

## [v0.137.1] - 2026-11-21

- Remove the bean-validation `@Pattern` from `TextSimilarityRequest.pattern`; a `pattern` sent with a non-`regex` tokenizer is now ignored instead of rejected with a 400. - Keep the 1..512-character bound and the compile check in `TextService.compileCustomPattern`, so `tokenizer=regex` still rejects blank, over-long or invalid patterns with `400 BAD_REQUEST`. - Document the regex-only, service-enforced bound in the README endpoint table.

