"""Patch manager package: the durable autonomous-commit budget (UTILTOOLS-126).

``PatchManager`` is the entry point; :class:`ai_maintainer.patcher.patch_manager.CommitLedger`
is the on-disk window it serialises under ``file_lock``.
"""

from __future__ import annotations

from ai_maintainer.patcher.patch_manager import (
    CommitBudgetExhausted,
    CommitLedger,
    CommitLedgerLockUnavailable,
    CommitLedgerWriteError,
    CommitPolicyRefused,
    CommitVerificationFailed,
    MAINTAINER_COMMIT_TRAILER,
    PatchManager,
    commit_message_with_trailer,
    ledger_path,
    resolve_data_dir,
)

__all__ = [
    "CommitBudgetExhausted",
    "CommitLedger",
    "CommitLedgerLockUnavailable",
    "CommitLedgerWriteError",
    "CommitPolicyRefused",
    "CommitVerificationFailed",
    "MAINTAINER_COMMIT_TRAILER",
    "PatchManager",
    "commit_message_with_trailer",
    "ledger_path",
    "resolve_data_dir",
]
