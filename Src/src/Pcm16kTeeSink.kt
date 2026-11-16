package com.zeko.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pass-through audio tap. Playback is unchanged; emits mono 16 kHz PCM16 for ASR. */
class Pcm16kTeeSink(
    private val onPcmChunk: (ByteArray) -> Unit
) : TeeAudioProcessor.AudioBufferSink {
    @Volatile var enabled: Boolean = false
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var resamplePhase = 0
    private var resampleSum = 0L
    private var resampleCount = 0
    private val pending = ByteArrayOutputStream(8000)

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        resamplePhase = 0
        resampleSum = 0L
        resampleCount = 0
        pending.reset()
    }

    @Synchronized
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || sampleRate < 16000 || channels <= 0 || encoding != C.ENCODING_PCM_16BIT) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * 2
        val frames = data.remaining() / frameBytes
        if (frames <= 0) return
        for (frame in 0 until frames) {
            var mixed = 0
            for (channel in 0 until channels) mixed += data.short.toInt()
            val mono = (mixed / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            // Average source frames before downsampling to reduce aliasing.
            resampleSum += mono
            resampleCount++
            resamplePhase += 16000
            if (resamplePhase >= sampleRate) {
                val filtered = (resampleSum / resampleCount.coerceAtLeast(1))
                    .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                pending.write(filtered and 0xFF)
                pending.write((filtered shr 8) and 0xFF)
                resamplePhase -= sampleRate
                resampleSum = 0L
                resampleCount = 0
            }
        }
        // 8 KB is approximately 250 ms of mono PCM16 at 16 kHz.
        if (pending.size() >= 8000) {
            val chunk = pending.toByteArray()
            pending.reset()
            onPcmChunk(chunk)
        }
    }
}
