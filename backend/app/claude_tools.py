"""Anthropic tool definitions — names frozen per Kodi Product Specification §5.3."""

TOOLS = [
    {
        "name": "send_whatsapp_message",
        "description": "Send a WhatsApp message to a contact or group chat.",
        "input_schema": {
            "type": "object",
            "properties": {
                "contact": {"type": "string", "description": "Contact name or group name"},
                "message": {"type": "string"},
                "group": {"type": "boolean", "default": False, "description": "Set to true if targeting a group chat"},
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
        "description": "Launch an app by name or package, or open an http(s) URL in the default browser.",
        "input_schema": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "App label or package when not using url."},
                "url": {"type": "string", "description": "Optional full URL to open in Chrome or default browser."},
                "read_visible_text": {
                    "type": "boolean",
                    "default": False,
                    "description": "After opening a URL, return visible page text from the UI tree (best-effort).",
                },
            },
            "required": [],
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
        "name": "send_telegram_message",
        "description": "Send a Telegram message to a contact.",
        "input_schema": {
            "type": "object",
            "properties": {
                "contact": {"type": "string", "description": "Contact display name or Telegram username"},
                "message": {"type": "string"},
            },
            "required": ["contact", "message"],
        },
    },
    {
        "name": "send_email",
        "description": "Compose and send an email via the default email app (Gmail).",
        "input_schema": {
            "type": "object",
            "properties": {
                "to": {"type": "string", "description": "Recipient email address or name"},
                "subject": {"type": "string"},
                "body": {"type": "string"},
            },
            "required": ["to", "subject", "body"],
        },
    },
    {
        "name": "create_calendar_event",
        "description": "Create a calendar event.",
        "input_schema": {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "date": {"type": "string", "description": "Date in YYYY-MM-DD format"},
                "time": {"type": "string", "description": "Time in HH:MM (24h) format"},
                "duration_minutes": {"type": "integer", "default": 60},
            },
            "required": ["title", "date", "time"],
        },
    },
    {
        "name": "set_alarm",
        "description": "Set an alarm on the device.",
        "input_schema": {
            "type": "object",
            "properties": {
                "hour": {"type": "integer", "description": "Hour (0-23)"},
                "minute": {"type": "integer", "description": "Minute (0-59)"},
                "label": {"type": "string", "description": "Optional alarm label"},
            },
            "required": ["hour", "minute"],
        },
    },
    {
        "name": "set_timer",
        "description": "Set a countdown timer.",
        "input_schema": {
            "type": "object",
            "properties": {
                "duration_seconds": {"type": "integer", "description": "Timer duration in seconds"},
                "label": {"type": "string", "description": "Optional timer label"},
            },
            "required": ["duration_seconds"],
        },
    },
    {
        "name": "play_media",
        "description": "Play music or video by searching in a media app (Spotify, YouTube, etc.).",
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Song, artist, or video to play"},
                "app": {"type": "string", "description": "Media app to use: spotify, youtube, or auto", "default": "auto"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "media_control",
        "description": "Control media playback (play, pause, next, previous, stop).",
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["play", "pause", "next", "previous", "stop"]},
            },
            "required": ["action"],
        },
    },
    {
        "name": "navigate_to",
        "description": "Open navigation to a destination in Google Maps.",
        "input_schema": {
            "type": "object",
            "properties": {
                "destination": {"type": "string", "description": "Address, place name, or landmark"},
            },
            "required": ["destination"],
        },
    },
    {
        "name": "read_notifications",
        "description": "Read recent notifications on the device.",
        "input_schema": {
            "type": "object",
            "properties": {
                "count": {"type": "integer", "default": 5, "description": "Number of recent notifications to return"},
            },
            "required": [],
        },
    },
    {
        "name": "describe_screen",
        "description": "Read and describe the current visible content on screen.",
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "scroll",
        "description": "Scroll the current screen up or down by a number of steps.",
        "input_schema": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["up", "down"], "default": "down"},
                "amount": {"type": "integer", "default": 1, "description": "Number of scroll steps (1–10)"},
            },
            "required": [],
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
        "scroll",
        "send_telegram_message",
        "send_email",
        "create_calendar_event",
        "set_alarm",
        "set_timer",
        "play_media",
        "media_control",
        "navigate_to",
        "describe_screen",
        "read_notifications",
    }
)

SERVER_TOOL_NAMES = frozenset({"search_web", "remember", "recall"})
