from __future__ import annotations

from dataclasses import dataclass
from typing import Any


def snapshot_signature(snapshot: dict[str, Any] | None) -> int:
    if not snapshot:
        return 0
    if "signature" in snapshot:
        return int(snapshot.get("signature") or 0)
    root = snapshot.get("root") if isinstance(snapshot, dict) else None
    return hash(str(root))


@dataclass
class VerificationResult:
    ok: bool
    reason: str

    def as_dict(self) -> dict[str, Any]:
        return {"ok": self.ok, "reason": self.reason}


class StateVerifier:
    def verify_action(
        self,
        action: dict[str, Any],
        before: dict[str, Any] | None,
        after: dict[str, Any] | None,
        response: dict[str, Any] | None = None,
    ) -> VerificationResult:
        if not before or not after:
            return VerificationResult(False, "missing_before_after_state")
        if response and response.get("verified") is True:
            return VerificationResult(True, "android_verified")
        if snapshot_signature(before) != snapshot_signature(after):
            return VerificationResult(True, "ui_signature_changed")
        if action.get("type") == "set_text" and action.get("text") and action["text"] in str(after):
            return VerificationResult(True, "target_text_present")
        return VerificationResult(False, "no_observable_state_change")
