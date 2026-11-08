"""Signal middleware: refuse role-transition signals that would drift the stores.

``_signal.txt`` is the transition channel of the orchestrator: a role writes a
token and the retire/leaf path reads it. Accepting a token that contradicts the
other stores is exactly how the SIGTERM incident produced divergence, so this
module validates the transition against a :class:`~ai_maintainer.consistency.
TaskStoreSnapshot` *before* the write is accepted, and journals every refusal
with ``OperationType.SIGNAL_REJECTED`` (``INV-SIG-1``: no refusal is silent).

Checks run in this fixed order; the first failure is the rejection and
``detail`` is ``f"{code}: {task_id}"``:

====  ==========================================  =============================
order condition                                   code
====  ==========================================  =============================
1     empty / whitespace-only signal text         ``EMPTY_SIGNAL``
2     unknown task (no snapshot for the id)       ``UNKNOWN_TASK``
3     ``.manual_override`` in the task slot       ``MANUAL_OVERRIDE``
4     archived task offered a non-terminal token  ``ALREADY_ARCHIVED``
5     token outside the declared state table      ``UNSUPPORTED_SIGNAL``
6     terminal token contradicting the archive    ``TERMINAL_MISMATCH``
7     otherwise                                   ``ACCEPTED``
====  ==========================================  =============================

Two precedence decisions follow from the brief: an archived task refuses
*active* tokens before the terminal comparison (a task cannot be revived by a
mid-air ``Running 1``), and a terminal token is compared against the archive
*before* it is accepted (terminal evidence may be confirmed, never rewritten).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field, replace
from typing import Any

from ai_maintainer.audit import PHASE_REFUSED, AuditedOperations, OperationType
from ai_maintainer.consistency import (
    SIGNAL_FILE,
    TaskStoreSnapshot,
    group_of,
    state_for_token,
)

__all__ = [
    "CODE_ACCEPTED",
    "CODE_ALREADY_ARCHIVED",
    "CODE_EMPTY_SIGNAL",
    "CODE_MANUAL_OVERRIDE",
    "CODE_TERMINAL_MISMATCH",
    "CODE_UNKNOWN_TASK",
    "CODE_UNSUPPORTED_SIGNAL",
    "SignalDecision",
    "build_signal_gate",
    "finding_for",
    "validate_transition",
]

CODE_ACCEPTED = "ACCEPTED"
CODE_EMPTY_SIGNAL = "EMPTY_SIGNAL"
CODE_UNKNOWN_TASK = "UNKNOWN_TASK"
CODE_MANUAL_OVERRIDE = "MANUAL_OVERRIDE"
CODE_ALREADY_ARCHIVED = "ALREADY_ARCHIVED"
CODE_UNSUPPORTED_SIGNAL = "UNSUPPORTED_SIGNAL"
CODE_TERMINAL_MISMATCH = "TERMINAL_MISMATCH"

INCOMING_LIMIT = 200
GATE_ENGINE_ADMIT = "engine_admit"


@dataclass(frozen=True)
class SignalDecision:
    """Outcome of one transition validation."""

    accepted: bool
    code: str
    detail: str = ""
    task: str = ""
    canonical: str | None = None
    evidence: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "accepted": self.accepted,
            "code": self.code,
            "detail": self.detail,
            "task": self.task,
            "canonical": self.canonical,
        }


def _signal_head(text: str) -> str | None:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return None


def _head(text: str | None) -> str | None:
    """Cap one evidence string so the journal stays bounded (plan §5)."""

    if text is None or len(text) <= INCOMING_LIMIT:
        return text
    return text[:INCOMING_LIMIT]


def _evidence(
    snapshot: TaskStoreSnapshot | None, signal_text: str, gate: str | None
) -> dict[str, Any]:
    return {
        "signal_head": _head(_signal_head(signal_text)),
        "incoming": signal_text.strip()[:INCOMING_LIMIT],
        "canonical": None if snapshot is None else snapshot.canonical,
        "canonical_source": None if snapshot is None else snapshot.canonical_source,
        "archive": None if snapshot is None else snapshot.archive.name,
        "existing_signal_head": (
            None if snapshot is None else _head(snapshot.signal.evidence)
        ),
        "gate": gate,
    }


def validate_transition(
    task_id: str,
    signal_text: str,
    *,
    snapshot: TaskStoreSnapshot | None,
    audit: AuditedOperations | None = None,
    gate: str | None = None,
) -> SignalDecision:
    """Validate one incoming signal token; journal any refusal under ``audit``."""

    evidence = _evidence(snapshot, signal_text, gate)
    text = (signal_text or "").strip()

    def decide(code: str) -> SignalDecision:
        accepted = code == CODE_ACCEPTED
        return SignalDecision(
            accepted=accepted,
            code=code,
            detail="" if accepted else f"{code}: {task_id}",
            task=task_id,
            canonical=None if snapshot is None else snapshot.canonical,
            evidence=evidence,
        )

    if not text:
        decision = decide(CODE_EMPTY_SIGNAL)
    elif snapshot is None or snapshot.task != task_id:
        decision = decide(CODE_UNKNOWN_TASK)
    elif snapshot.manual_override:
        decision = decide(CODE_MANUAL_OVERRIDE)
    else:
        token_head = _signal_head(text) or ""
        state = state_for_token(token_head)
        if state is None:
            decision = decide(CODE_UNSUPPORTED_SIGNAL)
        elif snapshot.archive.present and group_of(state) == "active":
            decision = decide(CODE_ALREADY_ARCHIVED)
        elif (
            snapshot.archive.present
            and group_of(state) == "terminal"
            and snapshot.archive.state is not None
            and state.name != snapshot.archive.state
        ):
            decision = decide(CODE_TERMINAL_MISMATCH)
        else:
            decision = decide(CODE_ACCEPTED)

    if not decision.accepted and audit is not None:
        entry = audit.new_entry(
            OperationType.SIGNAL_REJECTED,
            task=task_id,
            store=SIGNAL_FILE,
            action=decision.code,
            before=dict(evidence),
        )
        audit.record(_refused(entry))
    return decision


def _refused(entry):
    """Single terminal ``REFUSED`` line: nothing was written, nothing to confirm."""

    return replace(entry, phase=PHASE_REFUSED)


def finding_for(decision: SignalDecision) -> dict[str, Any]:
    """Compliance-gate finding shape carried in the consistency report."""

    return {
        "code": decision.code,
        "task": decision.task,
        "detail": decision.detail,
        "evidence": dict(decision.evidence),
    }


def build_signal_gate(
    service: Any, *, gate: str = GATE_ENGINE_ADMIT
) -> Callable[[str], SignalDecision]:
    """Build the engine admit-hook (REQ-03 at runtime) over ``service``.

    The engine calls the hook once per candidate per cycle with the task id; it
    is the same validation the HTTP route and the startup job use, so the
    rejection path — ``SIGNAL_REJECTED`` journal entry, ``findings[]`` entry,
    engine ``SIGNAL_REJECTED`` event — is one implementation, not three.

    A task slot that declares no signal (``_signal.txt`` absent/empty) has no
    transition to validate and is admitted: the gate guards transitions, it does
    not introduce a new admission requirement.
    """

    def admit(task_id: str) -> SignalDecision:
        snapshot = service.snapshot(task_id)
        declared = snapshot.signal.evidence if snapshot.signal.present else None
        if declared is None:
            return SignalDecision(
                accepted=True,
                code=CODE_ACCEPTED,
                task=task_id,
                canonical=snapshot.canonical,
            )
        decision = validate_transition(
            task_id, declared, snapshot=snapshot, audit=service.audit, gate=gate
        )
        if not decision.accepted:
            service.record_finding(finding_for(decision))
        return decision

    return admit
