from __future__ import annotations

import json
import logging
import socket
import threading
import time
from typing import Any

from .json_cleaner import loads_json_cleaned
from .logger import log_event
from .runtime import RuntimeConfig, ensure_localhost, exponential_backoff


class LocalJsonSocketClient:
    def __init__(
        self,
        runtime: RuntimeConfig,
        host: str,
        port: int,
        logger: logging.Logger,
        timeout_s: float = 4.0,
        persistent: bool = False,
    ) -> None:
        self.runtime = runtime
        self.host = ensure_localhost(host)
        self.port = port
        self.logger = logger
        self.timeout_s = timeout_s
        self.persistent = persistent
        self._socket: socket.socket | None = None
        self._reader = None
        self._writer = None
        self._lock = threading.Lock()

    def request(self, payload: dict[str, Any]) -> dict[str, Any]:
        last_error: Exception | None = None
        for attempt in range(self.runtime.max_retries):
            try:
                with self._lock:
                    self._ensure_connection()
                    assert self._writer is not None
                    assert self._reader is not None
                    wire = json.dumps(payload, ensure_ascii=True, separators=(",", ":"))
                    self._writer.write(wire + "\n")
                    self._writer.flush()
                    line = self._reader.readline()
                    if not line:
                        raise ConnectionError("Empty response from peer")
                    decoded = loads_json_cleaned(line.strip(), default={"ok": False, "error": "invalid_json"})
                    if isinstance(decoded, dict):
                        return decoded
                    return {"ok": True, "data": decoded}
            except Exception as error:  # noqa: BLE001
                last_error = error
                self.close()
                log_event(
                    self.logger,
                    logging.WARNING,
                    "socket request failed",
                    port=self.port,
                    attempt=attempt + 1,
                    error=str(error),
                )
                time.sleep(exponential_backoff(attempt))
            finally:
                if not self.persistent:
                    self.close()
        return {"ok": False, "error": str(last_error) if last_error else "unknown_client_error"}

    def close(self) -> None:
        if self._writer is not None:
            try:
                self._writer.close()
            except OSError:
                pass
        if self._reader is not None:
            try:
                self._reader.close()
            except OSError:
                pass
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass
        self._socket = None
        self._reader = None
        self._writer = None

    def _ensure_connection(self) -> None:
        if self.persistent and self._socket is not None and self._writer is not None and self._reader is not None:
            return
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout_s)
        sock.settimeout(self.timeout_s)
        self._socket = sock
        self._reader = sock.makefile("r", encoding="utf-8", newline="\n")
        self._writer = sock.makefile("w", encoding="utf-8", newline="\n")
        if not self.persistent:
            return
