import base64
import binascii
import json
import logging
import re
import time
from collections import deque
from typing import Annotated, Any

from collections.abc import Iterator

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from pydantic import AliasChoices, BaseModel, Field

from . import agent_loop
from .config import get_settings
from .devices_store import register_device
from .hmac_auth import verify_request_hmac
from .learning import compact_lessons, detect_correction, get_lessons, reflect_on_turn
from .memory_service import (
    memory_add_turn,
    memory_forget_all,
    memory_forget_last,
    memory_search,
    profile_is_stale,
    read_cached_profile,
    refresh_user_profile,
)
from .session_manager import sessions
from .stt import transcribe_wav

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

_START_TIME = time.time()

_settings = get_settings()
if _settings.sentry_dsn:
    try:
        import sentry_sdk

        sentry_sdk.init(
            dsn=_settings.sentry_dsn,
            environment=_settings.sentry_environment,
            traces_sample_rate=_settings.sentry_traces_sample_rate,
            send_default_pii=False,
        )
        logger.info("Sentry initialised (env=%s)", _settings.sentry_environment)
    except Exception:
        logger.exception("Sentry init failed")

app = FastAPI(title="Kodi Backend", version="1.0.0")

_register_by_ip: dict[str, deque[float]] = {}
_forget_all_pending_until: dict[str, float] = {}
CONFIRM_FORGET_ALL = re.compile(
    r"(confirm\s+delete(\s+everything)?)|(yes.{0,24}delete\s+everything)|delete\s+all\s+my\s+memories",
    re.IGNORECASE,
)


def _check_register_rate(request: Request) -> None:
    settings = get_settings()
    limit = settings.register_rate_limit_per_hour
    if limit <= 0:
        return
    ip = (request.client.host if request.client else None) or "unknown"
    now = time.time()
    q = _register_by_ip.setdefault(ip, deque())
    while q and now - q[0] > 3600.0:
        q.popleft()
    if len(q) >= limit:
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many device registrations; try again later.")
    q.append(now)


@app.get("/v1/health")
async def health() -> dict[str, str | int]:
    return {
        "status": "ok",
        "version": "1.0.0",
        "uptime_seconds": int(time.time() - _START_TIME),
    }


class RegisterResponse(BaseModel):
    device_id: str
    api_secret: str


@app.post("/v1/devices/register", response_model=RegisterResponse)
def devices_register(
    request: Request,
    x_kodi_setup_token: Annotated[str | None, Header(alias="X-Kodi-Setup-Token")] = None,
) -> RegisterResponse:
    settings = get_settings()
    if settings.kodi_setup_token and x_kodi_setup_token != settings.kodi_setup_token:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Invalid setup token")
    _check_register_rate(request)
    device_id, secret = register_device(settings.devices_store_path, settings.kodi_master_secret)
    logger.info("Registered device %s", device_id)
    return RegisterResponse(device_id=device_id, api_secret=secret)


class SessionCreateResponse(BaseModel):
    session_id: str


@app.post("/v1/sessions", response_model=SessionCreateResponse)
async def create_session(device_id: Annotated[str, Depends(verify_request_hmac)]) -> SessionCreateResponse:
    sid = sessions.create(device_id)
    return SessionCreateResponse(session_id=sid)


class ToolResultBody(BaseModel):
    model_config = {"populate_by_name": True}

    tool_use_id: str = Field(validation_alias=AliasChoices("tool_use_id", "toolUseId"))
    content: str
    is_error: bool = Field(False, validation_alias=AliasChoices("is_error", "isError"))


class TranscriptBody(BaseModel):
    model_config = {"populate_by_name": True}

    text: str = Field(..., min_length=1, max_length=4000)


def _forget_preprocess(transcript: str, user_id: str) -> str | None:
    t = transcript.lower()
    now = time.time()
    if "forget that" in t and not re.search(r"forget\s+everything", t):
        return memory_forget_last(user_id)

    if re.search(r"forget\s+everything|delete\s+all\s+memories", t):
        deadline = _forget_all_pending_until.get(user_id, 0.0)
        if deadline > now and CONFIRM_FORGET_ALL.search(transcript):
            _forget_all_pending_until.pop(user_id, None)
            return memory_forget_all(user_id)
        if deadline > now:
            return "Say confirm delete everything to erase all memories, or wait for this prompt to expire."
        _forget_all_pending_until[user_id] = now + 180.0
        return "This removes all memories on your server. Say confirm delete everything within three minutes to proceed."
    return None


_CTX_KEY_LABELS = {
    "time_local": "time",
    "timezone": "timezone",
    "day_of_week": "day of week",
    "is_weekend": "weekend",
    "locale": "locale",
    "network": "network",
    "foreground_app": "foreground app",
    "battery_percent": "battery",
    "charging": "charging",
    "latitude": "latitude",
    "longitude": "longitude",
    "location_accuracy_m": "location accuracy (m)",
}


def _decode_client_context(header_value: str | None) -> str:
    """Decode the X-Kodi-Client-Context header into a short labelled block."""
    if not header_value:
        return ""
    try:
        raw = base64.urlsafe_b64decode(header_value + "==")
        data: dict[str, Any] = json.loads(raw.decode("utf-8"))
    except (binascii.Error, ValueError, UnicodeDecodeError):
        return ""
    if not isinstance(data, dict):
        return ""
    lines: list[str] = []
    for key, label in _CTX_KEY_LABELS.items():
        if key in data and data[key] is not None:
            lines.append(f"- {label}: {data[key]}")
    return "\n".join(lines)


def _sse(events: Iterator[dict[str, Any]]) -> Iterator[str]:
    """Serialise event dicts as Server-Sent Events frames."""
    try:
        for ev in events:
            yield f"event: {ev['type']}\ndata: {json.dumps(ev)}\n\n"
    except Exception as exc:  # pragma: no cover - defensive
        logger.exception("SSE stream failed")
        yield f"event: error\ndata: {json.dumps({'type': 'error', 'message': str(exc)})}\n\n"


def _stream_agent_events(st: Any) -> Iterator[dict[str, Any]]:
    """Run the streaming agent loop, remapping event keys to the client's camelCase."""
    for ev in agent_loop.run_agent_step_streaming(st):
        etype = ev.get("type")
        if etype == "device_action":
            yield {
                "type": "device_action",
                "toolUseId": ev.get("tool_use_id"),
                "toolName": ev.get("tool_name"),
                "toolInput": ev.get("tool_input"),
            }
        elif etype == "done":
            yield {"type": "done", "assistantText": ev.get("assistant_text", "")}
        else:
            yield ev


def _post_turn(device_id: str, transcript: str, st: Any, outcome: dict[str, Any]) -> None:
    """Background work after a turn fully streamed: memory write, profile + lesson learning."""
    if outcome.get("done_text") is None:
        return  # device_action mid-flight or error — no completed turn to learn from
    try:
        memory_add_turn(device_id, transcript)
        if profile_is_stale(device_id):
            refresh_user_profile(device_id)
        if st.had_error or detect_correction(transcript):
            reflect_on_turn(device_id, list(st.claude_messages), transcript)
            compact_lessons(device_id)
    except Exception:  # pragma: no cover - defensive
        logger.exception("post-turn learning failed")


def _agent_event_stream(
    st: Any,
    transcript: str,
    device_id: str,
    client_context: str,
    outcome: dict[str, Any],
    *,
    emit_transcript: bool,
) -> Iterator[dict[str, Any]]:
    if emit_transcript:
        yield {"type": "transcript", "text": transcript}

    forget_reply = _forget_preprocess(transcript, device_id)
    if forget_reply:
        outcome["done_text"] = forget_reply
        yield {"type": "done", "assistantText": forget_reply}
        return

    mem_block = memory_search(device_id, transcript, limit=5)
    profile = read_cached_profile(device_id)
    lessons = get_lessons(device_id, transcript)
    agent_loop.start_command(
        st,
        transcript,
        mem_block,
        client_context=client_context,
        user_profile=profile,
        lessons_block=lessons,
    )
    for ev in _stream_agent_events(st):
        if ev.get("type") == "done":
            outcome["done_text"] = ev.get("assistantText", "")
        yield ev


def _toolresult_event_stream(st: Any, outcome: dict[str, Any]) -> Iterator[dict[str, Any]]:
    for ev in _stream_agent_events(st):
        if ev.get("type") == "done":
            outcome["done_text"] = ev.get("assistantText", "")
        yield ev


_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"}


@app.post("/v1/sessions/{session_id}/audio")
async def session_audio(
    session_id: str,
    request: Request,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
    x_kodi_client_context: Annotated[str | None, Header(alias="X-Kodi-Client-Context")] = None,
) -> StreamingResponse:
    try:
        st = sessions.require(session_id)
    except KeyError:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session") from None
    if st.device_id != device_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Session not owned by this device")

    wav_bytes = await request.body()
    if not wav_bytes:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty body")

    try:
        transcript = transcribe_wav(wav_bytes)
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("STT failed")
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"Transcription failed: {exc!s}") from exc

    ctx = _decode_client_context(x_kodi_client_context)
    outcome: dict[str, Any] = {"done_text": None}
    gen = _agent_event_stream(st, transcript, device_id, ctx, outcome, emit_transcript=True)
    background_tasks.add_task(_post_turn, device_id, transcript, st, outcome)
    return StreamingResponse(_sse(gen), media_type="text/event-stream", headers=_SSE_HEADERS)


@app.post("/v1/sessions/{session_id}/text")
async def session_text(
    session_id: str,
    body: TranscriptBody,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
    x_kodi_client_context: Annotated[str | None, Header(alias="X-Kodi-Client-Context")] = None,
) -> StreamingResponse:
    """Same agent flow as /audio but with client-provided text (e.g. on-device STT fallback)."""
    try:
        st = sessions.require(session_id)
    except KeyError:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session") from None
    if st.device_id != device_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Session not owned by this device")

    transcript = body.text.strip()
    if not transcript:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty transcript")

    ctx = _decode_client_context(x_kodi_client_context)
    outcome: dict[str, Any] = {"done_text": None}
    gen = _agent_event_stream(st, transcript, device_id, ctx, outcome, emit_transcript=False)
    background_tasks.add_task(_post_turn, device_id, transcript, st, outcome)
    return StreamingResponse(_sse(gen), media_type="text/event-stream", headers=_SSE_HEADERS)


class BriefingResponse(BaseModel):
    text: str


class BriefingRequest(BaseModel):
    """Client context travels in the signed POST body so HMAC covers it (no replay)."""

    context: str = ""


@app.post("/v1/briefing", response_model=BriefingResponse)
async def briefing(
    device_id: Annotated[str, Depends(verify_request_hmac)],
    body: BriefingRequest,
) -> BriefingResponse:
    """One-shot personalised briefing — no session, draws on memory + client context."""
    settings = get_settings()
    if not settings.anthropic_api_key:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "LLM not configured")

    import anthropic

    mem_block = memory_search(device_id, "morning briefing daily plans calendar", limit=8)
    ctx = _decode_client_context(body.context or None)
    sections: list[str] = []
    if ctx:
        sections.append(f"[Current client context]\n{ctx}")
    if mem_block:
        sections.append(f"[Long-term memory excerpts]\n{mem_block}")
    sections.append(
        "Compose a short spoken morning briefing for the user. Keep it under 4 sentences, "
        "warm but efficient. Mention today's date and anything noteworthy from the memory excerpts. "
        "If memory is empty, give a generic but friendly greeting that mentions the date."
    )
    user_body = "\n\n".join(sections)

    try:
        client = anthropic.Anthropic(
            api_key=settings.anthropic_api_key,
            timeout=settings.llm_timeout_seconds,
        )
        resp = client.messages.create(
            model=settings.anthropic_model,
            max_tokens=400,
            temperature=0.5,
            system="You are Kodi, a voice AI assistant. Speak naturally; the output goes to TTS.",
            messages=[{"role": "user", "content": user_body}],
        )
        text_parts: list[str] = []
        for block in resp.content:
            btype = block.type if hasattr(block, "type") else block.get("type")
            if btype == "text":
                text_parts.append(block.text if hasattr(block, "text") else block.get("text", ""))
        text = "".join(text_parts).strip() or "Good morning."
        return BriefingResponse(text=text)
    except Exception as exc:
        logger.exception("briefing failed")
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, f"Briefing failed: {exc!s}") from exc


@app.post("/v1/sessions/{session_id}/tool-result")
async def session_tool_result(
    session_id: str,
    body: ToolResultBody,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
) -> StreamingResponse:
    settings = get_settings()
    try:
        st = sessions.require(session_id)
    except KeyError:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session") from None
    if st.device_id != device_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Session not owned by this device")

    timed_out = False
    if st.pending_device_since is not None:
        elapsed = time.time() - st.pending_device_since
        if elapsed > settings.tool_timeout_seconds:
            timed_out = True

    tool_use_id = body.tool_use_id
    if timed_out and st.pending_device_tool:
        tool_use_id = st.pending_device_tool.get("tool_use_id") or tool_use_id

    if timed_out:
        agent_loop.apply_tool_result(st, tool_use_id, "Device tool timed out.", True)
    else:
        agent_loop.apply_tool_result(st, tool_use_id, body.content, body.is_error)

    outcome: dict[str, Any] = {"done_text": None}
    gen = _toolresult_event_stream(st, outcome)
    background_tasks.add_task(_post_turn, device_id, st.last_transcript, st, outcome)
    return StreamingResponse(_sse(gen), media_type="text/event-stream", headers=_SSE_HEADERS)
