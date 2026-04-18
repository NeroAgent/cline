from __future__ import annotations

import base64
import logging
import threading
import time
from typing import Any

import numpy as np

from nerovision.core.logger import log_event
from nerovision.core.runtime import RuntimeConfig
from nerovision.core.snapshot import SnapshotStore
from nerovision.core.threaded_server import ThreadedJsonServer

try:
    import whisper
except ImportError:  # pragma: no cover - optional dependency at generation time
    whisper = None  # type: ignore[assignment]


class VoiceService:
    name = "voice_service"

    def __init__(self, runtime: RuntimeConfig, logger: logging.Logger) -> None:
        self.runtime = runtime
        self.logger = logger
        self.snapshots = SnapshotStore(runtime, "voice")
        self.server = ThreadedJsonServer(runtime, self.name, runtime.voice_service_port, logger, self._handle)
        self._lock = threading.Lock()
        self._model = None
        self._buffers: dict[str, bytearray] = {}
        self._latest_transcript: dict[str, Any] = {"ok": True, "text": ""}

    def start(self) -> None:
        self.server.start()

    def stop(self) -> None:
        self.server.stop()

    def health(self) -> dict[str, Any]:
        health = self.server.health()
        health["bufferCount"] = len(self._buffers)
        return health

    def _handle(self, payload: dict[str, Any]) -> dict[str, Any]:
        command = payload.get("command", "health")
        if command == "health":
            return self.health()
        if command == "pcm_chunk":
            return self._append_chunk(payload)
        if command == "transcribe":
            return self._transcribe_base64(payload)
        if command == "flush_stream":
            return self._flush_stream(payload.get("streamId", "default"), int(payload.get("sampleRate", 16000)))
        if command == "latest_transcript":
            return self._latest_transcript
        return {"ok": False, "error": f"unknown_command:{command}"}

    def _append_chunk(self, payload: dict[str, Any]) -> dict[str, Any]:
        stream_id = str(payload.get("streamId", "default"))
        sample_rate = int(payload.get("sampleRate", 16000))
        chunk = base64.b64decode(payload.get("pcmBase64", "") or b"")
        with self._lock:
            buffer = self._buffers.setdefault(stream_id, bytearray())
            buffer.extend(chunk)
            buffered_ms = int((len(buffer) / 2) / sample_rate * 1000)
        if buffered_ms >= 2500:
            return self._flush_stream(stream_id, sample_rate)
        return {"ok": True, "accepted": len(chunk), "buffered_ms": buffered_ms}

    def _transcribe_base64(self, payload: dict[str, Any]) -> dict[str, Any]:
        sample_rate = int(payload.get("sampleRate", 16000))
        pcm = base64.b64decode(payload.get("pcmBase64", "") or b"")
        result = self._transcribe_pcm(pcm, sample_rate)
        self._latest_transcript = result
        self.snapshots.write("transcribe", result)
        return result

    def _flush_stream(self, stream_id: str, sample_rate: int) -> dict[str, Any]:
        with self._lock:
            pcm = bytes(self._buffers.pop(stream_id, bytearray()))
        result = self._transcribe_pcm(pcm, sample_rate)
        result["streamId"] = stream_id
        self._latest_transcript = result
        self.snapshots.write("stream_flush", result)
        return result

    def _transcribe_pcm(self, pcm_bytes: bytes, sample_rate: int) -> dict[str, Any]:
        if not pcm_bytes:
            return {"ok": False, "error": "empty_audio"}
        audio = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
        if whisper is None:
            return {
                "ok": True,
                "text": "",
                "warning": "whisper_not_installed",
                "duration_s": round(len(audio) / sample_rate, 2),
            }
        if self._model is None:
            self._model = whisper.load_model("tiny.en")
            log_event(self.logger, logging.INFO, "whisper model loaded", model="tiny.en")
        result = self._model.transcribe(audio, fp16=False, language="en")
        transcript = {
            "ok": True,
            "text": str(result.get("text", "")).strip(),
            "segments": result.get("segments", []),
            "duration_s": round(len(audio) / sample_rate, 2),
            "created_at": time.time(),
        }
        return transcript
