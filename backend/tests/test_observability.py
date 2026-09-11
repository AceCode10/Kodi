"""The turn trace is the interface the eval runner reads, so its shape is tested."""
import json
import logging

import pytest

from app import config, observability
from app.observability import JsonFormatter, TurnTrace, new_turn, prune_traces


@pytest.fixture
def traced(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("DEVICES_STORE_PATH", str(tmp_path / "devices.json"))
    monkeypatch.setenv("SEARCH_CACHE_PATH", str(tmp_path / "search.db"))
    monkeypatch.setenv("TRACE_INCLUDE_TEXT", "false")
    config.get_settings.cache_clear()
    yield tmp_path
    config.get_settings.cache_clear()


def _written(tmp_path) -> list[dict]:
    files = list((tmp_path / "traces").glob("*.jsonl"))
    assert len(files) == 1, files
    return [json.loads(line) for line in files[0].read_text().splitlines()]


def test_trace_records_tools_usage_and_stages(traced):
    trace = new_turn("dev1", "text amara")
    trace.provider = "anthropic"
    trace.model = "claude-opus-5"
    trace.stages["stt_ms"] = 420
    trace.record_tool("get_contacts", "device", 130, is_error=False)
    trace.record_tool("send_whatsapp_message", "device", 2100, is_error=False)
    trace.assistant_text = "Sent."
    trace.finish()

    (record,) = _written(traced)
    assert record["event"] == "turn"
    assert record["device_id"] == "dev1"
    assert record["provider"] == "anthropic"
    assert record["model"] == "claude-opus-5"
    assert [t["name"] for t in record["tools"]] == ["get_contacts", "send_whatsapp_message"]
    assert record["tools"][0]["kind"] == "device"
    assert record["stages"]["stt_ms"] == 420
    assert "total_ms" in record["stages"]
    assert record["had_error"] is False


def test_a_failing_tool_marks_the_turn(traced):
    trace = new_turn("dev1")
    trace.record_tool("send_sms", "device", 90, is_error=True)
    trace.finish()
    (record,) = _written(traced)
    assert record["had_error"] is True
    assert record["tools"][0]["is_error"] is True


def test_text_is_withheld_unless_opted_in(traced, monkeypatch):
    trace = new_turn("dev1", "call my sister")
    trace.assistant_text = "Calling."
    trace.finish()
    (record,) = _written(traced)
    assert "transcript" not in record
    assert "assistant_text" not in record

    monkeypatch.setenv("TRACE_INCLUDE_TEXT", "true")
    config.get_settings.cache_clear()
    trace2 = new_turn("dev1", "call my sister")
    trace2.assistant_text = "Calling."
    trace2.finish()
    assert _written(traced)[-1]["transcript"] == "call my sister"


def test_usage_accumulates_across_model_calls(traced):
    class Usage:
        def __init__(self, i, o, cr):
            self.input_tokens = i
            self.output_tokens = o
            self.cache_read_input_tokens = cr

    trace = TurnTrace(turn_id="t", device_id="d")
    trace.record_usage(Usage(100, 20, 0))
    trace.record_usage(Usage(140, 35, 90))
    assert trace.usage == {
        "input_tokens": 240,
        "output_tokens": 55,
        "cache_read_input_tokens": 90,
    }


def test_record_usage_tolerates_unknown_shapes(traced):
    trace = TurnTrace(turn_id="t", device_id="d")
    trace.record_usage(None)
    trace.record_usage(object())
    assert trace.usage == {}


def test_stage_context_manager_accumulates(traced):
    trace = TurnTrace(turn_id="t", device_id="d")
    with trace.stage("llm"):
        pass
    with trace.stage("llm"):
        pass
    assert "llm_ms" in trace.stages


def test_trace_write_failure_never_breaks_the_turn(traced, monkeypatch):
    """Observability must never be able to fail a user's turn."""
    def boom(*a, **k):
        raise OSError("read-only filesystem")

    monkeypatch.setattr(observability.Path, "mkdir", boom)
    new_turn("dev1").finish()  # must not raise


def test_json_formatter_carries_context_and_extras():
    observability.request_id_var.set("req123")
    observability.turn_id_var.set("turn456")
    record = logging.LogRecord("kodi", logging.INFO, __file__, 1, "hello", None, None)
    record.custom = {"a": 1}
    payload = json.loads(JsonFormatter().format(record))
    assert payload["msg"] == "hello"
    assert payload["level"] == "INFO"
    assert payload["request_id"] == "req123"
    assert payload["turn_id"] == "turn456"
    assert payload["custom"] == {"a": 1}


def test_prune_traces_removes_old_files_only(traced, monkeypatch):
    import os
    import time

    directory = traced / "traces"
    directory.mkdir(parents=True)
    fresh = directory / "2026-09-11.jsonl"
    stale = directory / "2000-01-01.jsonl"
    fresh.write_text("{}\n")
    stale.write_text("{}\n")
    old = time.time() - 400 * 86400
    os.utime(stale, (old, old))

    assert prune_traces() == 1
    assert fresh.exists()
    assert not stale.exists()
