from __future__ import annotations

import logging
import threading
import time
from collections.abc import Iterable
from typing import Any, Protocol

from .logger import log_event
from .runtime import RuntimeConfig, exponential_backoff


class ManagedService(Protocol):
    name: str

    def start(self) -> None: ...

    def stop(self) -> None: ...

    def health(self) -> dict[str, Any]: ...


class ServiceManager:
    def __init__(self, runtime: RuntimeConfig, logger: logging.Logger) -> None:
        self.runtime = runtime
        self.logger = logger
        self._services: dict[str, ManagedService] = {}
        self._watchdog_thread: threading.Thread | None = None
        self._watchdog_running = threading.Event()

    def register(self, services: Iterable[ManagedService]) -> None:
        for service in services:
            self._services[service.name] = service

    def start_all(self) -> None:
        for service in self._services.values():
            service.start()
            log_event(self.logger, logging.INFO, "service started", service=service.name)

    def stop_all(self) -> None:
        self._watchdog_running.clear()
        if self._watchdog_thread is not None:
            self._watchdog_thread.join(timeout=2.0)
            self._watchdog_thread = None
        for service in self._services.values():
            service.stop()

    def health_snapshot(self) -> dict[str, Any]:
        return {name: service.health() for name, service in self._services.items()}

    def start_watchdog(self) -> None:
        if self._watchdog_running.is_set():
            return
        self._watchdog_running.set()
        self._watchdog_thread = threading.Thread(target=self._watchdog_loop, name="service-watchdog", daemon=True)
        self._watchdog_thread.start()

    def _watchdog_loop(self) -> None:
        while self._watchdog_running.is_set():
            for service in self._services.values():
                health = service.health()
                if health.get("ok"):
                    continue
                self._restart(service)
            time.sleep(self.runtime.service_health_interval_s)

    def _restart(self, service: ManagedService) -> None:
        for attempt in range(self.runtime.max_retries):
            try:
                service.stop()
                service.start()
                log_event(self.logger, logging.WARNING, "service restarted", service=service.name, attempt=attempt + 1)
                return
            except Exception as error:  # noqa: BLE001
                log_event(
                    self.logger,
                    logging.ERROR,
                    "service restart failed",
                    service=service.name,
                    attempt=attempt + 1,
                    error=str(error),
                )
                time.sleep(exponential_backoff(attempt))
