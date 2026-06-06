"""Tests for client-context decoding used by the agent + briefing endpoints."""
import base64
import json

from app.main import BriefingRequest, _decode_client_context


def _encode(d: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(d).encode("utf-8")).decode("ascii")


def test_decode_client_context_round_trip():
    payload = {
        "time_local": "2026-05-21T20:00:00+01:00",
        "timezone": "Europe/London",
        "day_of_week": "Thursday",
        "is_weekend": False,
        "network": "wifi",
        "battery_percent": 88,
        "latitude": 51.5,
        "longitude": -0.12,
    }
    block = _decode_client_context(_encode(payload))
    assert "time: 2026-05-21T20:00:00+01:00" in block
    assert "day of week: Thursday" in block
    assert "network: wifi" in block
    assert "latitude: 51.5" in block


def test_decode_client_context_handles_garbage():
    assert _decode_client_context(None) == ""
    assert _decode_client_context("") == ""
    assert _decode_client_context("not-base64!!!") == ""
    assert _decode_client_context(_encode({"unknown_key": 1})) == ""


def test_briefing_request_defaults_empty():
    assert BriefingRequest().context == ""
    assert BriefingRequest(context="abc").context == "abc"
