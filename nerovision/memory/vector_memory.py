"""Persistent semantic memory with FAISS and TF-IDF fallback.

Runs on port 9007.  Stores text entries with vector embeddings for
similarity search.  Uses sentence-transformers when available, falling
back to a zero-dependency TF-IDF feature-hashing backend.  Index is
FAISS IndexFlatIP when available, otherwise brute-force numpy cosine.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import pickle
import threading
import time
import uuid
from collections import Counter
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from nerovision.core.runtime import get_stop_event
from nerovision.core.safe_clients import PORT_VECTOR_MEMORY
from nerovision.core.service_manager import ThreadedSocketServer

_log = logging.getLogger("nerovision.vector_memory")

_DEFAULT_MEMORY_DIR = "/data/data/com.nerovision.assistant/files/memory/"
_MEMORY_DIR = os.environ.get("NERO_MEMORY_DIR", _DEFAULT_MEMORY_DIR)
_MAX_ENTRIES = int(os.environ.get("NERO_MEMORY_MAX_ENTRIES", "10000"))
_SAVE_EVERY = 20
_EMBED_DIM = 384
_DELETED_SENTINEL = "__DELETED__"


# ---------------------------------------------------------------------------
# MemoryEntry
# ---------------------------------------------------------------------------

@dataclass
class MemoryEntry:
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    text: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    ts: float = field(default_factory=time.time)


# ---------------------------------------------------------------------------
# Embedding backends
# ---------------------------------------------------------------------------

class SentenceTransformerBackend:
    """Wraps sentence-transformers for dense embeddings."""

    def __init__(self) -> None:
        from sentence_transformers import SentenceTransformer  # type: ignore[import-untyped]

        model_name = os.environ.get("NERO_EMBED_MODEL", "all-MiniLM-L6-v2")
        self._model = SentenceTransformer(model_name)
        self.dim = self._model.get_sentence_embedding_dimension()
        _log.info("SentenceTransformerBackend loaded: %s (dim=%d)", model_name, self.dim)

    @property
    def name(self) -> str:
        return "sentence_transformers"

    def embed(self, text: str) -> List[float]:
        import numpy as np  # type: ignore[import-untyped]
        vec = self._model.encode(text, normalize_embeddings=True)
        return vec.tolist() if isinstance(vec, np.ndarray) else list(vec)


class TFIDFHashBackend:
    """Zero-dependency fallback: TF-IDF weights hashed into fixed-dim space."""

    def __init__(self, dim: int = _EMBED_DIM) -> None:
        self.dim = dim
        self._idf: Dict[str, float] = {}
        self._lock = threading.Lock()
        _log.info("TFIDFHashBackend initialized (dim=%d)", dim)

    @property
    def name(self) -> str:
        return "tfidf_hash"

    def update_idf(self, texts: List[str]) -> None:
        """Recalculate IDF from a corpus of texts."""
        n = len(texts)
        if n == 0:
            return
        df: Counter[str] = Counter()
        for t in texts:
            df.update(set(self._tokenize(t)))
        with self._lock:
            self._idf = {
                tok: math.log((n + 1) / (cnt + 1)) + 1.0
                for tok, cnt in df.items()
            }

    def embed(self, text: str) -> List[float]:
        tokens = self._tokenize(text)
        if not tokens:
            return [0.0] * self.dim

        tf: Counter[str] = Counter(tokens)
        vec = [0.0] * self.dim
        with self._lock:
            idf = dict(self._idf)

        for tok, count in tf.items():
            weight = count * idf.get(tok, 1.0)
            idx = int(hashlib.md5(tok.encode()).hexdigest(), 16) % self.dim
            sign = 1.0 if (int(hashlib.sha1(tok.encode()).hexdigest(), 16) & 1) == 0 else -1.0
            vec[idx] += weight * sign

        norm = math.sqrt(sum(v * v for v in vec)) or 1.0
        return [v / norm for v in vec]

    @staticmethod
    def _tokenize(text: str) -> List[str]:
        return text.lower().split()


def _load_embed_backend() -> SentenceTransformerBackend | TFIDFHashBackend:
    try:
        return SentenceTransformerBackend()
    except Exception as exc:
        _log.debug("sentence-transformers unavailable: %s", exc)
    return TFIDFHashBackend()


# ---------------------------------------------------------------------------
# VectorIndex
# ---------------------------------------------------------------------------

class VectorIndex:
    """FAISS IndexFlatIP with brute-force numpy fallback."""

    def __init__(self, dim: int = _EMBED_DIM) -> None:
        self.dim = dim
        self._use_faiss = False
        self._faiss_index: Any = None
        self._id_list: List[str] = []
        self._vecs: List[List[float]] = []
        self._id_to_pos: Dict[str, int] = {}
        self._init_faiss(dim)

    def _init_faiss(self, dim: int) -> None:
        try:
            import faiss  # type: ignore[import-untyped]
            self._faiss_index = faiss.IndexFlatIP(dim)
            self._use_faiss = True
            _log.info("VectorIndex using FAISS IndexFlatIP (dim=%d)", dim)
        except ImportError:
            _log.info("VectorIndex using brute-force numpy fallback (dim=%d)", dim)

    @property
    def index_type(self) -> str:
        return "faiss" if self._use_faiss else "numpy"

    def __len__(self) -> int:
        return len(self._id_to_pos)

    def add(self, entry_id: str, vec: List[float]) -> None:
        if entry_id in self._id_to_pos:
            return
        import numpy as np  # type: ignore[import-untyped]
        arr = np.array(vec, dtype=np.float32).reshape(1, -1)
        if self._use_faiss:
            self._faiss_index.add(arr)
        self._vecs.append(vec)
        pos = len(self._id_list)
        self._id_list.append(entry_id)
        self._id_to_pos[entry_id] = pos

    def remove(self, entry_id: str) -> None:
        pos = self._id_to_pos.get(entry_id)
        if pos is None:
            return
        self._id_list[pos] = _DELETED_SENTINEL
        del self._id_to_pos[entry_id]

    def search(self, vec: List[float], top_k: int = 5) -> List[Tuple[str, float]]:
        if not self._id_list:
            return []
        import numpy as np  # type: ignore[import-untyped]
        query = np.array(vec, dtype=np.float32).reshape(1, -1)

        if self._use_faiss and self._faiss_index.ntotal > 0:
            k = min(top_k + len(self._id_list) - len(self._id_to_pos), self._faiss_index.ntotal)
            if k <= 0:
                return []
            scores, indices = self._faiss_index.search(query, k)
            results: List[Tuple[str, float]] = []
            for score, idx in zip(scores[0], indices[0]):
                if idx < 0 or idx >= len(self._id_list):
                    continue
                eid = self._id_list[idx]
                if eid == _DELETED_SENTINEL:
                    continue
                results.append((eid, float(score)))
                if len(results) >= top_k:
                    break
            return results

        mat = np.array(self._vecs, dtype=np.float32)
        scores_arr = (mat @ query.T).flatten()
        ranked = scores_arr.argsort()[::-1]
        results = []
        for idx in ranked:
            eid = self._id_list[int(idx)]
            if eid == _DELETED_SENTINEL:
                continue
            results.append((eid, float(scores_arr[idx])))
            if len(results) >= top_k:
                break
        return results

    def save(self, directory: str) -> None:
        os.makedirs(directory, exist_ok=True)
        meta = {"id_list": self._id_list, "vecs": self._vecs, "id_to_pos": self._id_to_pos}
        with open(os.path.join(directory, "index.meta"), "wb") as f:
            pickle.dump(meta, f)
        if self._use_faiss:
            import faiss  # type: ignore[import-untyped]
            faiss.write_index(self._faiss_index, os.path.join(directory, "index.faiss"))
        _log.debug("VectorIndex saved to %s (%d entries)", directory, len(self._id_to_pos))

    def load(self, directory: str) -> bool:
        meta_path = os.path.join(directory, "index.meta")
        if not os.path.isfile(meta_path):
            return False
        try:
            with open(meta_path, "rb") as f:
                meta = pickle.load(f)
            self._id_list = meta["id_list"]
            self._vecs = meta["vecs"]
            self._id_to_pos = meta["id_to_pos"]

            faiss_path = os.path.join(directory, "index.faiss")
            if self._use_faiss and os.path.isfile(faiss_path):
                import faiss  # type: ignore[import-untyped]
                self._faiss_index = faiss.read_index(faiss_path)
            elif self._use_faiss:
                self._rebuild_faiss()
            _log.info("VectorIndex loaded from %s (%d entries)", directory, len(self._id_to_pos))
            return True
        except Exception:
            _log.exception("Failed to load VectorIndex from %s", directory)
            return False

    def _rebuild_faiss(self) -> None:
        import numpy as np  # type: ignore[import-untyped]
        import faiss  # type: ignore[import-untyped]
        self._faiss_index = faiss.IndexFlatIP(self.dim)
        if self._vecs:
            mat = np.array(self._vecs, dtype=np.float32)
            self._faiss_index.add(mat)


# ---------------------------------------------------------------------------
# MemoryStore  (thread-safe JSONL persistence)
# ---------------------------------------------------------------------------

class MemoryStore:
    """Append-only JSONL store with periodic compaction."""

    def __init__(self, directory: str) -> None:
        self._dir = directory
        self._entries: Dict[str, MemoryEntry] = {}
        self._lock = threading.Lock()
        self._writes_since_compact = 0
        os.makedirs(directory, exist_ok=True)
        self._load()

    @property
    def path(self) -> str:
        return os.path.join(self._dir, "entries.jsonl")

    def count(self) -> int:
        with self._lock:
            return len(self._entries)

    def all_entries(self) -> List[MemoryEntry]:
        with self._lock:
            return list(self._entries.values())

    def get(self, entry_id: str) -> Optional[MemoryEntry]:
        with self._lock:
            return self._entries.get(entry_id)

    def add(self, text: str, metadata: Optional[Dict[str, Any]] = None) -> MemoryEntry:
        entry = MemoryEntry(text=text, metadata=metadata or {})
        with self._lock:
            self._evict_oldest()
            self._entries[entry.id] = entry
            self._append(entry)
            self._maybe_compact()
        return entry

    def delete(self, entry_id: str) -> bool:
        with self._lock:
            if entry_id not in self._entries:
                return False
            del self._entries[entry_id]
            self._append_tombstone(entry_id)
            self._maybe_compact()
            return True

    def clear(self) -> int:
        with self._lock:
            count = len(self._entries)
            self._entries.clear()
            self._compact()
            return count

    # ---- internals --------------------------------------------------------

    def _load(self) -> None:
        if not os.path.isfile(self.path):
            return
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    obj = json.loads(line)
                    if obj.get("_deleted"):
                        self._entries.pop(obj["id"], None)
                    else:
                        self._entries[obj["id"]] = MemoryEntry(**{
                            k: v for k, v in obj.items() if k in MemoryEntry.__dataclass_fields__
                        })
        except (OSError, json.JSONDecodeError):
            _log.exception("Failed to load entries.jsonl")

    def _append(self, entry: MemoryEntry) -> None:
        try:
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(json.dumps(asdict(entry), default=str, ensure_ascii=False) + "\n")
            self._writes_since_compact += 1
        except OSError:
            _log.debug("Failed to append entry", exc_info=True)

    def _append_tombstone(self, entry_id: str) -> None:
        try:
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"id": entry_id, "_deleted": True}) + "\n")
            self._writes_since_compact += 1
        except OSError:
            pass

    def _maybe_compact(self) -> None:
        if self._writes_since_compact >= _SAVE_EVERY:
            self._compact()

    def _compact(self) -> None:
        try:
            with open(self.path, "w", encoding="utf-8") as f:
                for entry in self._entries.values():
                    f.write(json.dumps(asdict(entry), default=str, ensure_ascii=False) + "\n")
            self._writes_since_compact = 0
        except OSError:
            _log.debug("Compaction failed", exc_info=True)

    def _evict_oldest(self) -> None:
        while len(self._entries) >= _MAX_ENTRIES:
            oldest_id = min(self._entries, key=lambda k: self._entries[k].ts)
            del self._entries[oldest_id]


# ---------------------------------------------------------------------------
# VectorMemoryService — port 9007
# ---------------------------------------------------------------------------

class VectorMemoryService(ThreadedSocketServer):
    """Semantic memory service on port 9007."""

    def __init__(self, directory: str = _MEMORY_DIR) -> None:
        super().__init__(port=PORT_VECTOR_MEMORY, name="vector_memory")
        self._dir = directory
        self._backend = _load_embed_backend()
        self._index = VectorIndex(dim=self._backend.dim)
        self._store = MemoryStore(directory)
        self._write_lock = threading.Lock()
        self._startup_load()

    def _startup_load(self) -> None:
        loaded = self._index.load(self._dir)
        indexed_ids = set(self._index._id_to_pos.keys())
        entries = self._store.all_entries()

        if isinstance(self._backend, TFIDFHashBackend):
            self._backend.update_idf([e.text for e in entries])

        missing = [e for e in entries if e.id not in indexed_ids]
        if missing:
            _log.info("Re-embedding %d entries not yet in index", len(missing))
            for e in missing:
                vec = self._backend.embed(e.text)
                self._index.add(e.id, vec)

        if not loaded and entries:
            self._index.save(self._dir)

        _log.info(
            "VectorMemoryService ready: %d entries, backend=%s, index=%s",
            self._store.count(), self._backend.name, self._index.index_type,
        )

    def handle_request(self, req: Dict[str, Any]) -> Dict[str, Any]:
        rtype = req.get("type", "")
        handler = self._DISPATCH.get(rtype)
        if handler is None:
            return {"error": "unknown_type", "type": rtype}
        return handler(self, req)

    # ---- handlers ----------------------------------------------------------

    def _handle_store(self, req: Dict[str, Any]) -> Dict[str, Any]:
        text = req.get("text", "")
        if not text:
            return {"error": "missing_text"}
        metadata = req.get("metadata", {})

        with self._write_lock:
            entry = self._store.add(text, metadata)
            vec = self._backend.embed(text)
            self._index.add(entry.id, vec)

            if isinstance(self._backend, TFIDFHashBackend):
                texts = [e.text for e in self._store.all_entries()]
                self._backend.update_idf(texts)

        return {"status": "ok", "id": entry.id}

    def _handle_search(self, req: Dict[str, Any]) -> Dict[str, Any]:
        query = req.get("query", "")
        if not query:
            return {"error": "missing_query"}
        top_k = int(req.get("top_k", 5))
        vec = self._backend.embed(query)
        hits = self._index.search(vec, top_k=top_k)

        results: List[Dict[str, Any]] = []
        for entry_id, score in hits:
            entry = self._store.get(entry_id)
            if entry is None:
                continue
            results.append({
                "id": entry.id,
                "text": entry.text,
                "score": round(score, 4),
                "metadata": entry.metadata,
                "ts": entry.ts,
            })
        return {"status": "ok", "results": results}

    def _handle_delete(self, req: Dict[str, Any]) -> Dict[str, Any]:
        entry_id = req.get("id", "")
        if not entry_id:
            return {"error": "missing_id"}
        with self._write_lock:
            ok = self._store.delete(entry_id)
            if ok:
                self._index.remove(entry_id)
        return {"status": "ok", "deleted": ok}

    def _handle_clear(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        with self._write_lock:
            count = self._store.clear()
            self._index = VectorIndex(dim=self._backend.dim)
        return {"status": "ok", "deleted_count": count}

    def _handle_stats(self, _req: Dict[str, Any]) -> Dict[str, Any]:
        disk_mb = 0.0
        try:
            for fname in os.listdir(self._dir):
                fpath = os.path.join(self._dir, fname)
                if os.path.isfile(fpath):
                    disk_mb += os.path.getsize(fpath) / (1024 * 1024)
        except OSError:
            pass
        return {
            "status": "ok",
            "count": self._store.count(),
            "index_type": self._index.index_type,
            "embed_backend": self._backend.name,
            "disk_mb": round(disk_mb, 2),
        }

    _DISPATCH: Dict[str, Any] = {
        "store": _handle_store,
        "search": _handle_search,
        "delete": _handle_delete,
        "clear": _handle_clear,
        "stats": _handle_stats,
    }

    def save_on_exit(self) -> None:
        try:
            self._index.save(self._dir)
            _log.info("VectorMemoryService: index saved on exit")
        except Exception:
            _log.exception("Failed to save index on exit")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def start_vector_memory(stop_event: Optional[threading.Event] = None) -> None:
    """Blocking entry point -- starts VectorMemoryService on port 9007."""
    svc = VectorMemoryService()
    try:
        svc.serve_forever(stop_event=stop_event)
    finally:
        svc.save_on_exit()
