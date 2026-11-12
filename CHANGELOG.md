# Changelog

## [v0.109.0] - 2026-11-12

- Add email field (default "") to MaintainerConfig dataclass (REQ-01/REQ-03)
- validate() reports exactly one 'email: invalid format: <value>' issue for non-empty malformed email; total over hostile non-str types (REQ-02)
- Regression-locked by tests/test_config.py + tests/test_config_validate.py (30/30 green)

