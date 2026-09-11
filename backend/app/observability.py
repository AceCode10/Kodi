"""Structured logging and per-turn traces.

Two things live here:

* JSON logging, with request/device/turn ids carried on context vars so every line
  emitted anywhere during a turn is attributable without threading an argument
  through every call.
* A `TurnTrace` recording what one turn actually did - provider, model, token usage
  (including cache reads), each tool call and how long it took, per-stage latency.

Traces are appended as JSONL under `data/traces/`. This is the format the eval
runner reads, so it is a deliberate interface: add fields, don't repurpose them.

Turn text (what the user said, what the assistant replied) is omitted unless
`TRACE_INCLUDE_TEXT` is set. Evals turn it on against synthetic data; a real
deployment should not be writing the user's speech to disk by default.
"""
from __future__ import annotations

import contextlib
import json
import logging
import time
import uuid
from contextvars import ContextVar
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .config import get_settings

logger = logging.getLogger(__name__)

request_id_var: ContextVar[str] = ContextVar("request_id", default="")
device_id_var: ContextVar[str] = ContextVar("device_id", default="")
turn_id_var: ContextVar[str] = ContextVar("turn_id", default="")

_CONTEXT_VARS = (("request_id", request_id_var), ("device_id", device_id_var), ("turn_id", turn_id_var))

# Attributes the stdlib puts on every LogRecord; anything else was passed as `extra`
# and belongs in the emitted object.
_STD_RECORD_ATTRS = frozenset(
    """args asctime created exc_info exc_text filename funcName levelname levelno lineno
    module msecs message msg name pathname process processName relativeCreated stack_info
    thread threadName taskName""".split()
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created))
            + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for name, var in _CONTEXT_VARS:
            value = var.get()
            if value:
                payload[name] = value
        for key, value in record.__dict__.items():
            if key not in _STD_RECORD_ATTRS and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging() -> None:
    """Replace the root handler with a JSON one. Safe to call more than once."""
    settings = get_settings()
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(getattr(logging, settings.log_level.upper(), logging.INFO))


@dataclass
class ToolCall:
    name: str
    kind: str  # "device" | "server"
    ms: int
    is_error: bool


@dataclass
class TurnTrace:
    """One user turn, from utterance to final assistant reply."""

    turn_id: str
    device_id: str
    provider: str = ""
    model: str = ""
    transcript: str = ""
    assistant_text: str = ""
    tools: list[ToolCall] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=dict)
    stages: dict[str, int] = field(default_factory=dict)
    had_error: bool = False
    started_at: float = field(default_factory=time.monotonic)

    # -- recording ------------------------------------------------------------

    @contextlib.contextmanager
    def stage(self, name: str):
        """Time a named stage (stt, llm, tool, ...) into `stages["<name>_ms"]`."""
        t0 = time.monotonic()
        try:
            yield
        finally:
            elapsed = int((time.monotonic() - t0) * 1000)
            self.stages[f"{name}_ms"] = self.stages.get(f"{name}_ms", 0) + elapsed

    def record_tool(self, name: str, kind: str, ms: int, is_error: bool) -> None:
        self.tools.append(ToolCall(name=name, kind=kind, ms=ms, is_error=is_error))
        if is_error:
            self.had_error = True

    def record_usage(self, usage: Any) -> None:
        """Accumulate token usage across the turn's model calls.

        Takes whatever the provider SDK returns; unknown shapes are ignored rather
        than raising, since a trace must never break a turn.
        """
        if usage is None:
            return
        for key in (
            "input_tokens",
            "output_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens",
        ):
            value = getattr(usage, key, None)
            if isinstance(value, int):
                self.usage[key] = self.usage.get(key, 0) + value

    # -- emitting -------------------------------------------------------------

    def to_dict(self, include_text: bool) -> dict[str, Any]:
        out: dict[str, Any] = {
            "event": "turn",
            "turn_id": self.turn_id,
            "device_id": self.device_id,
            "provider": self.provider,
            "model": self.model,
            "tools": [vars(t) for t in self.tools],
            "usage": self.usage,
            "stages": self.stages,
            "had_error": self.had_error,
        }
        if include_text:
            out["transcript"] = self.transcript
            out["assistant_text"] = self.assistant_text
        return out

    def finish(self) -> None:
        """Emit the trace. Never raises: observability must not be able to fail a turn."""
        try:
            self.stages["total_ms"] = int((time.monotonic() - self.started_at) * 1000)
            settings = get_settings()
            record = self.to_dict(include_text=settings.trace_include_text)
            logger.info("turn complete", extra={"turn": record})
            _append_jsonl(record)
        except Exception:
            logger.debug("trace finish failed", exc_info=True)


def _append_jsonl(record: dict[str, Any]) -> None:
    """Append one trace to today's file. Never raises into the turn."""
    try:
        settings = get_settings()
        directory: Path = settings.data_dir / "traces"
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{time.strftime('%Y-%m-%d', time.gmtime())}.jsonl"
        line = json.dumps({"ts": time.time(), **record}, default=str)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except Exception:
        logger.debug("trace write failed", exc_info=True)


def new_turn(device_id: str, transcript: str = "") -> TurnTrace:
    """Start a trace and bind its id to the logging context for this task."""
    turn_id = uuid.uuid4().hex[:16]
    turn_id_var.set(turn_id)
    device_id_var.set(device_id)
    return TurnTrace(turn_id=turn_id, device_id=device_id, transcript=transcript)


def prune_traces() -> int:
    """Delete trace files older than LOG_RETENTION_DAYS. Returns the count removed."""
    settings = get_settings()
    directory = settings.data_dir / "traces"
    if not directory.exists():
        return 0
    cutoff = time.time() - settings.log_retention_days * 86400
    removed = 0
    for path in directory.glob("*.jsonl"):
        try:
            if path.stat().st_mtime < cutoff:
                path.unlink()
                removed += 1
        except OSError:
            continue
    return removed
