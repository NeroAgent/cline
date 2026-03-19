"""Structured logger for the NeroVision system.

Provides JSONL file logging and coloured console output with rotation,
thread-safe contextual bindings, and a timing context manager.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from typing import Any, Dict, Generator, Optional

_DEFAULT_LOG_DIR = "/data/data/com.nerovision.assistant/files/logs/"
_LOG_DIR = os.environ.get("NERO_LOG_DIR", _DEFAULT_LOG_DIR)

_LEVEL_COLOURS = {
    "DEBUG": "\033[36m",     # cyan
    "INFO": "\033[32m",      # green
    "WARNING": "\033[33m",   # yellow
    "ERROR": "\033[31m",     # red
    "CRITICAL": "\033[35m",  # magenta
    "SUCCESS": "\033[92m",   # bright green
    "FAILURE": "\033[91m",   # bright red
}
_RESET = "\033[0m"

SUCCESS = 25
FAILURE = 45
logging.addLevelName(SUCCESS, "SUCCESS")
logging.addLevelName(FAILURE, "FAILURE")


class JSONFormatter(logging.Formatter):
    """Writes one JSON object per line (JSONL) for machine parsing."""

    def format(self, record: logging.LogRecord) -> str:
        entry: Dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "thread": record.threadName,
        }

        if record.exc_info and record.exc_info[0] is not None:
            entry["exception"] = self.formatException(record.exc_info)

        ctx: Dict[str, Any] = getattr(record, "_nero_ctx", {})
        if ctx:
            entry["context"] = ctx

        extra: Dict[str, Any] = getattr(record, "_nero_extra", {})
        if extra:
            entry.update(extra)

        return json.dumps(entry, default=str, ensure_ascii=False)


class ColorConsoleFormatter(logging.Formatter):
    """Human-readable coloured output for the Termux terminal."""

    _FMT = "{colour}{level:<8}{reset} {ts} [{name}] {message}"

    def format(self, record: logging.LogRecord) -> str:
        colour = _LEVEL_COLOURS.get(record.levelname, "")
        ts = datetime.fromtimestamp(record.created, tz=timezone.utc).strftime(
            "%H:%M:%S.%f"
        )[:-3]
        text = self._FMT.format(
            colour=colour,
            reset=_RESET,
            level=record.levelname,
            ts=ts,
            name=record.name,
            message=record.getMessage(),
        )
        if record.exc_info and record.exc_info[0] is not None:
            text += "\n" + self.formatException(record.exc_info)

        ctx: Dict[str, Any] = getattr(record, "_nero_ctx", {})
        if ctx:
            text += f"  {json.dumps(ctx, default=str)}"

        return text


class NeroLogger:
    """Thread-safe logger with contextual bindings and convenience helpers."""

    def __init__(self, name: str) -> None:
        self._logger = logging.getLogger(name)
        self._lock = threading.Lock()
        self._context: Dict[str, Any] = {}

    def bind(self, context: Dict[str, Any]) -> None:
        """Merge *context* into all subsequent log records from this logger."""
        with self._lock:
            self._context.update(context)

    def _log(
        self,
        level: int,
        msg: str,
        *args: Any,
        extra_fields: Optional[Dict[str, Any]] = None,
        exc_info: Any = None,
    ) -> None:
        with self._lock:
            ctx = dict(self._context)
        extra = {
            "_nero_ctx": ctx,
            "_nero_extra": extra_fields or {},
        }
        self._logger.log(level, msg, *args, exc_info=exc_info, extra=extra)

    # ---- standard levels ----
    def debug(self, msg: str, *args: Any, **kw: Any) -> None:
        self._log(logging.DEBUG, msg, *args, **kw)

    def info(self, msg: str, *args: Any, **kw: Any) -> None:
        self._log(logging.INFO, msg, *args, **kw)

    def warning(self, msg: str, *args: Any, **kw: Any) -> None:
        self._log(logging.WARNING, msg, *args, **kw)

    def error(self, msg: str, *args: Any, exc_info: Any = None, **kw: Any) -> None:
        self._log(logging.ERROR, msg, *args, exc_info=exc_info, **kw)

    def critical(self, msg: str, *args: Any, exc_info: Any = None, **kw: Any) -> None:
        self._log(logging.CRITICAL, msg, *args, exc_info=exc_info, **kw)

    # ---- custom levels ----
    def success(self, msg: str, *args: Any, **kw: Any) -> None:
        self._log(SUCCESS, msg, *args, **kw)

    def failure(self, msg: str, *args: Any, exc_info: Any = None, **kw: Any) -> None:
        self._log(FAILURE, msg, *args, exc_info=exc_info, **kw)

    def timing(self, label: str, elapsed_s: float) -> None:
        self._log(
            logging.INFO,
            "%s completed in %.3fs",
            label,
            elapsed_s,
            extra_fields={"timing": {"label": label, "elapsed_s": round(elapsed_s, 6)}},
        )

    @contextmanager
    def timed(self, label: str) -> Generator[None, None, None]:
        """Context manager that logs wall-clock duration on exit."""
        start = time.monotonic()
        try:
            yield
        finally:
            self.timing(label, time.monotonic() - start)


def _ensure_log_dir() -> str:
    os.makedirs(_LOG_DIR, exist_ok=True)
    return _LOG_DIR


def get_logger(name: str) -> NeroLogger:
    """Module-level factory — returns a fully configured NeroLogger."""
    underlying = logging.getLogger(name)

    if underlying.handlers:
        return NeroLogger(name)

    underlying.setLevel(logging.DEBUG)

    console = logging.StreamHandler()
    console.setLevel(logging.DEBUG)
    console.setFormatter(ColorConsoleFormatter())
    underlying.addHandler(console)

    try:
        log_dir = _ensure_log_dir()
        log_path = os.path.join(log_dir, f"{name}.jsonl")
        file_h = RotatingFileHandler(
            log_path,
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        file_h.setLevel(logging.DEBUG)
        file_h.setFormatter(JSONFormatter())
        underlying.addHandler(file_h)
    except OSError:
        pass

    underlying.propagate = False
    return NeroLogger(name)
