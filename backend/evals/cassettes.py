"""Record and replay the model calls an eval case makes.

The agent talks to Claude through exactly one seam - `client.messages.stream(...)` in
`app/agent_loop.py` - so a cassette wraps that and nothing else. Record once against the
real API; replay thereafter for free, deterministically, in CI.

**What invalidates a cassette is the whole design.** The key is the *semantic* request:
the system prompt, the tool names, and the message history. It deliberately excludes
tuning fields - `max_tokens`, `temperature`, `cache_control`, `output_config.effort` -
because changing those is not a change in what the agent decided. So:

* A refactor that preserves behaviour replays clean. That is the Phase 2 regression proof.
* Turning on prompt caching or changing effort replays clean, because neither alters the
  conversation.
* The agent choosing a different tool, or the system prompt being edited, does *not*
  replay clean - it raises `CassetteMismatch`. That is the alarm, not a nuisance: when it
  fires, either a refactor changed behaviour (investigate) or the prompt changed on
  purpose (re-record, then read the scores).
"""
from __future__ import annotations

import hashlib
import json
import os
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

CASSETTE_DIR = Path(__file__).parent / "cassettes"


class CassetteMismatch(RuntimeError):
    """Replay hit a request the cassette does not contain at that position."""


class CassetteMissing(RuntimeError):
    """No cassette on disk for this case, and recording is not enabled."""


# -- response shapes (mirror the bits of the SDK the agent actually touches) -------


@dataclass
class _Block:
    type: str
    text: str = ""
    id: str = ""
    name: str = ""
    input: dict[str, Any] = field(default_factory=dict)


@dataclass
class _Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0


@dataclass
class _FinalMessage:
    content: list[_Block]
    usage: _Usage
    model: str


class _ReplayStream:
    """Stands in for the SDK's streaming context manager."""

    def __init__(self, interaction: dict[str, Any]) -> None:
        self._interaction = interaction

    def __enter__(self) -> _ReplayStream:
        return self

    def __exit__(self, *_exc: Any) -> bool:
        return False

    @property
    def text_stream(self):
        yield from self._interaction.get("text_chunks", [])

    def get_final_message(self) -> _FinalMessage:
        blocks = [_Block(**b) for b in self._interaction.get("content", [])]
        return _FinalMessage(
            content=blocks,
            usage=_Usage(**self._interaction.get("usage", {})),
            model=self._interaction.get("model", ""),
        )


# -- request identity --------------------------------------------------------------


def request_key(*, system: Any, tools: Any, messages: Any) -> str:
    """Hash the semantic request. Tuning fields are deliberately excluded - see module docstring."""
    payload = {
        "system": _normalise_system(system),
        "tools": sorted(t.get("name", "") for t in (tools or [])),
        "messages": _normalise_messages(messages),
    }
    blob = json.dumps(payload, sort_keys=True, default=str)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def _normalise_system(system: Any) -> str:
    """System may be a string or a list of blocks once caching lands; flatten to text."""
    if isinstance(system, str):
        return system
    if isinstance(system, list):
        return "\n".join(
            b.get("text", "") if isinstance(b, dict) else str(getattr(b, "text", ""))
            for b in system
        )
    return str(system or "")


def _normalise_messages(messages: Any) -> list[dict[str, Any]]:
    """Strip cache_control and other non-semantic annotations from the history."""
    out: list[dict[str, Any]] = []
    for message in messages or []:
        content = message.get("content")
        if isinstance(content, str):
            out.append({"role": message.get("role"), "content": content})
            continue
        blocks = []
        for block in content or []:
            if not isinstance(block, dict):
                block = {"type": getattr(block, "type", "")}
            clean = {k: v for k, v in block.items() if k != "cache_control"}
            blocks.append(clean)
        out.append({"role": message.get("role"), "content": blocks})
    return out


# -- the cassette ------------------------------------------------------------------


class Cassette:
    def __init__(self, case_id: str, path: Path) -> None:
        self.case_id = case_id
        self.path = path
        self.interactions: list[dict[str, Any]] = []
        self._cursor = 0

    @classmethod
    def load(cls, case_id: str, directory: Path = CASSETTE_DIR) -> Cassette:
        path = directory / f"{case_id}.json"
        cassette = cls(case_id, path)
        if not path.exists():
            raise CassetteMissing(
                f"No cassette for case {case_id!r} at {path}. "
                f"Record one with: python -m evals.runner --record --case {case_id}"
            )
        data = json.loads(path.read_text(encoding="utf-8"))
        cassette.interactions = data.get("interactions", [])
        return cassette

    def save(self, model: str) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(
                {"case_id": self.case_id, "model": model, "interactions": self.interactions},
                indent=2,
            ),
            encoding="utf-8",
        )

    def next_for(self, key: str, *, preview: dict[str, Any]) -> dict[str, Any]:
        if self._cursor >= len(self.interactions):
            raise CassetteMismatch(
                f"Case {self.case_id!r}: the agent made model call #{self._cursor + 1}, but the "
                f"cassette only has {len(self.interactions)}. The agent is taking more steps "
                f"than when this was recorded - a behaviour change. Investigate, or re-record "
                f"if the change was intended.\nRequest was: {_short(preview)}"
            )
        interaction = self.interactions[self._cursor]
        if interaction.get("request_key") != key:
            raise CassetteMismatch(
                f"Case {self.case_id!r}: model call #{self._cursor + 1} does not match the "
                f"recording. The conversation reaching the model differs - a different tool "
                f"choice, a different tool result, or an edited system prompt.\n"
                f"  recorded: {_short(interaction.get('preview', {}))}\n"
                f"  now:      {_short(preview)}"
            )
        self._cursor += 1
        return interaction

    def exhausted(self) -> bool:
        return self._cursor >= len(self.interactions)

    def unused(self) -> int:
        return max(0, len(self.interactions) - self._cursor)


def _short(preview: dict[str, Any]) -> str:
    last = preview.get("last_message", "")
    return f"{preview.get('n_messages', '?')} messages, last: {str(last)[:160]!r}"


# -- patching the seam -------------------------------------------------------------


class _FakeMessages:
    def __init__(self, owner: _FakeClient) -> None:
        self._owner = owner

    def stream(self, **kwargs: Any) -> _ReplayStream:
        key = request_key(
            system=kwargs.get("system"),
            tools=kwargs.get("tools"),
            messages=kwargs.get("messages"),
        )
        preview = _preview(kwargs.get("messages"))
        interaction = self._owner.cassette.next_for(key, preview=preview)
        return _ReplayStream(interaction)


class _FakeClient:
    def __init__(self, cassette: Cassette) -> None:
        self.cassette = cassette
        self.messages = _FakeMessages(self)


def _preview(messages: Any) -> dict[str, Any]:
    msgs = list(messages or [])
    last = msgs[-1]["content"] if msgs else ""
    if isinstance(last, list):
        last = json.dumps(last, default=str)
    return {"n_messages": len(msgs), "last_message": last}


@contextmanager
def replaying(case_id: str, directory: Path = CASSETTE_DIR):
    """Run the agent with its model calls served from a cassette."""
    import anthropic

    cassette = Cassette.load(case_id, directory)
    original = anthropic.Anthropic
    anthropic.Anthropic = lambda **_kw: _FakeClient(cassette)  # type: ignore[assignment]
    try:
        yield cassette
    finally:
        anthropic.Anthropic = original  # type: ignore[assignment]


class _RecordingMessages:
    def __init__(self, owner: _RecordingClient) -> None:
        self._owner = owner

    def stream(self, **kwargs: Any):
        return _RecordingStream(self._owner, kwargs)


class _RecordingClient:
    def __init__(self, real: Any, cassette: Cassette) -> None:
        self.real = real
        self.cassette = cassette
        self.messages = _RecordingMessages(self)


class _RecordingStream:
    """Passes calls through to the real API while capturing them."""

    def __init__(self, owner: _RecordingClient, kwargs: dict[str, Any]) -> None:
        self._owner = owner
        self._kwargs = kwargs
        self._chunks: list[str] = []
        self._inner = None

    def __enter__(self):
        self._inner = self._owner.real.messages.stream(**self._kwargs).__enter__()
        return self

    def __exit__(self, *exc: Any):
        return self._inner.__exit__(*exc)

    @property
    def text_stream(self):
        for chunk in self._inner.text_stream:
            self._chunks.append(chunk)
            yield chunk

    def get_final_message(self):
        final = self._inner.get_final_message()
        self._owner.cassette.interactions.append(
            {
                "request_key": request_key(
                    system=self._kwargs.get("system"),
                    tools=self._kwargs.get("tools"),
                    messages=self._kwargs.get("messages"),
                ),
                "preview": _preview(self._kwargs.get("messages")),
                "text_chunks": list(self._chunks),
                "content": [_block_to_json(b) for b in final.content],
                "usage": _usage_to_json(getattr(final, "usage", None)),
                "model": getattr(final, "model", ""),
            }
        )
        return final


def _block_to_json(block: Any) -> dict[str, Any]:
    btype = getattr(block, "type", None) or (block.get("type") if isinstance(block, dict) else "")
    if btype == "tool_use":
        return {
            "type": "tool_use",
            "id": getattr(block, "id", "") or "",
            "name": getattr(block, "name", "") or "",
            "input": dict(getattr(block, "input", {}) or {}),
        }
    return {"type": "text", "text": getattr(block, "text", "") or ""}


def _usage_to_json(usage: Any) -> dict[str, int]:
    out: dict[str, int] = {}
    for key in ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"):
        value = getattr(usage, key, None)
        if isinstance(value, int):
            out[key] = value
    return out


@contextmanager
def recording(case_id: str, directory: Path = CASSETTE_DIR):
    """Run the agent against the real API, saving a cassette on the way out."""
    import anthropic

    if not os.environ.get("ANTHROPIC_API_KEY"):
        raise RuntimeError("Recording needs ANTHROPIC_API_KEY; replay does not.")

    cassette = Cassette(case_id, directory / f"{case_id}.json")
    original = anthropic.Anthropic
    model_seen = {"name": ""}

    def factory(**kwargs: Any) -> _RecordingClient:
        return _RecordingClient(original(**kwargs), cassette)

    anthropic.Anthropic = factory  # type: ignore[assignment]
    try:
        yield cassette
    finally:
        anthropic.Anthropic = original  # type: ignore[assignment]
        if cassette.interactions:
            model_seen["name"] = cassette.interactions[-1].get("model", "")
            cassette.save(model_seen["name"])


# -- scripted transport (for testing the harness itself) ---------------------------


class _ScriptedMessages:
    def __init__(self, owner: "_ScriptedClient") -> None:
        self._owner = owner

    def stream(self, **_kwargs: Any) -> _ReplayStream:
        if not self._owner.script:
            raise CassetteMismatch("scripted transport ran out of responses")
        return _ReplayStream(self._owner.script.pop(0))


class _ScriptedClient:
    def __init__(self, script: list[dict[str, Any]]) -> None:
        self.script = script
        self.messages = _ScriptedMessages(self)


def text_response(text: str, **usage: int) -> dict[str, Any]:
    """One model turn that just replies."""
    return {
        "text_chunks": [text],
        "content": [{"type": "text", "text": text}],
        "usage": usage or {"input_tokens": 100, "output_tokens": 20},
        "model": "scripted",
    }


def tool_response(name: str, tool_input: dict[str, Any], *, tool_use_id: str = "tu_1",
                  lead_in: str = "") -> dict[str, Any]:
    """One model turn that calls a tool."""
    content: list[dict[str, Any]] = []
    if lead_in:
        content.append({"type": "text", "text": lead_in})
    content.append({"type": "tool_use", "id": tool_use_id, "name": name, "input": tool_input})
    return {
        "text_chunks": [lead_in] if lead_in else [],
        "content": content,
        "usage": {"input_tokens": 100, "output_tokens": 20},
        "model": "scripted",
    }


@contextmanager
def scripted(responses: list[dict[str, Any]]):
    """Serve a fixed sequence of model turns, ignoring request identity.

    For testing the harness, NOT for grading the agent: it skips the request-key check
    that makes a real cassette meaningful. Use `replaying()` for anything that scores.
    """
    import anthropic

    client = _ScriptedClient(list(responses))
    original = anthropic.Anthropic
    anthropic.Anthropic = lambda **_kw: client  # type: ignore[assignment]
    try:
        yield client
    finally:
        anthropic.Anthropic = original  # type: ignore[assignment]


# -- synthetic cassettes (scaffolding only) ----------------------------------------


class _SynthMessages:
    def __init__(self, owner: "_SynthClient") -> None:
        self._owner = owner

    def stream(self, **kwargs: Any) -> _ReplayStream:
        if not self._owner.script:
            raise CassetteMismatch("synthetic script ran out of responses")
        interaction = dict(self._owner.script.pop(0))
        interaction["request_key"] = request_key(
            system=kwargs.get("system"),
            tools=kwargs.get("tools"),
            messages=kwargs.get("messages"),
        )
        interaction["preview"] = _preview(kwargs.get("messages"))
        self._owner.cassette.interactions.append(interaction)
        return _ReplayStream(interaction)


class _SynthClient:
    def __init__(self, script: list[dict[str, Any]], cassette: Cassette) -> None:
        self.script = script
        self.cassette = cassette
        self.messages = _SynthMessages(self)


@contextmanager
def synthesizing(case_id: str, responses: list[dict[str, Any]], directory: Path = CASSETTE_DIR):
    """Write a replayable cassette from hand-written responses, with no API key.

    The request keys are real - computed from the actual conversation the agent built -
    so the cassette replays exactly like a recorded one and still detects a behaviour
    change. What is NOT real is the model's decisions: they were written by hand. Such a
    cassette is marked `"synthetic": true` and the runner warns when it replays one, so
    a synthetic result is never mistaken for a measurement of the model.
    """
    import anthropic

    cassette = Cassette(case_id, directory / f"{case_id}.json")
    original = anthropic.Anthropic
    # One client for the whole case: the agent constructs a fresh Anthropic() on every
    # step, so handing out a new _SynthClient each time would replay the first scripted
    # response forever and make the agent repeat its first action until the tool budget
    # stopped it.
    client = _SynthClient(list(responses), cassette)
    anthropic.Anthropic = lambda **_kw: client  # type: ignore[assignment]
    try:
        yield cassette
    finally:
        anthropic.Anthropic = original  # type: ignore[assignment]
        if cassette.interactions:
            directory.mkdir(parents=True, exist_ok=True)
            cassette.path.write_text(
                json.dumps(
                    {
                        "case_id": case_id,
                        "model": "synthetic",
                        "synthetic": True,
                        "interactions": cassette.interactions,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )


def is_synthetic(case_id: str, directory: Path = CASSETTE_DIR) -> bool:
    path = directory / f"{case_id}.json"
    if not path.exists():
        return False
    try:
        return bool(json.loads(path.read_text(encoding="utf-8")).get("synthetic"))
    except ValueError:
        return False
