from __future__ import annotations

import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Any

from nerovision.core.snapshot import SnapshotStore


@dataclass
class TaskRecord:
    instruction: str
    metadata: dict[str, Any] = field(default_factory=dict)
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    status: str = "queued"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    attempts: int = 0
    result: dict[str, Any] | None = None


class TaskManager:
    def __init__(self, snapshots: SnapshotStore) -> None:
        self.snapshots = snapshots
        self._tasks: dict[str, TaskRecord] = {}
        self._queue: list[str] = []
        self._lock = threading.Lock()

    def enqueue(self, instruction: str, metadata: dict[str, Any] | None = None) -> TaskRecord:
        with self._lock:
            task = TaskRecord(instruction=instruction, metadata=metadata or {})
            self._tasks[task.task_id] = task
            self._queue.append(task.task_id)
            self._persist("task_enqueued", task)
            return task

    def next_task(self) -> TaskRecord | None:
        with self._lock:
            if not self._queue:
                return None
            task_id = self._queue.pop(0)
            task = self._tasks[task_id]
            task.status = "running"
            task.updated_at = time.time()
            task.attempts += 1
            self._persist("task_started", task)
            return task

    def complete(self, task_id: str, result: dict[str, Any]) -> TaskRecord | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            task.status = "completed"
            task.result = result
            task.updated_at = time.time()
            self._persist("task_completed", task)
            return task

    def fail(self, task_id: str, error: str) -> TaskRecord | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            task.status = "failed"
            task.result = {"ok": False, "error": error}
            task.updated_at = time.time()
            self._persist("task_failed", task)
            return task

    def get(self, task_id: str) -> TaskRecord | None:
        return self._tasks.get(task_id)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {task_id: asdict(task) for task_id, task in self._tasks.items()}

    def _persist(self, event: str, task: TaskRecord) -> None:
        self.snapshots.write(event, asdict(task))
