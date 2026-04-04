"""Anthropic tool definitions — names frozen per Kodi Product Specification §5.3."""

TOOLS = [
    {
        "name": "send_whatsapp_message",
        "description": "Send a message via WhatsApp to a contact identified by name.",
        "input_schema": {
            "type": "object",
            "properties": {
                "contact": {"type": "string"},
                "message": {"type": "string"},
            },
            "required": ["contact", "message"],
        },
    },
    {
        "name": "send_sms",
        "description": "Send an SMS to a contact by name or number.",
        "input_schema": {
            "type": "object",
            "properties": {
                "contact": {"type": "string"},
                "message": {"type": "string"},
            },
            "required": ["contact", "message"],
        },
    },
    {
        "name": "make_phone_call",
        "description": "Place a phone call to a contact name or phone number.",
        "input_schema": {
            "type": "object",
            "properties": {"contact": {"type": "string"}},
            "required": ["contact"],
        },
    },
    {
        "name": "search_web",
        "description": "Search the web and return summarized context. Deep mode is only applied when the user's wording explicitly requests research.",
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "deep": {"type": "boolean", "default": False},
            },
            "required": ["query"],
        },
    },
    {
        "name": "open_app",
        "description": "Launch an app by human-readable name or Android package name.",
        "input_schema": {
            "type": "object",
            "properties": {"app_name": {"type": "string"}},
            "required": ["app_name"],
        },
    },
    {
        "name": "toggle_setting",
        "description": "Change a system setting such as wifi, bluetooth, airplane_mode, brightness, or volume.",
        "input_schema": {
            "type": "object",
            "properties": {
                "setting": {"type": "string"},
                "value": {
                    "description": "Boolean toggle or numeric level depending on setting.",
                },
            },
            "required": ["setting", "value"],
        },
    },
    {
        "name": "remember",
        "description": "Store a fact in long-term memory for future turns.",
        "input_schema": {
            "type": "object",
            "properties": {"content": {"type": "string"}},
            "required": ["content"],
        },
    },
    {
        "name": "recall",
        "description": "Retrieve facts from memory matching a query.",
        "input_schema": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
        },
    },
    {
        "name": "get_contacts",
        "description": "Look up a contact's phone number by display name on the device.",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "read_last_message",
        "description": "Read the most recent WhatsApp or SMS message from a contact.",
        "input_schema": {
            "type": "object",
            "properties": {
                "contact": {"type": "string"},
                "app": {"type": "string", "description": "whatsapp or sms"},
            },
            "required": ["contact", "app"],
        },
    },
]

DEVICE_TOOL_NAMES = frozenset(
    {
        "send_whatsapp_message",
        "send_sms",
        "make_phone_call",
        "open_app",
        "toggle_setting",
        "get_contacts",
        "read_last_message",
    }
)

SERVER_TOOL_NAMES = frozenset({"search_web", "remember", "recall"})
