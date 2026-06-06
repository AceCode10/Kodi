# Kodi — Specification Amendment v1.0

_Adopted May 2026. Authoritative scope record for the shipped v1.0 build._

## Purpose

`Kodi_Product_Specification_v1.0.pdf` §5.3 states: *"No additional tools are added in V1
without a specification amendment."* This document **is** that amendment. It records the
as-built v1.0 scope, which extends the PDF. Where this document and the PDF disagree, this
document governs for v1.0.

## 1. Tool set (supersedes PDF §5.3)

The PDF defined 10 tools. The shipped build defines **28**. Full set, as in
`backend/app/claude_tools.py`:

**Device tools (23)** — executed on the Android device:
`send_whatsapp_message`, `send_sms`, `make_phone_call`, `open_app`, `toggle_setting`,
`get_contacts`, `read_last_message`, `scroll`, `send_telegram_message`, `send_email`,
`create_calendar_event`, `set_alarm`, `set_timer`, `play_media`, `media_control`,
`navigate_to`, `describe_screen`, `read_notifications`, `tap_on_screen`,
`type_into_field`, `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task`.

**Server tools (5)** — executed on the backend:
`search_web`, `remember`, `recall`, `call_home_assistant`, `record_lesson`.

Notes on tool behaviour that the model must phrase accurately:
- `send_email`, `create_calendar_event` and `play_media` open a pre-filled composer /
  editor / search — they do not silently complete the action. Their tool descriptions
  reflect this so the agent does not claim a completed send.
- `send_whatsapp_message` / `send_telegram_message` deliver via the AccessibilityService.
  Once the Send button is tapped, the tool never reports a retryable failure — this
  prevents a verification false-negative from causing a duplicate send.

## 2. Features beyond PDF §13 ("Out of Scope")

The following are **in scope for v1.0** as built, overriding the PDF's deferral/exclusion:

| Feature | PDF §13 status | v1.0 amended status |
|---|---|---|
| WhatsApp group messaging (`group` param) | V2 | In v1.0 |
| Daily morning briefing (`/v1/briefing`, `BriefingWorker`) | Excluded (proactive) | In v1.0 |
| Scheduled tasks (`schedule_task` family) | Excluded (proactive) | In v1.0 |
| Home Assistant control (`call_home_assistant`) | Excluded (smart home) | In v1.0 |
| Behavioural learning / lessons (`record_lesson`, `learning.py`) | Not specified | In v1.0 |

## 3. UI surface (supersedes PDF §1.1 "minimal surface")

The PDF mandated no screens beyond a 3-step onboarding. The build adds, and v1.0 retains:
- A home screen with a manual "Speak command" button and a conversation transcript view.
- A Settings screen (backend URL, wake word, appearance, briefing time, app access,
  device controls).
- An app-access (allowlist) screen.

The wake-word phrase shown to the user is **"Jarvis"** — the Porcupine free-tier built-in
keyword. The PDF's "Hey Kodi" label is not used, because no "Hey Kodi" model exists; a
custom model remains a V2 item.

## 4. Unchanged from the PDF

Architecture, request lifecycle, HMAC auth, memory model (Mem0 + Qdrant), STT (Whisper +
on-device fallback), TTS, the §5.2 system prompt, security/privacy boundaries, and the
performance targets are as the PDF specifies.
