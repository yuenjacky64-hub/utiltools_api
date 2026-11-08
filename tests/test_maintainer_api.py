"""Integration tests for the maintainer fingerprint-state endpoint.

Covers brief REQ-04 (DOD-09, DOD-10, DOD-15 in the plan). Each test boots the
real ``ThreadingHTTPServer`` from ``ai_maintainer.api`` on port 0 and fetches
the path with ``urllib.request`` — the contract is served, not simulated.
"""

from __future__ import annotations

import contextlib
import json
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from http.server import ThreadingHTTPServer

from ai_maintainer.api import (
    FINGERPRINT_STATE_PATH,
    build_handler,
    fingerprint_state_payload,
)
from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import FingerprintCircuitState, MaintainerEngine
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum, TaskSpec

VALUE_ERROR = "builtins.ValueError"
TIMEOUT_ERROR = "builtins.TimeoutError"


class FrozenClock:
    """Clock that only moves when the test moves it."""

    def __init__(self, now: float = 0.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        self.now += seconds
        return self.now


class ScriptedEngine(MaintainerEngine):
    """Engine whose monitor step raises the scripted error of a task, if any."""

    def __init__(self, *args, errors=None, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.errors: dict[str, BaseException] = dict(errors or {})

    def _monitor_step(self, task: TaskSpec) -> None:
        error = self.errors.get(task.name)
        if error is not None:
            raise error
        return None


def _spec(name: str) -> TaskSpec:
    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=4,
        last_proposed_cycle=100,
    )


def _quarantined_engine() -> tuple[MaintainerEngine, FrozenClock]:
    """Engine with one OPEN fingerprint and one CLOSED fingerprint."""

    clock = FrozenClock(125.0)
    config = MaintainerConfig(
        max_steps_per_cycle=8,
        fingerprint_quarantine_threshold=3,
        fingerprint_quarantine_sec=60.0,
    )
    specs = [_spec("a"), _spec("b"), _spec("c"), _spec("d")]
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = ScriptedEngine(
        curriculum,
        config=config,
        clock=clock,
        errors={
            "a": TimeoutError("slow"),
            "b": TimeoutError("slow"),
            "c": TimeoutError("slow"),
            "d": ValueError("boom"),
        },
    )
    engine.run_cycle()
    assert engine._buckets[TIMEOUT_ERROR].circuit_state is FingerprintCircuitState.OPEN
    assert engine._buckets[VALUE_ERROR].circuit_state is FingerprintCircuitState.CLOSED
    return engine, clock


@contextlib.contextmanager
def _serve(engine: MaintainerEngine) -> Iterator[str]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(engine))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = server.server_address[:2]
        yield f"http://{host}:{port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _get(url: str) -> tuple[int, dict, str]:
    with urllib.request.urlopen(url, timeout=10) as response:
        body = response.read().decode("utf-8")
        return response.status, json.loads(body), response.headers["Content-Type"]


def _request(url: str, method: str) -> tuple[int, dict, str]:
    request = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body), response.headers.get("Allow", "")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, json.loads(body), error.headers.get("Allow", "")


def test_fingerprint_state_endpoint_returns_per_fingerprint_state() -> None:
    """DOD-09 / DOD-15 / REQ-04: 200 plus the live per-fingerprint state."""

    engine, clock = _quarantined_engine()
    with _serve(engine) as base:
        status, body, content_type = _get(base + FINGERPRINT_STATE_PATH)

    assert status == 200
    assert content_type == "application/json; charset=utf-8"
    assert body == fingerprint_state_payload(engine)
    assert body["path"] == FINGERPRINT_STATE_PATH
    assert body["cycle"] == 1
    assert body["now"] == clock.now
    assert body["quarantined_count"] == 1

    buckets = {item["fingerprint"]: item for item in body["fingerprints"]}
    assert list(buckets) == [TIMEOUT_ERROR, VALUE_ERROR]  # quarantined first
    assert buckets[TIMEOUT_ERROR] == {
        "fingerprint": TIMEOUT_ERROR,
        "circuit_state": "OPEN",
        "consecutive_blocked": 3,
        "quarantine_active": True,
        "quarantine_until": 185.0,
        "remaining_sec": 60.0,
        "last_cycle": 1,
    }
    assert buckets[VALUE_ERROR] == {
        "fingerprint": VALUE_ERROR,
        "circuit_state": "CLOSED",
        "consecutive_blocked": 1,
        "quarantine_active": False,
        "quarantine_until": None,
        "remaining_sec": 0.0,
        "last_cycle": 1,
    }

    # The window is derived from the engine clock, not cached by the handler.
    clock.now = 185.0
    with _serve(engine) as base:
        _, after, _ = _get(base + FINGERPRINT_STATE_PATH)
    assert after["fingerprints"][0]["quarantine_active"] is False
    assert after["fingerprints"][0]["circuit_state"] == "OPEN"
    assert after["quarantined_count"] == 0

    with _serve(engine) as base:
        _, idle, _ = _get(base + FINGERPRINT_STATE_PATH)
    assert idle["cycle"] == 1 and len(idle["fingerprints"]) == 2


def test_fingerprint_state_endpoint_rejects_unknown_path_and_method() -> None:
    """DOD-10 / REQ-04: 404 on unknown path, 405 + Allow on non-GET, no echo."""

    engine, _ = _quarantined_engine()
    with _serve(engine) as base:
        status, body, _ = _request(base + "/api/v1/maintainer/engine/other", "GET")
        assert status == 404
        assert body == {
            "error": "not_found",
            "path": "/api/v1/maintainer/engine/other",
        }

        for method in ("POST", "DELETE", "PUT", "PATCH"):
            status, body, allow = _request(base + FINGERPRINT_STATE_PATH, method)
            assert status == 405
            assert allow == "GET"
            assert body == {"error": "method_not_allowed"}

    assert engine._buckets[TIMEOUT_ERROR].circuit_state is FingerprintCircuitState.OPEN


def test_idle_engine_payload_is_empty() -> None:
    """REQ-04 smoke: an idle engine serves an empty, well-formed list."""

    clock = FrozenClock(0.0)
    config = MaintainerConfig()
    engine = MaintainerEngine(
        TaskCurriculum(specs=[], config=config, clock=clock),
        config=config,
        clock=clock,
    )
    with _serve(engine) as base:
        status, body, _ = _get(base + FINGERPRINT_STATE_PATH)

    assert status == 200
    assert body["fingerprints"] == []
    assert body["quarantined_count"] == 0
    assert body["cycle"] == 0
