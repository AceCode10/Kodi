import concurrent.futures
import logging
import re
import time
from collections.abc import Iterator
from typing import Any

import anthropic

from .claude_tools import DEVICE_TOOL_NAMES, SERVER_TOOL_NAMES, TOOLS
from .config import KODI_SYSTEM_PROMPT, get_settings
from .memory_service import memory_search
from .search_service import search_web

logger = logging.getLogger(__name__)


def _tool_input(block: Any) -> dict[str, Any]:
    if isinstance(block, dict):
        return block.get("input") or {}
    return dict(getattr(block, "input", {}) or {})


def _tool_name(block: Any) -> str:
    if isinstance(block, dict):
        return block.get("name") or ""
    return getattr(block, "name", "") or ""


def _tool_id(block: Any) -> str:
    if isinstance(block, dict):
        return block.get("id") or ""
    return getattr(block, "id", "") or ""


def _block_to_dict(block: Any) -> dict[str, Any]:
    if isinstance(block, dict):
        return block
    btype = getattr(block, "type", None)
    if btype == "text":
        return {"type": "text", "text": getattr(block, "text", "")}
    if btype == "tool_use":
        return {
            "type": "tool_use",
            "id": _tool_id(block),
            "name": _tool_name(block),
            "input": _tool_input(block),
        }
    return {"type": "text", "text": ""}


def _execute_server_tool_impl(
    name: str,
    tool_input: dict[str, Any],
    *,
    transcript: str,
    user_id: str,
) -> str:
    if name == "search_web":
        q = tool_input.get("query") or ""
        deep = bool(tool_input.get("deep"))
        return search_web(q, deep, transcript)
    if name == "remember":
        content = tool_input.get("content") or ""
        from .memory_service import get_memory

        mem = get_memory()
        if mem is False:
            return "Memory unavailable."
        mem.add(content, user_id=user_id, infer=False)
        return "Stored."
    if name == "recall":
        q = tool_input.get("query") or ""
        return memory_search(user_id, q, limit=5) or "No matching memories."
    if name == "call_home_assistant":
        from .devices_store import GRANT_HOME_ASSISTANT, has_grant
        from .home_assistant import call_home_assistant

        # Home Assistant runs on server-wide credentials, so holding a device
        # credential must not be enough on its own to actuate the user's home.
        if not has_grant(get_settings().devices_store_path, user_id, GRANT_HOME_ASSISTANT):
            return (
                "This device is not allowed to control Home Assistant. Grant it on the "
                "server: set HOME_ASSISTANT_AUTO_GRANT=true and re-pair, or add \"home_assistant\" "
                "to this device's grants in the device store."
            )
        op = tool_input.get("operation") or ""
        kwargs = {k: v for k, v in tool_input.items() if k != "operation"}
        return call_home_assistant(op, **kwargs)
    if name == "record_lesson":
        from .learning import add_lesson

        add_lesson(user_id, tool_input.get("lesson") or "")
        return "Lesson recorded."
    return "Unknown tool."


def _execute_server_tool(
    name: str,
    tool_input: dict[str, Any],
    *,
    transcript: str,
    user_id: str,
) -> str:
    settings = get_settings()
    timeout = max(1.0, settings.tool_timeout_seconds)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(
                _execute_server_tool_impl,
                name,
                tool_input,
                transcript=transcript,
                user_id=user_id,
            )
            return fut.result(timeout=timeout)
    except concurrent.futures.TimeoutError:
        logger.warning("server tool %s exceeded %.1fs", name, timeout)
        return f"Tool timed out after {int(timeout)} seconds."
    except Exception as exc:
        logger.exception("server tool %s failed", name)
        return f"Tool error: {exc!s}."


def _is_error_result(text: str) -> bool:
    low = (text or "").lower()
    return low.startswith(("tool error", "tool timed out", "unknown tool", "could not", "memory unavailable"))


def _finish(state: Any, text: str) -> dict[str, Any]:
    """Record the final assistant reply in history so the next command keeps context."""
    clean = text.strip() or "Done."
    with state.lock:
        state.claude_messages.append({"role": "assistant", "content": clean})
    return {"status": "done", "assistant_text": clean}


_SENTENCE_RE = re.compile(r"(.+?[.!?]+)(\s+)", re.S)


def _drain_sentences(buffer: str) -> tuple[list[str], str]:
    """Split off every complete sentence; return (sentences, remaining_partial)."""
    sentences: list[str] = []
    rest = buffer
    while True:
        m = _SENTENCE_RE.match(rest)
        if not m:
            break
        sentences.append(m.group(1).strip())
        rest = rest[m.end():]
    return sentences, rest


def _append_filtered_assistant(state: Any, content: Any) -> None:
    """Append the assistant message keeping at most the first tool_use block.

    Dropping the rest disables parallel tool use and costs a round trip per tool;
    the shared agent core replaces this with full multi-call handling.
    """
    filtered: list[dict[str, Any]] = []
    tool_seen = False
    for b in content:
        btype = b.type if hasattr(b, "type") else b.get("type")
        if btype == "text":
            filtered.append(_block_to_dict(b))
        elif btype == "tool_use":
            if tool_seen:
                continue
            tool_seen = True
            filtered.append(_block_to_dict(b))
    with state.lock:
        state.claude_messages.append({"role": "assistant", "content": filtered})


def run_agent_step_streaming(state: Any) -> Iterator[dict[str, Any]]:
    """Run one agent turn, yielding SSE-shaped event dicts:

    {"type":"delta","text":...} | {"type":"device_action",...} |
    {"type":"done","assistant_text":...} | {"type":"error","message":...}
    """
    settings = get_settings()
    if not settings.anthropic_api_key:
        yield {"type": "error", "message": "LLM not configured (ANTHROPIC_API_KEY)."}
        return

    client = anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=settings.llm_timeout_seconds)
    max_inner = 16

    for _ in range(max_inner):
        if state.tools_used_this_command >= settings.max_tools_per_command:
            res = _finish(state, "I hit the tool limit for this command; try a shorter request.")
            yield {"type": "delta", "text": res["assistant_text"]}
            yield {"type": "done", "assistant_text": res["assistant_text"]}
            return

        buffer = ""
        full_text = ""
        try:
            with client.messages.stream(
                model=settings.anthropic_model,
                max_tokens=1024,
                temperature=0.3,
                system=KODI_SYSTEM_PROMPT,
                tools=TOOLS,
                messages=state.claude_messages,
            ) as stream:
                for chunk in stream.text_stream:
                    buffer += chunk
                    full_text += chunk
                    sentences, buffer = _drain_sentences(buffer)
                    for s in sentences:
                        if s:
                            yield {"type": "delta", "text": s}
                final = stream.get_final_message()
        except Exception as exc:
            logger.exception("claude messages.stream failed")
            yield {"type": "error", "message": f"LLM failed: {exc!s}."}
            return

        content = final.content
        tool_blocks = [b for b in content if (b.type if hasattr(b, "type") else b.get("type")) == "tool_use"]

        if not tool_blocks:
            tail = buffer.strip()
            if tail:
                yield {"type": "delta", "text": tail}
            res = _finish(state, full_text)
            yield {"type": "done", "assistant_text": res["assistant_text"]}
            return

        # A tool turn — any lead-in text was already streamed above.
        _append_filtered_assistant(state, content)
        block = tool_blocks[0]
        name = _tool_name(block)
        tid = _tool_id(block)
        inp = _tool_input(block)

        if name in DEVICE_TOOL_NAMES:
            state.tools_used_this_command += 1
            state.pending_device_since = time.time()
            state.pending_device_tool = {"tool_use_id": tid, "name": name, "input": inp}
            yield {
                "type": "device_action",
                "tool_use_id": tid,
                "tool_name": name,
                "tool_input": inp,
            }
            return

        if name in SERVER_TOOL_NAMES:
            state.tools_used_this_command += 1
            out = _execute_server_tool(name, inp, transcript=state.last_transcript, user_id=state.device_id)
            truncated_out = out[:6000] if len(out) > 6000 else out
            with state.lock:
                if _is_error_result(truncated_out):
                    state.had_error = True
                state.claude_messages.append(
                    {
                        "role": "user",
                        "content": [{"type": "tool_result", "tool_use_id": tid, "content": truncated_out}],
                    }
                )
            continue

        with state.lock:
            state.had_error = True
            state.claude_messages.append(
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": tid,
                            "content": f"Unknown tool {name}.",
                            "is_error": True,
                        }
                    ],
                }
            )

    yield {"type": "error", "message": "Too many agent steps."}


def _compact_history(messages: list[dict[str, Any]], max_messages: int) -> list[dict[str, Any]]:
    """Keep only plain text user/assistant turns — drop tool_use / tool_result blocks.

    Prior user turns have their injected context blocks stripped back to the spoken text
    so stale time/location/memory does not pollute the rolling window.
    """
    clean: list[dict[str, Any]] = []
    for msg in messages:
        role = msg.get("role")
        content = msg.get("content")
        if not isinstance(content, str):
            continue  # tool_use (assistant list) or tool_result (user list)
        if role == "user":
            marker = "User said: "
            idx = content.rfind(marker)
            spoken = content[idx + len(marker):] if idx >= 0 else content
            clean.append({"role": "user", "content": spoken.strip()})
        elif role == "assistant":
            clean.append({"role": "assistant", "content": content.strip()})
    return clean[-max_messages:] if max_messages > 0 else []


def start_command(
    state: Any,
    transcript: str,
    memory_block: str,
    client_context: str = "",
    user_profile: str = "",
    lessons_block: str = "",
) -> None:
    settings = get_settings()
    with state.lock:
        state.last_transcript = transcript
        state.tools_used_this_command = 0
        state.had_error = False
        state.pending_device_tool = None
        state.pending_device_since = None

        history = _compact_history(state.claude_messages, settings.max_history_messages)

        sections: list[str] = []
        if user_profile:
            sections.append(f"[User profile]\n{user_profile}")
        if lessons_block:
            sections.append(f"[Lessons learned]\n{lessons_block}")
        if client_context:
            sections.append(f"[Current client context]\n{client_context}")
        if memory_block:
            sections.append(f"[Context from memory]\n{memory_block}")
        sections.append(f"User said: {transcript}")
        user_body = "\n\n".join(sections) if len(sections) > 1 else transcript

        state.claude_messages = history + [{"role": "user", "content": user_body}]


def apply_tool_result(state: Any, tool_use_id: str, result_text: str, is_error: bool = False) -> None:
    truncated = result_text[:6000] if len(result_text) > 6000 else result_text
    with state.lock:
        state.pending_device_tool = None
        state.pending_device_since = None
        if is_error:
            state.had_error = True
        state.claude_messages.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": tool_use_id,
                        "content": truncated,
                        "is_error": is_error,
                    }
                ],
            }
        )
