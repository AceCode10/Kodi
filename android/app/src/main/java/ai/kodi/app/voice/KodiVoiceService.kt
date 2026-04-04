package ai.kodi.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.kodi.app.BuildConfig
import ai.kodi.app.MainActivity
import ai.kodi.app.R
import ai.kodi.app.KodiApplication
import ai.kodi.app.accessibility.DeviceToolExecutor
import ai.kodi.app.data.CommandDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.sqrt

class KodiVoiceService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private var listenThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var commandInProgress = false
    private var porcupine: Porcupine? = null
    private lateinit var tts: TtsManager

    override fun onCreate() {
        super.onCreate()
        tts = TtsManager(this)
        tts.init { ok ->
            if (!ok) Log.w(TAG, "TTS init failed")
        }
        try {
            if (BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()) {
                porcupine = Porcupine.Builder()
                    .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .build(applicationContext)
            }
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine failed — set PICOVOICE_ACCESS_KEY in local.properties", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_COMMAND -> {
                startForegroundIfNeeded()
                scope.launch {
                    commandInProgress = true
                    try {
                        tts.stop()
                        val pcm = recordCommandPcm()
                        runPipeline(pcm)
                    } finally {
                        commandInProgress = false
                    }
                }
                return START_STICKY
            }
        }
        startForegroundIfNeeded()
        if (!running) {
            running = true
            startPorcupineLoop()
        }
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        val channelId = "kodi_voice"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Kodi", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_listening))
            .setSmallIcon(R.drawable.ic_kodi)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startPorcupineLoop() {
        val p = porcupine ?: run {
            Log.w(TAG, "No Porcupine key — open the app and use “Speak command”")
            return
        }
        listenThread = Thread {
            val frameLen = p.frameLength
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameLen * 2),
            )
            rec.startRecording()
            val frame = ShortArray(frameLen)
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    if (commandInProgress) {
                        Thread.sleep(30)
                        continue
                    }
                    val read = rec.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    if (p.process(frame) >= 0) {
                        Log.i(TAG, "Wake word")
                        scope.launch {
                            commandInProgress = true
                            try {
                                tts.stop()
                                val pcm = recordCommandPcm()
                                runPipeline(pcm)
                            } finally {
                                commandInProgress = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "porcupine loop", e)
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {}
                rec.release()
            }
        }.also { it.start() }
    }

    private suspend fun recordCommandPcm(): ByteArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096),
        )
        rec.startRecording()
        val out = ByteArrayOutputStream(65536)
        val frame = ShortArray(512)
        var totalShorts = 0
        var silenceStart = 0L
        val t0 = System.currentTimeMillis()
        val maxSamples = SAMPLE_RATE * MAX_SECONDS
        try {
            while (totalShorts < maxSamples) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                for (i in 0 until n) {
                    val s = frame[i]
                    out.write(s.toInt() and 0xff)
                    out.write((s.toInt() shr 8) and 0xff)
                }
                totalShorts += n
                val rms = rms(frame, n)
                if (rms < SILENCE_RMS) {
                    if (silenceStart == 0L) silenceStart = System.currentTimeMillis()
                    if (System.currentTimeMillis() - silenceStart > SILENCE_MS &&
                        System.currentTimeMillis() - t0 > MIN_SPEECH_MS
                    ) {
                        break
                    }
                } else {
                    silenceStart = 0L
                }
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return@withContext out.toByteArray()
    }

    private suspend fun runPipeline(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val wav = WavUtil.pcm16MonoToWav(pcm, SAMPLE_RATE)
        val repo = (application as KodiApplication).repository
        val reply = withContext(Dispatchers.IO) {
            try {
                repo.runVoiceCommand(wav) { cmd: CommandDto ->
                    DeviceToolExecutor.execute(this@KodiVoiceService, cmd)
                }
            } catch (e: SocketTimeoutException) {
                "That took too long. Please try again."
            } catch (e: UnknownHostException) {
                "I'm having trouble connecting. Try again in a moment."
            } catch (e: Exception) {
                Log.e(TAG, "pipeline", e)
                "I'm having trouble connecting. Try again in a moment."
            }
        }
        withContext(Dispatchers.Main) {
            tts.speak(reply)
        }
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var s = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            s += v * v
        }
        return sqrt(s / len)
    }

    override fun onDestroy() {
        running = false
        listenThread?.interrupt()
        listenThread = null
        porcupine?.delete()
        porcupine = null
        tts.shutdown()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "KodiVoice"
        private const val NOTIF_ID = 42
        private const val SAMPLE_RATE = 16000
        private const val MAX_SECONDS = 30
        private const val SILENCE_MS = 900L
        private const val MIN_SPEECH_MS = 400L
        private const val SILENCE_RMS = 120.0
        const val ACTION_STOP = "ai.kodi.app.STOP_VOICE"
        const val ACTION_MANUAL_COMMAND = "ai.kodi.app.MANUAL_COMMAND"
    }
}
