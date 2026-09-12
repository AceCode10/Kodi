"""End-to-end: synthesize a cassette, replay it, and confirm drift is detected.

This is the property the whole eval exists for. If replay cannot tell that the agent
behaved differently, it cannot serve as the Phase 2 regression proof.
"""
import pytest

from evals.cassettes import (
    CassetteMismatch,
    is_synthetic,
    replaying,
    synthesizing,
    text_response,
    tool_response,
)
from evals.harness import run_case


@pytest.fixture(autouse=True)
def _key(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "eval-key")
    from app import config

    config.get_settings.cache_clear()
    yield
    config.get_settings.cache_clear()


CASE = {
    "id": "roundtrip",
    "utterance": "text Amara that I'm running late",
    "world": {"contacts": {"Amara": "+260971111111"}, "installed": ["com.whatsapp"]},
}

SCRIPT = [
    tool_response("send_whatsapp_message", {"contact": "Amara", "message": "Running late"}),
    text_response("Told Amara."),
]


def test_synthesize_then_replay_reproduces_the_same_end_state(tmp_path):
    with synthesizing("roundtrip", SCRIPT, tmp_path):
        recorded = run_case(CASE)
    assert recorded.ok, recorded.error

    with replaying("roundtrip", tmp_path):
        replayed = run_case(CASE)

    assert replayed.ok, replayed.error
    assert replayed.reply == recorded.reply
    assert [e.as_dict() for e in replayed.outbox] == [e.as_dict() for e in recorded.outbox]


def test_one_client_serves_the_whole_case(tmp_path):
    """Regression: a fresh client per step replayed response #1 forever.

    The agent builds a new Anthropic() on every step, so the transport must hold the
    script across steps or the agent repeats its first action until the tool budget
    stops it - which looks like a passing send plus nine unexpected ones.
    """
    with synthesizing("once", SCRIPT, tmp_path):
        result = run_case(CASE)

    assert len(result.outbound()) == 1, [e.as_dict() for e in result.outbox]
    assert result.steps == 2


def test_a_changed_utterance_fails_replay(tmp_path):
    """Different input reaches the model, so the cassette must refuse it."""
    with synthesizing("drift", SCRIPT, tmp_path):
        run_case(CASE)

    changed = {**CASE, "utterance": "call Amara instead"}
    with replaying("drift", tmp_path):
        result = run_case(changed)

    assert not result.ok
    assert "does not match the recording" in result.error


def test_a_changed_system_prompt_fails_replay(tmp_path, monkeypatch):
    """An accidental prompt edit during a refactor must not pass silently."""
    with synthesizing("prompt", SCRIPT, tmp_path):
        run_case(CASE)

    from app import agent_loop, config

    monkeypatch.setattr(agent_loop, "KODI_SYSTEM_PROMPT", config.KODI_SYSTEM_PROMPT + "\nBe terse.")
    with replaying("prompt", tmp_path):
        result = run_case(CASE)

    assert not result.ok
    assert "does not match the recording" in result.error


def test_a_changed_device_result_fails_replay(tmp_path):
    """The tool result feeds the next request, so a device change is caught too."""
    with synthesizing("device", SCRIPT, tmp_path):
        run_case(CASE)

    # WhatsApp is now missing, so the tool fails and the second request differs.
    changed = {**CASE, "world": {**CASE["world"], "installed": []}}
    with replaying("device", tmp_path):
        result = run_case(changed)

    assert not result.ok
    assert "does not match the recording" in result.error


def test_synthetic_cassettes_are_labelled(tmp_path):
    with synthesizing("labelled", SCRIPT, tmp_path):
        run_case(CASE)
    assert is_synthetic("labelled", tmp_path) is True


def test_shipped_scaffolding_cassettes_are_all_synthetic():
    """Nothing hand-written may masquerade as a recording of the real model."""
    from evals.cassettes import CASSETTE_DIR
    from evals.runner import load_cases

    for case in load_cases():
        assert is_synthetic(case["id"], CASSETTE_DIR), (
            f"{case['id']} has a cassette that is not marked synthetic - if it was "
            f"recorded against the real API, move it out of the scaffolding set."
        )
