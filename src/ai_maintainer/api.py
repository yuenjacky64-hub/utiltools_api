"""Read-only HTTP view of the maintainer engine's fingerprint registry.

Stdlib only: the repo has no web framework, so the endpoint is mounted on a
``http.server`` handler. The handler never mutates engine state — it renders
:func:`fingerprint_state_payload`, which is a lock-protected copy taken by
:meth:`ai_maintainer.engine.MaintainerEngine.fingerprint_state`. Nothing but
counters and timestamps is exposed; the fingerprint is the exception *type*,
never its message.

Two routes are served: the fingerprint registry and
``GET /api/v1/utils/consistency-check`` (REQ-01), a read-only cross-store
report. :func:`serve` also runs the startup recovery job (REQ-02) once, before
the scheduler and the request loop start, so drift left behind by a crashed
role is repaired before anything can be adopted.
"""

from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlsplit

from ai_maintainer.consistency import (
    ConsistencyService,
    UnknownTaskError,
    run_startup_recovery,
)
from ai_maintainer.engine import MaintainerEngine

__all__ = [
    "CONSISTENCY_CHECK_PATH",
    "FINGERPRINT_STATE_PATH",
    "build_handler",
    "fingerprint_state_payload",
    "run_startup_recovery",
    "serve",
]

FINGERPRINT_STATE_PATH = "/api/v1/maintainer/engine/fingerprint-state"
CONSISTENCY_CHECK_PATH = "/api/v1/utils/consistency-check"

#: Task ids are directory names; the route refuses anything that could escape
#: the state root (``..``, ``/``, empty) with the same 404 as an unknown id.
TASK_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

CONTENT_TYPE = "application/json; charset=utf-8"


def fingerprint_state_payload(
    engine: MaintainerEngine, *, now: float | None = None
) -> dict[str, Any]:
    """JSON payload of the endpoint: ``path`` plus the live registry copy."""

    payload: dict[str, Any] = {"path": FINGERPRINT_STATE_PATH}
    payload.update(engine.fingerprint_state(now=now))
    return payload


def build_handler(
    engine: MaintainerEngine, *, consistency: ConsistencyService | None = None
) -> type[BaseHTTPRequestHandler]:
    """Build a :class:`BaseHTTPRequestHandler` subclass bound to ``engine``.

    ``consistency`` injects the service behind ``GET /api/v1/utils/consistency-check``
    (tests bind it to a temporary state root); when omitted the route resolves
    the stores from the ambient configuration, lazily, per request.
    """

    class FingerprintStateHandler(BaseHTTPRequestHandler):
        server_version = "MaintainerEngine/1.0"

        def do_GET(self) -> None:  # noqa: N802 - http.server naming
            self._handle()

        def do_POST(self) -> None:  # noqa: N802
            self._handle()

        def do_PUT(self) -> None:  # noqa: N802
            self._handle()

        def do_PATCH(self) -> None:  # noqa: N802
            self._handle()

        def do_DELETE(self) -> None:  # noqa: N802
            self._handle()

        def do_HEAD(self) -> None:  # noqa: N802
            self._handle()

        def do_OPTIONS(self) -> None:  # noqa: N802
            self._handle()

        def _handle(self) -> None:
            path = urlsplit(self.path).path
            if path == CONSISTENCY_CHECK_PATH:
                self._handle_consistency()
                return
            if path != FINGERPRINT_STATE_PATH:
                self._write_json(404, {"error": "not_found", "path": path})
                return
            if self.command != "GET":
                self._write_json(
                    405,
                    {"error": "method_not_allowed"},
                    extra_headers={"Allow": "GET"},
                )
                return
            try:
                payload = fingerprint_state_payload(engine)
            except Exception:  # pragma: no cover - defensive: never echo errors
                self._write_json(500, {"error": "internal_error"})
                return
            self._write_json(200, payload)

        def _handle_consistency(self) -> None:
            """Read-only cross-store report; never repairs (REQ-01)."""

            if self.command != "GET":
                self._write_json(
                    405,
                    {"error": "method_not_allowed"},
                    extra_headers={"Allow": "GET"},
                )
                return
            split = urlsplit(self.path)
            requested = parse_qs(split.query, keep_blank_values=True).get("task", [])
            if len(requested) > 1:
                self._write_json(404, {"error": "task_not_found"})
                return
            task = requested[0] if requested else None
            if task is not None and not TASK_ID_PATTERN.match(task):
                self._write_json(404, {"error": "task_not_found", "task": task})
                return
            try:
                service = consistency if consistency is not None else (
                    ConsistencyService.from_environment()
                )
                payload: dict[str, Any] = {"path": CONSISTENCY_CHECK_PATH}
                payload.update(service.report(task=task))
            except UnknownTaskError:
                self._write_json(404, {"error": "task_not_found", "task": task})
                return
            except Exception:  # pragma: no cover - defensive: never echo errors
                self._write_json(500, {"error": "internal_error"})
                return
            self._write_json(200, payload)

        def _write_json(
            self,
            status: int,
            payload: dict[str, Any],
            *,
            extra_headers: dict[str, str] | None = None,
        ) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", CONTENT_TYPE)
            self.send_header("Content-Length", str(len(body)))
            for name, value in (extra_headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            """Silence the default stderr access log."""

            return None

    return FingerprintStateHandler


def serve(
    engine: MaintainerEngine,
    host: str = "127.0.0.1",
    port: int = 8000,
    sweep_interval_sec: float | None = None,
    *,
    consistency: ConsistencyService | None = None,
) -> ThreadingHTTPServer:
    """Serve the endpoints (blocking) and run the quarantine sweep alongside them.

    The startup consistency job runs first (REQ-02): it scans every known task
    and repairs cross-store drift under the recovery journal. It never raises —
    a failure is counted in the summary — so a broken store can never prevent
    the server from starting.
    """

    try:
        if consistency is not None:
            consistency.recover_all()
        else:
            run_startup_recovery()
    except Exception:  # pragma: no cover - defensive: startup must not abort
        pass
    server = ThreadingHTTPServer((host, port), build_handler(engine, consistency=consistency))
    engine.start_scheduler(sweep_interval_sec)
    try:
        server.serve_forever()
    finally:
        engine.stop_scheduler()
        server.server_close()
    return server
