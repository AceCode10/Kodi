import logging
import time
from pathlib import Path
from typing import Any

from .config import get_settings

logger = logging.getLogger(__name__)

_memory: Any = None

PROFILE_TTL_SECONDS = 24 * 3600


def get_memory():
    global _memory
    if _memory is not None:
        return _memory
    settings = get_settings()
    try:
        from mem0 import Memory

        key = settings.openai_api_key
        chroma_dir = settings.chroma_path
        chroma_dir.mkdir(parents=True, exist_ok=True)
        config = {
            "vector_store": {
                "provider": "chroma",
                "config": {
                    "collection_name": settings.qdrant_collection,
                    "path": str(chroma_dir),
                },
            },
            "embedder": {
                "provider": "openai",
                "config": {
                    "model": "text-embedding-3-small",
                    "api_key": key,
                },
            },
            "llm": {
                "provider": "openai",
                "config": {
                    "model": "gpt-4o-mini",
                    "temperature": 0.2,
                    "api_key": key,
                },
            },
        }
        _memory = Memory.from_config(config)
        logger.info("Mem0 initialized with Chroma at %s", chroma_dir)
    except Exception as exc:
        logger.warning("Mem0 unavailable (%s); memory features degraded", exc)
        _memory = False
    return _memory


def memory_search(user_id: str, query: str, limit: int = 5) -> str:
    mem = get_memory()
    if mem is False:
        return ""
    try:
        out = mem.search(query, filters={"user_id": user_id}, top_k=limit)
        results = out.get("results") or []
        lines = []
        for r in results[:limit]:
            text = r.get("memory") or r.get("text") or ""
            if text:
                lines.append(f"- {text}")
        return "\n".join(lines) if lines else ""
    except Exception as exc:
        logger.warning("memory search failed: %s", exc)
        return ""


def memory_add_turn(user_id: str, user_text: str, assistant_text: str = "") -> None:
    """Extract durable facts from the USER's words only.

    The assistant reply is intentionally NOT stored — feeding model output back into
    long-term memory risks persisting hallucinated claims as durable facts.
    `assistant_text` is accepted for call-site compatibility but ignored.
    """
    mem = get_memory()
    if mem is False:
        return
    if not user_text or not user_text.strip():
        return
    try:
        mem.add(
            [{"role": "user", "content": user_text}],
            user_id=user_id,
            infer=True,
        )
    except Exception as exc:
        logger.warning("memory add failed (non-blocking): %s", exc)


def memory_forget_last(user_id: str) -> str:
    mem = get_memory()
    if mem is False:
        return "Memory is offline."
    try:
        out = mem.get_all(filters={"user_id": user_id})
        results = out.get("results") or []
        if not results:
            return "Nothing to forget."
        sorted_results = sorted(results, key=lambda r: r.get("created_at") or "", reverse=True)
        mid = sorted_results[0].get("id")
        if mid:
            mem.delete(mid)
            return "Forgot the last memory."
        return "Could not forget."
    except Exception as exc:
        logger.warning("forget last failed: %s", exc)
        return "Could not forget that."


def memory_forget_all(user_id: str) -> str:
    mem = get_memory()
    if mem is False:
        return "Memory is offline."
    try:
        mem.delete_all(user_id=user_id)
        return "All stored memories for this device were cleared."
    except Exception as exc:
        logger.warning("forget all failed: %s", exc)
        return "Could not clear memories."


def _profile_path(user_id: str) -> Path:
    settings = get_settings()
    safe = "".join(c for c in user_id if c.isalnum() or c in "-_") or "default"
    d = settings.data_dir / "profiles"
    d.mkdir(parents=True, exist_ok=True)
    return d / f"{safe}.txt"


def read_cached_profile(user_id: str) -> str:
    """Return the cached compact user profile instantly. Empty string if none."""
    try:
        p = _profile_path(user_id)
        return p.read_text(encoding="utf-8").strip() if p.exists() else ""
    except Exception:
        return ""


def profile_is_stale(user_id: str) -> bool:
    try:
        p = _profile_path(user_id)
        if not p.exists():
            return True
        return (time.time() - p.stat().st_mtime) > PROFILE_TTL_SECONDS
    except Exception:
        return False


def refresh_user_profile(user_id: str) -> None:
    """Compress all stored facts into a <=200-word profile. Amortised — call off the turn path."""
    mem = get_memory()
    if mem is False:
        return
    settings = get_settings()
    if not settings.openai_api_key:
        return
    try:
        out = mem.get_all(filters={"user_id": user_id})
        results = out.get("results") or []
        facts = [r.get("memory") or r.get("text") or "" for r in results]
        facts = [f for f in facts if f]
        if not facts:
            return
        from openai import OpenAI

        client = OpenAI(api_key=settings.openai_api_key)
        joined = "\n".join(f"- {f}" for f in facts)
        resp = client.chat.completions.create(
            model="gpt-4o-mini",
            temperature=0.2,
            max_tokens=400,
            messages=[
                {
                    "role": "system",
                    "content": (
                        "Compress these facts about a user into a concise profile of at most "
                        "200 words. Group related facts. Drop duplicates; on contradictions keep "
                        "the most recent. Output plain text, no preamble."
                    ),
                },
                {"role": "user", "content": joined},
            ],
        )
        profile = (resp.choices[0].message.content or "").strip()
        if profile:
            _profile_path(user_id).write_text(profile, encoding="utf-8")
    except Exception as exc:
        logger.warning("profile refresh failed (non-blocking): %s", exc)
