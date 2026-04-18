from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path


LOCALHOST = "127.0.0.1"
MAX_RETRIES = 3


@dataclass(frozen=True)
class RuntimeConfig:
    base_dir: Path
    data_dir: Path
    log_dir: Path
    snapshot_dir: Path
    memory_dir: Path
    model_dir: Path
    host: str = LOCALHOST
    android_bridge_port: int = 8766
    vision_service_port: int = 8767
    voice_service_port: int = 8768
    operator_service_port: int = 8769
    cpu_idle_sleep_s: float = 0.05
    service_health_interval_s: float = 5.0
    max_retries: int = MAX_RETRIES

    def ensure_directories(self) -> "RuntimeConfig":
        for directory in (
            self.data_dir,
            self.log_dir,
            self.snapshot_dir,
            self.memory_dir,
            self.model_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)
        return self


def build_runtime() -> RuntimeConfig:
    base_dir = Path(__file__).resolve().parents[2]
    data_dir = base_dir / "var"
    runtime = RuntimeConfig(
        base_dir=base_dir,
        data_dir=data_dir,
        log_dir=data_dir / "logs",
        snapshot_dir=data_dir / "snapshots",
        memory_dir=data_dir / "memory",
        model_dir=data_dir / "models",
    )
    return runtime.ensure_directories()


def ensure_localhost(host: str) -> str:
    if host != LOCALHOST:
        raise ValueError(f"NeroVision only permits localhost sockets, got {host!r}")
    return host


def exponential_backoff(attempt: int, base_delay: float = 0.25) -> float:
    return base_delay * (2**attempt)


def cpu_throttle(runtime: RuntimeConfig) -> None:
    time.sleep(runtime.cpu_idle_sleep_s)


def utc_timestamp() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default
