# Kodi — Audit & Implementation Plan
_Generated after full codebase + spec review — April 2026_

---

## Audit: What Is Complete ✅

### Backend
- All 6 API endpoints (health, register, sessions, audio, text, tool-result)
- HMAC-SHA256 device auth (sign + verify, 5-min clock skew)
- Device registry with optional Fernet at-rest encryption
- In-memory session state manager
- Claude agent loop (max 4 tools/command, 16 inner steps)
- All 10 tools correctly defined in claude_tools.py
- Server tools: search_web (Brave + Jina + SQLite cache), remember/recall (Mem0+Qdrant)
- Memory: async write-after-turn, search-before-LLM, forget-last/forget-all with 3-min guard
- STT: OpenAI Whisper-1, mono 16 kHz WAV
- System prompt matches spec §5.2 verbatim

### Android
- 3-step onboarding (permissions → backend URL → accessibility)
- KodiVoiceService: foreground mic, Porcupine JARVIS wake word, VAD, mic-release latch
- KodiAccessibilityService: tap/gesture, text-set, root-wait, find-by-text, collect-visible-text
- DeviceToolExecutor: send_whatsapp_message (1 retry), send_sms, make_phone_call, open_app, toggle_setting, get_contacts, read_last_message
- KodiRepository: full tool loop, audio/text/tool-result API, on-device STT fallback
- KodiPrefs: EncryptedSharedPreferences AES256-GCM
- TtsManager: sentence-split queue, stop-on-new-speak
- WavUtil, HmacInterceptor, OnDeviceSpeech

---

## Audit: Gaps Found ❌

### CRITICAL — blocks spec compliance

| # | File | Gap |
|---|------|-----|
| C1 | KodiVoiceService.kt | Wake word is JARVIS; notification says "Hey Kodi" — misleads users; must update notification text to match actual keyword |
| C2 | KodiVoiceService.kt | TTS interruption broken — CountDownLatch blocks wake loop for up to 180s; a new wake trigger during TTS response cannot interrupt |
| C3 | DeviceToolExecutor.kt | send_whatsapp_message returns success without verifying sent-message appears in UI tree (spec §4.4.2) |
| C4 | DeviceToolExecutor.kt | toggle_setting opens a system panel instead of programmatically toggling WiFi/BT — CHANGE_WIFI_STATE & BLUETOOTH_CONNECT permissions declared but never used |
| C5 | KodiAccessibilityService.kt | No scrollUp/scrollDown; spec §4.4.1 requires scroll for "any app" |
| C6 | session_manager.py | Sessions never evicted — memory leak on long-running server |
| C7 | stt.py / KodiRepository.kt | Missing OPENAI_API_KEY raises RuntimeError → HTTP 400; Android fallback only catches HttpException/IOException, so on-device STT fallback never fires for this case |
| C8 | AndroidManifest.xml | READ_SMS not declared; read_last_message SMS path fails silently |

### MINOR — quality / completeness

| # | File | Gap |
|---|------|-----|
| M1 | DeviceToolExecutor.kt | knownPackages map missing: YouTube, Gmail, Maps, Spotify, Telegram, Instagram, Netflix, Calendar, TikTok, Twitter/X |
| M2 | DeviceToolExecutor.kt | resolvePhone fuzzy match too broad — "an" matches "Danny" |
| M3 | DeviceToolExecutor.kt | read_last_message types into focused field without tapping search bar first |
| M4 | memory_service.py | forget_last uses empty-string Mem0 search — undefined ordering |
| M5 | session_manager.py | SessionState mutated (claude_messages) outside lock — thread-unsafe under concurrent requests |
| M6 | MainActivity.kt | Onboarding step 2 shows plain text status, no green/red colour (spec §12.1) |
| M7 | .env.example | TOOL_TIMEOUT_SECONDS, LLM_TIMEOUT_SECONDS, MAX_TOOLS_PER_COMMAND not documented |
| M8 | stt.py | OpenAI client has no timeout set; should respect LLM_TIMEOUT_SECONDS |
| M9 | DeviceToolExecutor.kt | toggleSetting volume/brightness (numeric values) not handled — only WiFi/BT panel |
| M10 | agent_loop.py | Tool result content can be 8000 chars (collectVisibleText); no truncation before injecting back into Claude messages |

---

## Implementation Plan

Ordered by priority: Critical bugs first, then minor quality, then new capability.

---

### PHASE 1 — Critical Fixes (all blocking spec compliance)

#### P1-1: Fix notification text to match actual wake word
**File:** `strings.xml`  
Change `"Kodi is listening for "Hey Kodi""` → `"Kodi is listening for "Jarvis""` until custom model ships.  
Also update `MainHome` description text in `MainActivity.kt`.

#### P1-2: Fix TTS interruption — decouple wake loop from pipeline latch
**File:** `KodiVoiceService.kt`  
**Root cause:** `pipelineDone.await(180s)` in the Porcupine loop means no new wake word can be processed during TTS.  
**Fix:** Remove the `CountDownLatch` block. Allow the pipeline coroutine to run independently. Track a `@Volatile var pipelineRunning` flag and cancel the active pipeline job before starting a new one.

```kotlin
// Replace CountDownLatch with a cancellable Job
@Volatile private var activePipelineJob: Job? = null

// In wake detected block:
activePipelineJob?.cancel()
activePipelineJob = scope.launch {
    tts.stop()
    val pcm = recordCommandPcm()
    runPipeline(pcm)
}
// Do NOT await — loop continues immediately
```

#### P1-3: WhatsApp send confirmation via UI tree
**File:** `DeviceToolExecutor.kt` → `sendWhatsAppOnce`  
After tapping Send and delaying 600ms, scan the UI tree for the last message bubble and verify its text matches the sent message (partial match). If not found, return failure string.

#### P1-4: Programmatic WiFi/Bluetooth toggle
**File:** `DeviceToolExecutor.kt` → `toggleSetting`  
Use `WifiManager.setWifiEnabled` (API ≤ 28) and `BluetoothAdapter.enable/disable`. For API 29+ these are deprecated — fall back to opening the Settings panel with clear user messaging. Handle `volume` (AudioManager) and `brightness` (Settings.System.SCREEN_BRIGHTNESS).

#### P1-5: Add scroll capability
**File:** `KodiAccessibilityService.kt` — add `scrollNode(direction)` using `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD`.  
**File:** `DeviceToolExecutor.kt` — add `scroll` action (or extend `open_app` tool).  
**File:** `claude_tools.py` — add `scroll` tool: `{ direction: "up"|"down", amount?: int }`.

#### P1-6: Session TTL eviction
**File:** `session_manager.py`  
Add `created_at: float` to `SessionState`. Add `evict_stale(max_age_seconds=3600)` method. Call it on every `create()` call (lazy eviction — zero overhead).

#### P1-7: STT error → on-device fallback
**File:** `stt.py` — wrap `transcribe_wav` so missing key raises `HTTPException(503)` not 500/RuntimeError.  
**File:** `main.py` — catch the 503 specifically and trigger the text endpoint flow with a marker.  
**Simpler fix:** In `KodiRepository.kt`, also catch `HttpException` with code 400/503 to trigger local fallback.

#### P1-8: Declare READ_SMS permission
**File:** `AndroidManifest.xml` — add `<uses-permission android:name="android.permission.READ_SMS"/>`.  
**File:** `MainActivity.kt` — add `Manifest.permission.READ_SMS` to onboarding permissions list.

---

### PHASE 2 — Minor Quality Fixes

#### P2-1: Expand knownPackages map
**File:** `DeviceToolExecutor.kt`  
Add: youtube, gmail, maps, google maps, spotify, telegram, instagram, netflix, calendar, twitter, tiktok, facebook, snapchat, uber, google photos, clock, calculator.

#### P2-2: Fix resolvePhone fuzzy match
**File:** `DeviceToolExecutor.kt`  
Change matching logic: prefer exact match, then startsWith, then contains — and require needle length ≥ 3 before doing contains to avoid false positives.

#### P2-3: Fix read_last_message search flow
**File:** `DeviceToolExecutor.kt` → `readLastMessage`  
Add the same search-bar tap + typed search pattern used in `sendWhatsAppOnce` before typing the contact name.

#### P2-4: Fix forget_last ordering
**File:** `memory_service.py`  
Use `mem.get_all(user_id=user_id)` sorted by created_at descending, delete the most recent ID.

#### P2-5: Thread-safe SessionState mutations
**File:** `session_manager.py`  
Wrap `claude_messages` mutations in `agent_loop.py` with a per-session `threading.Lock`. Simplest: add `lock: threading.Lock = field(default_factory=threading.Lock)` to `SessionState` and acquire it in `start_command`, `apply_tool_result`, and `run_agent_step`.

#### P2-6: Onboarding green/red status colour
**File:** `MainActivity.kt` → `OnboardingBackend`  
Color the status Text: green if starts with "Connected", red otherwise.

#### P2-7: Document all env vars
**File:** `backend/.env.example`  
Add `TOOL_TIMEOUT_SECONDS=10.0`, `LLM_TIMEOUT_SECONDS=15.0`, `MAX_TOOLS_PER_COMMAND=4` with comments.

#### P2-8: STT timeout
**File:** `stt.py`  
Pass `timeout=get_settings().llm_timeout_seconds` to the OpenAI client constructor.

#### P2-9: Truncate large tool results before re-injecting
**File:** `agent_loop.py`  
Cap `out` from `_execute_server_tool` at 6000 chars. Cap device tool results posted via `apply_tool_result` at 6000 chars in `main.py`.

#### P2-10: Handle numeric toggle_setting values
**File:** `DeviceToolExecutor.kt`  
For `setting=volume`: use `AudioManager.STREAM_MUSIC` + `setStreamVolume`. For `setting=brightness`: write `Settings.System.SCREEN_BRIGHTNESS` (requires `WRITE_SETTINGS` permission — add to manifest with runtime request).

---

### PHASE 3 — New Capability (spec features not yet wired end-to-end)

#### P3-1: Scroll tool — end-to-end
Wire the new `scroll` tool (P1-5) through the full stack: claude_tools.py → agent_loop.py DEVICE_TOOL_NAMES → DeviceToolExecutor → KodiAccessibilityService.

#### P3-2: Telegram tool support
Add `send_telegram_message` tool (same pattern as WhatsApp) to claude_tools.py and DeviceToolExecutor. Telegram's UI tree is more consistent than WhatsApp's.

#### P3-3: Email via Gmail
Add `send_email` tool: `{ to: str, subject: str, body: str }`. Use Gmail accessibility path (open Gmail, compose, fill fields, send).

#### P3-4: Calendar event creation
Add `create_calendar_event` tool: `{ title: str, date: str, time: str, duration_minutes?: int }`. Use Android `CalendarContract` insert intent — no accessibility needed.

#### P3-5: Set alarm / timer
Add `set_alarm` tool and `set_timer` tool using `AlarmClock` intent — ACTION_SET_ALARM / ACTION_SET_TIMER. Requires no permissions, works on all launchers.

#### P3-6: Play music / media control
Add `play_media` tool: `{ query: str, app?: str }`. Opens Spotify/YouTube Music via deep-link with search query. Add `media_control` tool: `{ action: "play"|"pause"|"next"|"previous" }` using `MediaSessionManager` or `AudioManager` transport controls.

#### P3-7: Read notifications
Add `read_notifications` tool. Use `NotificationListenerService` — add service to manifest with `BIND_NOTIFICATION_LISTENER_SERVICE` permission. Returns last N notification titles+texts.

#### P3-8: Take a screenshot / describe screen
Add `describe_screen` tool. Use `collectVisibleText` from current foreground window and return it to the agent. No screenshot permission needed — accessibility tree is sufficient.

#### P3-9: Navigation / Maps
Add `navigate_to` tool: `{ destination: str }`. Opens Google Maps via URI `geo:0,0?q=<destination>` or `https://maps.google.com/?q=...`. No permissions needed.

#### P3-10: WhatsApp group messaging (V1.1 prep)
Document the UI tree path for WhatsApp groups. Add optional `group: bool` parameter to `send_whatsapp_message`. The group chat open flow differs — groups appear in the main list without needing search navigation.

---

### PHASE 4 — Infrastructure & Ops Hardening

#### P4-1: Dockerize backend
Add `backend/Dockerfile` (Python 3.12-slim, non-root user, uvicorn). Update `infra/docker-compose.yml` to include the backend service behind Caddy.

#### P4-2: Caddy reverse proxy config
Add `infra/Caddyfile` with automatic Let's Encrypt TLS, reverse proxy to `localhost:8000`, HTTP→HTTPS redirect.

#### P4-3: Systemd service file
`backend/deploy/kodi-backend.service` already referenced in infra README — create it.

#### P4-4: Log rotation config
Add `backend/deploy/logrotate.conf` for `/var/log/kodi/backend.log`.

#### P4-5: Backend health endpoint — add version + uptime
Extend `GET /v1/health` to return `{"status":"ok","version":"1.0.0","uptime_seconds":N}`.

#### P4-6: Android release build — enable minification
`build.gradle.kts`: set `isMinifyEnabled = true` for release and add ProGuard rules for Retrofit, Gson, Porcupine.

---

## Execution Order

```
Week 1:  P1-1 through P1-8  (all critical fixes — app is spec-compliant)
Week 2:  P2-1 through P2-10 (minor quality — reliability pass)
Week 3:  P3-1 through P3-6  (high-value new tools: scroll, email, alarm, media, maps)
Week 4:  P3-7 through P3-10 (notifications, screen-read, groups)
Week 5:  P4-1 through P4-6  (infra hardening — production-ready)
```

## Cost Impact of New Tools
All Phase 3 tools are device-side (AccessibilityService or Android intents) — **zero additional API cost**. Each adds one Claude tool call at ~$0.003/use.

---

---

## Implementation Status — ALL PHASES COMPLETE ✅
_Last updated: April 2026_

### What was implemented (phases 1–4 + extras)

| Phase | Items | Status |
|-------|-------|--------|
| P1 Critical Fixes | P1-1 through P1-8 | ✅ Done |
| P2 Quality Fixes | P2-1 through P2-10 | ✅ Done |
| P3 New Capabilities | P3-1 through P3-10 (incl. groups) | ✅ Done |
| P4 Infra Hardening | P4-1 through P4-6 | ✅ Done |
| Extras | Session 404 auto-recovery, WRITE_SETTINGS canWrite guard, Map<String,Any> health fix | ✅ Done |

### New files created
- `backend/Dockerfile` — non-root Python 3.12 container
- `infra/Caddyfile` — HTTPS reverse proxy + HSTS
- `backend/deploy/kodi-backend.service` — hardened systemd unit
- `backend/deploy/logrotate.conf` — 14-day log rotation
- `android/.../KodiNotificationService.kt` — NotificationListenerService + NotificationBridge
- `android/res/xml/notification_service_config.xml`
- `android/app/proguard-rules.pro` — Retrofit/Gson/Porcupine keep rules

### Manual steps before production
1. **Picovoice key** — set `PICOVOICE_ACCESS_KEY` in `android/local.properties`
2. **Backend env** — copy `backend/.env.example` to `backend/.env`, fill all keys
3. **Caddy domain** — replace `YOUR_DOMAIN` in `infra/Caddyfile`
4. **Notification listener** — user must grant in Settings > Apps > Special app access > Notification access
5. **WRITE_SETTINGS** — user must grant in Settings > Apps > Special app access > Modify system settings (prompted automatically on first brightness command)
6. **Logrotate install** — `sudo cp backend/deploy/logrotate.conf /etc/logrotate.d/kodi`
7. **Systemd install** — `sudo cp backend/deploy/kodi-backend.service /etc/systemd/system/ && sudo systemctl enable --now kodi-backend`
