from __future__ import annotations

import logging
import time
from typing import Any

from nerovision.core.logger import log_event
from nerovision.core.runtime import RuntimeConfig
from nerovision.core.safe_clients import LocalJsonSocketClient
from nerovision.core.snapshot import SnapshotStore
from nerovision.core.threaded_server import ThreadedJsonServer
from nerovision.llm.llama_interface import LlamaInterface


class VisionService:
    name = "vision_service"

    def __init__(self, runtime: RuntimeConfig, logger: logging.Logger, llm: LlamaInterface) -> None:
        self.runtime = runtime
        self.logger = logger
        self.llm = llm
        self.snapshots = SnapshotStore(runtime, "vision")
        self.android = LocalJsonSocketClient(
            runtime=runtime,
            host=runtime.host,
            port=runtime.android_bridge_port,
            logger=logger,
            persistent=False,
        )
        self.latest_context: dict[str, Any] = {}
        self.server = ThreadedJsonServer(runtime, self.name, runtime.vision_service_port, logger, self._handle)

    def start(self) -> None:
        self.server.start()

    def stop(self) -> None:
        self.android.close()
        self.server.stop()

    def health(self) -> dict[str, Any]:
        health = self.server.health()
        health["latestContextAt"] = self.latest_context.get("captured_at")
        return health

    def _handle(self, payload: dict[str, Any]) -> dict[str, Any]:
        command = payload.get("command", "health")
        if command == "health":
            return self.health()
        if command == "latest_context":
            return {"ok": True, "context": self.latest_context}
        if command == "capture_context":
            return self.capture_context(include_screen=bool(payload.get("include_screen", True)))
        return {"ok": False, "error": f"unknown_command:{command}"}

    def capture_context(self, include_screen: bool = True) -> dict[str, Any]:
        ui = self.android.request({"command": "dump_ui"})
        screen = self.android.request({"command": "screen_capture"}) if include_screen else {"ok": False, "skipped": True}
        context = {
            "ok": True,
            "captured_at": time.time(),
            "ui": ui,
            "screen": screen,
        }
        analysis = self._analyze_ui(ui)
        if analysis:
            context["analysis"] = analysis
        self.latest_context = context
        path = self.snapshots.write("context", context)
        log_event(self.logger, logging.INFO, "vision context captured", snapshot=str(path))
        return context

    def _analyze_ui(self, ui: dict[str, Any]) -> dict[str, Any] | None:
        if not ui.get("available", ui.get("ok", False)):
            return None
        prompt = (
            "Analyze this Android accessibility UI snapshot and respond with strict JSON.\n"
            "Return object keys: screen_type, primary_targets, risk_notes.\n"
            f"Snapshot: {ui}"
        )
        result = self.llm.generate_json(prompt, system_prompt="You are a deterministic mobile UI analyst.")
        return result
