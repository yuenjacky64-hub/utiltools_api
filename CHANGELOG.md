# Changelog

## [v0.46.0] - 2026-11-07

- Add `Priority` (HIGH/NORMAL/LOW) to `TaskSpec` with validation, JSON round-trip and difficulty-derived default (`src/ai_maintainer/tasks/curriculum.py`)
- Curriculum assigns HIGH for Complex, NORMAL for Hard, LOW for Medium/Easy, plus recency and aging boosts (`effective_priority`)
- Engine monitors in priority order and preempts a lower-priority task while preserving progress/state (`src/ai_maintainer/engine.py`)
- Aging boost after `prioritization_aging_interval_ms` (default 300000 ms); nothing starves — LOW reaches monitoring under continuous HIGH load

