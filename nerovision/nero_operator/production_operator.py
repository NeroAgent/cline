"""Deterministic plan-act-verify execution loop.

The ProductionOperator consumes tasks from TaskManager, uses the LLM to
plan actions, executes them via the AccessibilityClient, and verifies
the result by comparing UI snapshots before and after.
"""

from __future__ import annotations

import logging
import time
import threading
from typing import Any, Dict, List, Optional, Tuple

from nerovision.core.json_cleaner import safe_parse_action, validate_action_schema
from nerovision.core.runtime import get_stop_event
from nerovision.core.safe_clients import (
    AccessibilityClient,
    LLMClient,
    MemoryClient,
    VisionClient,
)
from nerovision.core.verifier import VerificationStatus, verify_action
from nerovision.nero_operator.task_manager import (
    TaskManager,
    TaskRecord,
    TaskStatus,
    get_task_manager,
)

_log = logging.getLogger("nerovision.production_operator")

ACTION_SETTLE_MS = 600
MAX_CONSECUTIVE_NOOP = 4
PLAN_RETRY = 3
_HISTORY_WINDOW = 8

# ---------------------------------------------------------------------------
# Planner system prompt (embedded verbatim)
# ---------------------------------------------------------------------------

PLANNER_SYSTEM = """\
You are NeroVision's UI automation planner running on Android.

You receive:
  goal      – the user's high-level objective
  ui_state  – compact text summary of the current screen
  history   – list of recent actions and their outcomes
  step      – current step number

You must output ONLY a JSON object (no preamble, no markdown) with exactly:
{
  "reasoning": "<one sentence>",
  "action": "<action_name>",
  ... action-specific fields ...
}

Valid actions and their required fields:
  click_by_id   : id (string)
  click_by_text : text (string)
  click_coords  : x (int), y (int)
  type_text     : text (string)
  scroll        : direction ("up"|"down"|"left"|"right"), steps (int, default 3)
  back          : (no extra fields)
  home          : (no extra fields)
  wait          : seconds (float, max 5)
  done          : result (string — describe what was accomplished)
  fail          : reason (string — describe why you cannot proceed)

Rules:
- Prefer click_by_id when a resource-id is visible.
- Use click_by_text for buttons with visible labels.
- Only use click_coords as last resort.
- Never loop more than 3 times on the same screen without progress.
- If the goal is achieved, output done. If stuck or impossible, output fail.\
"""


# ---------------------------------------------------------------------------
# ActionExecutor
# ---------------------------------------------------------------------------

class ActionExecutor:
    """Maps action dicts to AccessibilityClient socket calls."""

    def __init__(self) -> None:
        self._accessibility = AccessibilityClient()

    def execute(self, action_dict: Dict[str, Any]) -> Tuple[bool, str]:
        """Execute *action_dict* and return ``(success, detail)``."""
        action = action_dict.get("action", "")

        if action == "done":
            return (True, "done")

        if action == "fail":
            return (True, "fail")

        if action == "wait":
            secs = min(float(action_dict.get("seconds", 1.0)), 5.0)
            stop = get_stop_event()
            stop.wait(secs)
            return (True, f"waited {secs:.1f}s")

        msg: Dict[str, Any] = {"type": "execute_action", "action": action_dict}
        resp = self._accessibility.request(msg)

        if resp is None:
            return (False, "accessibility service unreachable")

        ok = resp.get("status") == "ok" or resp.get("success", False)
        detail = resp.get("detail", resp.get("error", action))
        return (ok, str(detail))


# ---------------------------------------------------------------------------
# Planner
# ---------------------------------------------------------------------------

class Planner:
    """Calls the LLM to produce the next action given current UI state."""

    def __init__(self) -> None:
        self._llm = LLMClient()

    def plan(
        self,
        goal: str,
        ui_summary: str,
        history: List[Dict[str, Any]],
        step: int,
    ) -> Optional[Dict[str, Any]]:
        """Return an action dict, or ``None`` if the LLM produces garbage."""
        recent = history[-_HISTORY_WINDOW:]
        prompt = (
            f"goal: {goal}\n"
            f"step: {step}\n"
            f"ui_state:\n{ui_summary}\n"
            f"history: {recent}"
        )

        for attempt in range(1, PLAN_RETRY + 1):
            resp = self._llm.request({
                "type": "generate",
                "prompt": prompt,
                "system": PLANNER_SYSTEM,
                "max_tokens": 512,
                "temperature": 0.1,
            })

            if resp is None or resp.get("status") != "ok":
                _log.warning("Planner: LLM request failed (attempt %d/%d)", attempt, PLAN_RETRY)
                continue

            text = resp.get("text", "")
            action = safe_parse_action(text)
            if action is not None and validate_action_schema(action):
                return action

            _log.warning(
                "Planner: bad JSON from LLM (attempt %d/%d): %.100s",
                attempt, PLAN_RETRY, text,
            )

        return None


# ---------------------------------------------------------------------------
# ProductionOperator
# ---------------------------------------------------------------------------

class ProductionOperator:
    """Deterministic plan-act-verify execution loop."""

    def __init__(
        self,
        task_manager: Optional[TaskManager] = None,
    ) -> None:
        self._tm = task_manager or get_task_manager()
        self._planner = Planner()
        self._executor = ActionExecutor()
        self._vision = VisionClient()
        self._memory = MemoryClient()

    def run(self, stop_event: Optional[threading.Event] = None) -> None:
        """Blocking loop — consumes tasks until *stop_event* is set."""
        stop = stop_event or get_stop_event()
        _log.info("ProductionOperator started")
        while not stop.is_set():
            task = self._tm.get_next_task(timeout=1.0)
            if task is None:
                continue
            try:
                self._execute_task(task, stop)
            except Exception:
                _log.exception("Unhandled error executing task %s", task.id[:8])
                if not task.is_terminal:
                    self._tm.fail(task, "internal error")

    def _execute_task(self, task: TaskRecord, stop: threading.Event) -> None:
        noop_streak = 0

        for step in range(1, task.max_steps + 1):
            if stop.is_set():
                if not task.is_terminal:
                    self._tm.cancel(task.id)
                return

            if task.is_terminal:
                return

            ui_summary = self._get_ui_summary()
            action = self._planner.plan(
                goal=task.goal,
                ui_summary=ui_summary,
                history=task.steps,
                step=step,
            )

            if action is None:
                self._tm.fail(task, "Planner could not produce a valid action")
                return

            action_type = action.get("action", "")

            if action_type == "done":
                result = action.get("result", "Task completed")
                self._tm.complete(task, result)
                self._record_step(task, step, action, True, "done")
                self._record_memory(task, success=True)
                return

            if action_type == "fail":
                reason = action.get("reason", "Task failed")
                self._tm.fail(task, reason)
                self._record_step(task, step, action, False, "fail")
                self._record_memory(task, success=False)
                return

            ok, detail = self._executor.execute(action)
            self._record_step(task, step, action, ok, detail)

            if not ok:
                noop_streak += 1
            else:
                stop.wait(ACTION_SETTLE_MS / 1000.0)
                if stop.is_set():
                    return

                vstatus, _changed = verify_action(action)
                if vstatus in (
                    VerificationStatus.SUCCESS,
                    VerificationStatus.SUCCESS_ASSUMED,
                    VerificationStatus.SKIP,
                ):
                    noop_streak = 0
                else:
                    noop_streak += 1

            if noop_streak >= MAX_CONSECUTIVE_NOOP:
                self._tm.fail(
                    task,
                    f"UI unchanged after {MAX_CONSECUTIVE_NOOP} consecutive actions",
                )
                self._record_memory(task, success=False)
                return

        if not task.is_terminal:
            self._tm.fail(task, f"Exceeded max steps ({task.max_steps})")
            self._record_memory(task, success=False)

    # ---- helpers -----------------------------------------------------------

    def _get_ui_summary(self) -> str:
        resp = self._vision.request({"type": "summary"})
        if resp and resp.get("status") == "ok":
            return resp.get("summary", "")
        return "[UI unavailable]"

    @staticmethod
    def _record_step(
        task: TaskRecord,
        step: int,
        action: Dict[str, Any],
        ok: bool,
        detail: str,
    ) -> None:
        task.steps.append({
            "step": step,
            "action": action.get("action", ""),
            "params": {k: v for k, v in action.items() if k not in ("action", "reasoning")},
            "reasoning": action.get("reasoning", ""),
            "ok": ok,
            "detail": detail,
            "ts": time.time(),
        })

    def _record_memory(self, task: TaskRecord, success: bool) -> None:
        text = (
            f"Task: {task.goal}\n"
            f"Outcome: {'success' if success else 'failed'}\n"
            f"Result: {task.result or task.error}\n"
            f"Steps: {len(task.steps)}"
        )
        metadata = {
            "task_id": task.id,
            "success": success,
            "goal": task.goal,
            "step_count": len(task.steps),
        }
        try:
            self._memory.request({
                "type": "store",
                "text": text,
                "metadata": metadata,
            })
        except Exception:
            _log.debug("Failed to record task to memory", exc_info=True)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def start_operator(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking entry point — runs the ProductionOperator loop."""
    op = ProductionOperator()
    tm = get_task_manager()
    enforcer = threading.Thread(
        target=tm.start_timeout_enforcer,
        args=(stop_event or get_stop_event(),),
        name="timeout_enforcer",
        daemon=True,
    )
    enforcer.start()
    op.run(stop_event=stop_event)
