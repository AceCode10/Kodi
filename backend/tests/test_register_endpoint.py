"""Endpoint tests for device registration.

Registration hands out a working credential, so the interesting cases are the ones
that must be refused. `app.main` resolves the effective setup token at import time,
so each test reloads the module against a temp data dir.
"""
import importlib

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client_factory(tmp_path, monkeypatch):
    created = []

    def _make(**env: str) -> TestClient:
        monkeypatch.setenv("DATA_DIR", str(tmp_path))
        monkeypatch.setenv("DEVICES_STORE_PATH", str(tmp_path / "devices.json"))
        monkeypatch.setenv("SEARCH_CACHE_PATH", str(tmp_path / "search.db"))
        monkeypatch.setenv("KODI_SETUP_TOKEN", "")
        monkeypatch.setenv("KODI_MASTER_SECRET", "")
        monkeypatch.setenv("HOME_ASSISTANT_AUTO_GRANT", "false")
        for k, v in env.items():
            monkeypatch.setenv(k, v)

        from app import config

        config.get_settings.cache_clear()
        import app.main as main

        importlib.reload(main)
        created.append(main)
        return TestClient(main.app)

    yield _make

    from app import config

    config.get_settings.cache_clear()


def test_register_rejects_missing_token(client_factory):
    client = client_factory(KODI_SETUP_TOKEN="s3cret")
    assert client.post("/v1/devices/register").status_code == 403


def test_register_rejects_wrong_token(client_factory):
    client = client_factory(KODI_SETUP_TOKEN="s3cret")
    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": "nope"})
    assert r.status_code == 403


def test_register_accepts_correct_token(client_factory):
    client = client_factory(KODI_SETUP_TOKEN="s3cret")
    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": "s3cret"})
    assert r.status_code == 200
    body = r.json()
    assert body["device_id"]
    assert len(body["api_secret"]) > 20


def test_register_closed_even_when_token_unconfigured(client_factory):
    """An unset KODI_SETUP_TOKEN must generate one, not disable the check."""
    client = client_factory()
    assert client.post("/v1/devices/register").status_code == 403

    import app.main as main

    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": main._SETUP_TOKEN})
    assert r.status_code == 200


def test_registered_device_has_no_ha_grant_by_default(client_factory, tmp_path):
    client = client_factory(KODI_SETUP_TOKEN="s3cret")
    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": "s3cret"})
    device_id = r.json()["device_id"]

    from app.devices_store import GRANT_HOME_ASSISTANT, has_grant

    assert not has_grant(tmp_path / "devices.json", device_id, GRANT_HOME_ASSISTANT)


def test_ha_auto_grant_opts_the_device_in(client_factory, tmp_path):
    client = client_factory(KODI_SETUP_TOKEN="s3cret", HOME_ASSISTANT_AUTO_GRANT="true")
    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": "s3cret"})
    device_id = r.json()["device_id"]

    from app.devices_store import GRANT_HOME_ASSISTANT, has_grant

    assert has_grant(tmp_path / "devices.json", device_id, GRANT_HOME_ASSISTANT)


def test_health_stays_unauthenticated(client_factory):
    client = client_factory(KODI_SETUP_TOKEN="s3cret")
    r = client.get("/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
