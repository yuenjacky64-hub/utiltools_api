# Changelog

## [v0.2.0] - 2026-10-31

- POST /api/v1/utils/json/format now honors optional indent (1-8, default 2 spaces) via Jackson DefaultPrettyPrinter/DefaultIndenter
- indent=0/9 rejected with HTTP 400 VALIDATION_ERROR (@Min(1)/@Max(8) on JsonFormatRequest)
- minify=true output unchanged; sizeBytes still computed from final formatted output
- README §3.A documents indent range, default, and 4-space sample
- 7 new tests (4 service, 3 controller); full suite 39/39 green

## [v0.1.0] - 2026-10-31

- JSON format endpoint now honors indent (1-8) via per-call DefaultPrettyPrinter/DefaultIndenter; null/omitted defaults to 2 spaces
- JsonFormatRequest.indent validated with @Min(1)/@Max(8); out-of-range -> 400 VALIDATION_ERROR
- minify=true behavior and sizeBytes semantics unchanged
- Added 4 service + 3 controller tests; README 3.A documents indent

