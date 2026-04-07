import base64
import hashlib
import json
import secrets
import threading
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet

_lock = threading.Lock()


def _fernet(master_secret: str) -> Fernet | None:
    if not (master_secret or "").strip():
        return None
    key = base64.urlsafe_b64encode(hashlib.sha256(master_secret.encode("utf-8")).digest())
    return Fernet(key)


def _load(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _save(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def register_device(store_path: Path, master_secret: str) -> tuple[str, str]:
    """Returns (device_id, plaintext_secret). Plain secret is shown once to the client only."""
    device_id = secrets.token_urlsafe(16)
    secret = secrets.token_urlsafe(32)
    f = _fernet(master_secret)
    with _lock:
        data = _load(store_path)
        if f is not None:
            data[device_id] = {"secret_enc": f.encrypt(secret.encode("utf-8")).decode("ascii")}
        else:
            data[device_id] = {"secret": secret}
        _save(store_path, data)
    return device_id, secret


def get_secret(store_path: Path, device_id: str, master_secret: str) -> str | None:
    f = _fernet(master_secret)
    with _lock:
        row = _load(store_path).get(device_id)
    if not row:
        return None
    if f is not None and "secret_enc" in row:
        try:
            return f.decrypt(row["secret_enc"].encode("ascii")).decode("utf-8")
        except Exception:
            return None
    return row.get("secret")


def device_exists(store_path: Path, device_id: str) -> bool:
    with _lock:
        return device_id in _load(store_path)
