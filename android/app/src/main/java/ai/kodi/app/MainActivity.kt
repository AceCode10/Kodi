package ai.kodi.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.kodi.app.data.KodiPrefs
import ai.kodi.app.data.TranscriptEntry
import ai.kodi.app.data.TranscriptRole
import ai.kodi.app.ui.AppAllowlistScreen
import ai.kodi.app.ui.KodiTheme
import ai.kodi.app.ui.SettingsScreen
import ai.kodi.app.voice.KodiVoiceService
import ai.kodi.app.voice.VoiceState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { KodiPrefs(this) }
            KodiTheme(dynamicColor = prefs.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KodiRoot(prefs)
                }
            }
        }
    }
}

@Composable
private fun KodiRoot(prefs: KodiPrefs) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(if (prefs.onboardingComplete) 3 else 0) }
    var showSettings by remember { mutableStateOf(false) }
    var showAllowlist by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = showSettings || showAllowlist) {
        when {
            showAllowlist -> showAllowlist = false
            showSettings -> showSettings = false
        }
    }

    if (showAllowlist) {
        AppAllowlistScreen(onBack = { showAllowlist = false })
        return
    }

    if (showSettings) {
        SettingsScreen(
            prefs = prefs,
            onBack = { showSettings = false },
            onResetOnboarding = {
                showSettings = false
                step = 0
            },
            onOpenAllowlist = { showAllowlist = true },
        )
        return
    }

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
                if (prefs.wakeWordEnabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, KodiVoiceService::class.java),
                    )
                }
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
        else -> MainHome(prefs = prefs, onSettings = { showSettings = true })
    }
}

@Composable
private fun OnboardingPermissions(onNext: () -> Unit) {
    val context = LocalContext.current
    val permissions = remember {
        mutableListOf<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    // Microphone is the only hard requirement; everything else is best-effort and can be
    // granted later from system settings, so onboarding never deadlocks on a denial.
    val micGrantedAlready = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var micDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted) onNext() else micDenied = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Step 1 — Permissions", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Kodi needs the microphone for wake word and commands, contacts to call people by name, " +
                "SMS and phone to message and dial, location for here-and-now answers, and notifications " +
                "for the background listener.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (micDenied) {
            Text(
                "Kodi cannot work without the microphone. Please grant it to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                if (micGrantedAlready) onNext() else launcher.launch(permissions)
            },
            Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    micGrantedAlready -> "Continue"
                    micDenied -> "Grant microphone permission"
                    else -> "Grant permissions"
                },
            )
        }
    }
}

@Composable
private fun OnboardingBackend(prefs: KodiPrefs, onNext: () -> Unit) {
    var url by remember { mutableStateOf(prefs.backendBaseUrl) }
    var status by remember { mutableStateOf("") }
    var pins by remember { mutableStateOf(prefs.certificatePins) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as KodiApplication

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
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
                busy = true
                scope.launch {
                    status = try {
                        app.repository.healthCheck().fold(
                            onSuccess = { "Connected: $it" },
                            onFailure = { "Failed: ${it.message}" },
                        )
                    } catch (e: Exception) {
                        e.message ?: "error"
                    } finally {
                        busy = false
                    }
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text("Test connection")
        }
        if (status.isNotEmpty()) Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        Button(
            onClick = {
                prefs.backendBaseUrl = url.trimEnd('/')
                prefs.certificatePins = pins
                busy = true
                scope.launch {
                    try {
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
                    } finally {
                        busy = false
                    }
                }
            },
            Modifier.fillMaxWidth(),
            enabled = url.startsWith("https://") && !busy,
        ) {
            Text(if (prefs.deviceId.isNotBlank()) "Continue" else "Register device & continue")
        }
    }
}

@Composable
private fun OnboardingAccessibility(onNext: () -> Unit) {
    val context = LocalContext.current
    var showNotEnabled by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
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
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Modifier.fillMaxWidth(),
        ) {
            Text("Open Accessibility settings")
        }
        if (showNotEnabled) {
            Text(
                "Kodi accessibility is not enabled yet. Turn it on in the settings screen, then continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (isAccessibilityEnabled(context)) onNext() else showNotEnabled = true
            },
            Modifier.fillMaxWidth(),
        ) {
            Text("I enabled Kodi accessibility — continue")
        }
    }
}

private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(
        context.contentResolver,
        AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.contains(context.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainHome(prefs: KodiPrefs, onSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as KodiApplication
    val hasWakeKey = BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()
    var wakeOn by remember { mutableStateOf(prefs.wakeWordEnabled && hasWakeKey) }
    val entries by app.transcripts.entries.collectAsState()
    val voiceState by KodiVoiceService.voiceState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    DisposableEffect(wakeOn) {
        if (wakeOn && hasWakeKey) {
            ContextCompat.startForegroundService(context, Intent(context, KodiVoiceService::class.java))
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kodi") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            StatusPill(wakeOn = wakeOn && hasWakeKey, hasKey = hasWakeKey, voiceState = voiceState)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (hasWakeKey) "Tap to speak, or say \"Jarvis\"."
                            else "Tap the button below to speak.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your conversation history will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(entries) { e -> TranscriptBubble(e) }
                    }
                }
            }

            Button(
                onClick = {
                    val i = Intent(context, KodiVoiceService::class.java)
                    ContextCompat.startForegroundService(context, i)
                    context.startService(
                        Intent(context, KodiVoiceService::class.java)
                            .setAction(KodiVoiceService.ACTION_MANUAL_COMMAND),
                    )
                },
                Modifier.fillMaxWidth().height(64.dp),
                enabled = voiceState != VoiceState.THINKING,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    when (voiceState) {
                        VoiceState.LISTENING -> "Listening…  (tap to stop)"
                        VoiceState.THINKING -> "Thinking…"
                        VoiceState.SPEAKING -> "Speaking…  (tap to stop)"
                        VoiceState.IDLE -> "🎤  Speak command"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (hasWakeKey) {
                Button(
                    onClick = {
                        wakeOn = !wakeOn
                        prefs.wakeWordEnabled = wakeOn
                        if (wakeOn) {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, KodiVoiceService::class.java),
                            )
                        } else {
                            context.stopService(Intent(context, KodiVoiceService::class.java))
                        }
                    },
                    Modifier.fillMaxWidth(),
                    colors = if (wakeOn)
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else ButtonDefaults.buttonColors(),
                ) {
                    Text(if (wakeOn) "Stop wake-word listener" else "Start wake-word listener")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TranscriptBubble(e: TranscriptEntry) {
    val isUser = e.role == TranscriptRole.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val container = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(e.text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
    }
}

@Composable
private fun StatusPill(wakeOn: Boolean, hasKey: Boolean, voiceState: VoiceState) {
    val active = voiceState != VoiceState.IDLE
    val color = when {
        active -> MaterialTheme.colorScheme.primary
        !hasKey -> MaterialTheme.colorScheme.outline
        wakeOn -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val text = when {
        voiceState == VoiceState.LISTENING -> "Listening…"
        voiceState == VoiceState.THINKING -> "Thinking…"
        voiceState == VoiceState.SPEAKING -> "Speaking…"
        !hasKey -> "Wake-word disabled — Picovoice key missing"
        wakeOn -> "Listening for \"Jarvis\""
        else -> "Wake-word listener off"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
