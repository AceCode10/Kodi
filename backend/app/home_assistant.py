"""Thin Home Assistant REST client used by the call_home_assistant server tool."""
from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from .config import get_settings

logger = logging.getLogger(__name__)


def call_home_assistant(operation: str, **kwargs: Any) -> str:
    settings = get_settings()
    base = settings.home_assistant_url.rstrip("/")
    token = settings.home_assistant_token
    if not base or not token:
        return "Home Assistant is not configured on this backend."

    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }

    try:
        if operation == "get_state":
            entity_id = kwargs.get("entity_id") or ""
            if not entity_id:
                return "entity_id is required."
            r = httpx.get(f"{base}/api/states/{entity_id}", headers=headers, timeout=8.0)
            if r.status_code == 404:
                return f"Entity {entity_id} not found."
            r.raise_for_status()
            data = r.json()
            return f"{entity_id} = {data.get('state')} (attrs: {json.dumps(data.get('attributes', {}))[:300]})"

        if operation == "call_service":
            domain = kwargs.get("domain") or ""
            service = kwargs.get("service") or ""
            payload = kwargs.get("service_data") or {}
            if not domain or not service:
                return "domain and service are required."
            if not isinstance(payload, dict):
                return "service_data must be an object."
            r = httpx.post(
                f"{base}/api/services/{domain}/{service}",
                headers=headers,
                json=payload,
                timeout=10.0,
            )
            r.raise_for_status()
            changed = r.json() if r.content else []
            return f"OK. Affected entities: {[e.get('entity_id') for e in changed][:8] or 'none reported'}"

        return f"Unknown operation '{operation}'. Use get_state or call_service."
    except httpx.HTTPStatusError as exc:
        logger.warning("HA HTTP %s: %s", exc.response.status_code, exc.response.text[:200])
        return f"Home Assistant returned HTTP {exc.response.status_code}."
    except httpx.RequestError as exc:
        logger.warning("HA request error: %s", exc)
        return f"Could not reach Home Assistant: {exc!s}"
