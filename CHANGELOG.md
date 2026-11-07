# Changelog

## [v0.37.0] - 2026-11-07

- POST /api/v1/utils/json/flatten and /api/v1/utils/json/unflatten (deterministic dot-path flatten/unflatten; arrays and empty containers preserved as leaves)
- JSON_FLATTEN / JSON_UNFLATTEN audit operations with journal replay support
- Bounded inputs (depth<=64, <=10,000 keys), path-conflict and ambiguous-separator detection returning 400 BAD_REQUEST
- JsonServiceTest / JsonControllerTest coverage including flatten->unflatten round trip

