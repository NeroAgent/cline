"""UI accessibility tree snapshot management.

Parses the JSON tree delivered by NeroAccessibilityService, provides
search helpers, LLM-readable summaries, and a rolling snapshot history
with optional disk persistence.
"""

from __future__ import annotations

import json
import logging
import os
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Deque, Dict, List, Optional, Tuple

_log = logging.getLogger("nerovision.snapshot")

_DEFAULT_SNAPSHOT_DIR = "/data/data/com.nerovision.assistant/files/snapshots/"
_SNAPSHOT_DIR = os.environ.get("NERO_SNAPSHOT_DIR", _DEFAULT_SNAPSHOT_DIR)

_MEMORY_HISTORY = 20
_DISK_MAX_FILES = 50
_SUMMARY_MAX_CHARS = 800


# ---------------------------------------------------------------------------
# UINode
# ---------------------------------------------------------------------------

@dataclass
class UINode:
    """Single node in the accessibility tree."""

    resource_id: str = ""
    class_name: str = ""
    text: str = ""
    content_desc: str = ""
    bounds: Tuple[int, int, int, int] = (0, 0, 0, 0)  # left, top, right, bottom
    clickable: bool = False
    focusable: bool = False
    scrollable: bool = False
    enabled: bool = True
    children: List[UINode] = field(default_factory=list)

    def center(self) -> Tuple[int, int]:
        """Midpoint of the bounding rectangle."""
        l, t, r, b = self.bounds
        return ((l + r) // 2, (t + b) // 2)

    def label(self) -> str:
        """First non-empty of text, content_desc, resource_id, class_name."""
        return self.text or self.content_desc or self.resource_id or self.class_name

    def signature(self) -> str:
        """Compact string for hashing / diff comparisons."""
        return f"{self.resource_id}|{self.class_name}|{self.text}|{self.content_desc}|{self.bounds}|{self.clickable}|{self.enabled}"


def _parse_node(raw: Dict[str, Any]) -> UINode:
    """Recursively parse a raw dict into a UINode tree."""
    bounds_raw = raw.get("bounds", (0, 0, 0, 0))
    if isinstance(bounds_raw, (list, tuple)) and len(bounds_raw) == 4:
        bounds = tuple(int(v) for v in bounds_raw)
    else:
        bounds = (0, 0, 0, 0)

    children = [_parse_node(c) for c in raw.get("children", [])]

    return UINode(
        resource_id=str(raw.get("resource_id", raw.get("resourceId", ""))),
        class_name=str(raw.get("class_name", raw.get("className", ""))),
        text=str(raw.get("text", "")),
        content_desc=str(raw.get("content_desc", raw.get("contentDesc", raw.get("contentDescription", "")))),
        bounds=bounds,  # type: ignore[arg-type]
        clickable=bool(raw.get("clickable", False)),
        focusable=bool(raw.get("focusable", False)),
        scrollable=bool(raw.get("scrollable", False)),
        enabled=bool(raw.get("enabled", True)),
        children=children,
    )


def _walk(node: UINode) -> List[UINode]:
    """Flatten the tree into a pre-order list."""
    result: List[UINode] = [node]
    for child in node.children:
        result.extend(_walk(child))
    return result


# ---------------------------------------------------------------------------
# Snapshot
# ---------------------------------------------------------------------------

class Snapshot:
    """Immutable view of the UI at a single point in time."""

    def __init__(self, root: UINode, ts: float | None = None) -> None:
        self.root = root
        self.ts = ts or time.time()

    @classmethod
    def from_tree(cls, raw_dict: Dict[str, Any]) -> Snapshot:
        """Parse the JSON tree delivered by AccessibilityService."""
        root = _parse_node(raw_dict)
        return cls(root=root)

    def _all_nodes(self) -> List[UINode]:
        return _walk(self.root)

    def find_by_text(self, text: str, partial: bool = True) -> List[UINode]:
        target = text.lower()
        results: List[UINode] = []
        for node in self._all_nodes():
            haystack = (node.text + " " + node.content_desc).lower()
            if partial:
                if target in haystack:
                    results.append(node)
            else:
                if node.text.lower() == target or node.content_desc.lower() == target:
                    results.append(node)
        return results

    def find_clickable(self) -> List[UINode]:
        return [n for n in self._all_nodes() if n.clickable]

    def find_editable(self) -> List[UINode]:
        return [
            n for n in self._all_nodes()
            if "edittext" in n.class_name.lower() or "edit" in n.class_name.lower()
        ]

    def find_by_class(self, class_name: str) -> List[UINode]:
        target = class_name.lower()
        return [n for n in self._all_nodes() if target in n.class_name.lower()]

    def to_summary(self) -> str:
        """LLM-readable text listing screen contents, max 800 chars."""
        lines: List[str] = []
        for node in self._all_nodes():
            lbl = node.label()
            if not lbl:
                continue
            parts: List[str] = [lbl]
            if node.clickable:
                parts.append("[clickable]")
            if node.scrollable:
                parts.append("[scroll]")
            if not node.enabled:
                parts.append("[disabled]")
            lines.append(" ".join(parts))

        text = "\n".join(lines)
        if len(text) > _SUMMARY_MAX_CHARS:
            text = text[: _SUMMARY_MAX_CHARS - 3] + "..."
        return text

    def to_compact_json(self) -> Dict[str, Any]:
        """Minimal JSON representation for storage."""
        return {
            "ts": self.ts,
            "tree": self._node_to_dict(self.root),
        }

    @staticmethod
    def _node_to_dict(node: UINode) -> Dict[str, Any]:
        d: Dict[str, Any] = {}
        if node.resource_id:
            d["id"] = node.resource_id
        if node.class_name:
            d["cls"] = node.class_name
        if node.text:
            d["txt"] = node.text
        if node.content_desc:
            d["desc"] = node.content_desc
        if node.bounds != (0, 0, 0, 0):
            d["b"] = list(node.bounds)
        flags = ""
        if node.clickable:
            flags += "c"
        if node.focusable:
            flags += "f"
        if node.scrollable:
            flags += "s"
        if not node.enabled:
            flags += "d"
        if flags:
            d["fl"] = flags
        if node.children:
            d["ch"] = [Snapshot._node_to_dict(c) for c in node.children]
        return d


# ---------------------------------------------------------------------------
# SnapshotManager
# ---------------------------------------------------------------------------

class SnapshotManager:
    """Rolling in-memory history with optional disk persistence."""

    def __init__(self) -> None:
        self._history: Deque[Snapshot] = deque(maxlen=_MEMORY_HISTORY)
        self._disk_dir: str = _SNAPSHOT_DIR

    def ingest(self, raw_tree_dict: Dict[str, Any]) -> Snapshot:
        """Parse a raw accessibility tree, store it, and return the Snapshot."""
        snap = Snapshot.from_tree(raw_tree_dict)
        self._history.append(snap)
        self._persist(snap)
        return snap

    def latest(self) -> Optional[Snapshot]:
        return self._history[-1] if self._history else None

    def history(self) -> List[Snapshot]:
        return list(self._history)

    # ---- diff helpers ------------------------------------------------------

    @staticmethod
    def diff_nodes(snap_a: Snapshot, snap_b: Snapshot) -> List[UINode]:
        """Return nodes in *snap_b* whose signature differs from *snap_a*."""
        sigs_a = {n.signature() for n in snap_a._all_nodes()}
        return [n for n in snap_b._all_nodes() if n.signature() not in sigs_a]

    @staticmethod
    def changed_since(snap_a: Snapshot, snap_b: Snapshot) -> bool:
        """Quick check: did anything change between two snapshots?"""
        nodes_a = snap_a._all_nodes()
        nodes_b = snap_b._all_nodes()
        if len(nodes_a) != len(nodes_b):
            return True
        return any(a.signature() != b.signature() for a, b in zip(nodes_a, nodes_b))

    # ---- disk persistence --------------------------------------------------

    def _persist(self, snap: Snapshot) -> None:
        try:
            os.makedirs(self._disk_dir, exist_ok=True)
            filename = f"snap_{int(snap.ts * 1000)}.json"
            path = os.path.join(self._disk_dir, filename)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(snap.to_compact_json(), f, ensure_ascii=False)
            self._prune_disk()
        except OSError:
            _log.debug("Failed to persist snapshot to disk", exc_info=True)

    def _prune_disk(self) -> None:
        try:
            files = sorted(
                (
                    os.path.join(self._disk_dir, f)
                    for f in os.listdir(self._disk_dir)
                    if f.startswith("snap_") and f.endswith(".json")
                ),
            )
            while len(files) > _DISK_MAX_FILES:
                os.remove(files.pop(0))
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Global singleton
# ---------------------------------------------------------------------------

_manager: Optional[SnapshotManager] = None
_manager_lock = __import__("threading").Lock()


def get_snapshot_manager() -> SnapshotManager:
    """Return the process-wide SnapshotManager singleton."""
    global _manager
    if _manager is None:
        with _manager_lock:
            if _manager is None:
                _manager = SnapshotManager()
    return _manager
