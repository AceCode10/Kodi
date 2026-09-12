"""Tests for cassette identity.

What invalidates a cassette is the whole point of the design, so these tests pin it:
tuning changes must replay clean, behaviour changes must not.
"""
import json

import pytest

from evals.cassettes import (
    Cassette,
    CassetteMismatch,
    CassetteMissing,
    request_key,
)

SYSTEM = "You are Kodi."
TOOLS = [{"name": "send_sms"}, {"name": "make_phone_call"}]
MESSAGES = [{"role": "user", "content": "text Amara running late"}]


def test_same_request_is_the_same_key():
    a = request_key(system=SYSTEM, tools=TOOLS, messages=MESSAGES)
    b = request_key(system=SYSTEM, tools=list(reversed(TOOLS)), messages=MESSAGES)
    assert a == b, "tool declaration order is not semantic"


def test_cache_control_does_not_change_the_key():
    """Phase 2 turns on prompt caching; that must not invalidate every cassette."""
    cached = [
        {
            "role": "user",
            "content": [{"type": "text", "text": "hi", "cache_control": {"type": "ephemeral"}}],
        }
    ]
    plain = [{"role": "user", "content": [{"type": "text", "text": "hi"}]}]
    assert request_key(system=SYSTEM, tools=TOOLS, messages=cached) == request_key(
        system=SYSTEM, tools=TOOLS, messages=plain
    )


def test_system_as_blocks_matches_system_as_string():
    blocks = [{"type": "text", "text": SYSTEM}]
    assert request_key(system=blocks, tools=TOOLS, messages=MESSAGES) == request_key(
        system=SYSTEM, tools=TOOLS, messages=MESSAGES
    )


def test_different_conversation_is_a_different_key():
    other = [{"role": "user", "content": "call Amara"}]
    assert request_key(system=SYSTEM, tools=TOOLS, messages=other) != request_key(
        system=SYSTEM, tools=TOOLS, messages=MESSAGES
    )


def test_edited_system_prompt_is_a_different_key():
    assert request_key(system=SYSTEM + " Be brief.", tools=TOOLS, messages=MESSAGES) != request_key(
        system=SYSTEM, tools=TOOLS, messages=MESSAGES
    )


def test_added_tool_is_a_different_key():
    assert request_key(system=SYSTEM, tools=TOOLS + [{"name": "new"}], messages=MESSAGES) != request_key(
        system=SYSTEM, tools=TOOLS, messages=MESSAGES
    )


# -- playback ----------------------------------------------------------------------


def _write(tmp_path, case_id, interactions):
    path = tmp_path / f"{case_id}.json"
    path.write_text(json.dumps({"case_id": case_id, "interactions": interactions}), encoding="utf-8")
    return path


def test_missing_cassette_says_how_to_record(tmp_path):
    with pytest.raises(CassetteMissing) as exc:
        Cassette.load("nope", tmp_path)
    assert "--record" in str(exc.value)


def test_playback_returns_interactions_in_order(tmp_path):
    key = request_key(system=SYSTEM, tools=TOOLS, messages=MESSAGES)
    _write(tmp_path, "c", [{"request_key": key, "content": [{"type": "text", "text": "ok"}]}])
    cassette = Cassette.load("c", tmp_path)
    interaction = cassette.next_for(key, preview={})
    assert interaction["content"][0]["text"] == "ok"
    assert cassette.exhausted()


def test_extra_model_call_is_reported_as_a_behaviour_change(tmp_path):
    key = request_key(system=SYSTEM, tools=TOOLS, messages=MESSAGES)
    _write(tmp_path, "c", [{"request_key": key, "content": []}])
    cassette = Cassette.load("c", tmp_path)
    cassette.next_for(key, preview={})
    with pytest.raises(CassetteMismatch) as exc:
        cassette.next_for(key, preview={"n_messages": 3, "last_message": "x"})
    assert "more steps than when this was recorded" in str(exc.value)


def test_diverged_conversation_names_both_sides(tmp_path):
    recorded_key = request_key(system=SYSTEM, tools=TOOLS, messages=MESSAGES)
    _write(
        tmp_path,
        "c",
        [{"request_key": recorded_key, "preview": {"n_messages": 1, "last_message": "text Amara"}, "content": []}],
    )
    cassette = Cassette.load("c", tmp_path)
    with pytest.raises(CassetteMismatch) as exc:
        cassette.next_for("deadbeef", preview={"n_messages": 1, "last_message": "call Amara"})
    message = str(exc.value)
    assert "recorded:" in message and "now:" in message
    assert "text Amara" in message and "call Amara" in message


def test_unused_counts_what_the_agent_skipped(tmp_path):
    key = request_key(system=SYSTEM, tools=TOOLS, messages=MESSAGES)
    _write(tmp_path, "c", [{"request_key": key, "content": []}, {"request_key": key, "content": []}])
    cassette = Cassette.load("c", tmp_path)
    cassette.next_for(key, preview={})
    assert cassette.unused() == 1
