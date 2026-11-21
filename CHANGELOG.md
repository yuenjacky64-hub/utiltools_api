# Changelog

## [v0.129.1] - 2026-11-21

- Contact reaper now interrupts the reaped generation's dispatch thread exactly once inside the registry lock, releasing the bounded delivery lane.
- Worker finally clears the dispatch-thread handle and the interrupt status before returning to the pool; late completions stay fenced (exactly-once journal and pendingAttempts).
- Document best-effort reclamation contract in ContactDispatcher Javadoc and README contact section.

