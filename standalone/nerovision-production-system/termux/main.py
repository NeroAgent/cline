from __future__ import annotations

import logging
import signal
import sys
import threading
import time

from nerovision.core.logger import get_logger, log_event
from nerovision.core.runtime import build_runtime, cpu_throttle
from nerovision.core.service_manager import ServiceManager
from nerovision.llm.llama_interface import LlamaInterface
from nerovision.memory.vector_memory import VectorMemory
from nerovision.operator.production_operator import ProductionOperator
from nerovision.services.vision_service import VisionService
from nerovision.services.voice_service import VoiceService


def main() -> int:
    runtime = build_runtime()
    logger = get_logger("nerovision.main", runtime)
    llm = LlamaInterface(runtime, logger)
    memory = VectorMemory(runtime, logger)

    vision = VisionService(runtime, get_logger("nerovision.vision", runtime), llm)
    voice = VoiceService(runtime, get_logger("nerovision.voice", runtime))
    operator = ProductionOperator(runtime, get_logger("nerovision.operator", runtime), llm, memory)

    manager = ServiceManager(runtime, logger)
    manager.register([vision, voice, operator])
    manager.start_all()
    manager.start_watchdog()

    stop_event = threading.Event()

    def stop_handler(signum: int, _frame: object) -> None:
        log_event(logger, logging.INFO, "shutdown requested", signal=signum)
        stop_event.set()

    signal.signal(signal.SIGINT, stop_handler)
    signal.signal(signal.SIGTERM, stop_handler)

    log_event(logger, logging.INFO, "nerovision runtime started", health=manager.health_snapshot())
    try:
        while not stop_event.is_set():
            cpu_throttle(runtime)
            time.sleep(0.25)
    finally:
        manager.stop_all()
        log_event(logger, logging.INFO, "nerovision runtime stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
