"""Pytest bootstrap: put ``<repo>/src`` on ``sys.path``.

The repo has no ``pyproject.toml``/``setup.py``, so ``import ai_maintainer``
only resolves once the source root is importable. No fixtures live here.
"""

from __future__ import annotations

import sys
from pathlib import Path

SRC_ROOT = Path(__file__).resolve().parents[1] / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))
