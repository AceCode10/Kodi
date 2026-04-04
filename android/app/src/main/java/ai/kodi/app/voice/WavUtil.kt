package ai.kodi.app.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtil {
    fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size
        val riffSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToLe(riffSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLe(16))
        out.write(shortToLe(1))
        out.write(shortToLe(channels.toShort()))
        out.write(intToLe(sampleRate))
        out.write(intToLe(byteRate))
        out.write(shortToLe(blockAlign))
        out.write(shortToLe(bitsPerSample.toShort()))
        out.write("data".toByteArray())
        out.write(intToLe(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToLe(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortToLe(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
