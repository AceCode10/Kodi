"""Tests for the case harness.

These use the scripted transport, so the model's decisions are fixed and what is under
test is the harness: does it wire the case's world, memory and context in, does it loop
tool calls correctly, and does it surface what the device was made to do.
"""
import pytest

from evals.cassettes import scripted, text_response, tool_response
from evals.harness import client_context_block, run_case, world_from_case


@pytest.fixture(autouse=True)
def _anthropic_key(monkeypatch):
    # start_command and the agent loop bail out early without a key; the scripted
    # transport never uses it.
    monkeypatch.setenv("ANTHROPIC_API_KEY", "eval-key")
    from app import config

    config.get_settings.cache_clear()
    yield
    config.get_settings.cache_clear()


BASE_CASE = {
    "id": "t",
    "utterance": "text Amara that I'm running late",
    "world": {
        "contacts": {"Amara": "+260971111111"},
        "installed": ["com.whatsapp"],
    },
}


def test_world_is_built_from_the_case():
    world = world_from_case(
        {
            "world": {
                "contacts": {"Amara": "+260971111111"},
                "installed": ["com.whatsapp"],
                "granted": ["contacts"],
                "screens": {"com.whatsapp": {"text": "Chats", "tappable": ["Search"], "editable": True}},
            }
        }
    )
    assert world.contacts == {"Amara": "+260971111111"}
    assert world.granted == {"contacts"}
    assert world.screens["com.whatsapp"].editable is True
    # allowed_packages defaults to installed so a case need not repeat itself.
    assert world.allowed_packages == {"com.whatsapp"}


def test_context_block_uses_the_real_header_decoder():
    block = client_context_block(
        {"context": {"time_local": "2026-09-12T08:30:00+02:00", "day_of_week": "Saturday"}}
    )
    assert "time: 2026-09-12T08:30:00+02:00" in block
    assert "day of week: Saturday" in block


def test_empty_context_is_empty():
    assert client_context_block({}) == ""


def test_tool_call_then_reply_produces_an_outbox():
    script = [
        tool_response("send_whatsapp_message", {"contact": "Amara", "message": "running late"}),
        text_response("Sent."),
    ]
    with scripted(script):
        result = run_case(BASE_CASE)

    assert result.ok, result.error
    assert result.reply == "Sent."
    assert result.steps == 2
    (entry,) = result.outbound()
    assert entry.kind == "message"
    assert entry.target == "Amara"
    assert entry.body == "running late"


def test_a_reply_with_no_tools_leaves_the_outbox_empty():
    with scripted([text_response("It's 8:30.")]):
        result = run_case({"id": "t", "utterance": "what time is it"})
    assert result.ok
    assert result.outbox == []
    assert result.steps == 1


def test_device_failure_reaches_the_agent_and_is_traced():
    """The tool result the model sees must carry the device's real error."""
    script = [
        tool_response("send_sms", {"contact": "Mutale", "message": "hi"}),
        text_response("I couldn't find Mutale in your contacts."),
    ]
    with scripted(script):
        result = run_case(BASE_CASE)

    assert result.ok
    assert result.outbound() == []
    assert result.trace.had_error is True
    assert result.trace.tools[0].is_error is True


def test_server_tools_are_scripted_not_networked():
    case = {
        **BASE_CASE,
        "server_tools": {"search_web": "Lusaka: 27C and sunny."},
    }
    script = [
        tool_response("search_web", {"query": "weather Lusaka"}),
        text_response("It's 27 and sunny."),
    ]
    with scripted(script):
        result = run_case(case)

    assert result.ok
    assert result.server_calls[0][0] == "search_web"
    assert "27 and sunny" in result.reply


def test_unscripted_server_tool_is_inert_rather_than_networked():
    script = [
        tool_response("search_web", {"query": "anything"}),
        text_response("I couldn't look that up."),
    ]
    with scripted(script):
        result = run_case(BASE_CASE)
    assert result.ok
    assert result.server_calls[0][0] == "search_web"


def test_multi_step_flow_accumulates_tool_calls():
    case = {
        "id": "multi",
        "utterance": "open spotify and play something",
        "world": {
            "installed": ["com.spotify.music"],
            "screens": {"com.spotify.music": {"text": "Home", "tappable": ["Search"]}},
        },
    }
    script = [
        tool_response("open_app", {"app_name": "spotify"}, tool_use_id="tu_1"),
        tool_response("play_media", {"query": "afrobeats"}, tool_use_id="tu_2"),
        text_response("Playing afrobeats."),
    ]
    with scripted(script):
        result = run_case(case)

    assert result.ok
    assert [name for name, _ in result.tool_calls] == ["open_app", "play_media"]
    assert {e.kind for e in result.outbox} == {"app_open", "media"}


def test_runaway_tool_loop_is_stopped_by_the_agent_budget():
    """A model that keeps calling tools must be stopped, and by the agent's own budget.

    MAX_STEPS in the harness is only a backstop; if it is what fires, the agent's
    MAX_TOOLS_PER_COMMAND is not doing its job.
    """
    from app.config import get_settings

    script = [tool_response("scroll", {"direction": "down"}, tool_use_id=f"tu_{i}") for i in range(30)]
    with scripted(script):
        result = run_case({"id": "loop", "utterance": "scroll forever"})

    assert "tool limit" in result.reply.lower(), result.reply
    assert result.steps <= get_settings().max_tools_per_command + 1
    assert not result.error


def test_memory_block_reaches_the_model():
    """The mechanism-is-wired check: seeded memory must actually be in the request."""
    seen = {}

    script = [text_response("Your sister is Amara.")]
    with scripted(script) as client:
        original = client.messages.stream

        def capture(**kwargs):
            # claude_messages is mutated after the call (the reply is appended to the
            # same list), so snapshot rather than alias it.
            seen["messages"] = [dict(m) for m in kwargs["messages"]]
            return original(**kwargs)

        client.messages.stream = capture
        run_case({**BASE_CASE, "memory": "- the user's sister is Amara"})

    body = seen["messages"][-1]["content"]
    assert "the user's sister is Amara" in body
    assert "[Context from memory]" in body
