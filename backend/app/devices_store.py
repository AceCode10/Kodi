import json
import secrets
import threading
from pathlib import Path
from typing import Any

_lock = threading.Lock()


def _load(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _save(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def register_device(store_path: Path) -> tuple[str, str]:
    """Returns (device_id, plaintext_secret). Secret is shown once."""
    device_id = secrets.token_urlsafe(16)
    secret = secrets.token_urlsafe(32)
    with _lock:
        data = _load(store_path)
        data[device_id] = {"secret": secret}
        _save(store_path, data)
    return device_id, secret


def get_secret(store_path: Path, device_id: str) -> str | None:
    with _lock:
        row = _load(store_path).get(device_id)
    if not row:
        return None
    return row.get("secret")


def device_exists(store_path: Path, device_id: str) -> bool:
    with _lock:
        return device_id in _load(store_path)
