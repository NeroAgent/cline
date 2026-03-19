"""VisionService — UI tree snapshots and screenshot ingestion.

Runs on port 9004.  Background thread auto-refreshes the accessibility
tree from port 9002 every 500 ms, rate-limited by Throttle.MEDIUM.
Clients can query the current tree, search nodes, or get LLM-ready
summaries via JSON-line requests.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any, Dict, List, Optional

from nerovision.core.runtime import Throttle, get_stop_event
from nerovision.core.safe_clients import AccessibilityClient, PORT_VISION_SERVICE
from nerovision.core.service_manager import ThreadedSocketServer
from nerovision.core.snapshot import Snapshot, SnapshotManager, UINode, get_snapshot_manager

_log = logging.getLogger("nerovision.vision_service")

_REFRESH_INTERVAL_S = 0.5


# ---------------------------------------------------------------------------
# Screenshot metadata store
# ---------------------------------------------------------------------------

class _ScreenshotMeta:
    __slots__ = ("width", "height", "ts")

    def __init__(self) -> None:
        self.width: int = 0
        self.height: int = 0
        self.ts: float = 0.0


# ---------------------------------------------------------------------------
# VisionService
# ---------------------------------------------------------------------------

class VisionService(ThreadedSocketServer):
    """UI tree + screenshot service on port 9004."""

    def __init__(self) -> None:
        super().__init__(port=PORT_VISION_SERVICE, name="vision_service")
        self._snap_mgr: SnapshotManager = get_snapshot_manager()
        self._accessibility = AccessibilityClient()
        self._throttle = Throttle(Throttle.MEDIUM)
        self._screenshot = _ScreenshotMeta()
        self._screenshot_lock = threading.Lock()
        self._refresh_thread: Optional[threading.Thread] = None

    # ---- request dispatch --------------------------------------------------

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        rtype = req.get("type", "")
        handler = self._DISPATCH.get(rtype)
        if handler is None:
            return {"error": "unknown_type", "type": rtype}
        return handler(self, req)

    def _handle_analyze(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        snap = self._fetch_tree()
        if snap is None:
            return {"error": "no_tree"}
        return {"status": "ok", "node_count": len(snap._all_nodes()), "ts": snap.ts}

    def _handle_screenshot(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        with self._screenshot_lock:
            if self._screenshot.ts == 0.0:
                return {"error": "no_screenshot"}
            return {
                "status": "ok",
                "width": self._screenshot.width,
                "height": self._screenshot.height,
                "ts": self._screenshot.ts,
            }

    def _handle_find(self, req: Dict[str, Any]) -> Dict[str, Any]:
        query = req.get("query", "")
        if not query:
            return {"error": "missing_query"}
        snap = self._snap_mgr.latest()
        if snap is None:
            return {"error": "no_snapshot"}
        nodes = snap.find_by_text(query, partial=True)
        return {"status": "ok", "results": [self._node_summary(n) for n in nodes]}

    def _handle_find_clickable(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        snap = self._snap_mgr.latest()
        if snap is None:
            return {"error": "no_snapshot"}
        nodes = snap.find_clickable()
        return {"status": "ok", "results": [self._node_summary(n) for n in nodes]}

    def _handle_summary(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        snap = self._snap_mgr.latest()
        if snap is None:
            return {"error": "no_snapshot"}
        return {"status": "ok", "summary": snap.to_summary()}

    def _handle_compact(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        snap = self._snap_mgr.latest()
        if snap is None:
            return {"error": "no_snapshot"}
        return {"status": "ok", "data": snap.to_compact_json()}

    def _handle_refresh(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        snap = self._fetch_tree()
        if snap is None:
            return {"error": "refresh_failed"}
        return {"status": "ok", "node_count": len(snap._all_nodes()), "ts": snap.ts}

    _DISPATCH: Dict[str, Any] = {
        "analyze": _handle_analyze,
        "screenshot": _handle_screenshot,
        "find": _handle_find,
        "find_clickable": _handle_find_clickable,
        "summary": _handle_summary,
        "compact": _handle_compact,
        "refresh": _handle_refresh,
    }

    # ---- tree fetching -----------------------------------------------------

    def _fetch_tree(self) -> Optional[Snapshot]:
        """Request the current tree from AccessibilityService (:9002)."""
        resp = self._accessibility.request({"type": "get_tree"})
        if resp is None or "tree" not in resp:
            return None
        return self._snap_mgr.ingest(resp["tree"])

    # ---- screenshot ingestion ----------------------------------------------

    def ingest_screenshot(self, png_bytes: bytes, width: int, height: int) -> None:
        """Store screenshot metadata (called by ScreenCaptureService)."""
        with self._screenshot_lock:
            self._screenshot.width = width
            self._screenshot.height = height
            self._screenshot.ts = time.time()
        _log.debug("Screenshot ingested: %dx%d (%d bytes)", width, height, len(png_bytes))

    # ---- background auto-refresh -------------------------------------------

    def _start_refresh_loop(self, stop: threading.Event) -> None:
        while not stop.is_set():
            self._throttle.acquire()
            if stop.is_set():
                break
            try:
                self._fetch_tree()
            except Exception:
                _log.debug("Auto-refresh failed", exc_info=True)
            stop.wait(_REFRESH_INTERVAL_S)

    def serve_forever(self, stop_event: Optional[threading.Event] = None) -> None:
        stop = stop_event or get_stop_event()
        self._refresh_thread = threading.Thread(
            target=self._start_refresh_loop, args=(stop,),
            name="vision_refresh", daemon=True,
        )
        self._refresh_thread.start()
        super().serve_forever(stop_event=stop)

    # ---- helpers -----------------------------------------------------------

    @staticmethod
    def _node_summary(node: UINode) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "label": node.label(),
            "center": list(node.center()),
            "bounds": list(node.bounds),
        }
        if node.resource_id:
            d["id"] = node.resource_id
        if node.clickable:
            d["clickable"] = True
        if not node.enabled:
            d["disabled"] = True
        return d


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def start_vision_service(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking entry point — starts VisionService on port 9004."""
    svc = VisionService()
    svc.serve_forever(stop_event=stop_event)
