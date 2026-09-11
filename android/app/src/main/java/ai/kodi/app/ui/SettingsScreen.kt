package ai.kodi.app.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.kodi.app.BuildConfig
import ai.kodi.app.KodiApplication
import ai.kodi.app.briefing.BriefingScheduler
import ai.kodi.app.data.KodiPrefs
import ai.kodi.app.voice.KodiVoiceService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: KodiPrefs,
    onBack: () -> Unit,
    onResetOnboarding: () -> Unit,
    onOpenAllowlist: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as KodiApplication
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.backendBaseUrl) }
    var pins by remember { mutableStateOf(prefs.certificatePins) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var liveMode by remember { mutableStateOf(prefs.liveModeEnabled) }
    var dynamicColor by remember { mutableStateOf(prefs.dynamicColor) }
    var briefing by remember { mutableStateOf(prefs.briefingEnabled) }
    var briefingHour by remember { mutableStateOf(prefs.briefingHour) }
    var briefingMinute by remember { mutableStateOf(prefs.briefingMinute) }
    var status by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Backend")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("HTTPS base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pins,
                onValueChange = { pins = it },
                label = { Text("Certificate pins") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                supportingText = { Text("Format: host|sha256/AAAA…, comma-separated. Blank = default trust.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        prefs.backendBaseUrl = url.trimEnd('/')
                        prefs.certificatePins = pins
                        status = "Saved."
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                Button(
                    onClick = {
                        prefs.backendBaseUrl = url.trimEnd('/')
                        prefs.certificatePins = pins
                        scope.launch {
                            status = try {
                                app.repository.healthCheck().fold(
                                    onSuccess = { "Connected: $it" },
                                    onFailure = { "Failed: ${it.message}" },
                                )
                            } catch (e: Exception) { e.message ?: "error" }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("Connected") || status == "Saved.")
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            SectionHeader("Voice")
            ToggleRow(
                title = "Wake word listener",
                subtitle = if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank())
                    "Picovoice key missing — set PICOVOICE_ACCESS_KEY in local.properties"
                else
                    "Listen for \"Jarvis\" in the background",
                checked = wakeWord,
                enabled = BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank(),
                onCheckedChange = {
                    wakeWord = it
                    prefs.wakeWordEnabled = it
                    if (it) {
                        val i = Intent(context, KodiVoiceService::class.java)
                        androidx.core.content.ContextCompat.startForegroundService(context, i)
                    } else {
                        context.stopService(Intent(context, KodiVoiceService::class.java))
                    }
                },
            )

            ToggleRow(
                title = "Live conversation mode",
                subtitle = "Stream audio both ways for a natural back-and-forth. " +
                    "Falls back to the single-turn pipeline if it can't connect.",
                checked = liveMode,
                enabled = true,
                onCheckedChange = {
                    liveMode = it
                    prefs.liveModeEnabled = it
                },
            )

            HorizontalDivider()
            SectionHeader("Appearance")
            ToggleRow(
                title = "Dynamic color (Material You)",
                subtitle = "Use system wallpaper-derived palette on Android 12+",
                checked = dynamicColor,
                enabled = true,
                onCheckedChange = {
                    dynamicColor = it
                    prefs.dynamicColor = it
                },
            )

            HorizontalDivider()
            SectionHeader("App access")
            OutlinedButton(
                onClick = onOpenAllowlist,
                Modifier.fillMaxWidth(),
            ) { Text("Manage app access") }
            Text(
                "Pick which non-built-in apps Kodi may open and operate via accessibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionHeader("Daily briefing")
            ToggleRow(
                title = "Morning briefing",
                subtitle = "Kodi reads a personalised summary at the time you pick",
                checked = briefing,
                enabled = true,
                onCheckedChange = {
                    briefing = it
                    prefs.briefingEnabled = it
                    BriefingScheduler.apply(context)
                },
            )
            OutlinedButton(
                onClick = { showTimePicker = true },
                Modifier.fillMaxWidth(),
            ) {
                Text("Briefing time: %02d:%02d".format(briefingHour, briefingMinute))
            }

            if (needsExactAlarmAccess(context)) {
                HorizontalDivider()
                SectionHeader("Scheduling")
                Text(
                    "On Android 12+, scheduled tasks fire on time only with Alarms & reminders access. " +
                        "Without it they may run minutes late.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    Modifier.fillMaxWidth(),
                ) { Text("Grant exact alarm access") }
            }

            HorizontalDivider()
            SectionHeader("Device")
            Text(
                "Device ID: ${prefs.deviceId.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        app.repository.resetSession()
                        status = "Session cleared. Next command will create a fresh one."
                    }
                },
                Modifier.fillMaxWidth(),
            ) { Text("Clear conversation session") }
            OutlinedButton(
                onClick = {
                    app.transcripts.clear()
                    status = "Transcript history cleared."
                },
                Modifier.fillMaxWidth(),
            ) { Text("Clear transcript history") }
            OutlinedButton(
                onClick = { confirmReset = true },
                Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Re-run onboarding (wipes pairing)") }

            Spacer(Modifier.height(8.dp))
            Text(
                "Kodi v${BuildConfig.VERSION_NAME ?: "1.0.0"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = briefingHour,
            initialMinute = briefingMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Briefing time") },
            text = { TimePicker(state = tpState) },
            confirmButton = {
                TextButton(onClick = {
                    briefingHour = tpState.hour
                    briefingMinute = tpState.minute
                    prefs.briefingHour = briefingHour
                    prefs.briefingMinute = briefingMinute
                    BriefingScheduler.apply(context)
                    showTimePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Wipe pairing?") },
            text = { Text("This deletes the device credentials, session, and backend URL. You'll re-run onboarding next launch.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    context.stopService(Intent(context, KodiVoiceService::class.java))
                    prefs.clearAll()
                    onResetOnboarding()
                }) { Text("Wipe", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun needsExactAlarmAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return try {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms().not()
    } catch (_: Exception) { false }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
