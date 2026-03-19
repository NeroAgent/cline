"""Robust JSON extraction from LLM output that contains garbage.

Handles preamble text, markdown fences, trailing commentary,
single-quoted keys, unquoted keys, JS/Python primitives, trailing
commas, comments, and truncated JSON.
"""

from __future__ import annotations

import json
import re
from typing import Any, Dict, Optional

# ---------------------------------------------------------------------------
# Cleaning pipeline
# ---------------------------------------------------------------------------

_FENCE_RE = re.compile(r"```(?:json)?\s*\n?(.*?)\n?\s*```", re.DOTALL)
_LINE_COMMENT_RE = re.compile(r"//[^\n]*")
_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_TRAILING_COMMA_RE = re.compile(r",\s*([}\]])")
_UNQUOTED_KEY_RE = re.compile(r'(?<=[\{,])\s*([A-Za-z_]\w*)\s*:')
_SINGLE_QUOTE_RE = re.compile(r"'")

_JS_PRIMITIVES = {
    "NaN": "null",
    "Infinity": "null",
    "-Infinity": "null",
    "undefined": "null",
}

_PY_PRIMITIVES = {
    "True": "true",
    "False": "false",
    "None": "null",
}


def _strip_fences(text: str) -> str:
    m = _FENCE_RE.search(text)
    if m:
        return m.group(1).strip()
    return text


def _remove_comments(text: str) -> str:
    text = _BLOCK_COMMENT_RE.sub("", text)
    text = _LINE_COMMENT_RE.sub("", text)
    return text


def _fix_trailing_commas(text: str) -> str:
    return _TRAILING_COMMA_RE.sub(r"\1", text)


def _replace_primitives(text: str) -> str:
    for token, replacement in _JS_PRIMITIVES.items():
        text = re.sub(rf"\b{token}\b", replacement, text)
    for token, replacement in _PY_PRIMITIVES.items():
        text = re.sub(rf"\b{token}\b", replacement, text)
    return text


def _fix_single_quotes(text: str) -> str:
    """Convert single-quoted JSON strings to double-quoted.

    Uses a simple state machine to handle escaped quotes and avoid
    mangling apostrophes inside already-double-quoted strings.
    """
    result: list[str] = []
    in_double = False
    in_single = False
    i = 0
    while i < len(text):
        ch = text[i]

        if ch == "\\" and i + 1 < len(text):
            result.append(ch)
            result.append(text[i + 1])
            i += 2
            continue

        if ch == '"' and not in_single:
            in_double = not in_double
            result.append(ch)
        elif ch == "'" and not in_double:
            if not in_single:
                in_single = True
                result.append('"')
            else:
                in_single = False
                result.append('"')
        else:
            result.append(ch)
        i += 1

    return "".join(result)


def _fix_unquoted_keys(text: str) -> str:
    return _UNQUOTED_KEY_RE.sub(r' "\1":', text)


def _find_json_bounds(text: str) -> Optional[str]:
    """Find the outermost ``{...}`` or ``[...]`` by brace counting."""
    start = -1
    open_ch = ""
    close_ch = ""

    for i, ch in enumerate(text):
        if ch in ("{", "["):
            start = i
            open_ch = ch
            close_ch = "}" if ch == "{" else "]"
            break

    if start == -1:
        return None

    depth = 0
    in_str = False
    escape = False
    for i in range(start, len(text)):
        ch = text[i]
        if escape:
            escape = False
            continue
        if ch == "\\":
            escape = True
            continue
        if ch == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return text[start : i + 1]

    # Truncated — attempt recovery by appending closing braces/brackets
    if depth > 0:
        suffix = close_ch * depth
        return text[start:] + suffix

    return None


def _clean(text: str) -> str:
    text = _strip_fences(text)
    text = _remove_comments(text)
    text = _replace_primitives(text)
    text = _fix_single_quotes(text)
    text = _fix_unquoted_keys(text)
    text = _fix_trailing_commas(text)
    return text


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def extract_json(text: str) -> Optional[str]:
    """Return the first valid JSON string found in *text*, or ``None``."""
    cleaned = _clean(text)
    candidate = _find_json_bounds(cleaned)
    if candidate is None:
        return None

    try:
        json.loads(candidate)
        return candidate
    except json.JSONDecodeError:
        pass

    candidate = _fix_trailing_commas(candidate)
    try:
        json.loads(candidate)
        return candidate
    except json.JSONDecodeError:
        pass

    return candidate


def safe_parse(text: str) -> Optional[Dict[str, Any]]:
    """Full extraction + parsing. Returns a dict or ``None``."""
    raw = extract_json(text)
    if raw is None:
        return None
    try:
        obj = json.loads(raw)
        if isinstance(obj, dict):
            return obj
    except json.JSONDecodeError:
        pass
    return None


def safe_parse_action(text: str) -> Optional[Dict[str, Any]]:
    """Parse and validate that the result contains an ``action`` key."""
    obj = safe_parse(text)
    if obj is not None and "action" in obj:
        return obj
    return None


# ---------------------------------------------------------------------------
# Action schema validation
# ---------------------------------------------------------------------------

_ACTION_REQUIRED_FIELDS: Dict[str, set[str]] = {
    "click_by_id": {"id"},
    "click_by_text": {"text"},
    "click_coords": {"x", "y"},
    "type_text": {"text"},
    "scroll": {"direction"},
    "back": set(),
    "home": set(),
    "wait": {"seconds"},
    "done": {"result"},
    "fail": {"reason"},
}

_VALID_SCROLL_DIRECTIONS = {"up", "down", "left", "right"}


def validate_action_schema(action: Dict[str, Any]) -> bool:
    """Check that *action* has all required fields for its action type.

    Returns ``True`` if valid, ``False`` otherwise.
    """
    action_type = action.get("action")
    if action_type is None:
        return False

    required = _ACTION_REQUIRED_FIELDS.get(action_type)
    if required is None:
        return False

    for field in required:
        if field not in action:
            return False

    if action_type == "scroll":
        if action.get("direction") not in _VALID_SCROLL_DIRECTIONS:
            return False

    return True
