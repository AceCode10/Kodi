"""Gemini Live session config — the interactive-voice brain.

Builds a per-device LiveConnectConfig: native-audio model, prebuilt voice,
Kodi's system prompt augmented with the device's profile + learned lessons,
the full tool set, input/output audio transcription (for UI bubbles + memory),
and sliding-window context compression to bound long-session audio-context cost.

`google.genai` is imported lazily so modules that only need tool conversion
(and the unit tests) don't require the SDK to be installed.
"""
from __future__ import annotations

import logging
from typing import Any

from .config import KODI_SYSTEM_PROMPT, get_settings
from .gemini_tools import build_tools_config
from .learning import get_lessons
from .memory_service import read_cached_profile

logger = logging.getLogger(__name__)


def make_client() -> Any:
    """Construct the genai client for the Live API."""
    from google import genai

    settings = get_settings()
    if not settings.gemini_api_key:
        raise RuntimeError("GEMINI_API_KEY not configured")
    return genai.Client(api_key=settings.gemini_api_key)


def _system_instruction(device_id: str, client_context: str = "") -> str:
    sections = [KODI_SYSTEM_PROMPT]
    try:
        profile = read_cached_profile(device_id)
        if profile:
            sections.append(f"[User profile]\n{profile}")
        lessons = get_lessons(device_id, "general behaviour")
        if lessons:
            sections.append(f"[Lessons learned]\n{lessons}")
    except Exception:  # pragma: no cover - profile/lessons are best-effort
        logger.exception("failed to load profile/lessons for system instruction")
    if client_context:
        sections.append(f"[Current client context]\n{client_context}")
    return "\n\n".join(sections)


def build_live_config(device_id: str, client_context: str = "") -> Any:
    """Build the LiveConnectConfig for one device's session."""
    from google.genai import types

    settings = get_settings()

    config_kwargs: dict[str, Any] = {
        "response_modalities": ["AUDIO"],
        "system_instruction": _system_instruction(device_id, client_context),
        "speech_config": types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=settings.gemini_voice),
            ),
        ),
        "tools": build_tools_config(),
        "input_audio_transcription": types.AudioTranscriptionConfig(),
        "output_audio_transcription": types.AudioTranscriptionConfig(),
    }

    # Sliding-window context compression bounds accumulating audio-context cost on
    # long sessions. Guarded: skip if the installed SDK lacks the type.
    try:
        config_kwargs["context_window_compression"] = types.ContextWindowCompressionConfig(
            sliding_window=types.SlidingWindow(),
        )
    except AttributeError:  # pragma: no cover - SDK version without the type
        logger.warning("ContextWindowCompressionConfig unavailable in this google-genai version")

    return types.LiveConnectConfig(**config_kwargs)
