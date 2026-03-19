"""LLM inference service wrapping llama-cpp-python for on-device use.

Runs on port 9006.  Searches for a GGUF model on disk, loads it via
llama-cpp-python (CPU only, no mlock), and serves ``generate`` and
``status`` requests over JSON-line protocol.  Falls back to a stub
engine when no model or library is available.
"""

from __future__ import annotations

import glob
import logging
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from nerovision.core.runtime import Throttle, get_stop_event
from nerovision.core.safe_clients import PORT_LLM_INTERFACE
from nerovision.core.service_manager import ThreadedSocketServer

_log = logging.getLogger("nerovision.llm_interface")

_MAX_TOKENS_CAP = 1024

# ---------------------------------------------------------------------------
# GBNF grammar — constrains LLM output to valid JSON objects
# ---------------------------------------------------------------------------

JSON_GBNF = r"""
root   ::= object
value  ::= object | array | string | number | "true" | "false" | "null"

object ::=
  "{" ws "}" |
  "{" ws member ( "," ws member )* ws "}"

member ::= string ws ":" ws value

array  ::=
  "[" ws "]" |
  "[" ws value ( "," ws value )* ws "]"

string ::=
  "\"" characters "\""

characters ::=
  "" |
  characters character

character ::=
  [^"\\] |
  "\\" escape

escape ::= ["\\bfnrt/] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]

number ::= integer fraction? exponent?
integer ::= "-"? ( "0" | [1-9] [0-9]* )
fraction ::= "." [0-9]+
exponent ::= [eE] [+-]? [0-9]+

ws ::= [ \t\n]*
""".strip()

# ---------------------------------------------------------------------------
# Model discovery
# ---------------------------------------------------------------------------

_PREFERRED_PATTERNS: List[str] = [
    "phi-3-mini*q4*.gguf",
    "phi-3.5-mini*.gguf",
    "llama-3.2-1b*.gguf",
    "mistral-7b*.gguf",
    "llama-3.2-3b*.gguf",
]


def _find_model_path() -> Optional[str]:
    """Search well-known locations for a GGUF model file.

    Priority:
    1. ``NERO_LLM_MODEL_PATH`` env var (exact file)
    2. ``models/`` next to this script
    3. ``~/models/``
    4. ``/data/data/com.nerovision.assistant/files/models/``

    Within each directory, preferred filenames are tried first; if none
    match, the first ``*.gguf`` file is used.
    """
    env_path = os.environ.get("NERO_LLM_MODEL_PATH")
    if env_path and os.path.isfile(env_path):
        return env_path

    search_dirs = [
        str(Path(__file__).resolve().parent.parent / "models"),
        os.path.expanduser("~/models"),
        "/data/data/com.nerovision.assistant/files/models",
    ]

    for d in search_dirs:
        if not os.path.isdir(d):
            continue
        for pattern in _PREFERRED_PATTERNS:
            matches = sorted(glob.glob(os.path.join(d, pattern)))
            if matches:
                return matches[0]
        fallback = sorted(glob.glob(os.path.join(d, "*.gguf")))
        if fallback:
            return fallback[0]

    return None


# ---------------------------------------------------------------------------
# Prompt formatting
# ---------------------------------------------------------------------------

def _detect_format(model_path: str) -> str:
    """Guess the instruct format from the model filename."""
    name = os.path.basename(model_path).lower()
    if "phi" in name:
        return "phi3"
    return "llama3"


def _format_prompt(fmt: str, prompt: str, system: str) -> str:
    if fmt == "phi3":
        parts = []
        if system:
            parts.append(f"<|system|>\n{system}\n<|end|>\n")
        parts.append(f"<|user|>\n{prompt}\n<|end|>\n<|assistant|>\n")
        return "".join(parts)

    # llama3 instruct
    parts = ["<|begin_of_text|>"]
    if system:
        parts.append(
            f"<|start_header_id|>system<|end_header_id|>\n\n{system}<|eot_id|>"
        )
    parts.append(
        f"<|start_header_id|>user<|end_header_id|>\n\n{prompt}<|eot_id|>"
        f"<|start_header_id|>assistant<|end_header_id|>\n\n"
    )
    return "".join(parts)


# ---------------------------------------------------------------------------
# LlamaCppEngine
# ---------------------------------------------------------------------------

class LlamaCppEngine:
    """Wraps ``llama_cpp.Llama`` for on-device CPU inference."""

    def __init__(self, model_path: str) -> None:
        from llama_cpp import Llama, LlamaGrammar  # type: ignore[import-untyped]

        self.model_path = model_path
        self._fmt = _detect_format(model_path)
        n_threads = int(os.environ.get("NERO_LLM_THREADS", "4"))

        self._llm = Llama(
            model_path=model_path,
            n_threads=n_threads,
            n_ctx=2048,
            n_gpu_layers=0,
            use_mlock=False,
            verbose=False,
        )
        self._grammar = LlamaGrammar.from_string(JSON_GBNF)
        self._lock = threading.Lock()

        _log.info(
            "LlamaCppEngine loaded: %s (fmt=%s, threads=%d)",
            model_path, self._fmt, n_threads,
        )

    @property
    def engine_name(self) -> str:
        return "llama_cpp"

    def generate(
        self,
        prompt: str,
        system: str = "",
        max_tokens: int = 512,
        temperature: float = 0.1,
    ) -> str:
        max_tokens = min(max_tokens, _MAX_TOKENS_CAP)
        full_prompt = _format_prompt(self._fmt, prompt, system)

        with self._lock:
            result = self._llm(
                full_prompt,
                max_tokens=max_tokens,
                temperature=temperature,
                grammar=self._grammar,
                stop=["<|end|>", "<|eot_id|>"],
            )

        choices = result.get("choices", [])
        if choices:
            return choices[0].get("text", "").strip()
        return ""


# ---------------------------------------------------------------------------
# StubEngine
# ---------------------------------------------------------------------------

class StubEngine:
    """Fallback when no GGUF model or llama-cpp-python is available."""

    _RESPONSE = '{"action":"fail","reason":"LLM not available — no model loaded"}'

    def __init__(self) -> None:
        self.model_path: Optional[str] = None
        self._warned = False

    @property
    def engine_name(self) -> str:
        return "stub"

    def generate(
        self,
        prompt: str,
        system: str = "",
        max_tokens: int = 512,
        temperature: float = 0.1,
    ) -> str:
        if not self._warned:
            _log.warning("StubEngine: no LLM model loaded — returning canned failure")
            self._warned = True
        return self._RESPONSE


# ---------------------------------------------------------------------------
# Engine loader
# ---------------------------------------------------------------------------

def _load_engine() -> LlamaCppEngine | StubEngine:
    model_path = _find_model_path()
    if model_path is None:
        _log.warning("No GGUF model found in any search path — using StubEngine")
        return StubEngine()

    try:
        return LlamaCppEngine(model_path)
    except Exception:
        _log.exception("Failed to load LlamaCppEngine from %s — falling back to StubEngine", model_path)
        stub = StubEngine()
        stub.model_path = model_path
        return stub


# ---------------------------------------------------------------------------
# LLMService — port 9006
# ---------------------------------------------------------------------------

class LLMService(ThreadedSocketServer):
    """LLM inference service on port 9006."""

    def __init__(self) -> None:
        super().__init__(port=PORT_LLM_INTERFACE, name="llm_interface")
        self._engine: LlamaCppEngine | StubEngine = _load_engine()
        self._throttle = Throttle(Throttle.SLOW)
        _log.info(
            "LLMService ready: engine=%s, model=%s",
            self._engine.engine_name,
            getattr(self._engine, "model_path", None),
        )

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        rtype = req.get("type", "")
        if rtype == "generate":
            return self._handle_generate(req)
        if rtype == "status":
            return self._handle_status()
        return {"error": "unknown_type", "type": rtype}

    def _handle_generate(self, req: Dict[str, Any]) -> Dict[str, Any]:
        prompt = req.get("prompt", "")
        if not prompt:
            return {"error": "missing_prompt"}

        system = req.get("system", "")
        max_tokens = min(int(req.get("max_tokens", 512)), _MAX_TOKENS_CAP)
        temperature = float(req.get("temperature", 0.1))

        self._throttle.acquire()
        if get_stop_event().is_set():
            return {"error": "shutting_down"}

        try:
            text = self._engine.generate(
                prompt=prompt,
                system=system,
                max_tokens=max_tokens,
                temperature=temperature,
            )
            return {"status": "ok", "text": text}
        except Exception:
            _log.exception("LLMService generate failed")
            return {"error": "inference_failed"}

    def _handle_status(self) -> Dict[str, Any]:
        return {
            "status": "ok",
            "engine": self._engine.engine_name,
            "model_path": getattr(self._engine, "model_path", None),
            "ready": self._engine.engine_name != "stub",
        }


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def start_llm_service(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking entry point — starts LLMService on port 9006."""
    svc = LLMService()
    svc.serve_forever(stop_event=stop_event)
