# Changelog

## [v0.99.0] - 2026-11-11

- `BatchHashJobResponse` gains an additive `autoFailovers` field; manual `POST /api/v1/utils/jobs/{jobId}/retry` neither increments nor resets the budget.

