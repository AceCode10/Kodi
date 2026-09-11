import json
from pathlib import Path

from app.devices_store import (
    GRANT_HOME_ASSISTANT,
    ensure_setup_token,
    get_secret,
    has_grant,
    register_device,
    set_grant,
)


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


def test_grants_default_empty_and_fail_closed(tmp_path: Path):
    store = tmp_path / "g.json"
    did, _ = register_device(store, "")
    assert not has_grant(store, did, GRANT_HOME_ASSISTANT)
    # Unknown devices never hold a grant.
    assert not has_grant(store, "no-such-device", GRANT_HOME_ASSISTANT)


def test_grant_can_be_added_and_revoked(tmp_path: Path):
    store = tmp_path / "g.json"
    did, _ = register_device(store, "")
    assert set_grant(store, did, GRANT_HOME_ASSISTANT, True)
    assert has_grant(store, did, GRANT_HOME_ASSISTANT)
    assert set_grant(store, did, GRANT_HOME_ASSISTANT, False)
    assert not has_grant(store, did, GRANT_HOME_ASSISTANT)


def test_set_grant_on_unknown_device_returns_false(tmp_path: Path):
    store = tmp_path / "g.json"
    register_device(store, "")
    assert not set_grant(store, "ghost", GRANT_HOME_ASSISTANT, True)


def test_register_at_registration_time_grants(tmp_path: Path):
    store = tmp_path / "g.json"
    did, _ = register_device(store, "", grants=[GRANT_HOME_ASSISTANT])
    assert has_grant(store, did, GRANT_HOME_ASSISTANT)


def test_second_device_does_not_inherit_first_devices_grant(tmp_path: Path):
    store = tmp_path / "g.json"
    first, _ = register_device(store, "", grants=[GRANT_HOME_ASSISTANT])
    second, _ = register_device(store, "")
    assert has_grant(store, first, GRANT_HOME_ASSISTANT)
    assert not has_grant(store, second, GRANT_HOME_ASSISTANT)


def test_cache_reflects_external_rewrite(tmp_path: Path):
    """A cached read must not mask a change made by another writer."""
    store = tmp_path / "c.json"
    did, sec = register_device(store, "")
    assert get_secret(store, did, "") == sec
    data = json.loads(store.read_text())
    data[did]["secret"] = "rotated-by-someone-else"
    store.write_text(json.dumps(data, indent=2))
    assert get_secret(store, did, "") == "rotated-by-someone-else"


def test_no_temp_file_left_behind(tmp_path: Path):
    store = tmp_path / "t.json"
    register_device(store, "")
    register_device(store, "")
    assert [p.name for p in tmp_path.iterdir()] == ["t.json"]


def test_ensure_setup_token_is_stable_across_calls(tmp_path: Path):
    first = ensure_setup_token(tmp_path)
    assert len(first) > 20
    assert ensure_setup_token(tmp_path) == first
    assert (tmp_path / "setup_token.txt").read_text().strip() == first
