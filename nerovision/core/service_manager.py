"""Socket server base class and service lifecycle management.

ThreadedSocketServer provides a JSON-line TCP server that spawns one
daemon thread per client.  HealthServer reports aggregate status on
port 9001.  ServiceManager orchestrates service threads and runs a
watchdog that auto-restarts crashed services.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import threading
import time
from typing import Any, Callable, Dict, List, Optional

from nerovision.core.runtime import get_stop_event

_log = logging.getLogger("nerovision.service_manager")

_MAX_CLIENTS = 32
_CLIENT_TIMEOUT_S = 30.0
_RECV_BUF = 65536


# ---------------------------------------------------------------------------
# ThreadedSocketServer
# ---------------------------------------------------------------------------

class ThreadedSocketServer:
    """JSON-line TCP server.  Override *handle_request* in subclasses.

    * Binds to 127.0.0.1 only.
    * One daemon thread per connected client.
    * Built-in ``ping`` → ``pong`` that bypasses *handle_request*.
    * *broadcast()* sends to every connected client.
    """

    def __init__(self, port: int, name: str, host: str = "127.0.0.1") -> None:
        self.host = host
        self.port = port
        self.name = name
        self._clients: List[socket.socket] = []
        self._clients_lock = threading.Lock()
        self._server_sock: Optional[socket.socket] = None

    # ---- public API -------------------------------------------------------

    def serve_forever(self, stop_event: Optional[threading.Event] = None) -> None:
        """Accept connections until *stop_event* (or the global one) is set."""
        stop = stop_event or get_stop_event()
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.settimeout(1.0)
        srv.bind((self.host, self.port))
        srv.listen(_MAX_CLIENTS)
        self._server_sock = srv
        _log.info("%s listening on %s:%d", self.name, self.host, self.port)

        try:
            while not stop.is_set():
                try:
                    client, addr = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break

                with self._clients_lock:
                    if len(self._clients) >= _MAX_CLIENTS:
                        _log.warning("%s: rejecting connection — %d clients", self.name, _MAX_CLIENTS)
                        client.close()
                        continue
                    self._clients.append(client)

                client.settimeout(_CLIENT_TIMEOUT_S)
                t = threading.Thread(
                    target=self._handle_client, args=(client,), daemon=True,
                )
                t.start()
        finally:
            srv.close()
            self._server_sock = None

    def broadcast(self, msg_dict: Dict[str, Any]) -> None:
        """Send *msg_dict* as a JSON line to every connected client."""
        line = json.dumps(msg_dict, default=str, ensure_ascii=False) + "\n"
        data = line.encode("utf-8")
        with self._clients_lock:
            dead: List[socket.socket] = []
            for c in self._clients:
                try:
                    c.sendall(data)
                except OSError:
                    dead.append(c)
            for c in dead:
                self._remove_client(c)

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        """Override in subclasses to handle a parsed request dict."""
        return {"error": "not_implemented"}

    def shutdown(self) -> None:
        """Close the listening socket so *serve_forever* returns."""
        if self._server_sock:
            try:
                self._server_sock.close()
            except OSError:
                pass

    # ---- internals --------------------------------------------------------

    def _remove_client(self, client: socket.socket) -> None:
        with self._clients_lock:
            try:
                self._clients.remove(client)
            except ValueError:
                pass
        try:
            client.close()
        except OSError:
            pass

    def _handle_client(self, client: socket.socket) -> None:
        buf = b""
        try:
            while not get_stop_event().is_set():
                try:
                    chunk = client.recv(_RECV_BUF)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    self._process_line(client, line)
        finally:
            self._remove_client(client)

    def _process_line(self, client: socket.socket, raw: bytes) -> None:
        try:
            req = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            resp = {"error": "invalid_json"}
            self._send(client, resp)
            return

        if isinstance(req, dict) and req.get("type") == "ping":
            resp = {"status": "ok", "service": self.name}
        else:
            try:
                resp = self.handle_request(req)
            except Exception:
                _log.exception("%s: unhandled error in handle_request", self.name)
                resp = {"error": "internal"}

        self._send(client, resp)

    @staticmethod
    def _send(client: socket.socket, msg: Dict[str, Any]) -> None:
        line = json.dumps(msg, default=str, ensure_ascii=False) + "\n"
        try:
            client.sendall(line.encode("utf-8"))
        except OSError:
            pass


# ---------------------------------------------------------------------------
# HealthServer — port 9001
# ---------------------------------------------------------------------------

class HealthServer(ThreadedSocketServer):
    """Reports aggregate service health on port 9001."""

    def __init__(self, service_manager: ServiceManager) -> None:
        super().__init__(port=9001, name="health_monitor")
        self._mgr = service_manager
        self._start_time = time.monotonic()

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        status_map: Dict[str, str] = {}
        for name in self._mgr.service_names():
            status_map[name] = self._mgr.get_status(name)

        return {
            "status": "ok",
            "uptime_s": round(time.monotonic() - self._start_time, 2),
            "services": status_map,
            "mem_mb": self._read_mem_mb(),
        }

    @staticmethod
    def _read_mem_mb() -> float:
        try:
            with open(f"/proc/{os.getpid()}/status") as f:
                for line in f:
                    if line.startswith("VmRSS:"):
                        return round(int(line.split()[1]) / 1024.0, 1)
        except (OSError, ValueError, IndexError):
            pass
        return -1.0


# ---------------------------------------------------------------------------
# ServiceManager
# ---------------------------------------------------------------------------

class ServiceManager:
    """Orchestrates service threads with a health watchdog.

    *register(name, fn)* — *fn* is a zero-arg callable that blocks.
    *start_all()*        — launches each *fn* in a daemon thread.
    *stop_all()*         — sets the global stop event and joins with 5 s timeout.
    """

    _WATCHDOG_INTERVAL_S = 8.0
    _MAX_RESTART_ATTEMPTS = 5

    def __init__(self) -> None:
        self._services: Dict[str, _ServiceEntry] = {}
        self._lock = threading.Lock()
        self._watchdog_thread: Optional[threading.Thread] = None

    # ---- registration / query ---------------------------------------------

    def register(self, name: str, fn: Callable[[], None]) -> None:
        with self._lock:
            self._services[name] = _ServiceEntry(name=name, fn=fn)
        _log.info("Registered service: %s", name)

    def service_names(self) -> List[str]:
        with self._lock:
            return list(self._services.keys())

    def get_status(self, name: str) -> str:
        """Return ``'running'``, ``'stopped'``, or ``'crashed'``."""
        with self._lock:
            entry = self._services.get(name)
        if entry is None:
            return "stopped"
        if entry.thread is None or not entry.thread.is_alive():
            return "crashed" if entry.started else "stopped"
        return "running"

    # ---- lifecycle ---------------------------------------------------------

    def start_all(self) -> None:
        with self._lock:
            entries = list(self._services.values())
        for entry in entries:
            self._start_entry(entry)
        self._start_watchdog()

    def stop_all(self) -> None:
        stop = get_stop_event()
        stop.set()
        with self._lock:
            entries = list(self._services.values())
        for entry in entries:
            if entry.thread and entry.thread.is_alive():
                entry.thread.join(timeout=5.0)
        if self._watchdog_thread and self._watchdog_thread.is_alive():
            self._watchdog_thread.join(timeout=5.0)

    # ---- internals ---------------------------------------------------------

    def _start_entry(self, entry: _ServiceEntry) -> None:
        t = threading.Thread(target=entry.fn, name=entry.name, daemon=True)
        entry.thread = t
        entry.started = True
        entry.restart_count = 0
        t.start()
        _log.info("Started service thread: %s", entry.name)

    def _start_watchdog(self) -> None:
        if self._watchdog_thread and self._watchdog_thread.is_alive():
            return
        self._watchdog_thread = threading.Thread(
            target=self._watchdog_loop, name="watchdog", daemon=True,
        )
        self._watchdog_thread.start()

    def _watchdog_loop(self) -> None:
        stop = get_stop_event()
        while not stop.is_set():
            stop.wait(self._WATCHDOG_INTERVAL_S)
            if stop.is_set():
                break
            with self._lock:
                entries = list(self._services.values())
            for entry in entries:
                self._check_entry(entry)

    def _check_entry(self, entry: _ServiceEntry) -> None:
        if entry.thread is None or entry.thread.is_alive():
            return

        if entry.restart_count >= self._MAX_RESTART_ATTEMPTS:
            _log.error(
                "Watchdog: %s exhausted %d restart attempts — giving up",
                entry.name, self._MAX_RESTART_ATTEMPTS,
            )
            return

        entry.restart_count += 1
        delay = min(2 ** entry.restart_count, 60)
        _log.warning(
            "Watchdog: %s not running — restart %d/%d in %ds",
            entry.name, entry.restart_count, self._MAX_RESTART_ATTEMPTS, delay,
        )
        get_stop_event().wait(delay)
        if get_stop_event().is_set():
            return

        t = threading.Thread(target=entry.fn, name=entry.name, daemon=True)
        entry.thread = t
        t.start()
        _log.info("Watchdog: restarted %s", entry.name)

    def _ping_port(self, port: int) -> bool:
        """Send a ping to *port* and return True if we get a response."""
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=2.0) as s:
                s.sendall(json.dumps({"type": "ping"}).encode("utf-8") + b"\n")
                s.settimeout(2.0)
                data = s.recv(4096)
                if data:
                    return True
        except OSError:
            pass
        return False


# ---------------------------------------------------------------------------
# Internal data class
# ---------------------------------------------------------------------------

class _ServiceEntry:
    __slots__ = ("name", "fn", "thread", "started", "restart_count")

    def __init__(self, name: str, fn: Callable[[], None]) -> None:
        self.name = name
        self.fn = fn
        self.thread: Optional[threading.Thread] = None
        self.started = False
        self.restart_count = 0
