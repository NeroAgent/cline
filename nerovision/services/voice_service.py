"""Voice pipeline — audio receiver, Whisper engine, and voice service.

AudioReceiver (port 9003)
    Accepts raw audio from Android AudioCaptureService using
    DataOutputStream framing, assembles utterances, and queues them.

WhisperEngine
    Three-tier fallback: pywhispercpp → openai-whisper → stub.

VoiceService (port 9005)
    Worker thread transcribes queued utterances and broadcasts results
    to all connected clients.
"""

from __future__ import annotations

import json
import logging
import os
import queue
import socket
import struct
import threading
import time
from typing import Any, Dict, Optional

from nerovision.core.runtime import get_stop_event
from nerovision.core.safe_clients import PORT_AUDIO_RECEIVER, PORT_VOICE_SERVICE
from nerovision.core.service_manager import ThreadedSocketServer

_log = logging.getLogger("nerovision.voice_service")

_RECV_BUF = 65536
_SILENCE_MARKER_MAX_BYTES = 4
_MAX_QUEUE = 64


# ---------------------------------------------------------------------------
# AudioReceiver — port 9003
# ---------------------------------------------------------------------------

class AudioReceiver:
    """Persistent socket server that accepts Android DataOutputStream audio.

    Protocol per connection (repeating):
        2 bytes  — big-endian unsigned short: header string length
        N bytes  — UTF-8 JSON header (sample_rate, channels, format)
        4 bytes  — big-endian int: PCM chunk length in bytes
        M bytes  — raw PCM-16 mono audio data

    A zero-length or very short PCM chunk (≤ 4 bytes) signals end-of-utterance.
    Complete utterances are placed on *utterance_queue*.
    """

    def __init__(self) -> None:
        self.utterance_queue: queue.Queue[bytes] = queue.Queue(maxsize=_MAX_QUEUE)
        self._host = "127.0.0.1"
        self._port = PORT_AUDIO_RECEIVER

    def serve_forever(self, stop_event: Optional[threading.Event] = None) -> None:
        stop = stop_event or get_stop_event()
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.settimeout(1.0)
        srv.bind((self._host, self._port))
        srv.listen(4)
        _log.info("AudioReceiver listening on %s:%d", self._host, self._port)

        try:
            while not stop.is_set():
                try:
                    client, _ = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                client.settimeout(30.0)
                t = threading.Thread(
                    target=self._handle_client, args=(client, stop),
                    daemon=True,
                )
                t.start()
        finally:
            srv.close()

    def _handle_client(self, client: socket.socket, stop: threading.Event) -> None:
        try:
            utterance_chunks: list[bytes] = []
            while not stop.is_set():
                header_len_raw = self._recv_exact(client, 2)
                if header_len_raw is None:
                    break
                header_len = struct.unpack(">H", header_len_raw)[0]

                header_raw = self._recv_exact(client, header_len)
                if header_raw is None:
                    break
                try:
                    _header = json.loads(header_raw.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    _log.warning("AudioReceiver: malformed header")
                    break

                pcm_len_raw = self._recv_exact(client, 4)
                if pcm_len_raw is None:
                    break
                pcm_len = struct.unpack(">i", pcm_len_raw)[0]

                if pcm_len <= _SILENCE_MARKER_MAX_BYTES:
                    if utterance_chunks:
                        full = b"".join(utterance_chunks)
                        utterance_chunks.clear()
                        try:
                            self.utterance_queue.put_nowait(full)
                        except queue.Full:
                            _log.warning("AudioReceiver: utterance queue full, dropping")
                    if pcm_len > 0:
                        self._recv_exact(client, pcm_len)
                    continue

                pcm_data = self._recv_exact(client, pcm_len)
                if pcm_data is None:
                    break
                utterance_chunks.append(pcm_data)

            if utterance_chunks:
                full = b"".join(utterance_chunks)
                try:
                    self.utterance_queue.put_nowait(full)
                except queue.Full:
                    pass
        except OSError:
            pass
        finally:
            try:
                client.close()
            except OSError:
                pass

    @staticmethod
    def _recv_exact(sock: socket.socket, n: int) -> Optional[bytes]:
        """Read exactly *n* bytes or return ``None`` on EOF/error."""
        buf = bytearray()
        while len(buf) < n:
            try:
                chunk = sock.recv(n - len(buf))
            except (socket.timeout, OSError):
                return None
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)


# ---------------------------------------------------------------------------
# WhisperEngine
# ---------------------------------------------------------------------------

_DEFAULT_MODEL_PATH = os.path.expanduser("~/.cache/whisper/ggml-base.en.bin")
_MODEL_PATH = os.environ.get("NERO_WHISPER_MODEL", _DEFAULT_MODEL_PATH)


class WhisperEngine:
    """Three-tier Whisper fallback: pywhispercpp → openai-whisper → stub."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._engine_name: str = "none"
        self._transcribe_fn = self._init_engine()

    def _init_engine(self):  # noqa: ANN202
        # Tier 1: pywhispercpp
        try:
            from pywhispercpp.model import Model as PwcppModel  # type: ignore[import-untyped]
            model = PwcppModel(_MODEL_PATH)
            self._engine_name = "pywhispercpp"
            _log.info("WhisperEngine: loaded pywhispercpp from %s", _MODEL_PATH)
            return self._make_pwcpp_fn(model)
        except Exception as exc:
            _log.debug("pywhispercpp unavailable: %s", exc)

        # Tier 2: openai-whisper
        try:
            import whisper as oa_whisper  # type: ignore[import-untyped]
            model = oa_whisper.load_model("base.en")
            self._engine_name = "openai-whisper"
            _log.info("WhisperEngine: loaded openai-whisper base.en")
            return self._make_oaw_fn(model)
        except Exception as exc:
            _log.debug("openai-whisper unavailable: %s", exc)

        # Tier 3: stub
        self._engine_name = "stub"
        _log.warning("WhisperEngine: no backend available — using stub")
        return self._stub_fn

    @staticmethod
    def _make_pwcpp_fn(model):  # noqa: ANN001, ANN205
        def _transcribe(audio_f32):  # noqa: ANN001, ANN202
            segments = model.transcribe(audio_f32)
            return " ".join(seg.text.strip() for seg in segments if seg.text.strip())
        return _transcribe

    @staticmethod
    def _make_oaw_fn(model):  # noqa: ANN001, ANN205
        def _transcribe(audio_f32):  # noqa: ANN001, ANN202
            import numpy as np  # type: ignore[import-untyped]
            result = model.transcribe(np.copy(audio_f32), fp16=False)
            return result.get("text", "").strip()
        return _transcribe

    @staticmethod
    def _stub_fn(_audio_f32: Any) -> str:
        return "[voice unavailable]"

    @property
    def engine_name(self) -> str:
        return self._engine_name

    def transcribe(self, pcm16_bytes: bytes) -> str:
        """Convert PCM-16 mono bytes to text (thread-safe, one at a time)."""
        try:
            import numpy as np  # type: ignore[import-untyped]
        except ImportError:
            return "[voice unavailable]"

        audio_i16 = np.frombuffer(pcm16_bytes, dtype=np.int16)
        audio_f32 = audio_i16.astype(np.float32) / 32768.0

        with self._lock:
            try:
                return self._transcribe_fn(audio_f32)
            except Exception:
                _log.exception("Whisper transcription failed")
                return ""


# ---------------------------------------------------------------------------
# VoiceService — port 9005
# ---------------------------------------------------------------------------

class VoiceService(ThreadedSocketServer):
    """Transcription broadcast service on port 9005."""

    def __init__(self, audio_receiver: AudioReceiver) -> None:
        super().__init__(port=PORT_VOICE_SERVICE, name="voice_service")
        self._receiver = audio_receiver
        self._engine = WhisperEngine()
        self._worker_thread: Optional[threading.Thread] = None

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        rtype = req.get("type", "")
        if rtype == "status":
            return {
                "status": "ok",
                "engine": self._engine.engine_name,
                "queue_depth": self._receiver.utterance_queue.qsize(),
            }
        return {"error": "unknown_type", "type": rtype}

    def serve_forever(self, stop_event: Optional[threading.Event] = None) -> None:
        stop = stop_event or get_stop_event()
        self._worker_thread = threading.Thread(
            target=self._transcription_worker, args=(stop,),
            name="voice_worker", daemon=True,
        )
        self._worker_thread.start()
        super().serve_forever(stop_event=stop)

    def _transcription_worker(self, stop: threading.Event) -> None:
        _log.info("Transcription worker started (engine=%s)", self._engine.engine_name)
        while not stop.is_set():
            try:
                pcm_data = self._receiver.utterance_queue.get(timeout=1.0)
            except queue.Empty:
                continue

            text = self._engine.transcribe(pcm_data)
            if text:
                msg = {
                    "type": "transcription",
                    "text": text,
                    "ts": time.time(),
                }
                self.broadcast(msg)
                _log.debug("Transcribed: %s", text[:80])


# ---------------------------------------------------------------------------
# Entry points
# ---------------------------------------------------------------------------

_shared_receiver: Optional[AudioReceiver] = None
_receiver_lock = threading.Lock()


def _get_shared_receiver() -> AudioReceiver:
    global _shared_receiver
    if _shared_receiver is None:
        with _receiver_lock:
            if _shared_receiver is None:
                _shared_receiver = AudioReceiver()
    return _shared_receiver


def start_audio_receiver(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking — starts AudioReceiver on port 9003."""
    receiver = _get_shared_receiver()
    receiver.serve_forever(stop_event=stop_event)


def start_voice_service(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking — starts VoiceService on port 9005."""
    receiver = _get_shared_receiver()
    svc = VoiceService(audio_receiver=receiver)
    svc.serve_forever(stop_event=stop_event)
