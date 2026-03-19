"""Client helpers for inter-service communication.

Every connection targets 127.0.0.1 and uses the NeroVision JSON-line
protocol (one JSON object per ``\\n``-terminated line).

* ``ServiceClient`` — persistent connection with auto-reconnect.
* ``request()``     — one-shot convenience function (never raises).
* ``get_client()``  — cached factory keyed by service name.
* Typed wrappers for each well-known service port.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
import time
from typing import Any, Dict, Optional

_log = logging.getLogger("nerovision.safe_clients")

_HOST = "127.0.0.1"
_RECV_BUF = 65536

# Canonical port registry
PORT_MAIN_BRIDGE = 9000
PORT_HEALTH_MONITOR = 9001
PORT_ACCESSIBILITY = 9002
PORT_AUDIO_RECEIVER = 9003
PORT_VISION_SERVICE = 9004
PORT_VOICE_SERVICE = 9005
PORT_LLM_INTERFACE = 9006
PORT_VECTOR_MEMORY = 9007
PORT_AVATAR_WS = 9090


# ---------------------------------------------------------------------------
# ServiceClient
# ---------------------------------------------------------------------------

class ServiceClient:
    """Persistent JSON-line TCP client with auto-reconnect.

    Parameters
    ----------
    host : str
        Always ``127.0.0.1`` for NeroVision services.
    port : int
        Target service port.
    timeout : float
        Socket timeout in seconds (connect + recv).
    """

    _RECONNECT_ATTEMPTS = 3
    _RECONNECT_DELAY_S = 1.0

    def __init__(self, host: str = _HOST, port: int = 0, timeout: float = 5.0) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock: Optional[socket.socket] = None
        self._lock = threading.Lock()
        self._buf = b""

    # ---- connection --------------------------------------------------------

    def connect(self) -> bool:
        """Establish a connection, retrying up to 3 times with 1 s delay."""
        with self._lock:
            if self._sock is not None:
                return True
            return self._connect_locked()

    def _connect_locked(self) -> bool:
        for attempt in range(1, self._RECONNECT_ATTEMPTS + 1):
            try:
                s = socket.create_connection(
                    (self.host, self.port), timeout=self.timeout,
                )
                s.settimeout(self.timeout)
                self._sock = s
                self._buf = b""
                return True
            except OSError as exc:
                _log.debug(
                    "ServiceClient(%s:%d) connect attempt %d/%d failed: %s",
                    self.host, self.port, attempt, self._RECONNECT_ATTEMPTS, exc,
                )
                if attempt < self._RECONNECT_ATTEMPTS:
                    time.sleep(self._RECONNECT_DELAY_S)
        return False

    def close(self) -> None:
        with self._lock:
            self._close_locked()

    def _close_locked(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
            self._buf = b""

    # ---- send / recv -------------------------------------------------------

    def send(self, msg_dict: Dict[str, Any]) -> bool:
        """Send *msg_dict* as a JSON line. Returns ``True`` on success."""
        line = json.dumps(msg_dict, default=str, ensure_ascii=False) + "\n"
        data = line.encode("utf-8")
        with self._lock:
            if self._sock is None and not self._connect_locked():
                return False
            try:
                self._sock.sendall(data)  # type: ignore[union-attr]
                return True
            except OSError:
                self._close_locked()
                if not self._connect_locked():
                    return False
                try:
                    self._sock.sendall(data)  # type: ignore[union-attr]
                    return True
                except OSError:
                    self._close_locked()
                    return False

    def recv(self) -> Optional[Dict[str, Any]]:
        """Read and parse one JSON line. Returns ``None`` on failure."""
        with self._lock:
            if self._sock is None:
                return None
            try:
                while b"\n" not in self._buf:
                    chunk = self._sock.recv(_RECV_BUF)
                    if not chunk:
                        self._close_locked()
                        return None
                    self._buf += chunk
                raw_line, self._buf = self._buf.split(b"\n", 1)
                return json.loads(raw_line)
            except (OSError, json.JSONDecodeError, UnicodeDecodeError):
                self._close_locked()
                return None

    def request(self, msg_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Send *msg_dict* and return the response dict (send + recv)."""
        if not self.send(msg_dict):
            return None
        return self.recv()


# ---------------------------------------------------------------------------
# One-shot convenience
# ---------------------------------------------------------------------------

def request(
    host: str,
    port: int,
    msg_dict: Dict[str, Any],
    timeout: float = 5.0,
) -> Optional[Dict[str, Any]]:
    """One-shot: connect → send → recv → close.  Never raises."""
    client = ServiceClient(host=host, port=port, timeout=timeout)
    try:
        if not client.connect():
            return None
        return client.request(msg_dict)
    except Exception:
        _log.debug("one-shot request to %s:%d failed", host, port, exc_info=True)
        return None
    finally:
        client.close()


# ---------------------------------------------------------------------------
# Cached factory
# ---------------------------------------------------------------------------

_client_cache: Dict[str, ServiceClient] = {}
_cache_lock = threading.Lock()

_NAME_TO_PORT: Dict[str, int] = {
    "main_bridge": PORT_MAIN_BRIDGE,
    "health_monitor": PORT_HEALTH_MONITOR,
    "accessibility": PORT_ACCESSIBILITY,
    "audio_receiver": PORT_AUDIO_RECEIVER,
    "vision_service": PORT_VISION_SERVICE,
    "voice_service": PORT_VOICE_SERVICE,
    "llm_interface": PORT_LLM_INTERFACE,
    "vector_memory": PORT_VECTOR_MEMORY,
    "avatar_ws": PORT_AVATAR_WS,
}


def get_client(name: str) -> ServiceClient:
    """Return a cached ``ServiceClient`` for the named service.

    Uses the canonical port registry.  One persistent client per name.
    """
    with _cache_lock:
        if name in _client_cache:
            return _client_cache[name]
        port = _NAME_TO_PORT.get(name)
        if port is None:
            raise ValueError(f"Unknown service name: {name!r}")
        client = ServiceClient(host=_HOST, port=port)
        _client_cache[name] = client
        return client


# ---------------------------------------------------------------------------
# Typed service clients
# ---------------------------------------------------------------------------

class _TypedClient:
    """Thin wrapper that fixes host/port and exposes a single *request*."""

    _PORT: int = 0

    def __init__(self, timeout: float = 5.0) -> None:
        self._client = ServiceClient(host=_HOST, port=self._PORT, timeout=timeout)

    def request(self, msg: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        return self._client.request(msg)

    def close(self) -> None:
        self._client.close()


class AccessibilityClient(_TypedClient):
    _PORT = PORT_ACCESSIBILITY


class LLMClient(_TypedClient):
    _PORT = PORT_LLM_INTERFACE


class VisionClient(_TypedClient):
    _PORT = PORT_VISION_SERVICE


class MemoryClient(_TypedClient):
    _PORT = PORT_VECTOR_MEMORY
