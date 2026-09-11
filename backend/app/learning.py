"""Behavioural self-improvement: detect mistakes, distil lessons, recall them.

Lessons live in their own Mem0 namespace ("{device_id}::lessons") so they reuse all
existing memory plumbing without touching the user-facts store.
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any

from .config import get_settings
from .memory_service import get_memory, memory_get_all, memory_search

logger = logging.getLogger(__name__)

LESSON_CAP = 30
LESSON_COMPACT_TRIGGER = 40

_CORRECTION_RE = re.compile(
    r"\b("
    r"no[,. ]|that'?s wrong|that is wrong|not what i|i did ?n'?t|"
    r"i said|i meant|you got it wrong|wrong (one|number|person|contact|app)|"
    r"that'?s not right|cancel that|undo that|stop,|try again"
    r")",
    re.IGNORECASE,
)


def lessons_namespace(device_id: str) -> str:
    return f"{device_id}::lessons"


def detect_correction(transcript: str) -> bool:
    """True when the user's words look like a correction of Kodi's previous action."""
    return bool(transcript and _CORRECTION_RE.search(transcript))


def add_lesson(device_id: str, text: str) -> None:
    mem = get_memory()
    if mem is False:
        return
    text = (text or "").strip()
    if not text or text.upper() == "NONE":
        return
    try:
        mem.add(text, user_id=lessons_namespace(device_id), infer=False)
        logger.info("lesson stored for %s: %s", device_id, text)
    except Exception as exc:
        logger.warning("add_lesson failed (non-blocking): %s", exc)


def get_lessons(device_id: str, query: str, limit: int = 6) -> str:
    return memory_search(lessons_namespace(device_id), query or "general behaviour", limit=limit)


def _render_turns(messages: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for m in messages[-12:]:
        role = m.get("role", "?")
        content = m.get("content")
        if isinstance(content, str):
            lines.append(f"{role}: {content}")
            continue
        for b in content or []:
            btype = b.get("type")
            if btype == "text":
                lines.append(f"{role}: {b.get('text', '')}")
            elif btype == "tool_use":
                lines.append(f"{role} -> tool {b.get('name')}({json.dumps(b.get('input', {}))})")
            elif btype == "tool_result":
                err = " [ERROR]" if b.get("is_error") else ""
                lines.append(f"tool result{err}: {str(b.get('content', ''))[:400]}")
    return "\n".join(lines)


def reflect_on_turn(device_id: str, messages: list[dict[str, Any]], transcript: str) -> None:
    """Inspect a finished turn; if Kodi erred, distil and store one concise lesson.

    Amortised — call from a background task, never on the turn path.
    """
    mem = get_memory()
    if mem is False:
        return
    settings = get_settings()
    if not settings.openai_api_key:
        return
    try:
        from openai import OpenAI

        client = OpenAI(api_key=settings.openai_api_key)
        rendered = _render_turns(messages)
        resp = client.chat.completions.create(
            model="gpt-4o-mini",
            temperature=0.2,
            max_tokens=80,
            messages=[
                {
                    "role": "system",
                    "content": (
                        "You review one interaction between a user and the Kodi phone assistant. "
                        "If Kodi made a mistake — wrong tool, wrong target, misunderstood intent, "
                        "or a failed/incorrect action — output ONE concise imperative lesson "
                        "(under 25 words) that would prevent it next time. "
                        "If Kodi handled it correctly, output exactly NONE."
                    ),
                },
                {"role": "user", "content": f"User's latest words: {transcript}\n\nInteraction:\n{rendered}"},
            ],
        )
        lesson = (resp.choices[0].message.content or "").strip()
        if lesson and lesson.upper() != "NONE":
            add_lesson(device_id, lesson)
    except Exception as exc:
        logger.warning("reflect_on_turn failed (non-blocking): %s", exc)


def compact_lessons(device_id: str) -> None:
    """Merge duplicate/stale lessons and cap the set. Amortised; safe to skip on failure."""
    mem = get_memory()
    if mem is False:
        return
    settings = get_settings()
    if not settings.openai_api_key:
        return
    ns = lessons_namespace(device_id)
    try:
        results = memory_get_all(ns)
        if len(results) <= LESSON_COMPACT_TRIGGER:
            return
        lessons = [r.get("memory") or r.get("text") or "" for r in results]
        lessons = [x for x in lessons if x]

        from openai import OpenAI

        client = OpenAI(api_key=settings.openai_api_key)
        resp = client.chat.completions.create(
            model="gpt-4o-mini",
            temperature=0.2,
            max_tokens=600,
            messages=[
                {
                    "role": "system",
                    "content": (
                        f"Merge these behavioural lessons for an assistant into at most {LESSON_CAP} "
                        "distinct imperative rules. Drop duplicates and near-duplicates; on conflicts "
                        "keep the most specific. Output one rule per line, no numbering, no preamble."
                    ),
                },
                {"role": "user", "content": "\n".join(f"- {x}" for x in lessons)},
            ],
        )
        compacted = [
            ln.strip().lstrip("-* ").strip()
            for ln in (resp.choices[0].message.content or "").splitlines()
            if ln.strip()
        ][:LESSON_CAP]
        if not compacted:
            return
        # Rewrite the namespace only after a successful compaction.
        mem.delete_all(user_id=ns)
        for rule in compacted:
            mem.add(rule, user_id=ns, infer=False)
        logger.info("compacted lessons for %s: %d -> %d", device_id, len(results), len(compacted))
    except Exception as exc:
        logger.warning("compact_lessons failed (non-blocking): %s", exc)
