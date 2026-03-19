"""Thread-safe task queue and state machine.

Tasks are priority-ordered (lower number = higher priority) and consumed
by the ProductionOperator.  A background timeout enforcer ensures no
task runs indefinitely.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from nerovision.core.runtime import get_stop_event

_log = logging.getLogger("nerovision.task_manager")


# ---------------------------------------------------------------------------
# TaskStatus
# ---------------------------------------------------------------------------

class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"


TERMINAL_STATES = frozenset({
    TaskStatus.SUCCESS,
    TaskStatus.FAILED,
    TaskStatus.TIMEOUT,
    TaskStatus.CANCELLED,
})


# ---------------------------------------------------------------------------
# TaskRecord
# ---------------------------------------------------------------------------

@dataclass
class TaskRecord:
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    goal: str = ""
    priority: int = 5
    max_steps: int = 20
    timeout_s: float = 120.0
    status: TaskStatus = TaskStatus.PENDING
    result: str = ""
    error: str = ""
    steps: List[Dict[str, Any]] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    started_at: float = 0.0
    finished_at: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def elapsed(self) -> float:
        if self.started_at == 0.0:
            return 0.0
        end = self.finished_at if self.finished_at > 0.0 else time.time()
        return end - self.started_at

    @property
    def is_terminal(self) -> bool:
        return self.status in TERMINAL_STATES

    def to_summary(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "goal": self.goal,
            "priority": self.priority,
            "status": self.status.value,
            "result": self.result,
            "error": self.error,
            "step_count": len(self.steps),
            "elapsed": round(self.elapsed, 2),
            "created_at": self.created_at,
        }


# ---------------------------------------------------------------------------
# TaskManager
# ---------------------------------------------------------------------------

class TaskManager:
    """Thread-safe priority task queue with timeout enforcement."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._queue: List[TaskRecord] = []
        self._active: Dict[str, TaskRecord] = {}
        self._history: List[TaskRecord] = []
        self._callbacks: List[Callable[[TaskRecord], None]] = []

    # ---- enqueue / consume -------------------------------------------------

    def enqueue(
        self,
        goal: str,
        priority: int = 5,
        max_steps: int = 20,
        timeout_s: float = 120.0,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Insert a task in priority order and notify consumers."""
        task = TaskRecord(
            goal=goal,
            priority=priority,
            max_steps=max_steps,
            timeout_s=timeout_s,
            metadata=metadata or {},
        )
        with self._cond:
            idx = 0
            for i, t in enumerate(self._queue):
                if t.priority > task.priority:
                    break
                idx = i + 1
            self._queue.insert(idx, task)
            self._cond.notify()
        _log.info("Enqueued task %s: %s (priority=%d)", task.id[:8], goal[:60], priority)
        return task.id

    def get_next_task(self, timeout: float = 1.0) -> Optional[TaskRecord]:
        """Block up to *timeout* seconds for the next task."""
        with self._cond:
            deadline = time.monotonic() + timeout
            while not self._queue:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self._cond.wait(timeout=remaining)
                if get_stop_event().is_set():
                    return None
            task = self._queue.pop(0)
            task.status = TaskStatus.RUNNING
            task.started_at = time.time()
            self._active[task.id] = task
        _log.info("Task %s started: %s", task.id[:8], task.goal[:60])
        return task

    # ---- terminal transitions ----------------------------------------------

    def complete(self, task: TaskRecord, result: str) -> None:
        self._finish(task, TaskStatus.SUCCESS, result=result)

    def fail(self, task: TaskRecord, error: str) -> None:
        self._finish(task, TaskStatus.FAILED, error=error)

    def timeout_task(self, task: TaskRecord) -> None:
        self._finish(task, TaskStatus.TIMEOUT, error="Task timed out")

    def cancel(self, task_id: str) -> bool:
        with self._lock:
            for i, t in enumerate(self._queue):
                if t.id == task_id:
                    t.status = TaskStatus.CANCELLED
                    t.finished_at = time.time()
                    self._queue.pop(i)
                    self._history.append(t)
                    self._fire_callbacks(t)
                    return True
            task = self._active.get(task_id)
            if task and not task.is_terminal:
                self._finish_locked(task, TaskStatus.CANCELLED, error="Cancelled")
                return True
        return False

    def _finish(
        self,
        task: TaskRecord,
        status: TaskStatus,
        result: str = "",
        error: str = "",
    ) -> None:
        with self._lock:
            self._finish_locked(task, status, result=result, error=error)

    def _finish_locked(
        self,
        task: TaskRecord,
        status: TaskStatus,
        result: str = "",
        error: str = "",
    ) -> None:
        if task.is_terminal:
            return
        task.status = status
        task.result = result or task.result
        task.error = error or task.error
        task.finished_at = time.time()
        self._active.pop(task.id, None)
        self._history.append(task)
        _log.info(
            "Task %s finished: %s (%.1fs)",
            task.id[:8], status.value, task.elapsed,
        )
        self._fire_callbacks_unlocked(task)

    # ---- queries -----------------------------------------------------------

    def get_status(self, task_id: str) -> Optional[TaskStatus]:
        with self._lock:
            t = self._find(task_id)
            return t.status if t else None

    def get_task(self, task_id: str) -> Optional[TaskRecord]:
        with self._lock:
            return self._find(task_id)

    def _find(self, task_id: str) -> Optional[TaskRecord]:
        for t in self._queue:
            if t.id == task_id:
                return t
        if task_id in self._active:
            return self._active[task_id]
        for t in reversed(self._history):
            if t.id == task_id:
                return t
        return None

    def get_history(self, n: int = 20) -> List[TaskRecord]:
        with self._lock:
            return list(self._history[-n:])

    def queue_depth(self) -> int:
        with self._lock:
            return len(self._queue)

    def active_count(self) -> int:
        with self._lock:
            return len(self._active)

    # ---- callbacks ---------------------------------------------------------

    def on_complete(self, fn: Callable[[TaskRecord], None]) -> None:
        with self._lock:
            self._callbacks.append(fn)

    def _fire_callbacks(self, task: TaskRecord) -> None:
        for fn in self._callbacks:
            try:
                fn(task)
            except Exception:
                _log.exception("Task callback error")

    def _fire_callbacks_unlocked(self, task: TaskRecord) -> None:
        cbs = list(self._callbacks)
        for fn in cbs:
            try:
                fn(task)
            except Exception:
                _log.exception("Task callback error")

    # ---- timeout enforcer --------------------------------------------------

    def start_timeout_enforcer(
        self, stop_event: Optional[threading.Event] = None,
    ) -> None:
        """Background thread that checks every 5 s for timed-out tasks."""
        stop = stop_event or get_stop_event()
        while not stop.is_set():
            stop.wait(5.0)
            if stop.is_set():
                break
            with self._lock:
                for task in list(self._active.values()):
                    if task.elapsed > task.timeout_s:
                        self._finish_locked(
                            task, TaskStatus.TIMEOUT, error="Task timed out",
                        )


# ---------------------------------------------------------------------------
# Global singleton
# ---------------------------------------------------------------------------

_manager: Optional[TaskManager] = None
_manager_lock = threading.Lock()


def get_task_manager() -> TaskManager:
    """Return the process-wide TaskManager singleton."""
    global _manager
    if _manager is None:
        with _manager_lock:
            if _manager is None:
                _manager = TaskManager()
    return _manager
