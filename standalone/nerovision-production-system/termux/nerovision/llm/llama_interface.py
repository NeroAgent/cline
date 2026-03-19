from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

from nerovision.core.json_cleaner import loads_json_cleaned
from nerovision.core.logger import log_event
from nerovision.core.runtime import RuntimeConfig

try:
    from llama_cpp import Llama
except ImportError:  # pragma: no cover - optional dependency at generation time
    Llama = None  # type: ignore[assignment]


class LlamaInterface:
    def __init__(self, runtime: RuntimeConfig, logger: logging.Logger, model_path: str | None = None) -> None:
        self.runtime = runtime
        self.logger = logger
        self.model_path = self.resolve_model_path(model_path)
        self._model = None

    def resolve_model_path(self, requested: str | None) -> Path | None:
        candidates: list[Path] = []
        if requested:
            candidates.append(Path(requested).expanduser())
        if os.getenv("NEROVISION_LLM_MODEL"):
            candidates.append(Path(os.environ["NEROVISION_LLM_MODEL"]).expanduser())
        candidates.extend(self.runtime.model_dir.glob("*.gguf"))
        candidates.extend((Path.home() / "storage" / "shared" / "NeroVision" / "models").glob("*.gguf"))
        candidates.extend((Path("/data/data/com.termux/files/home/storage/shared/NeroVision/models")).glob("*.gguf"))

        for candidate in candidates:
            fixed = candidate if candidate.is_absolute() else (self.runtime.base_dir / candidate)
            if fixed.exists():
                resolved = fixed.resolve()
                log_event(self.logger, logging.INFO, "llama model resolved", path=str(resolved))
                return resolved
        log_event(self.logger, logging.WARNING, "llama model not found", requested=requested or "")
        return None

    def ensure_loaded(self) -> bool:
        if self._model is not None:
            return True
        if Llama is None or self.model_path is None:
            return False
        threads = max(1, (os.cpu_count() or 2) // 2)
        self._model = Llama(
            model_path=str(self.model_path),
            n_ctx=4096,
            n_threads=threads,
            n_batch=128,
            verbose=False,
        )
        return True

    def generate_text(self, prompt: str, system_prompt: str = "") -> str:
        if not self.ensure_loaded():
            return '{"steps":[],"summary":"LLM model unavailable; falling back to deterministic no-op plan."}'
        composed = f"{system_prompt.strip()}\n\n{prompt.strip()}".strip()
        response = self._model.create_completion(  # type: ignore[union-attr]
            prompt=composed,
            temperature=0.0,
            max_tokens=512,
            stop=["</json>", "\n\nUser:"],
        )
        choices = response.get("choices") or []
        if not choices:
            return "{}"
        return str(choices[0].get("text") or "").strip()

    def generate_json(self, prompt: str, system_prompt: str = "") -> dict[str, Any]:
        raw = self.generate_text(prompt=prompt, system_prompt=system_prompt)
        cleaned = loads_json_cleaned(raw, default={"ok": False, "raw": raw})
        if isinstance(cleaned, dict):
            return cleaned
        return {"ok": True, "data": cleaned}
