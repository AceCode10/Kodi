import logging
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


def _execute_server_tool(
    name: str,
    tool_input: dict[str, Any],
    *,
    transcript: str,
    user_id: str,
) -> str:
    try:
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
    except Exception as exc:
        logger.exception("server tool %s failed", name)
        return f"Tool error: {exc!s}."
    return "Unknown tool."


def run_agent_step(state: Any) -> dict[str, Any]:
    settings = get_settings()
    if not settings.anthropic_api_key:
        return {"status": "error", "message": "LLM not configured (ANTHROPIC_API_KEY)."}

    client = anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=settings.llm_timeout_seconds)

    max_inner = 16
    for _ in range(max_inner):
        if state.tools_used_this_command >= settings.max_tools_per_command:
            return {
                "status": "done",
                "assistant_text": "I hit the tool limit for this command; try a shorter request.",
            }

        try:
            response = client.messages.create(
                model=settings.anthropic_model,
                max_tokens=1024,
                temperature=0.3,
                system=KODI_SYSTEM_PROMPT,
                tools=TOOLS,
                messages=state.claude_messages,
            )
        except Exception as exc:
            logger.exception("claude messages.create failed")
            return {"status": "error", "message": f"LLM failed: {exc!s}."}

        content = response.content
        tool_blocks = [b for b in content if (b.type if hasattr(b, "type") else b.get("type")) == "tool_use"]
        text_parts = []
        for block in content:
            btype = block.type if hasattr(block, "type") else block.get("type")
            if btype == "text":
                text_parts.append(block.text if hasattr(block, "text") else block.get("text", ""))

        if not tool_blocks:
            return {"status": "done", "assistant_text": "".join(text_parts).strip() or "Done."}

        if len(tool_blocks) > 1:
            logger.warning("Multiple tool_use blocks; processing the first only")
        block = tool_blocks[0]

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
        state.claude_messages.append({"role": "assistant", "content": filtered})

        name = _tool_name(block)
        tid = _tool_id(block)
        inp = _tool_input(block)

        if name in DEVICE_TOOL_NAMES:
            state.tools_used_this_command += 1
            state.pending_device_tool = {"tool_use_id": tid, "name": name, "input": inp}
            return {
                "status": "device_action",
                "tool_use_id": tid,
                "tool_name": name,
                "tool_input": inp,
            }

        if name in SERVER_TOOL_NAMES:
            state.tools_used_this_command += 1
            out = _execute_server_tool(
                name,
                inp,
                transcript=state.last_transcript,
                user_id=state.device_id,
            )
            state.claude_messages.append(
                {
                    "role": "user",
                    "content": [{"type": "tool_result", "tool_use_id": tid, "content": out}],
                }
            )
            continue

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

    return {"status": "error", "message": "Too many agent steps."}


def start_command(state: Any, transcript: str, memory_block: str) -> None:
    state.last_transcript = transcript
    state.tools_used_this_command = 0
    state.pending_device_tool = None
    user_body = transcript
    if memory_block:
        user_body = f"[Context from memory]\n{memory_block}\n\nUser said: {transcript}"
    state.claude_messages = [{"role": "user", "content": user_body}]


def apply_tool_result(state: Any, tool_use_id: str, result_text: str, is_error: bool = False) -> None:
    state.pending_device_tool = None
    state.claude_messages.append(
        {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": result_text,
                    "is_error": is_error,
                }
            ],
        }
    )
