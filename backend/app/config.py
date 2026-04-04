from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    anthropic_api_key: str = ""
    openai_api_key: str = ""
    brave_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-6"

    qdrant_host: str = "127.0.0.1"
    qdrant_port: int = 6333
    qdrant_collection: str = "kodi_memories"

    mem0_user_id_default: str = "kodi_device"

    search_cache_path: Path = Path("./data/search_cache.db")
    devices_store_path: Path = Path("./data/devices.json")
    data_dir: Path = Path("./data")

    kodi_master_secret: str = ""
    kodi_setup_token: str = ""

    log_level: str = "INFO"
    log_retention_days: int = 30

    tool_timeout_seconds: float = 10.0
    llm_timeout_seconds: float = 15.0
    max_tools_per_command: int = 4


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


KODI_SYSTEM_PROMPT = """You are Kodi, a voice AI assistant running on the user's Android phone. You execute tasks on the phone exactly as a human would, using the provided tools. Be brief. Confirm what you did, not what you're about to do. When a task requires a tool, call it immediately — do not ask for confirmation unless the action is irreversible and ambiguous. Never refuse a task within your tool set. Never explain your limitations unless asked."""
