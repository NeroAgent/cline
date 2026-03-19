"""Before/after UI verification for executed actions.

Captures snapshots before and after an action, polls for UI changes,
and classifies the outcome as one of the ``VerificationStatus`` values.
"""

from __future__ import annotations

import hashlib
import logging
import time
from enum import Enum
from typing import List, Optional, Tuple

from nerovision.core.runtime import get_stop_event
from nerovision.core.snapshot import Snapshot, SnapshotManager, UINode, get_snapshot_manager

_log = logging.getLogger("nerovision.verifier")

SKIP_ACTIONS = frozenset({"wait", "home", "back"})


class VerificationStatus(Enum):
    """Outcome of a post-action UI verification."""

    SUCCESS = "success"
    SUCCESS_ASSUMED = "success_assumed"
    FAILURE = "failure"
    TIMEOUT = "timeout"
    ERROR = "error"
    SKIP = "skip"


# ---------------------------------------------------------------------------
# ActionVerifier
# ---------------------------------------------------------------------------

class ActionVerifier:
    """Compares before/after snapshots to decide whether an action took effect."""

    def __init__(self, snapshot_mgr: Optional[SnapshotManager] = None) -> None:
        self._mgr = snapshot_mgr or get_snapshot_manager()

    def verify(
        self,
        action_dict: dict,
        settle_ms: int = 500,
        max_wait_s: float = 3.0,
    ) -> Tuple[VerificationStatus, List[UINode]]:
        """Verify that *action_dict* produced a visible UI change.

        1. Grab the current (before) snapshot.
        2. Wait *settle_ms* for animations / transitions.
        3. Poll every 300 ms up to *max_wait_s* for a tree change.
        4. Return ``(status, changed_nodes)``.
        """
        action_type = action_dict.get("action", "")
        if action_type in SKIP_ACTIONS:
            return (VerificationStatus.SKIP, [])

        try:
            before = self._mgr.latest()
            if before is None:
                return (VerificationStatus.SUCCESS_ASSUMED, [])

            stop = get_stop_event()
            settle_s = settle_ms / 1000.0
            stop.wait(settle_s)
            if stop.is_set():
                return (VerificationStatus.ERROR, [])

            before_hash = self._hash_tree(before)
            deadline = time.monotonic() + max_wait_s
            poll_interval = 0.3

            while time.monotonic() < deadline:
                after = self._mgr.latest()
                if after is not None and after is not before:
                    after_hash = self._hash_tree(after)
                    if after_hash != before_hash:
                        changed = self._diff_trees(before, after)
                        return (VerificationStatus.SUCCESS, changed)

                if stop.is_set():
                    return (VerificationStatus.ERROR, [])

                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                stop.wait(min(poll_interval, remaining))

            after = self._mgr.latest()
            if after is not None and after is not before:
                if self._hash_tree(after) != before_hash:
                    changed = self._diff_trees(before, after)
                    return (VerificationStatus.SUCCESS, changed)

            return (VerificationStatus.TIMEOUT, [])

        except Exception:
            _log.exception("Verification failed for action %s", action_type)
            return (VerificationStatus.ERROR, [])

    # ---- internals ---------------------------------------------------------

    @staticmethod
    def _hash_tree(snapshot: Snapshot) -> str:
        """MD5 of sorted node signatures for fast whole-tree comparison."""
        sigs = sorted(n.signature() for n in snapshot._all_nodes())
        raw = "\n".join(sigs).encode("utf-8")
        return hashlib.md5(raw).hexdigest()

    @staticmethod
    def _diff_trees(before: Snapshot, after: Snapshot) -> List[UINode]:
        """Return nodes in *after* whose text, bounds, or state changed."""
        sigs_before = {n.signature() for n in before._all_nodes()}
        return [n for n in after._all_nodes() if n.signature() not in sigs_before]


# ---------------------------------------------------------------------------
# Module-level convenience
# ---------------------------------------------------------------------------

_default_verifier: Optional[ActionVerifier] = None
_verifier_lock = __import__("threading").Lock()


def verify_action(
    action_dict: dict,
) -> Tuple[VerificationStatus, List[UINode]]:
    """Verify *action_dict* using a lazily-created default ActionVerifier."""
    global _default_verifier
    if _default_verifier is None:
        with _verifier_lock:
            if _default_verifier is None:
                _default_verifier = ActionVerifier()
    return _default_verifier.verify(action_dict)
