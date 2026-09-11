"""End-to-end turn: HMAC-signed request -> SSE stream -> trace written.

Exercises the real auth dependency, session lookup, event stream and trace wiring.
Only the model call itself is substituted.
"""
import hashlib
import hmac
import importlib
import json
import time

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def app_env(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("DEVICES_STORE_PATH", str(tmp_path / "devices.json"))
    monkeypatch.setenv("SEARCH_CACHE_PATH", str(tmp_path / "search.db"))
    monkeypatch.setenv("KODI_SETUP_TOKEN", "tok")
    monkeypatch.setenv("KODI_MASTER_SECRET", "")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    monkeypatch.setenv("TRACE_INCLUDE_TEXT", "true")

    from app import config

    config.get_settings.cache_clear()
    import app.main as main

    importlib.reload(main)
    yield main, tmp_path
    config.get_settings.cache_clear()


def _sign(secret: str, method: str, path: str, body: bytes) -> dict[str, str]:
    ts = str(int(time.time()))
    canonical = f"{ts}\n{method.upper()}\n{path}\n{hashlib.sha256(body).hexdigest()}"
    sig = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {"X-Kodi-Timestamp": ts, "X-Kodi-Signature": sig}


def _pair(client: TestClient) -> tuple[str, str]:
    r = client.post("/v1/devices/register", headers={"X-Kodi-Setup-Token": "tok"})
    assert r.status_code == 200
    return r.json()["device_id"], r.json()["api_secret"]


def _open_session(client: TestClient, device_id: str, secret: str) -> str:
    body = b"{}"
    headers = {"X-Kodi-Device-Id": device_id, **_sign(secret, "POST", "/v1/sessions", body)}
    r = client.post("/v1/sessions", content=body, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()["session_id"]


def _say(client: TestClient, device_id: str, secret: str, session_id: str, text: str):
    path = f"/v1/sessions/{session_id}/text"
    body = json.dumps({"text": text}).encode()
    headers = {
        "X-Kodi-Device-Id": device_id,
        "Content-Type": "application/json",
        **_sign(secret, "POST", path, body),
    }
    return client.post(path, content=body, headers=headers)


def _traces(tmp_path) -> list[dict]:
    files = list((tmp_path / "traces").glob("*.jsonl"))
    if not files:
        return []
    return [json.loads(line) for f in files for line in f.read_text().splitlines()]


def test_completed_turn_streams_and_writes_a_trace(app_env, monkeypatch):
    main, tmp_path = app_env

    def fake_stream(state):
        yield {"type": "delta", "text": "Calling her now."}
        yield {"type": "done", "assistant_text": "Calling her now."}

    monkeypatch.setattr(main.agent_loop, "run_agent_step_streaming", fake_stream)

    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)
        r = _say(client, device_id, secret, session_id, "call my sister")

    assert r.status_code == 200
    assert "Calling her now." in r.text
    assert "event: done" in r.text

    (trace,) = _traces(tmp_path)
    assert trace["event"] == "turn"
    assert trace["device_id"] == device_id
    assert trace["transcript"] == "call my sister"
    assert trace["assistant_text"] == "Calling her now."
    assert "total_ms" in trace["stages"]


def test_device_action_does_not_close_the_turn(app_env, monkeypatch):
    """A trace covers the whole turn, so a mid-flight device action must not end it."""
    main, tmp_path = app_env

    def fake_stream(state):
        yield {
            "type": "device_action",
            "tool_use_id": "tu_1",
            "tool_name": "make_phone_call",
            "tool_input": {"contact": "sister"},
        }

    monkeypatch.setattr(main.agent_loop, "run_agent_step_streaming", fake_stream)

    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)
        r = _say(client, device_id, secret, session_id, "call my sister")

    assert "event: device_action" in r.text
    assert _traces(tmp_path) == []


def test_unsigned_request_is_rejected(app_env):
    main, _ = app_env
    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)
        r = client.post(
            f"/v1/sessions/{session_id}/text",
            json={"text": "hello"},
            headers={"X-Kodi-Device-Id": device_id},
        )
    assert r.status_code == 401


def test_session_belonging_to_another_device_is_refused(app_env):
    main, _ = app_env
    with TestClient(main.app) as client:
        first_id, first_secret = _pair(client)
        session_id = _open_session(client, first_id, first_secret)
        other_id, other_secret = _pair(client)
        r = _say(client, other_id, other_secret, session_id, "hello")
    assert r.status_code == 403


def test_forget_command_short_circuits_before_the_model(app_env, monkeypatch):
    main, tmp_path = app_env
    called = []
    monkeypatch.setattr(
        main.agent_loop, "run_agent_step_streaming", lambda st: called.append(1) or iter(())
    )
    monkeypatch.setattr(main, "memory_forget_last", lambda uid: "Forgot the last memory.")

    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)
        r = _say(client, device_id, secret, session_id, "forget that")

    assert "Forgot the last memory." in r.text
    assert called == []
    (trace,) = _traces(tmp_path)
    assert trace["assistant_text"] == "Forgot the last memory."


def _tool_result(client, device_id, secret, session_id, tool_use_id, content, is_error=False):
    path = f"/v1/sessions/{session_id}/tool-result"
    body = json.dumps(
        {"toolUseId": tool_use_id, "content": content, "isError": is_error}
    ).encode()
    headers = {
        "X-Kodi-Device-Id": device_id,
        "Content-Type": "application/json",
        **_sign(secret, "POST", path, body),
    }
    return client.post(path, content=body, headers=headers)


def test_full_round_trip_records_the_device_tool_on_one_trace(app_env, monkeypatch):
    """A turn spanning three requests produces exactly one trace, with the tool on it."""
    main, tmp_path = app_env

    calls = {"n": 0}

    def fake_stream(state):
        calls["n"] += 1
        if calls["n"] == 1:
            state.pending_device_tool = {"tool_use_id": "tu_1", "name": "send_sms", "input": {}}
            state.pending_device_since = time.time()
            yield {
                "type": "device_action",
                "tool_use_id": "tu_1",
                "tool_name": "send_sms",
                "tool_input": {"contact": "Amara", "message": "running late"},
            }
        else:
            yield {"type": "done", "assistant_text": "Sent."}

    monkeypatch.setattr(main.agent_loop, "run_agent_step_streaming", fake_stream)
    monkeypatch.setattr(main.agent_loop, "apply_tool_result", lambda *a, **k: None)

    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)

        first = _say(client, device_id, secret, session_id, "text Amara running late")
        assert "event: device_action" in first.text
        assert _traces(tmp_path) == []

        second = _tool_result(client, device_id, secret, session_id, "tu_1", "SMS sent to Amara.")
        assert "event: done" in second.text

    (trace,) = _traces(tmp_path)
    assert trace["transcript"] == "text Amara running late"
    assert trace["assistant_text"] == "Sent."
    assert [(t["name"], t["kind"], t["is_error"]) for t in trace["tools"]] == [
        ("send_sms", "device", False)
    ]
    assert trace["had_error"] is False


def test_a_failed_device_tool_marks_the_trace(app_env, monkeypatch):
    """The whole point of the isError plumbing: a failure has to reach the trace."""
    main, tmp_path = app_env

    calls = {"n": 0}

    def fake_stream(state):
        calls["n"] += 1
        if calls["n"] == 1:
            state.pending_device_tool = {"tool_use_id": "tu_1", "name": "send_sms", "input": {}}
            state.pending_device_since = time.time()
            yield {
                "type": "device_action",
                "tool_use_id": "tu_1",
                "tool_name": "send_sms",
                "tool_input": {},
            }
        else:
            yield {"type": "done", "assistant_text": "I couldn't send that."}

    monkeypatch.setattr(main.agent_loop, "run_agent_step_streaming", fake_stream)
    monkeypatch.setattr(main.agent_loop, "apply_tool_result", lambda *a, **k: None)

    with TestClient(main.app) as client:
        device_id, secret = _pair(client)
        session_id = _open_session(client, device_id, secret)
        _say(client, device_id, secret, session_id, "text Amara")
        _tool_result(
            client, device_id, secret, session_id, "tu_1",
            "SMS permission not granted.", is_error=True,
        )

    (trace,) = _traces(tmp_path)
    assert trace["had_error"] is True
    assert trace["tools"][0]["is_error"] is True
