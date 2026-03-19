from __future__ import annotations

import json
import logging
import socket
import threading
from collections.abc import Callable
from typing import Any

from .json_cleaner import loads_json_cleaned
from .logger import log_event
from .runtime import RuntimeConfig, cpu_throttle, ensure_localhost


JsonHandler = Callable[[dict[str, Any]], dict[str, Any]]


class ThreadedJsonServer:
    def __init__(
        self,
        runtime: RuntimeConfig,
        name: str,
        port: int,
        logger: logging.Logger,
        handler: JsonHandler,
    ) -> None:
        self.runtime = runtime
        self.name = name
        self.host = ensure_localhost(runtime.host)
        self.port = port
        self.logger = logger
        self.handler = handler
        self._server_socket: socket.socket | None = None
        self._accept_thread: threading.Thread | None = None
        self._running = threading.Event()

    def start(self) -> None:
        if self._running.is_set():
            return
        self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_socket.bind((self.host, self.port))
        self._server_socket.listen()
        self._server_socket.settimeout(1.0)
        self._running.set()
        self._accept_thread = threading.Thread(target=self._accept_loop, name=f"{self.name}-accept", daemon=True)
        self._accept_thread.start()
        log_event(self.logger, logging.INFO, "server started", service=self.name, port=self.port)

    def stop(self) -> None:
        self._running.clear()
        if self._server_socket is not None:
            self._server_socket.close()
            self._server_socket = None
        if self._accept_thread is not None:
            self._accept_thread.join(timeout=2.0)
            self._accept_thread = None

    def health(self) -> dict[str, Any]:
        return {"ok": self._running.is_set(), "host": self.host, "port": self.port, "service": self.name}

    def _accept_loop(self) -> None:
        while self._running.is_set():
            try:
                assert self._server_socket is not None
                client, _addr = self._server_socket.accept()
                client.settimeout(10.0)
                threading.Thread(target=self._handle_client, args=(client,), daemon=True).start()
            except socket.timeout:
                cpu_throttle(self.runtime)
            except OSError:
                break

    def _handle_client(self, client: socket.socket) -> None:
        with client:
            reader = client.makefile("r", encoding="utf-8", newline="\n")
            writer = client.makefile("w", encoding="utf-8", newline="\n")
            with reader, writer:
                while self._running.is_set():
                    line = reader.readline()
                    if not line:
                        break
                    payload = loads_json_cleaned(line.strip(), default={"command": "invalid", "raw": line})
                    if not isinstance(payload, dict):
                        payload = {"command": "invalid", "raw": line}
                    try:
                        response = self.handler(payload)
                    except Exception as error:  # noqa: BLE001
                        log_event(self.logger, logging.ERROR, "handler failed", service=self.name, error=str(error))
                        response = {"ok": False, "error": str(error)}
                    writer.write(json.dumps(response, ensure_ascii=True, separators=(",", ":")) + "\n")
                    writer.flush()
