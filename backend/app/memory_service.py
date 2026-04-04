import logging
from typing import Any

from .config import get_settings

logger = logging.getLogger(__name__)

_memory: Any = None


def get_memory():
    global _memory
    if _memory is not None:
        return _memory
    settings = get_settings()
    try:
        from mem0 import Memory

        key = settings.openai_api_key
        config = {
            "vector_store": {
                "provider": "qdrant",
                "config": {
                    "collection_name": settings.qdrant_collection,
                    "host": settings.qdrant_host,
                    "port": settings.qdrant_port,
                    "embedding_model_dims": 1536,
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
        logger.info("Mem0 initialized with Qdrant at %s:%s", settings.qdrant_host, settings.qdrant_port)
    except Exception as exc:
        logger.warning("Mem0 unavailable (%s); memory features degraded", exc)
        _memory = False
    return _memory


def memory_search(user_id: str, query: str, limit: int = 5) -> str:
    mem = get_memory()
    if mem is False:
        return ""
    try:
        out = mem.search(query, user_id=user_id, limit=limit)
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


def memory_add_turn(user_id: str, user_text: str, assistant_text: str) -> None:
    mem = get_memory()
    if mem is False:
        return
    try:
        mem.add(
            [
                {"role": "user", "content": user_text},
                {"role": "assistant", "content": assistant_text},
            ],
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
        out = mem.search("", user_id=user_id, limit=1)
        results = out.get("results") or []
        if not results:
            return "Nothing to forget."
        mid = results[0].get("id")
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
