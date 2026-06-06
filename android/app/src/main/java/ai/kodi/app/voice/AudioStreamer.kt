package ai.kodi.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Full-duplex PCM audio for the Gemini Live path.
 *
 * Capture: 16 kHz mono PCM16 from the mic (VOICE_RECOGNITION), with hardware
 * AcousticEchoCanceler + NoiseSuppressor enabled when available so the user can
 * barge in over Kodi's own playback.
 * Playback: 24 kHz mono PCM16 streamed from Gemini.
 */
class AudioStreamer {

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private var track: AudioTrack? = null

    /** Start mic capture; [onPcm] receives raw little-endian PCM16 frames (~20 ms). */
    fun startCapture(onPcm: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (mic permission?)")
            rec.release()
            return false
        }
        enableEffects(rec.audioSessionId)
        record = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread({
            val buf = ByteArray(FRAME_BYTES)
            try {
                while (capturing) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        onPcm(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture loop", e)
            }
        }, "KodiLiveMic").also { it.start() }
        return true
    }

    private fun enableEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AEC unavailable", e)
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NS unavailable", e)
        }
    }

    fun stopCapture() {
        capturing = false
        try { captureThread?.join(500) } catch (_: InterruptedException) {}
        captureThread = null
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, PLAYBACK_RATE)) // ~0.5s headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        return t
    }

    /** Write one chunk of Gemini audio (24 kHz PCM16) to the speaker. */
    fun playbackWrite(bytes: ByteArray) {
        try {
            ensureTrack().write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "playback write", e)
        }
    }

    /** Drop any queued/playing audio immediately (barge-in / interruption). */
    fun clearPlayback() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (e: Exception) {
            Log.w(TAG, "clearPlayback", e)
        }
    }

    fun releasePlayback() {
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    fun release() {
        stopCapture()
        releasePlayback()
    }

    companion object {
        private const val TAG = "KodiAudio"
        const val CAPTURE_RATE = 16000
        const val PLAYBACK_RATE = 24000
        // 20 ms @ 16 kHz mono PCM16 = 320 samples * 2 bytes.
        private const val FRAME_BYTES = 640
    }
}
