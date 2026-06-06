"""Convert the as-built Kodi tool set (claude_tools.TOOLS) into Gemini Live
function declarations.

Gemini's function schema is the OpenAPI-subset `Schema` — JSON-Schema-shaped but
with UPPERCASE type names and no `default` keyword. We keep `type`, `description`,
`properties`, `required`, `enum`, and `items`; uppercase every `type`; drop
`default`; and default any untyped property (e.g. toggle_setting.value) to STRING.

The device/server partition is reused verbatim from claude_tools so the live proxy
routes tool calls exactly as the SSE agent loop does.
"""
from __future__ import annotations

from typing import Any

from .claude_tools import DEVICE_TOOL_NAMES, SERVER_TOOL_NAMES, TOOLS

_TYPE_MAP = {
    "object": "OBJECT",
    "string": "STRING",
    "boolean": "BOOLEAN",
    "integer": "INTEGER",
    "number": "NUMBER",
    "array": "ARRAY",
}


def _convert_schema(node: dict[str, Any]) -> dict[str, Any]:
    """Recursively map one JSON-Schema node to a Gemini Schema dict."""
    raw_type = node.get("type")
    gem_type = _TYPE_MAP.get(raw_type, "STRING")  # untyped (e.g. toggle value) -> STRING
    out: dict[str, Any] = {"type": gem_type}

    if "description" in node:
        out["description"] = node["description"]
    if "enum" in node:
        out["enum"] = [str(v) for v in node["enum"]]

    if gem_type == "OBJECT":
        props = node.get("properties") or {}
        if props:
            out["properties"] = {k: _convert_schema(v) for k, v in props.items()}
        if node.get("required"):
            out["required"] = list(node["required"])
    elif gem_type == "ARRAY":
        items = node.get("items") or {"type": "string"}
        out["items"] = _convert_schema(items)

    # `default` and other unsupported keywords are intentionally dropped.
    return out


def _to_declaration(tool: dict[str, Any]) -> dict[str, Any]:
    decl: dict[str, Any] = {
        "name": tool["name"],
        "description": tool.get("description", ""),
    }
    schema = tool.get("input_schema") or {}
    props = schema.get("properties") or {}
    if props:  # omit `parameters` entirely for no-arg tools (Gemini rejects empty OBJECT)
        decl["parameters"] = _convert_schema(schema)
    return decl


def build_function_declarations() -> list[dict[str, Any]]:
    """All 28 tools as Gemini FunctionDeclaration dicts."""
    return [_to_declaration(t) for t in TOOLS]


def build_tools_config() -> list[dict[str, Any]]:
    """The `tools` value for LiveConnectConfig: one Tool with all declarations."""
    return [{"function_declarations": build_function_declarations()}]


# Re-exported so the live proxy uses the identical device/server partition.
__all__ = [
    "build_function_declarations",
    "build_tools_config",
    "DEVICE_TOOL_NAMES",
    "SERVER_TOOL_NAMES",
]
