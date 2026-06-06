"""Verify the Kodi tool set converts cleanly to Gemini function declarations."""
from app.claude_tools import DEVICE_TOOL_NAMES, SERVER_TOOL_NAMES, TOOLS
from app.gemini_tools import build_function_declarations, build_tools_config

_GEMINI_TYPES = {"OBJECT", "STRING", "BOOLEAN", "INTEGER", "NUMBER", "ARRAY"}


def _walk(schema):
    yield schema
    for child in (schema.get("properties") or {}).values():
        yield from _walk(child)
    if "items" in schema:
        yield from _walk(schema["items"])


def test_all_tools_converted():
    decls = build_function_declarations()
    assert len(decls) == len(TOOLS)
    names = {d["name"] for d in decls}
    assert names == {t["name"] for t in TOOLS}


def test_no_default_keys_and_uppercase_types():
    for decl in build_function_declarations():
        params = decl.get("parameters")
        if params is None:
            continue
        for node in _walk(params):
            assert "default" not in node, f"{decl['name']} leaked a default"
            assert node["type"] in _GEMINI_TYPES, f"{decl['name']} bad type {node['type']}"


def test_toggle_setting_value_typed():
    decl = next(d for d in build_function_declarations() if d["name"] == "toggle_setting")
    value = decl["parameters"]["properties"]["value"]
    assert value["type"] in _GEMINI_TYPES  # untyped JSON-schema -> STRING fallback


def test_noarg_tools_omit_parameters():
    decl = next(d for d in build_function_declarations() if d["name"] == "describe_screen")
    assert "parameters" not in decl


def test_partition_preserved():
    # Every tool is in exactly one partition, and the union is the full set.
    all_names = {t["name"] for t in TOOLS}
    assert DEVICE_TOOL_NAMES | SERVER_TOOL_NAMES == all_names
    assert not (DEVICE_TOOL_NAMES & SERVER_TOOL_NAMES)


def test_tools_config_shape():
    cfg = build_tools_config()
    assert len(cfg) == 1
    assert "function_declarations" in cfg[0]
