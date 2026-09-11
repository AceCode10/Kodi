from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    anthropic_api_key: str = ""
    openai_api_key: str = ""
    brave_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-6"

    # Gemini Live — interactive voice brain (fused STT + reasoning + TTS over WebSocket)
    gemini_api_key: str = ""
    gemini_live_model: str = "gemini-2.5-flash-native-audio-preview-12-2025"
    gemini_voice: str = "Puck"

    qdrant_host: str = "127.0.0.1"
    qdrant_port: int = 6333
    qdrant_collection: str = "kodi_memories"

    # Local embedded vector store (Chroma) — no Docker/Qdrant required.
    chroma_path: Path = Path("./data/chroma")

    mem0_user_id_default: str = "kodi_device"

    search_cache_path: Path = Path("./data/search_cache.db")
    devices_store_path: Path = Path("./data/devices.json")
    data_dir: Path = Path("./data")

    kodi_master_secret: str = ""
    kodi_setup_token: str = ""

    log_level: str = "INFO"
    log_retention_days: int = 30
    # Write the user's words and the assistant's reply into turn traces. Off by
    # default - a real deployment should not persist speech to disk. Evals enable it.
    trace_include_text: bool = False

    tool_timeout_seconds: float = 10.0
    llm_timeout_seconds: float = 15.0
    max_tools_per_command: int = 10
    # Rolling multi-turn window: prior user/assistant text turns kept across commands.
    max_history_messages: int = 6

    # Max device registrations per client IP per rolling hour (0 = unlimited)
    register_rate_limit_per_hour: int = 20

    sentry_dsn: str = ""
    sentry_environment: str = "production"
    sentry_traces_sample_rate: float = 0.0

    home_assistant_url: str = ""
    home_assistant_token: str = ""
    # Grant Home Assistant control to devices at registration time. Off by default:
    # HA actuates hardware in the user's home, so it is not implied by pairing.
    home_assistant_auto_grant: bool = False


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.data_dir.mkdir(parents=True, exist_ok=True)
    if isinstance(s.search_cache_path, str):
        s.search_cache_path = Path(s.search_cache_path)
    if isinstance(s.devices_store_path, str):
        s.devices_store_path = Path(s.devices_store_path)
    s.search_cache_path.parent.mkdir(parents=True, exist_ok=True)
    s.devices_store_path.parent.mkdir(parents=True, exist_ok=True)
    return s


KODI_SYSTEM_PROMPT = """You are Kodi, a voice AI assistant running on the user's Android phone. You execute tasks on the phone exactly as a human would, using the provided tools. Be brief. Confirm what you did, not what you're about to do. When a task requires a tool, call it immediately — do not ask for confirmation unless the action is irreversible and ambiguous. Never refuse a task within your tool set. Never explain your limitations unless asked.

When the user asks you to operate an app you don't have a dedicated tool for, call open_app with that app's name, then use describe_screen, tap_on_screen, type_into_field, and scroll step by step. Re-read the screen between actions until the goal is met. If open_app reports the app is not in the allowlist, tell the user to grant access in Settings → Manage app access.

To schedule work for later, call schedule_task with an ISO 8601 local timestamp computed from the user's words and the current client context. To review or cancel pending work, use list_scheduled_tasks and cancel_scheduled_task.

Learn about the user as you go. When the user states a durable fact, preference, relationship, or routine — for example a name ("my sister is Amara"), a habit ("I take oat milk"), a recurring event ("standup is 9am"), or a setting they prefer — call the remember tool to store it concisely. When a request depends on something you might already know about the user, call recall before asking them. Do not store one-off context or trivia. Use the [User profile] and [Context from memory] blocks already provided before deciding to recall.

You are given a [Lessons learned] block — rules distilled from your past mistakes. Always follow them. When the user corrects you, or you realise you chose the wrong tool, target, or interpretation, call record_lesson with a concise imperative rule so you never repeat that mistake."""
