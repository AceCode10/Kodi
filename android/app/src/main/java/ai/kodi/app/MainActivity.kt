package ai.kodi.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.kodi.app.data.KodiPrefs
import ai.kodi.app.voice.KodiVoiceService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KodiRoot()
                }
            }
        }
    }
}

@Composable
private fun KodiRoot() {
    val context = LocalContext.current
    val prefs = remember { KodiPrefs(context) }
    var step by remember { mutableIntStateOf(if (prefs.onboardingComplete) 3 else 0) }
    val scope = rememberCoroutineScope()

    when (step) {
        0 -> OnboardingPermissions(onNext = { step = 1 })
        1 -> OnboardingBackend(
            prefs = prefs,
            onNext = { step = 2 },
        )
        2 -> OnboardingAccessibility(
            onNext = {
                prefs.onboardingComplete = true
                step = 3
                scope.launch(Dispatchers.Main) {
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = java.util.Locale.US
                            tts?.speak(
                                "I'm ready. Tap Speak command to get started.",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "ready",
                            )
                        }
                    }
                }
            },
        )
        else -> MainHome(prefs = prefs)
    }
}

@Composable
private fun OnboardingPermissions(onNext: () -> Unit) {
    val permissions = remember {
        mutableListOf<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) onNext()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Step 1 — Permissions", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Kodi needs the microphone for wake word and commands, contacts to call people by name, " +
                "SMS and phone to message and dial, and notifications for the background listener.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { launcher.launch(permissions) }, Modifier.fillMaxWidth()) {
            Text("Grant permissions")
        }
    }
}

@Composable
private fun OnboardingBackend(prefs: KodiPrefs, onNext: () -> Unit) {
    var url by remember { mutableStateOf(prefs.backendBaseUrl) }
    var status by remember { mutableStateOf("") }
    var pins by remember { mutableStateOf(prefs.certificatePins) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as KodiApplication

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Step 2 — Your backend", style = MaterialTheme.typography.headlineSmall)
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
            label = { Text("Certificate pins (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            supportingText = {
                Text("Format: host|sha256/AAAA…, one per comma. Leave empty to use default trust.")
            },
        )
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
                    } catch (e: Exception) {
                        e.message ?: "error"
                    }
                }
            },
            Modifier.fillMaxWidth(),
        ) {
            Text("Test connection")
        }
        if (status.isNotEmpty()) Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = {
                prefs.backendBaseUrl = url.trimEnd('/')
                prefs.certificatePins = pins
                scope.launch {
                    if (prefs.deviceId.isNotBlank() && prefs.apiSecret.isNotBlank()) {
                        prefs.clearSession()
                        onNext()
                        return@launch
                    }
                    val reg = app.repository.registerDevice(null)
                    reg.onSuccess { r ->
                        prefs.deviceId = r.deviceId
                        prefs.apiSecret = r.apiSecret
                        prefs.clearSession()
                        onNext()
                    }.onFailure {
                        status = "Register failed: ${it.message}"
                    }
                }
            },
            Modifier.fillMaxWidth(),
            enabled = url.startsWith("https://"),
        ) {
            Text(if (prefs.deviceId.isNotBlank()) "Continue" else "Register device & continue")
        }
    }
}

@Composable
private fun OnboardingAccessibility(onNext: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Step 3 — Accessibility", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Kodi uses Android Accessibility only to complete tasks you request by voice " +
                "(taps and typing in apps like WhatsApp). It does not run unprompted actions.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Modifier.fillMaxWidth(),
        ) {
            Text("Open Accessibility settings")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNext, Modifier.fillMaxWidth()) {
            Text("I enabled Kodi accessibility — continue")
        }
    }
}

@Composable
private fun MainHome(prefs: KodiPrefs) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kodi", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tap the button below and speak your command.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val i = Intent(context, KodiVoiceService::class.java)
                ContextCompat.startForegroundService(context, i)
                context.startService(
                    Intent(context, KodiVoiceService::class.java).setAction(KodiVoiceService.ACTION_MANUAL_COMMAND),
                )
                running = true
            },
            Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("🎤  Speak command", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            "Wake word (coming soon)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "Hands-free \"Jarvis\" detection requires a Picovoice key. Once approved, set " +
                "PICOVOICE_ACCESS_KEY in local.properties and rebuild.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedButton(
            onClick = {
                val i = Intent(context, KodiVoiceService::class.java)
                ContextCompat.startForegroundService(context, i)
                running = true
            },
            Modifier.fillMaxWidth(),
            enabled = !running,
        ) {
            Text(if (running) "Wake word listener active" else "Start wake word listener")
        }
        if (running) {
            OutlinedButton(
                onClick = {
                    context.stopService(Intent(context, KodiVoiceService::class.java))
                    running = false
                },
                Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Stop listener")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Backend: ${prefs.backendBaseUrl.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
