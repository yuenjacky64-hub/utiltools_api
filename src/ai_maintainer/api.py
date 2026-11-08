"""Read-only HTTP view of the maintainer engine's fingerprint registry.

Stdlib only: the repo has no web framework, so the endpoint is mounted on a
``http.server`` handler. The handler never mutates engine state — it renders
:func:`fingerprint_state_payload`, which is a lock-protected copy taken by
:meth:`ai_maintainer.engine.MaintainerEngine.fingerprint_state`. Nothing but
counters and timestamps is exposed; the fingerprint is the exception *type*,
never its message.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit

from ai_maintainer.engine import MaintainerEngine

__all__ = [
    "FINGERPRINT_STATE_PATH",
    "build_handler",
    "fingerprint_state_payload",
    "serve",
]

FINGERPRINT_STATE_PATH = "/api/v1/maintainer/engine/fingerprint-state"

CONTENT_TYPE = "application/json; charset=utf-8"


def fingerprint_state_payload(
    engine: MaintainerEngine, *, now: float | None = None
) -> dict[str, Any]:
    """JSON payload of the endpoint: ``path`` plus the live registry copy."""

    payload: dict[str, Any] = {"path": FINGERPRINT_STATE_PATH}
    payload.update(engine.fingerprint_state(now=now))
    return payload


def build_handler(engine: MaintainerEngine) -> type[BaseHTTPRequestHandler]:
    """Build a :class:`BaseHTTPRequestHandler` subclass bound to ``engine``."""

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
) -> ThreadingHTTPServer:
    """Serve the endpoint (blocking) and run the quarantine sweep alongside it."""

    server = ThreadingHTTPServer((host, port), build_handler(engine))
    engine.start_scheduler(sweep_interval_sec)
    try:
        server.serve_forever()
    finally:
        engine.stop_scheduler()
        server.server_close()
    return server
