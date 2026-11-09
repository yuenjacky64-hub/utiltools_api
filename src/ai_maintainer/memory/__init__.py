"""Maintainer memory package (UTILTOOLS-72 REQ-02).

Re-exports the public surface of :mod:`ai_maintainer.memory.store` so callers
write ``from ai_maintainer.memory import MemoryStore`` and need not know the
file layout. The document itself is ``maintainer_memory.json`` under the
injected state root; the consistency validators it is written through stay in
:mod:`ai_maintainer.consistency`.
"""

from __future__ import annotations

from ai_maintainer.memory.store import (
    MEMORY_DOC_NAME,
    MEMORY_DOC_VERSION,
    MemoryStore,
    atomic_write_text,
)

__all__ = [
    "MEMORY_DOC_NAME",
    "MEMORY_DOC_VERSION",
    "MemoryStore",
    "atomic_write_text",
]
