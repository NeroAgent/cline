from __future__ import annotations

import json
import re
from typing import Any


FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.IGNORECASE | re.DOTALL)


def sanitize_json_text(text: str) -> str:
    candidate = text.strip()
    fenced = FENCE_RE.search(candidate)
    if fenced:
        candidate = fenced.group(1).strip()
    candidate = candidate.replace("\ufeff", "").strip()
    if candidate.lower().startswith("json"):
        candidate = candidate[4:].strip(": \n")
    extracted = _extract_json_fragment(candidate)
    return extracted or candidate


def loads_json_cleaned(text: str, default: Any | None = None) -> Any:
    if not isinstance(text, str):
        return default
    cleaned = sanitize_json_text(text)
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        repaired = _trim_trailing_commas(cleaned)
        try:
            return json.loads(repaired)
        except json.JSONDecodeError:
            return default


def dumps_json_clean(data: Any) -> str:
    return json.dumps(data, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def _extract_json_fragment(text: str) -> str | None:
    start_positions = [idx for idx, ch in enumerate(text) if ch in "{["]
    for start in start_positions:
        end = _balanced_end(text, start)
        if end is None:
            continue
        fragment = text[start:end]
        try:
            json.loads(fragment)
            return fragment
        except json.JSONDecodeError:
            continue
    return None


def _balanced_end(text: str, start: int) -> int | None:
    stack: list[str] = []
    in_string = False
    escaped = False
    for index, char in enumerate(text[start:], start=start):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            continue
        if char in "{[":
            stack.append(char)
        elif char in "}]":
            if not stack:
                return None
            opener = stack.pop()
            if (opener, char) not in {("{", "}"), ("[", "]")}:
                return None
            if not stack:
                return index + 1
    return None


def _trim_trailing_commas(text: str) -> str:
    text = re.sub(r",(\s*[}\]])", r"\1", text)
    return text
