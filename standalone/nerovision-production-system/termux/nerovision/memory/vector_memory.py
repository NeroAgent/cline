from __future__ import annotations

import hashlib
import json
import logging
import time
from pathlib import Path
from typing import Any

import numpy as np

from nerovision.core.logger import log_event
from nerovision.core.runtime import RuntimeConfig

try:
    import faiss
except ImportError:  # pragma: no cover - optional dependency at generation time
    faiss = None  # type: ignore[assignment]


class VectorMemory:
    def __init__(self, runtime: RuntimeConfig, logger: logging.Logger, dimensions: int = 256) -> None:
        self.runtime = runtime
        self.logger = logger
        self.dimensions = dimensions
        self.records: list[dict[str, Any]] = []
        self.vectors = np.empty((0, dimensions), dtype=np.float32)
        self.index = faiss.IndexFlatIP(dimensions) if faiss is not None else None
        self.directory = runtime.memory_dir
        self.directory.mkdir(parents=True, exist_ok=True)
        self.load_latest()

    def add(self, text: str, metadata: dict[str, Any] | None = None) -> dict[str, Any]:
        vector = self._embed(text)
        self.vectors = np.vstack([self.vectors, vector[np.newaxis, :]])
        self.records.append(
            {
                "text": text,
                "metadata": metadata or {},
                "created_at": time.time(),
            }
        )
        if self.index is not None:
            self.index.add(vector[np.newaxis, :])
        snapshot = self.save_version()
        log_event(self.logger, logging.INFO, "memory stored", entries=len(self.records), path=str(snapshot))
        return self.records[-1]

    def search(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        if not self.records:
            return []
        vector = self._embed(query)
        if self.index is not None and len(self.records) > 0:
            scores, indices = self.index.search(vector[np.newaxis, :], min(limit, len(self.records)))
            return [
                {**self.records[index], "score": float(score)}
                for score, index in zip(scores[0], indices[0], strict=False)
                if index >= 0
            ]
        similarities = self.vectors @ vector
        ranked = np.argsort(similarities)[::-1][:limit]
        return [{**self.records[idx], "score": float(similarities[idx])} for idx in ranked]

    def save_version(self) -> Path:
        stamp = time.strftime("%Y%m%d_%H%M%S", time.gmtime())
        records_path = self.directory / f"records_{stamp}_{len(self.records)}.json"
        records_path.write_text(json.dumps(self.records, ensure_ascii=True, indent=2))
        if self.index is not None:
            index_path = self.directory / f"faiss_{stamp}_{len(self.records)}.index"
            faiss.write_index(self.index, str(index_path))
        else:
            vector_path = self.directory / f"vectors_{stamp}_{len(self.records)}.npy"
            np.save(vector_path, self.vectors)
        self._trim()
        return records_path

    def load_latest(self) -> None:
        record_files = sorted(self.directory.glob("records_*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
        if not record_files:
            return
        latest_records = record_files[0]
        self.records = json.loads(latest_records.read_text())
        if not self.records:
            return
        self.vectors = np.vstack([self._embed(record["text"]) for record in self.records])
        if self.index is not None:
            self.index.reset()
            self.index.add(self.vectors)

    def _embed(self, text: str) -> np.ndarray:
        vector = np.zeros(self.dimensions, dtype=np.float32)
        for token in text.lower().split():
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimensions
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[index] += sign
        norm = np.linalg.norm(vector)
        return vector / norm if norm else vector

    def _trim(self, keep: int = 20) -> None:
        for pattern in ("records_*.json", "faiss_*.index", "vectors_*.npy"):
            files = sorted(self.directory.glob(pattern), key=lambda item: item.stat().st_mtime, reverse=True)
            for stale in files[keep:]:
                stale.unlink(missing_ok=True)
