import hashlib
import hmac
import time
from typing import Annotated

from fastapi import Header, HTTPException, Request, status

from .config import get_settings
from .devices_store import get_secret


def _sign(secret: str, timestamp: str, method: str, path: str, body_sha256_hex: str) -> str:
    canonical = f"{timestamp}\n{method.upper()}\n{path}\n{body_sha256_hex}"
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()


async def verify_request_hmac(
    request: Request,
    x_kodi_device_id: Annotated[str | None, Header(alias="X-Kodi-Device-Id")] = None,
    x_kodi_timestamp: Annotated[str | None, Header(alias="X-Kodi-Timestamp")] = None,
    x_kodi_signature: Annotated[str | None, Header(alias="X-Kodi-Signature")] = None,
) -> str:
    if not x_kodi_device_id or not x_kodi_timestamp or not x_kodi_signature:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing Kodi auth headers")
    try:
        ts = int(x_kodi_timestamp)
    except ValueError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid timestamp") from exc
    if abs(int(time.time()) - ts) > 300:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Timestamp skew too large")

    settings = get_settings()
    secret = get_secret(settings.devices_store_path, x_kodi_device_id, settings.kodi_master_secret)
    if not secret:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Unknown device")

    body = await request.body()
    body_hash = hashlib.sha256(body).hexdigest()
    path = request.url.path
    expected = _sign(secret, x_kodi_timestamp, request.method, path, body_hash)
    if not hmac.compare_digest(expected, x_kodi_signature):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid signature")

    return x_kodi_device_id
