from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any

from .runtime import RuntimeConfig


class SnapshotStore:
    def __init__(self, runtime: RuntimeConfig, namespace: str) -> None:
        self.runtime = runtime
        self.namespace = namespace
        self.directory = runtime.snapshot_dir / namespace
        self.directory.mkdir(parents=True, exist_ok=True)
        self._counter = 0
        self._lock = threading.Lock()

    def write(self, name: str, payload: Any) -> Path:
        with self._lock:
            self._counter += 1
            stamp = time.strftime("%Y%m%d_%H%M%S", time.gmtime())
            path = self.directory / f"{name}_{stamp}_{self._counter}.json"
            path.write_text(json.dumps(payload, ensure_ascii=True, indent=2, sort_keys=True))
            self._trim()
            return path

    def latest(self, prefix: str | None = None) -> Path | None:
        candidates = sorted(self.directory.glob("*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
        if prefix is None:
            return candidates[0] if candidates else None
        for candidate in candidates:
            if candidate.name.startswith(prefix):
                return candidate
        return None

    def _trim(self, keep: int = 100) -> None:
        files = sorted(self.directory.glob("*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
        for stale in files[keep:]:
            stale.unlink(missing_ok=True)
