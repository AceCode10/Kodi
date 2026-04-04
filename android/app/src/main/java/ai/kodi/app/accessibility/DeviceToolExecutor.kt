package ai.kodi.app.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.kodi.app.data.CommandDto
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object DeviceToolExecutor {
    private const val TAG = "KodiTools"

    private fun JsonObject.optStr(key: String): String? {
        val e = get(key) ?: return null
        return if (e is JsonPrimitive && !e.isJsonNull) e.asString else null
    }

    private val sensitiveFragments = listOf(
        "bank", "wallet", "paypal", "chase", "lastpass", "1password", "bitwarden",
        "authenticator", "trezor", "ledger",
    )

    suspend fun execute(context: Context, cmd: CommandDto): String {
        val name = cmd.toolName ?: return "No tool name."
        val input = cmd.toolInput ?: JsonObject()
        return when (name) {
            "send_whatsapp_message" -> sendWhatsApp(context, input)
            "send_sms" -> sendSms(context, input)
            "make_phone_call" -> call(context, input)
            "open_app" -> openApp(context, input)
            "toggle_setting" -> toggleSetting(context, input)
            "get_contacts" -> getContacts(context, input)
            "read_last_message" -> readLastMessage(context, input)
            else -> "Unsupported device tool: $name"
        }
    }

    private fun sensitiveForeground(svc: KodiAccessibilityService): Boolean {
        val pkg = svc.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: return false
        return sensitiveFragments.any { pkg.contains(it) }
    }

    private suspend fun sendWhatsApp(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: return "Missing contact."
        val message = input.optStr("message") ?: return "Missing message."
        val svc = AccessibilityBridge.get() ?: return "Turn on Kodi accessibility first."
        if (sensitiveForeground(svc)) return "I can't use that app here."
        val ok = svc.openAppPackage("com.whatsapp")
        if (!ok) return "I can't find WhatsApp on this phone."
        delay(800)
        var root = svc.waitForRoot(4000) ?: return "WhatsApp UI not ready."
        // Search entry varies by version — try common patterns
        val searchHints = listOf("search", "search…", "search contacts")
        var tapped = false
        for (hint in searchHints) {
            val n = svc.findByText(root, hint, partial = true)
            if (n != null && svc.tapNode(n)) {
                tapped = true
                break
            }
        }
        if (!tapped) {
            // Fallback: try first clickable in toolbar area (best-effort)
            Log.i(TAG, "WhatsApp search control not found; continuing")
        }
        delay(400)
        svc.typeIntoFocusedField(contact)
        delay(1200)
        root = svc.waitForRoot(3000) ?: return "Could not open chat."
        val first = findFirstConversationRow(root)
        if (first != null) svc.tapNode(first) else svc.findByText(root, contact, true)?.let { svc.tapNode(it) }
        delay(1000)
        root = svc.waitForRoot(3000) ?: return "Conversation not open."
        svc.typeIntoFocusedField(message)
        delay(400)
        val send = svc.findByText(root, "send", partial = false)
            ?: svc.findByText(svc.rootInActiveWindow, "send", partial = true)
        if (send != null) svc.tapNode(send)
        delay(600)
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return "Sent WhatsApp to $contact."
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

    private suspend fun sendSms(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: return "Missing contact."
        val message = input.optStr("message") ?: return "Missing message."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS permission not granted."
        }
        val dest = resolvePhone(context, contact) ?: return "I don't recognise $contact. Can you check the spelling?"
        return withContext(Dispatchers.IO) {
            try {
                SmsManager.getDefault().sendTextMessage(dest, null, message, null, null)
                "SMS sent to $contact."
            } catch (e: Exception) {
                Log.e(TAG, "sms", e)
                "SMS failed: ${e.message}"
            }
        }
    }

    private suspend fun call(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: return "Missing contact."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Phone permission not granted."
        }
        val dest = resolvePhone(context, contact) ?: return "I don't recognise $contact. Can you check the spelling?"
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$dest"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Calling $contact."
            } catch (e: Exception) {
                Log.e(TAG, "call", e)
                "Call failed: ${e.message}"
            }
        }
    }

    private suspend fun openApp(context: Context, input: JsonObject): String {
        val name = input.optStr("app_name") ?: return "Missing app name."
        val pm = context.packageManager
        val pkg = resolvePackageForLabel(pm, name)
            ?: knownPackages[name.lowercase()]
        if (pkg == null) return "Could not find app $name."
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch == null) return "App not launchable."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { context.startActivity(launch) }
        delay(500)
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
        val key = input.optStr("setting")?.lowercase() ?: return "Missing setting."
        return withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val action = when (key) {
                    "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI
                    "bluetooth" -> Settings.Panel.ACTION_BLUETOOTH
                    else -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                }
                try {
                    context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened system panel for $key — toggle there."
                } catch (e: Exception) {
                    "Could not open settings panel."
                }
            } else {
                val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened settings."
            }
        }
    }

    private suspend fun getContacts(context: Context, input: JsonObject): String {
        val name = input.optStr("name") ?: return "Missing name."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission not granted."
        }
        val phone = resolvePhone(context, name)
        return phone ?: "No contact named $name."
    }

    private suspend fun readLastMessage(context: Context, input: JsonObject): String {
        val contact = input.optStr("contact") ?: return "Missing contact."
        val app = input.optStr("app")?.lowercase() ?: "whatsapp"
        val svc = AccessibilityBridge.get() ?: return "Accessibility not enabled."
        if (sensitiveForeground(svc)) return "Blocked in sensitive app."
        val pkg = when (app) {
            "sms", "messages" -> "com.google.android.apps.messaging"
            else -> "com.whatsapp"
        }
        if (svc.openAppPackage(pkg).not()) return "App not installed for $app."
        delay(1000)
        var root = svc.waitForRoot(4000) ?: return "UI not ready."
        svc.typeIntoFocusedField(contact)
        delay(800)
        root = svc.waitForRoot(3000) ?: return "Thread not found."
        val row = findFirstConversationRow(root)
        row?.let { svc.tapNode(it) }
        delay(1000)
        root = svc.waitForRoot(3000) ?: return "Could not read thread."
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

    private fun resolvePhone(context: Context, contact: String): String? {
        val trimmed = contact.trim()
        if (trimmed.all { it.isDigit() || it == '+' || it.isWhitespace() }) {
            return trimmed.filter { it.isDigit() || it == '+' }
        }
        val cr = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        cr.query(uri, proj, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val needle = trimmed.lowercase()
            while (c.moveToNext()) {
                val n = c.getString(nameIdx)?.lowercase() ?: continue
                if (n.contains(needle) || needle.contains(n)) {
                    return c.getString(numIdx)?.replace(" ", "")
                }
            }
        }
        return null
    }
}
