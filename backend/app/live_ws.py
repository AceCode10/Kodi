"""/v1/live — backend-proxied Gemini Live voice session.

Phone  <-- WebSocket -->  this backend  <-- WebSocket -->  Gemini Live

The phone streams 16 kHz PCM mic audio up and plays 24 kHz PCM audio down.
Gemini does STT + reasoning + TTS. Tool calls are routed exactly like the SSE
agent loop: server tools run inline here; device tools round-trip to the phone.

Wire protocol (phone <-> backend):
  phone -> backend
    binary frame              : raw PCM16 mono 16 kHz mic audio
    {"type":"tool_result","id":..,"content":..,"isError":bool}
    {"type":"interrupt"}      : user requested barge-in / stop
    {"type":"end"}            : end the session
  backend -> phone
    binary frame              : raw PCM16 24 kHz audio to play
    {"type":"transcript","text":..}   : incremental user STT
    {"type":"delta","text":..}        : incremental assistant text
    {"type":"device_action","id":..,"name":..,"input":{..}}
    {"type":"turn_complete"}
    {"type":"interrupted"}
    {"type":"error","message":..}
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from . import agent_loop
from .config import get_settings
from .gemini_live import build_live_config, make_client
from .gemini_tools import DEVICE_TOOL_NAMES, SERVER_TOOL_NAMES
from .hmac_auth import HmacError, verify_hmac_headers
from .learning import compact_lessons, detect_correction, reflect_on_turn
from .memory_service import memory_add_turn, profile_is_stale, refresh_user_profile

logger = logging.getLogger(__name__)

router = APIRouter()

# Close codes
_WS_POLICY_VIOLATION = 1008
_WS_INTERNAL_ERROR = 1011


def _post_turn_live(device_id: str, user_text: str, assistant_text: str, had_error: bool) -> None:
    """Background learning after a completed live turn (mirrors main._post_turn)."""
    if not user_text.strip():
        return
    try:
        memory_add_turn(device_id, user_text)
        if profile_is_stale(device_id):
            refresh_user_profile(device_id)
        if had_error or detect_correction(user_text):
            messages = [
                {"role": "user", "content": user_text},
                {"role": "assistant", "content": assistant_text},
            ]
            reflect_on_turn(device_id, messages, user_text)
            compact_lessons(device_id)
    except Exception:  # pragma: no cover - defensive
        logger.exception("live post-turn learning failed")


@router.websocket("/v1/live")
async def live(websocket: WebSocket) -> None:
    h = websocket.headers
    try:
        device_id = verify_hmac_headers(
            device_id=h.get("x-kodi-device-id"),
            timestamp=h.get("x-kodi-timestamp"),
            signature=h.get("x-kodi-signature"),
            method="GET",
            path="/v1/live",
            body=b"",
        )
    except HmacError as exc:
        await websocket.close(code=_WS_POLICY_VIOLATION, reason=str(exc))
        return

    settings = get_settings()
    if not settings.gemini_api_key:
        await websocket.close(code=_WS_INTERNAL_ERROR, reason="Gemini not configured")
        return

    await websocket.accept()

    # Decode the same client-context header the SSE path uses (time, locale, network,
    # battery, location) so time/location-dependent tools work in the live session.
    from .main import _decode_client_context

    client_context = _decode_client_context(h.get("x-kodi-client-context"))

    try:
        client = make_client()
        config = build_live_config(device_id, client_context)
    except Exception as exc:
        logger.exception("live: failed to build Gemini session")
        await _safe_send_json(websocket, {"type": "error", "message": str(exc)})
        await websocket.close(code=_WS_INTERNAL_ERROR)
        return

    # Per-connection turn state.
    pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
    user_buf: list[str] = []
    assistant_buf: list[str] = []
    had_error = {"v": False}
    loop = asyncio.get_running_loop()

    try:
        async with client.aio.live.connect(model=settings.gemini_live_model, config=config) as session:

            async def uplink() -> None:
                """Phone -> Gemini: mic audio + control frames."""
                while True:
                    msg = await websocket.receive()
                    if msg.get("type") == "websocket.disconnect":
                        raise WebSocketDisconnect()
                    data = msg.get("bytes")
                    if data is not None:
                        await session.send_realtime_input(
                            audio={"data": data, "mime_type": "audio/pcm;rate=16000"},
                        )
                        continue
                    text = msg.get("text")
                    if not text:
                        continue
                    await _handle_control(text)

            async def _handle_control(text: str) -> None:
                import json

                try:
                    obj = json.loads(text)
                except ValueError:
                    return
                ctype = obj.get("type")
                if ctype == "tool_result":
                    fut = pending.get(obj.get("id", ""))
                    if fut and not fut.done():
                        fut.set_result(obj)
                elif ctype == "interrupt":
                    # Server-side VAD usually handles barge-in; this is an explicit stop.
                    try:
                        await session.send_realtime_input(audio_stream_end=True)
                    except Exception:
                        logger.debug("interrupt send_realtime_input failed", exc_info=True)
                elif ctype == "end":
                    raise WebSocketDisconnect()

            async def _run_server_tool(name: str, args: dict[str, Any]) -> str:
                return await asyncio.to_thread(
                    agent_loop._execute_server_tool,
                    name,
                    args,
                    transcript=" ".join(user_buf),
                    user_id=device_id,
                )

            async def _run_device_tool(fc: Any) -> str:
                fut: asyncio.Future[dict[str, Any]] = loop.create_future()
                pending[fc.id] = fut
                await _safe_send_json(
                    websocket,
                    {
                        "type": "device_action",
                        "id": fc.id,
                        "name": fc.name,
                        "input": dict(fc.args or {}),
                    },
                )
                try:
                    reply = await asyncio.wait_for(fut, timeout=settings.tool_timeout_seconds + 20)
                except asyncio.TimeoutError:
                    return "Device tool timed out."
                finally:
                    pending.pop(fc.id, None)
                if reply.get("isError"):
                    had_error["v"] = True
                return str(reply.get("content", ""))

            async def _handle_tool_call(tool_call: Any) -> None:
                responses = []
                for fc in tool_call.function_calls:
                    args = dict(fc.args or {})
                    if fc.name in SERVER_TOOL_NAMES:
                        result = await _run_server_tool(fc.name, args)
                    elif fc.name in DEVICE_TOOL_NAMES:
                        result = await _run_device_tool(fc)
                    else:
                        result = f"Unknown tool {fc.name}."
                        had_error["v"] = True
                    responses.append({"id": fc.id, "name": fc.name, "response": {"result": result}})
                await session.send_tool_response(function_responses=responses)

            async def downlink() -> None:
                """Gemini -> phone: audio, transcripts, tool calls, turn control."""
                async for response in session.receive():
                    if response.data is not None:
                        await websocket.send_bytes(response.data)

                    sc = response.server_content
                    if sc is not None:
                        if sc.input_transcription and sc.input_transcription.text:
                            t = sc.input_transcription.text
                            user_buf.append(t)
                            await _safe_send_json(websocket, {"type": "transcript", "text": t})
                        if sc.output_transcription and sc.output_transcription.text:
                            t = sc.output_transcription.text
                            assistant_buf.append(t)
                            await _safe_send_json(websocket, {"type": "delta", "text": t})
                        if sc.interrupted:
                            await _safe_send_json(websocket, {"type": "interrupted"})
                        if sc.turn_complete:
                            await _safe_send_json(websocket, {"type": "turn_complete"})
                            user_text = "".join(user_buf).strip()
                            assistant_text = "".join(assistant_buf).strip()
                            err = had_error["v"]
                            asyncio.create_task(
                                asyncio.to_thread(
                                    _post_turn_live, device_id, user_text, assistant_text, err,
                                )
                            )
                            user_buf.clear()
                            assistant_buf.clear()
                            had_error["v"] = False

                    if response.tool_call is not None:
                        await _handle_tool_call(response.tool_call)

            up = asyncio.create_task(uplink())
            down = asyncio.create_task(downlink())
            done, pending_tasks = await asyncio.wait(
                {up, down}, return_when=asyncio.FIRST_EXCEPTION,
            )
            for task in pending_tasks:
                task.cancel()
            for task in done:
                exc = task.exception()
                if exc and not isinstance(exc, WebSocketDisconnect):
                    raise exc

    except WebSocketDisconnect:
        logger.info("live: phone disconnected (device=%s)", device_id)
    except Exception as exc:
        logger.exception("live: session error")
        await _safe_send_json(websocket, {"type": "error", "message": str(exc)})
    finally:
        for fut in pending.values():
            if not fut.done():
                fut.cancel()
        try:
            await websocket.close()
        except Exception:
            pass


async def _safe_send_json(websocket: WebSocket, payload: dict[str, Any]) -> None:
    try:
        await websocket.send_json(payload)
    except Exception:
        logger.debug("live: send_json failed (socket closing?)", exc_info=True)
