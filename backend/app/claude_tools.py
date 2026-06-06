"""Anthropic tool definitions — Kodi v1.0 as-built tool set.

The original spec (Kodi_Product_Specification_v1.0.pdf §5.3) defined 10 tools and
required a specification amendment for any additions. The shipped v1.0 build extends
this to the full set below; see SPEC_AMENDMENT_v1.0.md for the authoritative as-built
scope record.
"""

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
        "description": (
            "Open the email composer in the default mail app with the recipient, subject, "
            "and body pre-filled. The user taps send to deliver it — tell them it is ready to send."
        ),
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
        "description": (
            "Open the calendar event editor pre-filled with the title, date, and time. "
            "The user confirms in the calendar app to save it."
        ),
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
        "description": (
            "Open a media app (Spotify or YouTube) with a search for the query so the user "
            "can pick what to play. Use media_control afterwards to start playback if needed."
        ),
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
        "name": "call_home_assistant",
        "description": (
            "Control or query the user's Home Assistant instance. "
            "Use operation='call_service' to actuate (lights, switches, scripts, scenes, climate) — "
            "supply domain (e.g. light, switch, scene), service (e.g. turn_on, turn_off, toggle), and "
            "service_data (e.g. {\"entity_id\": \"light.kitchen\", \"brightness_pct\": 60}). "
            "Use operation='get_state' with entity_id to read current state."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "operation": {"type": "string", "enum": ["call_service", "get_state"]},
                "domain": {"type": "string"},
                "service": {"type": "string"},
                "service_data": {"type": "object"},
                "entity_id": {"type": "string"},
            },
            "required": ["operation"],
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
    {
        "name": "tap_on_screen",
        "description": (
            "Tap a UI element by visible text or content-description in the currently foreground app. "
            "Use after open_app + describe_screen to drive arbitrary apps."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Visible label or content-description to tap"},
                "partial": {"type": "boolean", "default": True, "description": "Match substring (true) or exact (false)"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "type_into_field",
        "description": (
            "Type text into whichever editable field is currently focused or the first editable field on screen. "
            "Tap that field first with tap_on_screen if needed."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "schedule_task",
        "description": (
            "Schedule a task to run at a specific local time. When the time arrives Kodi runs the task "
            "text through the normal agent loop as if the user had just spoken it."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "when_iso": {"type": "string", "description": "ISO 8601 local datetime, e.g. 2026-05-21T20:00:00"},
                "task": {"type": "string", "description": "What Kodi should do, phrased as a user command"},
                "id": {"type": "string", "description": "Optional stable id; auto-generated if omitted"},
            },
            "required": ["when_iso", "task"],
        },
    },
    {
        "name": "list_scheduled_tasks",
        "description": "List all currently pending scheduled tasks on this device.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "record_lesson",
        "description": (
            "Store a concise behavioural lesson after you made a mistake or the user corrected you, "
            "so you do not repeat it. Phrase it as a short imperative rule."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"lesson": {"type": "string"}},
            "required": ["lesson"],
        },
    },
    {
        "name": "cancel_scheduled_task",
        "description": "Cancel a pending scheduled task by id (obtain via list_scheduled_tasks).",
        "input_schema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
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
        "tap_on_screen",
        "type_into_field",
        "schedule_task",
        "list_scheduled_tasks",
        "cancel_scheduled_task",
    }
)

SERVER_TOOL_NAMES = frozenset(
    {"search_web", "remember", "recall", "call_home_assistant", "record_lesson"}
)
