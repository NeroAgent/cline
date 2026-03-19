"""CPU / memory management for a resource-constrained Android device.

Provides CPU throttling, a token-bucket rate limiter, memory guards,
retry / crash-safe decorators, and a managed service-loop thread.
"""

from __future__ import annotations

import functools
import gc
import logging
import os
import random
import signal
import struct
import threading
import time
from enum import Enum
from typing import Any, Callable, Optional, TypeVar

_log = logging.getLogger("nerovision.runtime")

# ---------------------------------------------------------------------------
# Global shutdown event
# ---------------------------------------------------------------------------
_stop_event = threading.Event()


def get_stop_event() -> threading.Event:
    """Return the process-wide shutdown event."""
    return _stop_event


def _signal_handler(signum: int, _frame: Any) -> None:
    _log.info("Received signal %s — setting stop event", signal.Signals(signum).name)
    _stop_event.set()


for _sig in (signal.SIGTERM, signal.SIGINT):
    try:
        signal.signal(_sig, _signal_handler)
    except (OSError, ValueError):
        pass

# ---------------------------------------------------------------------------
# CPU / IO throttling
# ---------------------------------------------------------------------------

_IOPRIO_WHO_PROCESS = 1
_IOPRIO_CLASS_IDLE = 3
_IOPRIO_CLASS_SHIFT = 13


def setup_cpu_throttle() -> None:
    """Lower CPU and I/O priority. Never raises."""
    try:
        os.nice(10)
    except (OSError, PermissionError):
        pass

    try:
        ioprio_val = (_IOPRIO_CLASS_IDLE << _IOPRIO_CLASS_SHIFT) | 0
        path = f"/proc/{os.getpid()}/ioprio"
        if os.path.exists(path):
            with open(path, "wb") as f:
                f.write(struct.pack("I", ioprio_val))
    except (OSError, PermissionError, IOError):
        pass


# ---------------------------------------------------------------------------
# Token-bucket rate limiter
# ---------------------------------------------------------------------------

class ThrottlePreset(Enum):
    FAST = 20.0
    MEDIUM = 2.0
    SLOW = 0.2


class Throttle:
    """Token-bucket rate limiter.

    Presets: FAST = 20 tokens/s, MEDIUM = 2/s, SLOW = 0.2/s.
    """

    FAST = ThrottlePreset.FAST
    MEDIUM = ThrottlePreset.MEDIUM
    SLOW = ThrottlePreset.SLOW

    def __init__(self, rate: float | ThrottlePreset) -> None:
        if isinstance(rate, ThrottlePreset):
            rate = rate.value
        self._interval = 1.0 / rate
        self._lock = threading.Lock()
        self._tokens = 1.0
        self._max_tokens = 1.0
        self._last = time.monotonic()

    def acquire(self) -> None:
        """Block until a token is available."""
        while True:
            with self._lock:
                now = time.monotonic()
                elapsed = now - self._last
                self._tokens = min(self._max_tokens, self._tokens + elapsed / self._interval)
                self._last = now
                if self._tokens >= 1.0:
                    self._tokens -= 1.0
                    return
                wait = (1.0 - self._tokens) * self._interval
            _stop_event.wait(wait)
            if _stop_event.is_set():
                return


# ---------------------------------------------------------------------------
# Memory guard
# ---------------------------------------------------------------------------

class MemoryGuard:
    """Monitors VmRSS for the current process.

    *warn_mb*: log a warning when RSS exceeds this.
    *gc_mb*:   force ``gc.collect()`` when RSS exceeds this.
    """

    def __init__(self, warn_mb: int = 400, gc_mb: int = 600) -> None:
        self.warn_mb = warn_mb
        self.gc_mb = gc_mb
        self._status_path = f"/proc/{os.getpid()}/status"

    def _read_rss_kb(self) -> Optional[int]:
        try:
            with open(self._status_path) as f:
                for line in f:
                    if line.startswith("VmRSS:"):
                        return int(line.split()[1])
        except (OSError, ValueError, IndexError):
            pass
        return None

    def check(self) -> Optional[int]:
        """Check memory; returns current RSS in MB or None if unreadable."""
        rss_kb = self._read_rss_kb()
        if rss_kb is None:
            return None
        rss_mb = rss_kb // 1024
        if rss_mb >= self.gc_mb:
            _log.warning("RSS %d MB >= gc threshold %d MB — forcing gc.collect()", rss_mb, self.gc_mb)
            gc.collect()
        elif rss_mb >= self.warn_mb:
            _log.warning("RSS %d MB >= warn threshold %d MB", rss_mb, self.warn_mb)
        return rss_mb


# ---------------------------------------------------------------------------
# Decorators
# ---------------------------------------------------------------------------

F = TypeVar("F", bound=Callable[..., Any])


def retry(
    max_attempts: int = 3,
    base_delay: float = 1.0,
    max_delay: float = 30.0,
) -> Callable[[F], F]:
    """Exponential-backoff retry with jitter."""

    def decorator(fn: F) -> F:
        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            last_exc: BaseException | None = None
            for attempt in range(1, max_attempts + 1):
                try:
                    return fn(*args, **kwargs)
                except Exception as exc:
                    last_exc = exc
                    if attempt == max_attempts:
                        raise
                    delay = min(base_delay * (2 ** (attempt - 1)), max_delay)
                    jitter = random.uniform(0, delay * 0.5)
                    _log.warning(
                        "%s attempt %d/%d failed: %s — retrying in %.1fs",
                        fn.__qualname__,
                        attempt,
                        max_attempts,
                        exc,
                        delay + jitter,
                    )
                    time.sleep(delay + jitter)
            raise last_exc  # type: ignore[misc]

        return wrapper  # type: ignore[return-value]

    return decorator


def crash_safe(default: Any = None) -> Callable[[F], F]:
    """Catch all exceptions, log them, and return *default*."""

    def decorator(fn: F) -> F:
        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            try:
                return fn(*args, **kwargs)
            except Exception:
                _log.exception("crash_safe caught exception in %s", fn.__qualname__)
                return default

        return wrapper  # type: ignore[return-value]

    return decorator


# ---------------------------------------------------------------------------
# ServiceLoop
# ---------------------------------------------------------------------------

class ServiceLoop:
    """Managed background thread that auto-restarts on failure.

    Restarts up to *max_restarts* times with exponential backoff.
    """

    def __init__(self, max_restarts: int = 5) -> None:
        self._max_restarts = max_restarts
        self._thread: Optional[threading.Thread] = None
        self._running = threading.Event()

    def start(self, fn: Callable[[], None]) -> None:
        """Start *fn* in a daemon thread with auto-restart."""
        if self._thread and self._thread.is_alive():
            return
        self._running.set()
        self._thread = threading.Thread(
            target=self._run_loop, args=(fn,), daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        """Signal the loop to stop."""
        self._running.clear()

    def _run_loop(self, fn: Callable[[], None]) -> None:
        restarts = 0
        while self._running.is_set() and not _stop_event.is_set():
            try:
                fn()
                break
            except Exception:
                restarts += 1
                if restarts > self._max_restarts:
                    _log.error(
                        "ServiceLoop: %s exceeded %d restarts — giving up",
                        fn.__qualname__,
                        self._max_restarts,
                    )
                    break
                delay = min(2 ** restarts, 60)
                _log.warning(
                    "ServiceLoop: %s crashed (restart %d/%d) — retrying in %ds",
                    fn.__qualname__,
                    restarts,
                    self._max_restarts,
                    delay,
                )
                _stop_event.wait(delay)
