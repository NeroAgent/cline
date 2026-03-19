#!/usr/bin/env python3
"""NeroVision Termux entry point.

Wires all services via ServiceManager and exposes the MainBridge on
port 9000 for Android ↔ Python IPC.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import sys
import threading
import time
from logging.handlers import RotatingFileHandler
from typing import Any, Dict, Optional, Set

# ---------------------------------------------------------------------------
# Logging — must be configured before any other nerovision imports
# ---------------------------------------------------------------------------

_DEFAULT_LOG_DIR = os.environ.get(
    "NERO_LOG_DIR", "/data/data/com.nerovision.assistant/files/logs/"
)


def _setup_logging(debug: bool = False) -> None:
    level = logging.DEBUG if debug else logging.INFO
    fmt = "%(asctime)s %(levelname)-8s [%(name)s] %(message)s"
    datefmt = "%H:%M:%S"

    root = logging.getLogger()
    root.setLevel(level)

    console = logging.StreamHandler(sys.stderr)
    console.setLevel(level)
    console.setFormatter(logging.Formatter(fmt, datefmt=datefmt))
    root.addHandler(console)

    try:
        os.makedirs(_DEFAULT_LOG_DIR, exist_ok=True)
        fh = RotatingFileHandler(
            os.path.join(_DEFAULT_LOG_DIR, "nerovision.log"),
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(fmt, datefmt=datefmt))
        root.addHandler(fh)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Parse CLI flags early so --debug takes effect before service imports
# ---------------------------------------------------------------------------

def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="NeroVision — on-device AI assistant")
    p.add_argument("--no-llm", action="store_true", help="Skip LLM service")
    p.add_argument("--no-memory", action="store_true", help="Skip vector memory")
    p.add_argument("--no-vision", action="store_true", help="Skip vision service")
    p.add_argument("--no-voice", action="store_true", help="Skip voice + audio receiver")
    p.add_argument("--no-avatar", action="store_true", help="Skip avatar WebSocket")
    p.add_argument("--debug", action="store_true", help="Set logging level to DEBUG")
    return p.parse_args()


# ---------------------------------------------------------------------------
# MainBridge — port 9000
# ---------------------------------------------------------------------------

_log = logging.getLogger("nerovision.main")


def _make_main_bridge():
    """Deferred import to avoid circular dependency at module level."""
    from nerovision.core.service_manager import ThreadedSocketServer
    from nerovision.core.safe_clients import PORT_MAIN_BRIDGE
    from nerovision.nero_operator.task_manager import get_task_manager

    class MainBridge(ThreadedSocketServer):
        """Android ↔ Python IPC on port 9000."""

        def __init__(self) -> None:
            super().__init__(port=PORT_MAIN_BRIDGE, name="main_bridge")
            self._tm = get_task_manager()

        def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
            rtype = req.get("type", "")
            handler = self._DISPATCH.get(rtype)
            if handler is None:
                return {"error": "unknown_type", "type": rtype}
            return handler(self, req)

        def _handle_enqueue(self, req: Dict[str, Any]) -> Dict[str, Any]:
            goal = req.get("goal", "")
            if not goal:
                return {"error": "missing_goal"}
            task_id = self._tm.enqueue(
                goal=goal,
                priority=int(req.get("priority", 5)),
                max_steps=int(req.get("max_steps", 20)),
                timeout_s=float(req.get("timeout_s", 120.0)),
                metadata=req.get("metadata", {}),
            )
            return {"ok": True, "task_id": task_id}

        def _handle_get_status(self, req: Dict[str, Any]) -> Dict[str, Any]:
            task_id = req.get("task_id", "")
            if not task_id:
                return {"error": "missing_task_id"}
            task = self._tm.get_task(task_id)
            if task is None:
                return {"error": "not_found", "task_id": task_id}
            return {"task_id": task_id, "status": task.status.value, "summary": task.to_summary()}

        def _handle_cancel(self, req: Dict[str, Any]) -> Dict[str, Any]:
            task_id = req.get("task_id", "")
            if not task_id:
                return {"error": "missing_task_id"}
            ok = self._tm.cancel(task_id)
            return {"ok": ok, "task_id": task_id}

        def _handle_history(self, req: Dict[str, Any]) -> Dict[str, Any]:
            n = int(req.get("n", 20))
            history = self._tm.get_history(n)
            return {"history": [t.to_summary() for t in history]}

        def _handle_queue_stats(self, _req: Dict[str, Any]) -> Dict[str, Any]:
            return {
                "queued": self._tm.queue_depth(),
                "active": self._tm.active_count(),
            }

        _DISPATCH: Dict[str, Any] = {
            "enqueue_task": _handle_enqueue,
            "get_status": _handle_get_status,
            "cancel_task": _handle_cancel,
            "get_history": _handle_history,
            "queue_stats": _handle_queue_stats,
        }

    return MainBridge


# ---------------------------------------------------------------------------
# AvatarWS — port 9090
# ---------------------------------------------------------------------------

def _start_avatar_ws(stop_event: Optional[threading.Event] = None) -> None:
    """asyncio + websockets rebroadcast server on ws://127.0.0.1:9090."""
    try:
        import websockets  # type: ignore[import-untyped]
        import websockets.server  # type: ignore[import-untyped]
    except ImportError:
        _log.warning("websockets not installed — avatar WS disabled")
        return

    from nerovision.core.runtime import get_stop_event

    stop = stop_event or get_stop_event()
    clients: Set[Any] = set()

    async def _handler(ws: Any) -> None:
        clients.add(ws)
        try:
            async for message in ws:
                targets = {c for c in clients if c is not ws}
                for t in targets:
                    try:
                        await t.send(message)
                    except Exception:
                        pass
        finally:
            clients.discard(ws)

    async def _serve() -> None:
        async with websockets.serve(_handler, "127.0.0.1", 9090):  # type: ignore[attr-defined]
            _log.info("AvatarWS listening on ws://127.0.0.1:9090")
            while not stop.is_set():
                await asyncio.sleep(0.5)

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(_serve())
    except Exception:
        _log.debug("AvatarWS stopped", exc_info=True)
    finally:
        loop.close()


# ---------------------------------------------------------------------------
# Wiring
# ---------------------------------------------------------------------------

def main() -> None:
    args = _parse_args()
    _setup_logging(debug=args.debug)

    _log.info("NeroVision starting (pid=%d)", os.getpid())

    from nerovision.core.runtime import get_stop_event, setup_cpu_throttle
    from nerovision.core.service_manager import HealthServer, ServiceManager

    setup_cpu_throttle()

    stop = get_stop_event()
    mgr = ServiceManager()

    # 1. HealthServer (always first)
    health = HealthServer(mgr)
    mgr.register("health_monitor", health.serve_forever)

    # 2. VisionService
    if not args.no_vision:
        from nerovision.services.vision_service import start_vision_service
        mgr.register("vision_service", start_vision_service)

    # 3–4. AudioReceiver + VoiceService
    if not args.no_voice:
        from nerovision.services.voice_service import (
            start_audio_receiver,
            start_voice_service,
        )
        mgr.register("audio_receiver", start_audio_receiver)
        mgr.register("voice_service", start_voice_service)

    # 5. LLMService
    if not args.no_llm:
        from nerovision.llm.llm_interface import start_llm_service
        mgr.register("llm_interface", start_llm_service)

    # 6. VectorMemory
    if not args.no_memory:
        from nerovision.memory.vector_memory import start_vector_memory
        mgr.register("vector_memory", start_vector_memory)

    # 7. MainBridge (last socket service)
    MainBridge = _make_main_bridge()
    bridge = MainBridge()
    mgr.register("main_bridge", bridge.serve_forever)

    # 8. ProductionOperator (no socket port)
    from nerovision.nero_operator.production_operator import start_operator
    mgr.register("production_operator", start_operator)

    # 9. AvatarWS
    if not args.no_avatar:
        mgr.register("avatar_ws", _start_avatar_ws)

    # ---- signal handling ---------------------------------------------------

    def _shutdown(signum: int, _frame: Any) -> None:
        name = signal.Signals(signum).name
        _log.info("Received %s — shutting down", name)
        stop.set()

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, _shutdown)
        except (OSError, ValueError):
            pass

    # ---- start everything --------------------------------------------------

    mgr.start_all()
    _log.info("All services started — waiting for stop event")

    try:
        while not stop.is_set():
            stop.wait(1.0)
    except KeyboardInterrupt:
        _log.info("KeyboardInterrupt — shutting down")
        stop.set()

    _log.info("Stop event set — draining threads")
    mgr.stop_all()
    time.sleep(2.0)
    _log.info("NeroVision stopped")
    sys.exit(0)


if __name__ == "__main__":
    main()
