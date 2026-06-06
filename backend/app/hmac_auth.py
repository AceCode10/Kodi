import hashlib
import hmac
import time
from typing import Annotated

from fastapi import Header, HTTPException, Request, status

from .config import get_settings
from .devices_store import get_secret


class HmacError(Exception):
    """Raised by verify_hmac_headers when authentication fails (no FastAPI dependency)."""


def _sign(secret: str, timestamp: str, method: str, path: str, body_sha256_hex: str) -> str:
    canonical = f"{timestamp}\n{method.upper()}\n{path}\n{body_sha256_hex}"
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def verify_hmac_headers(
    *,
    device_id: str | None,
    timestamp: str | None,
    signature: str | None,
    method: str,
    path: str,
    body: bytes = b"",
) -> str:
    """Verify the Kodi HMAC over a request/handshake. Returns device_id or raises HmacError.

    Transport-agnostic core shared by the HTTP dependency and the /v1/live WebSocket
    handshake (which signs `ts\\nGET\\n/v1/live\\n<sha256("")>`).
    """
    if not device_id or not timestamp or not signature:
        raise HmacError("Missing Kodi auth headers")
    try:
        ts = int(timestamp)
    except ValueError as exc:
        raise HmacError("Invalid timestamp") from exc
    if abs(int(time.time()) - ts) > 300:
        raise HmacError("Timestamp skew too large")

    settings = get_settings()
    secret = get_secret(settings.devices_store_path, device_id, settings.kodi_master_secret)
    if not secret:
        raise HmacError("Unknown device")

    body_hash = hashlib.sha256(body).hexdigest()
    expected = _sign(secret, timestamp, method, path, body_hash)
    if not hmac.compare_digest(expected, signature):
        raise HmacError("Invalid signature")

    return device_id


async def verify_request_hmac(
    request: Request,
    x_kodi_device_id: Annotated[str | None, Header(alias="X-Kodi-Device-Id")] = None,
    x_kodi_timestamp: Annotated[str | None, Header(alias="X-Kodi-Timestamp")] = None,
    x_kodi_signature: Annotated[str | None, Header(alias="X-Kodi-Signature")] = None,
) -> str:
    body = await request.body()
    try:
        return verify_hmac_headers(
            device_id=x_kodi_device_id,
            timestamp=x_kodi_timestamp,
            signature=x_kodi_signature,
            method=request.method,
            path=request.url.path,
            body=body,
        )
    except HmacError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc
