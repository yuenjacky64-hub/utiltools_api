"""Configuration knobs for the maintainer task lifecycle.

All tuning values live here so the curriculum and the engine never hard-code a
threshold. Units follow the brief verbatim: the aging interval is expressed in
milliseconds (``prioritization_aging_interval_ms``) while the injected clock
reports seconds, so the single conversion site is
:attr:`MaintainerConfig.aging_threshold_seconds`.
"""

from __future__ import annotations

from dataclasses import dataclass

DEFAULT_AGING_INTERVAL_MS = 300_000
DEFAULT_RECENCY_BOOST_CYCLES = 3
DEFAULT_MAX_STEPS_PER_CYCLE = 8


@dataclass(frozen=True)
class MaintainerConfig:
    """Immutable configuration for priority, preemption and aging."""

    prioritization_aging_interval_ms: int = DEFAULT_AGING_INTERVAL_MS
    recency_boost_cycles: int = DEFAULT_RECENCY_BOOST_CYCLES
    priority_preemption_enabled: bool = True
    max_steps_per_cycle: int = DEFAULT_MAX_STEPS_PER_CYCLE

    def __post_init__(self) -> None:
        interval = self.prioritization_aging_interval_ms
        if not isinstance(interval, int) or isinstance(interval, bool) or interval < 0:
            raise ValueError("prioritization_aging_interval_ms must be >= 0")
        recency = self.recency_boost_cycles
        if not isinstance(recency, int) or isinstance(recency, bool) or recency < 1:
            raise ValueError("recency_boost_cycles must be >= 1")
        steps = self.max_steps_per_cycle
        if not isinstance(steps, int) or isinstance(steps, bool) or steps < 1:
            raise ValueError("max_steps_per_cycle must be >= 1")

    @property
    def aging_threshold_seconds(self) -> float:
        """Aging threshold in the unit the clock reports (seconds)."""

        return self.prioritization_aging_interval_ms / 1000.0
