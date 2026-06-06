"""Tests for tool registry consistency and the multi-turn history compactor."""
from app.agent_loop import _compact_history
from app.claude_tools import DEVICE_TOOL_NAMES, SERVER_TOOL_NAMES, TOOLS


def test_new_tools_registered():
    names = {t["name"] for t in TOOLS}
    for new in (
        "tap_on_screen",
        "type_into_field",
        "schedule_task",
        "list_scheduled_tasks",
        "cancel_scheduled_task",
        "call_home_assistant",
        "record_lesson",
    ):
        assert new in names, f"{new} missing from TOOLS"


def test_every_tool_is_routed():
    """Each declared tool must be classified as either a device or server tool."""
    routed = DEVICE_TOOL_NAMES | SERVER_TOOL_NAMES
    for t in TOOLS:
        assert t["name"] in routed, f"{t['name']} not in DEVICE/SERVER name sets"


def test_device_and_server_sets_disjoint():
    assert not (DEVICE_TOOL_NAMES & SERVER_TOOL_NAMES)


def test_compact_history_strips_tool_blocks():
    messages = [
        {"role": "user", "content": "[Context from memory]\nx\n\nUser said: call mum"},
        {"role": "assistant", "content": [{"type": "tool_use", "id": "1", "name": "make_phone_call", "input": {}}]},
        {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "1", "content": "ok"}]},
        {"role": "assistant", "content": "Calling mum now."},
    ]
    out = _compact_history(messages, 6)
    assert out == [
        {"role": "user", "content": "call mum"},
        {"role": "assistant", "content": "Calling mum now."},
    ]


def test_compact_history_trims_to_window():
    messages = []
    for i in range(10):
        messages.append({"role": "user", "content": f"User said: turn {i}"})
        messages.append({"role": "assistant", "content": f"reply {i}"})
    out = _compact_history(messages, 4)
    assert len(out) == 4
    assert out[0]["content"] == "turn 8"
    assert out[-1]["content"] == "reply 9"


def test_compact_history_empty_window():
    messages = [{"role": "user", "content": "User said: hi"}]
    assert _compact_history(messages, 0) == []
