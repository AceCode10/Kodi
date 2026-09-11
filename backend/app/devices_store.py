"""Device credential store.

Backed by a single JSON file. Reads are served from an mtime-validated cache so an
authenticated request does not re-read and re-parse the whole file, and writes go
through a temp file + atomic rename so a crash mid-write cannot corrupt every
device's credentials at once.

A device row carries its secret (Fernet-encrypted when KODI_MASTER_SECRET is set)
and a list of grants. Grants are capabilities that are NOT implied by merely holding
a valid device credential - currently just "home_assistant", which actuates hardware
in the user's home using server-wide credentials.
"""

import base64
import hashlib
import json
import os
import secrets
import threading
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet

_lock = threading.RLock()

# path -> (mtime_ns, size, parsed)
_cache: dict[str, tuple[int, int, dict[str, Any]]] = {}

GRANT_HOME_ASSISTANT = "home_assistant"


def _fernet(master_secret: str) -> Fernet | None:
    if not (master_secret or "").strip():
        return None
    key = base64.urlsafe_b64encode(hashlib.sha256(master_secret.encode("utf-8")).digest())
    return Fernet(key)


def _load(path: Path) -> dict[str, Any]:
    """Parse the store, reusing the cached copy while the file is unchanged."""
    key = str(path)
    try:
        st = path.stat()
    except FileNotFoundError:
        _cache.pop(key, None)
        return {}
    cached = _cache.get(key)
    if cached is not None and cached[0] == st.st_mtime_ns and cached[1] == st.st_size:
        return cached[2]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return {}
    if not isinstance(data, dict):
        return {}
    _cache[key] = (st.st_mtime_ns, st.st_size, data)
    return data


def _save(path: Path, data: dict[str, Any]) -> None:
    """Write via temp file + atomic rename so a crash cannot truncate the store."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    try:
        st = path.stat()
        _cache[str(path)] = (st.st_mtime_ns, st.st_size, data)
    except OSError:
        _cache.pop(str(path), None)


def register_device(
    store_path: Path,
    master_secret: str,
    grants: list[str] | None = None,
) -> tuple[str, str]:
    """Returns (device_id, plaintext_secret). Plain secret is shown once to the client only."""
    device_id = secrets.token_urlsafe(16)
    secret = secrets.token_urlsafe(32)
    f = _fernet(master_secret)
    row: dict[str, Any] = {"grants": list(grants or [])}
    if f is not None:
        row["secret_enc"] = f.encrypt(secret.encode("utf-8")).decode("ascii")
    else:
        row["secret"] = secret
    with _lock:
        data = dict(_load(store_path))
        data[device_id] = row
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


def has_grant(store_path: Path, device_id: str, grant: str) -> bool:
    """True only when the device row explicitly carries the grant. Fails closed."""
    with _lock:
        row = _load(store_path).get(device_id)
    if not isinstance(row, dict):
        return False
    return grant in (row.get("grants") or [])


def set_grant(store_path: Path, device_id: str, grant: str, enabled: bool) -> bool:
    """Add or remove a grant. Returns False when the device is unknown."""
    with _lock:
        data = dict(_load(store_path))
        row = data.get(device_id)
        if not isinstance(row, dict):
            return False
        row = dict(row)
        current = list(row.get("grants") or [])
        if enabled and grant not in current:
            current.append(grant)
        elif not enabled and grant in current:
            current.remove(grant)
        row["grants"] = current
        data[device_id] = row
        _save(store_path, data)
        return True


def ensure_setup_token(data_dir: Path) -> str:
    """Return the persisted registration token, generating one on first boot.

    Registration must never be open to anyone who finds the URL, so when no token is
    configured we mint and persist one rather than falling back to allowing everything.
    The caller logs it once so the operator can read it out of the container logs.
    """
    path = data_dir / "setup_token.txt"
    with _lock:
        if path.exists():
            existing = path.read_text(encoding="utf-8").strip()
            if existing:
                return existing
        token = secrets.token_urlsafe(24)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(token, encoding="utf-8")
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        return token
