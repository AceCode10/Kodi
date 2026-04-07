import logging
import re
import time
from collections import deque
from typing import Annotated

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import AliasChoices, BaseModel, Field

from . import agent_loop
from .config import get_settings
from .devices_store import register_device
from .hmac_auth import verify_request_hmac
from .memory_service import memory_add_turn, memory_forget_all, memory_forget_last, memory_search
from .session_manager import sessions
from .stt import transcribe_wav

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

_START_TIME = time.time()

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


def _finish_memory_async(
    background_tasks: BackgroundTasks,
    user_id: str,
    user_text: str,
    assistant_text: str,
) -> None:
    def job() -> None:
        memory_add_turn(user_id, user_text, assistant_text)

    background_tasks.add_task(job)


def _run_agent_from_transcript(
    st,
    transcript: str,
    device_id: str,
    background_tasks: BackgroundTasks,
) -> JSONResponse:
    forget_reply = _forget_preprocess(transcript, device_id)
    if forget_reply:
        _finish_memory_async(background_tasks, device_id, transcript, forget_reply)
        return JSONResponse({"status": "done", "assistantText": forget_reply, "transcript": transcript})

    mem_block = memory_search(device_id, transcript, limit=5)
    agent_loop.start_command(st, transcript, mem_block)
    result = agent_loop.run_agent_step(st)

    if result.get("status") == "done":
        text = result.get("assistant_text", "")
        _finish_memory_async(background_tasks, device_id, transcript, text)
        return JSONResponse({"status": "done", "assistantText": text, "transcript": transcript})
    if result.get("status") == "device_action":
        return JSONResponse(
            {
                "status": "device_action",
                "transcript": transcript,
                "toolUseId": result.get("tool_use_id"),
                "toolName": result.get("tool_name"),
                "toolInput": result.get("tool_input"),
            }
        )
    return JSONResponse(
        {"status": "error", "message": result.get("message", "Error"), "transcript": transcript},
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
    )


@app.post("/v1/sessions/{session_id}/audio")
async def session_audio(
    session_id: str,
    request: Request,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
) -> JSONResponse:
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

    return _run_agent_from_transcript(st, transcript, device_id, background_tasks)


@app.post("/v1/sessions/{session_id}/text")
async def session_text(
    session_id: str,
    body: TranscriptBody,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
) -> JSONResponse:
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

    return _run_agent_from_transcript(st, transcript, device_id, background_tasks)


@app.post("/v1/sessions/{session_id}/tool-result")
async def session_tool_result(
    session_id: str,
    body: ToolResultBody,
    device_id: Annotated[str, Depends(verify_request_hmac)],
    background_tasks: BackgroundTasks,
) -> JSONResponse:
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
        agent_loop.apply_tool_result(
            st,
            tool_use_id,
            "Device tool timed out.",
            True,
        )
    else:
        agent_loop.apply_tool_result(st, tool_use_id, body.content, body.is_error)

    result = agent_loop.run_agent_step(st)

    if result.get("status") == "done":
        text = result.get("assistant_text", "")
        if st.last_transcript:
            _finish_memory_async(background_tasks, device_id, st.last_transcript, text)
        return JSONResponse({"status": "done", "assistantText": text})
    if result.get("status") == "device_action":
        return JSONResponse(
            {
                "status": "device_action",
                "toolUseId": result.get("tool_use_id"),
                "toolName": result.get("tool_name"),
                "toolInput": result.get("tool_input"),
            }
        )
    return JSONResponse(
        {"status": "error", "message": result.get("message", "Error")},
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
    )
