from __future__ import annotations

import logging
import time
from typing import Any

from nerovision.core.logger import log_event
from nerovision.core.runtime import RuntimeConfig, exponential_backoff
from nerovision.core.safe_clients import LocalJsonSocketClient
from nerovision.core.snapshot import SnapshotStore
from nerovision.core.threaded_server import ThreadedJsonServer
from nerovision.core.verifier import StateVerifier
from nerovision.llm.llama_interface import LlamaInterface
from nerovision.memory.vector_memory import VectorMemory
from nerovision.operator.task_manager import TaskManager


class ProductionOperator:
    name = "production_operator"

    def __init__(
        self,
        runtime: RuntimeConfig,
        logger: logging.Logger,
        llm: LlamaInterface,
        memory: VectorMemory,
    ) -> None:
        self.runtime = runtime
        self.logger = logger
        self.llm = llm
        self.memory = memory
        self.verifier = StateVerifier()
        self.snapshots = SnapshotStore(runtime, "operator")
        self.tasks = TaskManager(SnapshotStore(runtime, "tasks"))
        self.android = LocalJsonSocketClient(runtime, runtime.host, runtime.android_bridge_port, logger, persistent=False)
        self.vision = LocalJsonSocketClient(runtime, runtime.host, runtime.vision_service_port, logger, persistent=False)
        self.voice = LocalJsonSocketClient(runtime, runtime.host, runtime.voice_service_port, logger, persistent=False)
        self.server = ThreadedJsonServer(runtime, self.name, runtime.operator_service_port, logger, self._handle)

    def start(self) -> None:
        self.server.start()

    def stop(self) -> None:
        self.android.close()
        self.vision.close()
        self.voice.close()
        self.server.stop()

    def health(self) -> dict[str, Any]:
        return {
            **self.server.health(),
            "tasks": self.tasks.snapshot(),
        }

    def _handle(self, payload: dict[str, Any]) -> dict[str, Any]:
        command = payload.get("command", "health")
        if command == "health":
            return self.health()
        if command == "run_task":
            instruction = str(payload.get("instruction", "")).strip()
            metadata = payload.get("metadata", {})
            if not instruction:
                return {"ok": False, "error": "instruction_required"}
            task = self.tasks.enqueue(instruction, metadata if isinstance(metadata, dict) else {})
            result = self._execute_task(task.task_id)
            return {"ok": True, "taskId": task.task_id, "result": result}
        if command == "task_status":
            task = self.tasks.get(str(payload.get("taskId", "")))
            return {"ok": task is not None, "task": None if task is None else task.__dict__}
        if command == "memory_search":
            return {"ok": True, "matches": self.memory.search(str(payload.get("query", "")))}
        return {"ok": False, "error": f"unknown_command:{command}"}

    def _execute_task(self, task_id: str) -> dict[str, Any]:
        task = self.tasks.get(task_id)
        if task is None:
            return {"ok": False, "error": "task_not_found"}

        self._set_overlay("thinking", "Planning next action.")
        context = self.vision.request({"command": "capture_context", "include_screen": True})
        memories = self.memory.search(task.instruction)
        plan = self._plan_actions(task.instruction, context, memories)
        execution_log: list[dict[str, Any]] = []

        for step in plan.get("steps", []):
            execution = self._execute_step(step)
            execution_log.append(execution)
            if not execution.get("verified"):
                result = {"ok": False, "plan": plan, "executions": execution_log}
                self.tasks.fail(task_id, execution.get("error", "verification_failed"))
                self._set_overlay("alert", "Action verification failed.")
                self.snapshots.write("task_failed", result)
                return result

        result = {
            "ok": True,
            "summary": plan.get("summary", ""),
            "plan": plan,
            "executions": execution_log,
        }
        self.memory.add(task.instruction, {"result": result})
        self.tasks.complete(task_id, result)
        self._set_overlay("speaking", "Task complete.")
        self.snapshots.write("task_completed", result)
        return result

    def _plan_actions(
        self,
        instruction: str,
        context: dict[str, Any],
        memories: list[dict[str, Any]],
    ) -> dict[str, Any]:
        prompt = (
            "Return strict JSON with keys: summary, steps.\n"
            "steps must be an array of objects containing type and optional text, viewId, contentDescription, direction, globalAction.\n"
            "Allowed step types: click, set_text, scroll, global_action.\n"
            "Do not include markdown. If no safe action is available, return an empty steps array.\n"
            f"Instruction: {instruction}\n"
            f"Known memories: {memories}\n"
            f"Current context: {context}\n"
        )
        plan = self.llm.generate_json(prompt, system_prompt="You are a deterministic mobile task planner.")
        if not isinstance(plan, dict) or "steps" not in plan:
            plan = {"summary": "Fallback no-op plan", "steps": []}
        if not isinstance(plan.get("steps"), list):
            plan["steps"] = []
        if not plan["steps"]:
            plan = self._fallback_plan(instruction)
        return plan

    def _fallback_plan(self, instruction: str) -> dict[str, Any]:
        lowered = instruction.lower()
        if "back" in lowered:
            return {"summary": "Fallback back navigation", "steps": [{"type": "global_action", "globalAction": 1}]}
        return {"summary": "No safe deterministic action identified", "steps": []}

    def _execute_step(self, step: dict[str, Any]) -> dict[str, Any]:
        for attempt in range(self.runtime.max_retries):
            before = self.android.request({"command": "dump_ui"})
            response = self.android.request({"command": "perform_action", "action": step})
            after = response.get("after")
            if not isinstance(after, dict):
                after = self.android.request({"command": "dump_ui"})
            verification = self.verifier.verify_action(step, before, after, response)
            record = {
                "step": step,
                "attempt": attempt + 1,
                "response": response,
                "verification": verification.as_dict(),
                "verified": verification.ok,
            }
            self.snapshots.write("step_execution", record)
            if verification.ok:
                return record
            time.sleep(exponential_backoff(attempt))
        return {"step": step, "verified": False, "error": "max_retries_exceeded"}

    def _set_overlay(self, mood: str, message: str) -> None:
        self.android.request(
            {
                "command": "set_overlay_state",
                "state": {"visible": True, "mood": mood, "message": message, "amplitude": 0.25},
            }
        )
