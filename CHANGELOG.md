# Changelog

## [v0.79.0] - 2026-11-09

- Add `Lease` dataclass and ACTIVE→EXPIRED→RECLAIMED lease state machine to `MaintainerEngine` (REQ-01).
- Add blocking `acquire_lease()` / `release_lease()` guarded by `threading.Lock` + condition variable (REQ-02).
- Add `_reap_stale_leases()` to reclaim expired cycles, wired between cycles in `run_forever()` (REQ-03, REQ-04).
- Record `LEASE_ACQUIRED`/`LEASE_RELEASED`/`LEASE_EXPIRED`/`LEASE_RECLAIMED`/`LEASE_REFUSED` to `data/maintainer_leases.log` via `self.reporter` (REQ-05).
- Expose lease state through `snapshot()["leases"]`; add 15-test lease suite.

