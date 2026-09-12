"""Drive one eval case to completion.

The harness owns every input the agent reads, so a case is reproducible: the memory
block, the user profile, the learned lessons, the client context, the device, and the
server tools. Nothing reaches the network except the model call, and in replay mode not
even that.

It drives the agent **in-process** rather than over HTTP. The HTTP path has its own
coverage in `tests/test_turn_flow.py`; what an eval needs is the agent's decisions, and
going in-process means one adapter function to repoint when Phase 2 replaces
`agent_loop` with the shared core.
"""
from __future__ import annotations

import base64
import json
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any

from app import agent_loop
from app.observability import TurnTrace, new_turn
from app.session_manager import sessions

from .fake_device import DeviceWorld, FakeDevice, OutboxEntry, Screen

# A case that keeps calling tools is a bug, not a long task. The agent's own budget
# (MAX_TOOLS_PER_COMMAND) should bite first; this is the backstop.
MAX_STEPS = 12


@dataclass
class CaseResult:
    case_id: str
    transcript: str
    reply: str = ""
    outbox: list[OutboxEntry] = field(default_factory=list)
    tool_calls: list[tuple[str, dict[str, Any]]] = field(default_factory=list)
    server_calls: list[tuple[str, dict[str, Any]]] = field(default_factory=list)
    # (tool name, what the device replied, whether it was an error) in call order.
    tool_results: list[tuple[str, str, bool]] = field(default_factory=list)
    error: str = ""
    steps: int = 0
    trace: TurnTrace | None = None

    @property
    def ok(self) -> bool:
        return not self.error

    def outbound(self) -> list[OutboxEntry]:
        from .fake_device import OUTBOUND_KINDS

        return [e for e in self.outbox if e.kind in OUTBOUND_KINDS]

    def usage(self) -> dict[str, int]:
        return dict(self.trace.usage) if self.trace else {}

    def model(self) -> str:
        return self.trace.model if self.trace else ""


def world_from_case(case: dict[str, Any]) -> DeviceWorld:
    spec = case.get("world") or {}
    screens = {
        pkg: Screen(
            package=pkg,
            text=s.get("text", ""),
            tappable=list(s.get("tappable") or []),
            editable=bool(s.get("editable")),
            injected=s.get("injected", ""),
        )
        for pkg, s in (spec.get("screens") or {}).items()
    }
    world = DeviceWorld(
        contacts=dict(spec.get("contacts") or {}),
        installed=set(spec.get("installed") or []),
        allowed_packages=set(spec.get("allowed_packages") or spec.get("installed") or []),
        screens=screens,
        notifications=list(spec.get("notifications") or []),
        last_messages=dict(spec.get("last_messages") or {}),
        scheduled_tasks=list(spec.get("scheduled_tasks") or []),
        foreground=spec.get("foreground", ""),
    )
    if "granted" in spec:
        world.granted = set(spec["granted"])
    if "accessibility_enabled" in spec:
        world.accessibility_enabled = bool(spec["accessibility_enabled"])
    return world


def client_context_block(case: dict[str, Any]) -> str:
    """Render the case's context exactly as the live path would.

    Reuses `main._decode_client_context` rather than duplicating its label mapping, so a
    change to the real header format shows up in evals instead of silently diverging.
    """
    context = case.get("context") or {}
    if not context:
        return ""
    from app.main import _decode_client_context

    encoded = base64.urlsafe_b64encode(json.dumps(context).encode("utf-8")).decode("ascii")
    return _decode_client_context(encoded)


@contextmanager
def _scripted_server_tools(case: dict[str, Any], sink: list[tuple[str, dict[str, Any]]]):
    """Serve server tools from the case instead of the network.

    `search_web` in particular must never reach Brave during an eval - both because it
    costs money and because injection cases need to control exactly what comes back.
    """
    scripted = dict(case.get("server_tools") or {})
    original = agent_loop._execute_server_tool

    def fake(name: str, tool_input: dict[str, Any], *, transcript: str, user_id: str) -> str:
        sink.append((name, dict(tool_input)))
        if name in scripted:
            return str(scripted[name])
        # Defaults chosen so an unscripted call is harmless but visible in the trace.
        if name == "remember":
            return "Stored."
        if name == "recall":
            return case.get("memory", "") or "No matching memories."
        if name == "record_lesson":
            return "Lesson recorded."
        if name == "search_web":
            return "Search is not configured for this eval case."
        return f"{name} is not available in this eval."

    agent_loop._execute_server_tool = fake  # type: ignore[assignment]
    try:
        yield
    finally:
        agent_loop._execute_server_tool = original  # type: ignore[assignment]


def run_case(case: dict[str, Any], *, device_id: str = "eval-device") -> CaseResult:
    """Run one case to `done`, returning what the device was made to do.

    Assumes the caller has already established the model transport (a cassette context,
    or the real client for a live run).
    """
    case_id = case.get("id", "unnamed")
    transcript = case["utterance"]
    device = FakeDevice(world_from_case(case))
    server_calls: list[tuple[str, dict[str, Any]]] = []

    session_id = sessions.create(device_id)
    state = sessions.require(session_id)
    trace = new_turn(device_id, transcript)
    state.trace = trace
    trace.provider = "anthropic"

    result = CaseResult(case_id=case_id, transcript=transcript, trace=trace)

    agent_loop.start_command(
        state,
        transcript,
        case.get("memory", "") or "",
        client_context=client_context_block(case),
        user_profile=case.get("profile", "") or "",
        lessons_block=case.get("lessons", "") or "",
    )

    with _scripted_server_tools(case, server_calls):
        for step in range(MAX_STEPS):
            result.steps = step + 1
            pending_action: dict[str, Any] | None = None
            try:
                for event in agent_loop.run_agent_step_streaming(state):
                    kind = event.get("type")
                    if kind == "device_action":
                        pending_action = event
                    elif kind == "done":
                        result.reply = event.get("assistant_text", "")
                    elif kind == "error":
                        result.error = event.get("message", "agent error")
            except Exception as exc:  # cassette mismatch and friends surface here
                result.error = f"{type(exc).__name__}: {exc}"
                break

            if result.error or pending_action is None:
                break

            started = time.monotonic()
            content, is_error = device.execute(
                pending_action["tool_name"], pending_action.get("tool_input") or {}
            )
            trace.record_tool(
                name=pending_action["tool_name"],
                kind="device",
                ms=int((time.monotonic() - started) * 1000),
                is_error=is_error,
            )
            result.tool_results.append((pending_action["tool_name"], content, is_error))
            agent_loop.apply_tool_result(
                state, pending_action["tool_use_id"], content, is_error
            )
        else:
            result.error = f"case did not finish within {MAX_STEPS} steps"

    result.outbox = list(device.outbox)
    result.tool_calls = list(device.calls)
    result.server_calls = server_calls
    trace.assistant_text = result.reply
    return result
