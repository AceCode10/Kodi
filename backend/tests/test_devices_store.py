import json
from pathlib import Path

from app.devices_store import get_secret, register_device


def test_register_and_get_plaintext_when_no_master(tmp_path: Path):
    store = tmp_path / "d.json"
    did, sec = register_device(store, "")
    assert did
    assert len(sec) > 10
    loaded = get_secret(store, did, "")
    assert loaded == sec
    data = json.loads(store.read_text())
    assert "secret" in data[did]


def test_register_encrypted_when_master_set(tmp_path: Path):
    store = tmp_path / "e.json"
    master = "my-master-encryption-string"
    did, sec = register_device(store, master)
    data = json.loads(store.read_text())
    assert "secret_enc" in data[did]
    assert get_secret(store, did, master) == sec
