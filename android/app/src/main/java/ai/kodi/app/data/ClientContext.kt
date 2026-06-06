package ai.kodi.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import ai.kodi.app.accessibility.AccessibilityBridge
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.util.Base64

object ClientContext {

    /** Returns a base64-encoded JSON snapshot of the client's current context, or null. */
    fun snapshotHeader(context: Context): String? = try {
        val json = snapshotJson(context).toString()
        Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    } catch (_: Exception) {
        null
    }

    private fun snapshotJson(context: Context): JsonObject {
        val obj = JsonObject()
        val now = Date()
        val tz = TimeZone.getDefault()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = tz }.format(now)
        obj.addProperty("time_local", iso)
        obj.addProperty("timezone", tz.id)
        obj.addProperty("locale", Locale.getDefault().toLanguageTag())

        val cal = Calendar.getInstance()
        obj.addProperty("day_of_week", SimpleDateFormat("EEEE", Locale.US).format(now))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        obj.addProperty("is_weekend", dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)

        obj.addProperty("network", networkState(context))

        foregroundApp()?.let { obj.addProperty("foreground_app", it) }
        battery(context)?.let { (pct, charging) ->
            obj.addProperty("battery_percent", pct)
            obj.addProperty("charging", charging)
        }
        location(context)?.let { loc ->
            obj.addProperty("latitude", loc.latitude)
            obj.addProperty("longitude", loc.longitude)
            obj.addProperty("location_accuracy_m", loc.accuracy.toInt())
        }
        return obj
    }

    private fun foregroundApp(): String? {
        return try {
            AccessibilityBridge.get()?.rootOrNull()?.packageName?.toString()
        } catch (_: Exception) { null }
    }

    private fun networkState(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "online"
            }
        } catch (_: Exception) { "unknown" }
    }

    private fun battery(context: Context): Pair<Int, Boolean>? {
        return try {
            val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val pct = (level * 100f / scale).toInt()
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            pct to plugged
        } catch (_: Exception) { null }
    }

    /** Best-effort last-known location. Never blocks, never requests a fresh fix. */
    private fun location(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.mapNotNull { p ->
                try {
                    if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
                } catch (_: SecurityException) { null }
            }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }
}
