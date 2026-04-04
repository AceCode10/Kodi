import hashlib
import json
import logging
import re
import sqlite3
import time

import httpx

from .config import get_settings

logger = logging.getLogger(__name__)

DEEP_INTENT = re.compile(r"\b(research|look into|tell me about)\b", re.IGNORECASE)

BRAVE_URL = "https://api.search.brave.com/res/v1/llm/context"


def allow_deep_search(transcript: str, llm_requested_deep: bool) -> bool:
    if not llm_requested_deep:
        return False
    return bool(DEEP_INTENT.search(transcript or ""))


def _ensure_cache_table(conn: sqlite3.Connection) -> None:
    conn.execute(
        "CREATE TABLE IF NOT EXISTS search_cache (k TEXT PRIMARY KEY, v TEXT, exp REAL NOT NULL)"
    )


def _cache_get(db_path, key: str) -> str | None:
    conn = sqlite3.connect(str(db_path))
    try:
        _ensure_cache_table(conn)
        cur = conn.execute("SELECT v, exp FROM search_cache WHERE k = ?", (key,))
        row = cur.fetchone()
        if not row:
            return None
        v, exp = row
        if time.time() > exp:
            conn.execute("DELETE FROM search_cache WHERE k = ?", (key,))
            conn.commit()
            return None
        return v
    finally:
        conn.close()


def _cache_set(db_path, key: str, value: str, ttl_sec: int = 300) -> None:
    conn = sqlite3.connect(str(db_path))
    try:
        _ensure_cache_table(conn)
        exp = time.time() + ttl_sec
        conn.execute(
            "INSERT OR REPLACE INTO search_cache (k, v, exp) VALUES (?, ?, ?)",
            (key, value, exp),
        )
        conn.commit()
    finally:
        conn.close()


def brave_llm_context(query: str, client: httpx.Client) -> str:
    settings = get_settings()
    if not settings.brave_api_key:
        return "Web search is not configured (missing BRAVE_API_KEY)."
    headers = {
        "Accept": "application/json",
        "X-Subscription-Token": settings.brave_api_key,
    }
    r = client.get(
        BRAVE_URL,
        params={"q": query, "country": "us", "search_lang": "en"},
        headers=headers,
        timeout=20.0,
    )
    r.raise_for_status()
    data = r.json()
    if isinstance(data, dict):
        for key in ("llm_context", "context", "text"):
            if key in data and data[key]:
                return str(data[key])[:12000]
        if "results" in data:
            parts = []
            for item in data["results"][:10]:
                if isinstance(item, dict):
                    parts.append(json.dumps(item)[:2000])
                else:
                    parts.append(str(item)[:2000])
            return "\n".join(parts)[:12000]
        return json.dumps(data)[:12000]
    return str(data)[:12000]


def jina_read_url(url: str, client: httpx.Client) -> str:
    jina = f"https://r.jina.ai/{url}"
    r = client.get(jina, timeout=30.0, headers={"Accept": "text/plain"})
    r.raise_for_status()
    return (r.text or "")[:8000]


def _extract_urls_from_brave_text(text: str, limit: int = 3) -> list[str]:
    urls = re.findall(r"https?://[^\s\"'<>]+", text)
    out: list[str] = []
    for u in urls:
        u = u.rstrip(").,]")
        if u not in out:
            out.append(u)
        if len(out) >= limit:
            break
    return out


def search_web(query: str, deep: bool, transcript: str) -> str:
    settings = get_settings()
    deep_ok = allow_deep_search(transcript, deep)
    mode = "deep" if deep_ok else "fast"
    cache_key = hashlib.sha256(f"{mode}:{query}".encode()).hexdigest()
    cached = _cache_get(settings.search_cache_path, cache_key)
    if cached:
        return cached

    with httpx.Client() as client:
        try:
            base = brave_llm_context(query, client)
        except Exception as exc:
            logger.warning("Brave search failed: %s", exc)
            return f"Search failed: {exc!s}."

        if not deep_ok:
            _cache_set(settings.search_cache_path, cache_key, base)
            return base

        urls = _extract_urls_from_brave_text(base, 3)
        chunks = [base[:6000]]
        for u in urls:
            try:
                md = jina_read_url(u, client)
                chunks.append(f"\n---\nURL: {u}\n{md}")
            except Exception as exc:
                logger.info("Jina fetch skip %s: %s", u, exc)
        merged = "\n".join(chunks)[:20000]
        _cache_set(settings.search_cache_path, cache_key, merged)
        return merged
