package com.zeko.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through audio tap. Playback audio is left unchanged; a mono 16 kHz PCM16
 * copy is emitted for local speech recognition only while enabled.
 */
class VoskPcmTeeSink(
    private val onPcmChunk: (ByteArray) -> Unit
) : TeeAudioProcessor.AudioBufferSink {
    @Volatile var enabled: Boolean = false

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var resamplePhase = 0
    private val pending = ByteArrayOutputStream(32000)

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        resamplePhase = 0
        pending.reset()
    }

    @Synchronized
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || sampleRate <= 0 || channels <= 0 || encoding != C.ENCODING_PCM_16BIT) return

        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * 2
        val frames = data.remaining() / frameBytes
        if (frames <= 0) return

        for (frame in 0 until frames) {
            var mixed = 0
            for (channel in 0 until channels) mixed += data.short.toInt()
            val mono = (mixed / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            resamplePhase += 16000
            if (resamplePhase >= sampleRate) {
                resamplePhase -= sampleRate
                pending.write(mono and 0xFF)
                pending.write((mono shr 8) and 0xFF)
            }
        }

        // Deliver approximately one second at a time; callback must enqueue work quickly.
        if (pending.size() >= 32000) {
            val chunk = pending.toByteArray()
            pending.reset()
            onPcmChunk(chunk)
        }
    }
}
