package ai.kodi.app.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.kodi.app.data.AppAllowlist
import ai.kodi.app.data.CommandDto
import ai.kodi.app.schedule.ScheduledTask
import ai.kodi.app.schedule.ScheduledTaskStore
import ai.kodi.app.schedule.TaskScheduler
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

object DeviceToolExecutor {
    private const val TAG = "KodiTools"

    private fun JsonObject.optStr(key: String): String? {
        val e = get(key) ?: return null
        return if (e is JsonPrimitive && !e.isJsonNull) e.asString else null
    }

    private fun JsonObject.optBool(key: String, default: Boolean = false): Boolean {
        val e = get(key) ?: return default
        if (e !is JsonPrimitive || !e.isBoolean) return default
        return e.asBoolean
    }

    private val sensitiveFragments = listOf(
        "bank", "wallet", "paypal", "chase", "lastpass", "1password", "bitwarden",
        "authenticator", "trezor", "ledger",
    )

    /** Run one device tool. A [ToolFailure] anywhere inside becomes an error outcome. */
    suspend fun execute(context: Context, cmd: CommandDto): ToolOutcome = try {
        ToolOutcome(dispatch(context, cmd), isError = false)
    } catch (e: ToolFailure) {
        Log.w(TAG, "device tool ${cmd.toolName} failed: ${e.reason}")
        ToolOutcome(e.reason, isError = true)
    }

    private suspend fun dispatch(context: Context, cmd: CommandDto): String {
        val name = cmd.toolName ?: fail("No tool name.")
        val input = cmd.toolInput ?: JsonObject()
        return when (name) {
            "send_whatsapp_message" -> sendWhatsAppWithRetry(context, input)
            "send_sms" -> sendSms(context, input)
            "make_phone_call" -> call(context, input)
            "open_app" -> openApp(context, input)
            "toggle_setting" -> toggleSetting(context, input)
            "get_contacts" -> getContacts(context, input)
            "read_last_message" -> readLastMessage(context, input)
            "scroll" -> scroll(input)
            "send_telegram_message" -> sendTelegram(context, input)
            "send_email" -> sendEmail(context, input)
            "create_calendar_event" -> createCalendarEvent(context, input)
            "set_alarm" -> setAlarm(context, input)
            "set_timer" -> setTimer(context, input)
            "play_media" -> playMedia(context, input)
            "media_control" -> mediaControl(context, input)
            "navigate_to" -> navigateTo(context, input)
            "describe_screen" -> describeScreen()
            "read_notifications" -> readNotifications(input)
            "tap_on_screen" -> tapOnScreen(input)
            "type_into_field" -> typeIntoField(input)
            "schedule_task" -> scheduleTask(context, input)
            "list_scheduled_tasks" -> listScheduledTasks(context)
            "cancel_scheduled_task" -> cancelScheduledTask(context, input)
            else -> fail("Unsupported device tool: $name")
        }
    }

    private suspend fun tapOnScreen(input: JsonObject): String {
        val text = input.optStr("text")?.trim().orEmpty()
        if (text.isEmpty()) fail("Provide text to tap.")
        val partial = input.optBool("partial", true)
        val svc = AccessibilityBridge.get() ?: fail("Accessibility service not enabled.")
        val root = svc.waitForRoot(3000) ?: fail("Could not read screen.")
        val node = svc.findByText(root, text, partial) ?: fail("No tappable element matching \"$text\".")
        val ok = svc.tapNode(node)
        if (!ok) fail("Tap on \"$text\" failed.")
        return "Tapped \"$text\"."
    }

    private suspend fun typeIntoField(input: JsonObject): String {
        val text = input.optStr("text") ?: fail("Provide text to type.")
        val svc = AccessibilityBridge.get() ?: fail("Accessibility service not enabled.")
        val ok = svc.typeIntoFocusedField(text)
        if (!ok) fail("No editable field on screen.")
        return "Typed text."
    }

    private fun scheduleTask(context: Context, input: JsonObject): String {
        val whenIso = input.optStr("when_iso")?.trim().orEmpty()
        val task = input.optStr("task")?.trim().orEmpty()
        val id = input.optStr("id")?.trim().orEmpty().ifEmpty { UUID.randomUUID().toString() }
        if (whenIso.isEmpty() || task.isEmpty()) fail("Provide when_iso and task.")
        val whenMillis = parseLocalIso(whenIso) ?: fail("Could not parse when_iso \"$whenIso\".")
        if (whenMillis <= System.currentTimeMillis()) fail("Scheduled time is in the past.")
        val entry = ScheduledTaskStore(context).upsert(
            ScheduledTask(id = id, whenMillis = whenMillis, text = task),
        )
        val exact = TaskScheduler.schedule(context, entry)
        val human = SimpleDateFormat("EEE HH:mm 'on' d MMM", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(whenMillis))
        return if (exact) {
            "Scheduled \"${entry.text}\" for $human (id ${entry.id})."
        } else {
            "Scheduled \"${entry.text}\" for $human (inexact — grant Alarms & reminders permission for exact firing). Id ${entry.id}."
        }
    }

    private fun listScheduledTasks(context: Context): String {
        val list = ScheduledTaskStore(context).list().sortedBy { it.whenMillis }
        if (list.isEmpty()) return "No scheduled tasks."
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return list.joinToString("\n") { "${it.id} | ${fmt.format(Date(it.whenMillis))} | ${it.text}" }
    }

    private fun cancelScheduledTask(context: Context, input: JsonObject): String {
        val id = input.optStr("id")?.trim().orEmpty()
        if (id.isEmpty()) fail("Provide id.")
        val gone = ScheduledTaskStore(context).remove(id) ?: fail("No task with id $id.")
        TaskScheduler.cancel(context, gone.id, gone.text)
        return "Cancelled \"${gone.text}\"."
    }

    internal fun parseLocalIso(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                // If pattern lacks tz, interpret as device-local
                if (!p.contains("XXX")) sdf.timeZone = TimeZone.getDefault()
                return sdf.parse(iso)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun sensitiveForeground(svc: KodiAccessibilityService): Boolean {
        val pkg = svc.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: return false
        return sensitiveFragments.any { pkg.contains(it) }
    }

    private suspend fun sendWhatsAppWithRetry(context: Context, input: JsonObject): String {
        // Only pre-send failures unwind as ToolFailure. Once the Send button is tapped,
        // sendWhatsAppOnce returns normally, so a retry can never duplicate a delivered
        // message. If both attempts fail, report the first failure.
        val first: ToolFailure
        try {
            return sendWhatsAppOnce(context, input)
        } catch (e: ToolFailure) {
            first = e
        }
        delay(900)
        return try {
            sendWhatsAppOnce(context, input)
        } catch (_: ToolFailure) {
            throw first
        }
    }

    private suspend fun sendWhatsAppOnce(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: fail("Missing contact.")
        val message = input.optStr("message") ?: fail("Missing message.")
        val isGroup = input.optBool("group", false)
        val svc = AccessibilityBridge.get() ?: fail("Turn on Kodi accessibility first.")
        if (sensitiveForeground(svc)) fail("I can't use that app here.")
        val ok = svc.openAppPackage("com.whatsapp")
        if (!ok) fail("I can't find WhatsApp on this phone.")
        delay(800)
        var root = svc.waitForRoot(4000) ?: fail("WhatsApp UI not ready.")

        if (isGroup) {
            val groupNode = svc.findByText(root, contact, partial = true)
            if (groupNode != null) {
                svc.tapNode(groupNode)
            } else {
                fail("Group '$contact' not found in WhatsApp.")
            }
        } else {
            val searchHints = listOf("search", "search…", "search contacts")
            var tapped = false
            for (hint in searchHints) {
                val n = svc.findByText(root, hint, partial = true)
                if (n != null && svc.tapNode(n)) { tapped = true; break }
            }
            if (!tapped) Log.i(TAG, "WhatsApp search control not found; continuing")
            delay(400)
            svc.typeIntoFocusedField(contact)
            delay(1200)
            root = svc.waitForRoot(3000) ?: fail("Could not open chat.")
            val first = findFirstConversationRow(root)
            if (first != null) svc.tapNode(first) else svc.findByText(root, contact, true)?.let { svc.tapNode(it) }
        }

        delay(1000)
        root = svc.waitForRoot(3000) ?: fail("Conversation not open.")
        svc.typeIntoFocusedField(message)
        delay(400)
        val send = svc.findByText(root, "send", partial = false)
            ?: svc.findByText(svc.rootInActiveWindow, "send", partial = true)
        // A pre-send failure (button missing / tap failed) is safe to retry; once Send is
        // tapped the message is out, so every path below reports "Sent WhatsApp" (no retry).
        val sendTapped = send != null && svc.tapNode(send)
        if (!sendTapped) fail("Could not find the WhatsApp send button for $contact.")
        delay(800)
        val finalRoot = svc.waitForRoot(3000)
        val confirmed = finalRoot != null && verifySentMessage(finalRoot, message)
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (confirmed) {
            "Sent WhatsApp to $contact."
        } else {
            "Sent WhatsApp to $contact (could not confirm it appeared in the chat)."
        }
    }

    private fun findFirstConversationRow(root: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val cls = n.className?.toString() ?: ""
            if (cls.contains("RecyclerView", true) || cls.contains("ListView", true)) {
                if (n.childCount > 0) return n.getChild(0)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private suspend fun scroll(input: JsonObject): String {
        val direction = input.optStr("direction") ?: "down"
        val amount = input.get("amount")?.let { if (it is JsonPrimitive && it.isNumber) it.asInt else 1 } ?: 1
        val svc = AccessibilityBridge.get() ?: fail("Accessibility not enabled.")
        repeat(amount.coerceIn(1, 10)) {
            svc.scrollNode(direction)
            kotlinx.coroutines.delay(300)
        }
        return "Scrolled $direction."
    }

    private fun verifySentMessage(root: android.view.accessibility.AccessibilityNodeInfo?, message: String): Boolean {
        if (root == null) return false
        val needle = message.take(30).lowercase().trim()
        // An empty needle matches everything, which would confirm any send.
        if (needle.isEmpty()) return false
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            // When a send fails the text is still sitting in the compose box, so matching
            // an editable node would report the failure as a confirmed delivery. Only a
            // non-editable node (an actual chat bubble) counts as confirmation.
            if (!n.isEditable) {
                val t = n.text?.toString()?.lowercase() ?: ""
                if (t.contains(needle)) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    private suspend fun sendSms(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: fail("Missing contact.")
        val message = input.optStr("message") ?: fail("Missing message.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            fail("SMS permission not granted.")
        }
        val dest = resolvePhone(context, contact) ?: fail("I don't recognise $contact. Can you check the spelling?")
        return withContext(Dispatchers.IO) {
            try {
                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                if (sm == null) fail("SMS service unavailable.")
                val parts = sm.divideMessage(message)
                if (parts.size > 1) {
                    sm.sendMultipartTextMessage(dest, null, parts, null, null)
                } else {
                    sm.sendTextMessage(dest, null, message, null, null)
                }
                "SMS sent to $contact."
            } catch (e: Exception) {
                Log.e(TAG, "sms", e)
                fail("SMS failed: ${e.message}")
            }
        }
    }

    private suspend fun call(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: fail("Missing contact.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            fail("Phone permission not granted.")
        }
        val dest = resolvePhone(context, contact) ?: fail("I don't recognise $contact. Can you check the spelling?")
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$dest"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Calling $contact."
            } catch (e: Exception) {
                Log.e(TAG, "call", e)
                fail("Call failed: ${e.message}")
            }
        }
    }

    private suspend fun openApp(context: Context, input: JsonObject): String {
        val url = input.optStr("url")?.trim().orEmpty()
        val readVisible = input.optBool("read_visible_text", false)
        if (url.isNotEmpty()) {
            val err = withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            if (err != null) fail("Could not open URL: $err")
            if (!readVisible) return "Opened browser."
            delay(2500)
            val svc = AccessibilityBridge.get()
            val root = svc?.waitForRoot(5000)
            val text = svc?.collectVisibleText(root).orEmpty()
            return if (text.isBlank()) {
                "Opened URL; no readable text in the accessibility tree."
            } else {
                "Opened URL. Visible text (truncated):\n${text.take(6000)}"
            }
        }
        val name = input.optStr("app_name") ?: fail("Provide app_name or url.")
        val pm = context.packageManager
        val pkg = resolvePackageForLabel(pm, name)
            ?: knownPackages[name.lowercase()]
        if (pkg == null) fail("Could not find app $name.")
        if (pkg !in AppAllowlist.BUILT_IN_PACKAGES && !AppAllowlist(context).isAllowed(pkg)) {
            fail(
                "Kodi has not been granted access to $name ($pkg). " +
                    "Open Settings → Manage app access and toggle it on.",
            )
        }
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch == null) fail("App not launchable.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { context.startActivity(launch) }
        delay(500)
        if (readVisible) {
            val svc = AccessibilityBridge.get()
            val root = svc?.waitForRoot(3000)
            val text = svc?.collectVisibleText(root).orEmpty().take(6000)
            return if (text.isBlank()) "Opened $name." else "Opened $name. Visible text:\n$text"
        }
        return "Opened $name."
    }

    private val knownPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "settings" to "com.android.settings",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "navigation" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "music" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "netflix" to "com.netflix.mediaclient",
        "calendar" to "com.google.android.calendar",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "facebook" to "com.facebook.katana",
        "snapchat" to "com.snapchat.android",
        "uber" to "com.ubercab",
        "photos" to "com.google.android.apps.photos",
        "google photos" to "com.google.android.apps.photos",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "camera" to "com.android.camera2",
        "play store" to "com.android.vending",
        "files" to "com.google.android.documentsui",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "meet" to "com.google.android.apps.tachyon",
        "google meet" to "com.google.android.apps.tachyon",
        "duo" to "com.google.android.apps.tachyon",
    )

    private fun resolvePackageForLabel(pm: PackageManager, label: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val needle = label.lowercase()
        for (ri in list) {
            val ai = ri.activityInfo
            val appLabel = pm.getApplicationLabel(ai.applicationInfo).toString().lowercase()
            if (appLabel.contains(needle) || needle.contains(appLabel)) {
                return ai.packageName
            }
        }
        return null
    }

    private suspend fun toggleSetting(context: Context, input: JsonObject): String {
        val key = input.optStr("setting")?.lowercase() ?: fail("Missing setting.")
        val rawValue = input.get("value")
        val boolValue: Boolean? = when {
            rawValue is JsonPrimitive && rawValue.isBoolean -> rawValue.asBoolean
            rawValue is JsonPrimitive && rawValue.isString ->
                rawValue.asString.lowercase().let { if (it == "true" || it == "on") true else if (it == "false" || it == "off") false else null }
            else -> null
        }
        val numValue: Int? = if (rawValue is JsonPrimitive && rawValue.isNumber) rawValue.asInt else null

        return withContext(Dispatchers.Main) {
            when (key) {
                "wifi", "wi-fi" -> {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        @Suppress("DEPRECATION")
                        val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val enable = boolValue ?: !wm.isWifiEnabled
                        @Suppress("DEPRECATION")
                        wm.isWifiEnabled = enable
                        "Wi-Fi turned ${if (enable) "on" else "off"}."
                    } else {
                        context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened Wi-Fi panel."
                    }
                }
                "bluetooth" -> {
                    val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    if (bt == null) fail("Bluetooth not available on this device.")
                    val enable = boolValue ?: !bt.isEnabled
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened Bluetooth settings."
                    } else {
                        @Suppress("DEPRECATION")
                        if (enable) bt.enable() else bt.disable()
                        "Bluetooth turned ${if (enable) "on" else "off"}."
                    }
                }
                "airplane_mode", "airplane mode", "flight mode" -> {
                    context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened airplane mode settings."
                }
                "volume", "media_volume", "media volume" -> {
                    val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val level = numValue?.coerceIn(0, max) ?: if (boolValue == false) 0 else (max * 0.5).toInt()
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, android.media.AudioManager.FLAG_SHOW_UI)
                    "Volume set to $level of $max."
                }
                "brightness", "screen_brightness" -> {
                    val level = numValue?.coerceIn(0, 255) ?: if (boolValue == false) 30 else 180
                    if (Settings.System.canWrite(context)) {
                        try {
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                            "Brightness set to $level."
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            fail("Could not set brightness — opened display settings.")
                        }
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        fail("Brightness control needs a special permission. Please grant it in the screen that just opened.")
                    }
                }
                else -> {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened settings for $key."
                }
            }
        }
    }

    private suspend fun getContacts(context: Context, input: JsonObject): String {
        val name = input.optStr("name") ?: fail("Missing name.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            fail("Contacts permission not granted.")
        }
        return resolvePhone(context, name) ?: fail("No contact named $name.")
    }

    private suspend fun readLastMessage(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: fail("Missing contact.")
        val app = input.optStr("app")?.lowercase() ?: "whatsapp"
        val svc = AccessibilityBridge.get() ?: fail("Accessibility not enabled.")
        if (sensitiveForeground(svc)) fail("Blocked in sensitive app.")
        val pkg = when (app) {
            "sms", "messages" -> "com.google.android.apps.messaging"
            else -> "com.whatsapp"
        }
        if (svc.openAppPackage(pkg).not()) fail("App not installed for $app.")
        delay(1000)
        var root = svc.waitForRoot(4000) ?: fail("UI not ready.")
        val searchHints = listOf("search", "search…", "search contacts")
        for (hint in searchHints) {
            val n = svc.findByText(root, hint, partial = true)
            if (n != null && svc.tapNode(n)) break
        }
        delay(400)
        svc.typeIntoFocusedField(contact)
        delay(800)
        root = svc.waitForRoot(3000) ?: fail("Thread not found.")
        val row = findFirstConversationRow(root)
        row?.let { svc.tapNode(it) }
        delay(1000)
        root = svc.waitForRoot(3000) ?: fail("Could not read thread.")
        val bubble = findLastMessageBubble(root)
        val text = bubble?.text?.toString() ?: "(no text found)"
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return "Last message: $text"
    }

    private fun findLastMessageBubble(root: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null
        val texts = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (!n.text.isNullOrBlank()) texts.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return texts.lastOrNull()
    }

    /**
     * The contact-matching ladder: exact name, then prefix, then contains.
     *
     * Pure so it can be tested on the JVM - picking the wrong person is one of this
     * app's worst failure modes and it was previously only reachable through a
     * ContentResolver. The eval suite implements the same ladder in Python for its
     * device double; both are held to `backend/evals/fixtures/contact_matching.json`
     * so they cannot drift apart.
     *
     * Candidate order matters when two contacts tie within a tier - the first wins -
     * and on-device that order comes from the cursor. See the fixture's notes.
     */
    internal fun matchContact(candidates: List<Pair<String, String>>, needle: String): String? {
        val trimmed = needle.trim()
        // An empty needle must resolve to nothing. Two ways it used to resolve to
        // something: `"".all { ... }` is true, so it fell through the digits branch and
        // returned ""; and `anyName.startsWith("")` is also true, so it prefix-matched
        // whichever contact the cursor happened to return first.
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() || it == '+' || it.isWhitespace() }) {
            return trimmed.filter { it.isDigit() || it == '+' }
        }
        val lowered = trimmed.lowercase()
        var exact: String? = null
        var prefix: String? = null
        var contains: String? = null
        for ((name, number) in candidates) {
            if (name.isEmpty() || number.isEmpty()) continue
            val lowName = name.lowercase()
            val clean = number.replace(" ", "")
            when {
                lowName == lowered -> if (exact == null) exact = clean
                lowName.startsWith(lowered) || lowered.startsWith(lowName) ->
                    if (prefix == null) prefix = clean
                lowered.length >= 3 && lowName.contains(lowered) ->
                    if (contains == null) contains = clean
            }
        }
        return exact ?: prefix ?: contains
    }

    /** Read (display name, number) pairs in cursor order. */
    private fun readContacts(context: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null,
        )?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val num = c.getString(numIdx) ?: continue
                out.add(name to num)
            }
        }
        return out
    }

    private fun resolvePhone(context: Context, contact: String): String? {
        val trimmed = contact.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() || it == '+' || it.isWhitespace() }) {
            return trimmed.filter { it.isDigit() || it == '+' }
        }
        return matchContact(readContacts(context), trimmed)
    }

    private suspend fun sendTelegram(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: fail("Missing contact.")
        val message = input.optStr("message") ?: fail("Missing message.")
        val svc = AccessibilityBridge.get() ?: fail("Accessibility not enabled.")
        if (svc.openAppPackage("org.telegram.messenger").not()) fail("Telegram not installed.")
        delay(1200)
        var root = svc.waitForRoot(4000) ?: fail("Telegram UI not ready.")
        val searchHints = listOf("search", "search telegram")
        for (hint in searchHints) {
            val n = svc.findByText(root, hint, partial = true)
            if (n != null && svc.tapNode(n)) break
        }
        delay(400)
        svc.typeIntoFocusedField(contact)
        delay(800)
        root = svc.waitForRoot(3000) ?: fail("Contact not found in Telegram.")
        val row = findFirstConversationRow(root)
        if (row != null) svc.tapNode(row) else svc.findByText(root, contact, partial = true)?.let { svc.tapNode(it) }
        delay(1000)
        root = svc.waitForRoot(3000) ?: fail("Chat not open.")
        svc.typeIntoFocusedField(message)
        delay(400)
        val send = svc.findByText(root, "send", partial = false)
            ?: svc.findByText(svc.rootInActiveWindow, "send", partial = true)
        // Same contract as WhatsApp: once Send is tapped, never report a retryable failure.
        val sendTapped = send != null && svc.tapNode(send)
        if (!sendTapped) fail("Could not find the Telegram send button for $contact.")
        delay(800)
        val finalRoot = svc.waitForRoot(2000)
        val confirmed = finalRoot != null && verifySentMessage(finalRoot, message)
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (confirmed) {
            "Sent Telegram to $contact."
        } else {
            "Sent Telegram to $contact (could not confirm it appeared in the chat)."
        }
    }

    private suspend fun sendEmail(context: Context, input: JsonObject): String {
        val to = input.optStr("to") ?: fail("Missing recipient.")
        val subject = input.optStr("subject") ?: fail("Missing subject.")
        val body = input.optStr("body") ?: fail("Missing body.")
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Send email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened email composer to $to."
            } catch (e: Exception) {
                fail("Could not open email app: ${e.message}")
            }
        }
    }

    private suspend fun createCalendarEvent(context: Context, input: JsonObject): String {
        val title = input.optStr("title") ?: fail("Missing title.")
        val date = input.optStr("date") ?: fail("Missing date.")
        val time = input.optStr("time") ?: fail("Missing time.")
        val durationMinutes = input.get("duration_minutes")?.let {
            if (it is JsonPrimitive && it.isNumber) it.asInt else 60
        } ?: 60
        return withContext(Dispatchers.Main) {
            try {
                val parts = date.split("-")
                val timeParts = time.split(":")
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, parts[0].toInt())
                    set(Calendar.MONTH, parts[1].toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                    set(Calendar.MINUTE, timeParts[1].toInt())
                    set(Calendar.SECOND, 0)
                }
                val endMillis = cal.timeInMillis + durationMinutes * 60_000L
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                    putExtra(CalendarContract.Events.TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened calendar to create event: $title on $date at $time."
            } catch (e: Exception) {
                fail("Could not create calendar event: ${e.message}")
            }
        }
    }

    private suspend fun setAlarm(context: Context, input: JsonObject): String {
        val hour = input.get("hour")?.let { if (it is JsonPrimitive && it.isNumber) it.asInt else null }
            ?: fail("Missing hour.")
        val minute = input.get("minute")?.let { if (it is JsonPrimitive && it.isNumber) it.asInt else null }
            ?: fail("Missing minute.")
        val label = input.optStr("label")
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Alarm set for %02d:%02d.".format(hour, minute)
            } catch (e: Exception) {
                fail("Could not set alarm: ${e.message}")
            }
        }
    }

    private suspend fun setTimer(context: Context, input: JsonObject): String {
        val durationSeconds = input.get("duration_seconds")?.let {
            if (it is JsonPrimitive && it.isNumber) it.asInt else null
        } ?: fail("Missing duration.")
        val label = input.optStr("label")
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                    if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val mins = durationSeconds / 60
                val secs = durationSeconds % 60
                "Timer set for ${if (mins > 0) "${mins}m " else ""}${if (secs > 0) "${secs}s" else ""}."
            } catch (e: Exception) {
                fail("Could not set timer: ${e.message}")
            }
        }
    }

    private suspend fun playMedia(context: Context, input: JsonObject): String {
        val query = input.optStr("query") ?: fail("Missing query.")
        val app = input.optStr("app")?.lowercase() ?: "auto"
        return withContext(Dispatchers.Main) {
            try {
                val intent = when (app) {
                    "spotify" -> Intent(Intent.ACTION_VIEW,
                        Uri.parse("spotify:search:${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    "youtube" -> Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    else -> {
                        val spotify = Intent(Intent.ACTION_VIEW,
                            Uri.parse("spotify:search:${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pm = context.packageManager
                        if (pm.resolveActivity(spotify, PackageManager.MATCH_DEFAULT_ONLY) != null) spotify
                        else Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
                "Opening $query."
            } catch (e: Exception) {
                fail("Could not open media app: ${e.message}")
            }
        }
    }

    private suspend fun mediaControl(context: Context, input: JsonObject): String {
        val action = input.optStr("action")?.lowercase() ?: fail("Missing action.")
        return withContext(Dispatchers.Main) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (action) {
                "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                else -> fail("Unknown media action: $action")
            }
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            "Media $action."
        }
    }

    private suspend fun navigateTo(context: Context, input: JsonObject): String {
        val destination = input.optStr("destination") ?: fail("Missing destination.")
        return withContext(Dispatchers.Main) {
            try {
                val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pm = context.packageManager
                if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    context.startActivity(intent)
                } else {
                    val fallback = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://maps.google.com/?q=${Uri.encode(destination)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallback)
                }
                "Navigating to $destination."
            } catch (e: Exception) {
                fail("Could not open navigation: ${e.message}")
            }
        }
    }

    private suspend fun describeScreen(): String {
        val svc = AccessibilityBridge.get() ?: fail("Accessibility not enabled.")
        if (sensitiveForeground(svc)) fail("I can't read the screen in a sensitive app.")
        val root = svc.rootInActiveWindow ?: fail("Screen not readable.")
        val text = svc.collectVisibleText(root)
        return if (text.isBlank()) "Screen appears empty or unreadable." else text
    }

    private fun isSensitivePackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return sensitiveFragments.any { lower.contains(it) }
    }

    private fun readNotifications(input: JsonObject): String {
        if (!NotificationBridge.connected) fail("Notification listener not enabled. Enable it in Settings > Notifications > Special app access.")
        val count = input.get("count")?.let { if (it is JsonPrimitive && it.isNumber) it.asInt else 5 } ?: 5
        val entries = NotificationBridge.recent(count * 3).filterNot { isSensitivePackage(it.packageName) }.take(count)
        if (entries.isEmpty()) return "No notifications."
        return entries.joinToString("\n") { e ->
            val app = e.packageName.substringAfterLast(".")
            "[$app] ${e.title}: ${e.text}"
        }
    }
}
