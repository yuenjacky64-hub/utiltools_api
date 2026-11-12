# Changelog

## [v0.121.0] - 2026-11-12

- Add a durable commit ledger under `data/commit_ledger.json` so `max_commits_per_hour` survives daemon restarts; entries record ts/status/pid/task id/sha, pruned to the rolling 3600s window (REQ-01).
- Replace the non-atomic budget check with an atomic check-and-reserve section guarded by `ai_maintainer.memory.locking.file_lock`; a token is granted only when committed + live reservations stay below the cap (REQ-02).
- Consume reservations with the commit sha on success and release them on failure, abort, test-verification failure, commit-policy refusal and non-committing review; stale reservations expire after a bounded lease or a dead pid (REQ-03).
- Add the stable `Maintainer-Commit` trailer and reconcile the ledger against git history in the last hour so a commit that beats a crash still consumes a slot (REQ-04).
- Fail closed on corrupt ledgers: quarantine the file, rebuild the window from trailer-marked commits, never grant an unlimited budget; `max_commits_per_hour <= 0` still bypasses (REQ-05).

