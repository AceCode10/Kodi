# Kodi — Development Progress

_Last updated: April 2026_

## Status: All planned phases complete ✅

> **Scope:** this build extends the original PDF spec. The authoritative as-built scope
> (28 tools, briefing, Home Assistant, learning, expanded UI) is recorded in
> `SPEC_AMENDMENT_v1.0.md`.

---

## Phase 1 — Critical Fixes ✅

| ID | Task | File(s) |
|----|------|---------|
| P1-1 | Fix notification text to say "JARVIS" | `strings.xml`, `MainActivity.kt` |
| P1-2 | Fix TTS interruption — cancellable coroutine Job replaces blocking CountDownLatch | `KodiVoiceService.kt` |
| P1-3 | WhatsApp send confirmation via UI tree scan | `DeviceToolExecutor.kt` |
| P1-4 | Programmatic WiFi/BT toggle + volume/brightness | `DeviceToolExecutor.kt` |
| P1-5 | Scroll capability — service + executor + backend tool | `KodiAccessibilityService.kt`, `DeviceToolExecutor.kt`, `claude_tools.py` |
| P1-6 | Session TTL eviction — lazy eviction on every `create()` | `session_manager.py` |
| P1-7 | STT error → on-device fallback — raises HTTP 503 to trigger Android fallback | `stt.py`, `main.py`, `KodiRepository.kt` |
| P1-8 | Declare READ_SMS + WRITE_SETTINGS permissions | `AndroidManifest.xml` |

---

## Phase 2 — Quality Fixes ✅

| ID | Task | File(s) |
|----|------|---------|
| P2-1 | Expand knownPackages map (20+ apps) | `DeviceToolExecutor.kt` |
| P2-2 | Fix resolvePhone fuzzy match (exact → prefix → contains with length guard) | `DeviceToolExecutor.kt` |
| P2-3 | Fix read_last_message — tap search bar before typing | `DeviceToolExecutor.kt` |
| P2-4 | Fix forget_last ordering — sort by created_at desc | `memory_service.py` |
| P2-5 | Thread-safe SessionState mutations with per-session lock | `session_manager.py`, `agent_loop.py` |
| P2-6 | Onboarding backend status shows green/red colour | `MainActivity.kt` |
| P2-7 | Document TOOL_TIMEOUT_SECONDS, LLM_TIMEOUT_SECONDS, MAX_TOOLS_PER_COMMAND | `.env.example` |
| P2-8 | STT timeout configurable via env var | `stt.py` |
| P2-9 | Truncate tool results to 6000 chars before injecting into Claude messages | `agent_loop.py` |
| P2-10 | Handle numeric value for volume/brightness in toggle_setting | `DeviceToolExecutor.kt` |

---

## Phase 3 — New Capability Tools ✅

| ID | Task | File(s) |
|----|------|---------|
| P3-1 | Scroll tool end-to-end | `KodiAccessibilityService.kt`, `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-2 | Telegram send tool | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-3 | Email via Gmail (ACTION_SEND intent) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-4 | Calendar event creation (CalendarContract intent) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-5 | Set alarm + timer (AlarmClock intent) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-6 | Play media + media control (Spotify/YouTube intent + AudioManager key events) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-7 | Read notifications (NotificationListenerService + NotificationBridge) | `KodiNotificationService.kt`, `notification_service_config.xml`, `AndroidManifest.xml` |
| P3-8 | Describe screen (collect visible UI tree text) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-9 | Navigate to / Maps (geo URI intent) | `DeviceToolExecutor.kt`, `claude_tools.py` |
| P3-10 | WhatsApp group messaging (`group: true` parameter) | `DeviceToolExecutor.kt`, `claude_tools.py` |

---

## Phase 4 — Infrastructure & Ops ✅

| ID | Task | File(s) |
|----|------|---------|
| P4-1 | Dockerize backend (Python 3.12-slim, non-root) | `backend/Dockerfile` |
| P4-2 | Caddy reverse proxy (HTTPS, HSTS, gzip) | `infra/Caddyfile` |
| P4-3 | Systemd service (hardened, auto-restart) | `backend/deploy/kodi-backend.service` |
| P4-4 | Log rotation (14-day, compressed, kodi user) | `backend/deploy/logrotate.conf` |
| P4-5 | Health endpoint returns version + uptime_seconds | `backend/app/main.py` |
| P4-6 | Android release build minification + ProGuard rules | `build.gradle.kts`, `proguard-rules.pro` |

---

## Extra Fixes (caught during audit) ✅

| ID | Task | File(s) |
|----|------|---------|
| E-1 | Session 404 auto-recovery — clears stale session and retries transparently | `KodiRepository.kt` |
| E-2 | WRITE_SETTINGS canWrite guard — prompts ACTION_MANAGE_WRITE_SETTINGS if not granted | `DeviceToolExecutor.kt`, `AndroidManifest.xml` |
| E-3 | Fix `health()` Retrofit type `Map<String,Any>` — prevents Gson crash on `uptime_seconds` int | `KodiApi.kt`, `KodiRepository.kt` |
| E-4 | Manual "Speak command" UI — primary button, wake word demoted to coming-soon section | `MainActivity.kt` |
| E-5 | docker-compose.yml includes backend service alongside Qdrant | `infra/docker-compose.yml` |

---

## Second-Pass Production Audit Fixes ✅
_Last updated: May 2026_

| ID | Task | File(s) |
|----|------|---------|
| A2-1 | `SmsManager.getDefault()` deprecated on API 31+ — use `context.getSystemService(SmsManager)` and multipart send for long messages | `DeviceToolExecutor.kt` |
| A2-2 | `findByText` exact-match bug — was concatenating `text + contentDescription` so `partial=false` never matched (e.g. WhatsApp/Telegram Send button) | `KodiAccessibilityService.kt` |
| A2-3 | `describe_screen` now refuses to read sensitive-app screens (bank/wallet/password) | `DeviceToolExecutor.kt` |
| A2-4 | `read_notifications` filters out sensitive-package notifications and over-fetches to preserve requested count | `DeviceToolExecutor.kt` |
| A2-5 | STT fallback no longer fires on 401/403 — surfaces re-pair guidance instead of wasting on-device STT | `KodiRepository.kt` |
| A2-6 | Wake loop guards `AudioRecord.STATE_INITIALIZED` and `startRecording` failures with back-off; prevents CPU spin if mic permission is revoked at runtime | `KodiVoiceService.kt` |
| A2-7 | Onboarding step 1 skips re-prompt when all required permissions are already granted | `MainActivity.kt` |
| A2-8 | Server session eviction also runs on `get()` every 5 min — long-idle servers without new registrations still reclaim memory | `session_manager.py` |
| A2-9 | Removed unused imports (`AlarmManager`, `PendingIntent`, `Bundle`) | `DeviceToolExecutor.kt` |

---

## New Files Created

| File | Purpose |
|------|---------|
| `IMPLEMENTATION_PLAN.md` | Full audit + phased plan with completion status |
| `PROGRESS.md` | This file |
| `backend/Dockerfile` | Container image for backend |
| `infra/Caddyfile` | Caddy HTTPS reverse proxy config |
| `backend/deploy/kodi-backend.service` | systemd unit |
| `backend/deploy/logrotate.conf` | Log rotation config |
| `android/.../KodiNotificationService.kt` | NotificationListenerService implementation |
| `android/res/xml/notification_service_config.xml` | Notification service metadata |
| `android/app/proguard-rules.pro` | Release build ProGuard rules |

---

## Pending Manual Steps Before Production

1. **Picovoice key** — awaiting use-case approval; set `PICOVOICE_ACCESS_KEY` in `android/local.properties` when received
2. **Backend env** — copy `backend/.env.example` → `backend/.env`, fill all API keys
3. **Caddy domain** — replace `YOUR_DOMAIN` in `infra/Caddyfile`
4. **Notification access** — grant in Settings → Apps → Special app access → Notification access
5. **Modify system settings** — auto-prompted on first brightness command
6. **Logrotate** — `sudo cp backend/deploy/logrotate.conf /etc/logrotate.d/kodi`
7. **Systemd** — `sudo cp backend/deploy/kodi-backend.service /etc/systemd/system/ && sudo systemctl enable --now kodi-backend`
