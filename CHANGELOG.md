# Changelog

## [v0.1.0] - 2026-10-31

- JSON format endpoint now honors indent (1-8) via per-call DefaultPrettyPrinter/DefaultIndenter; null/omitted defaults to 2 spaces
- JsonFormatRequest.indent validated with @Min(1)/@Max(8); out-of-range -> 400 VALIDATION_ERROR
- minify=true behavior and sizeBytes semantics unchanged
- Added 4 service + 3 controller tests; README 3.A documents indent

