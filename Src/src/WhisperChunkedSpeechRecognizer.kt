package com.zeko.player

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Offline English ASR using whisper.cpp.
 * The free Android AAR exposes file transcription, so the player supplies six-second WAV chunks.
 */
class WhisperChunkedSpeechRecognizer(
    context: Context,
    modelFile: File
) : Closeable {
    private val model: WhisperModel
    private val workFile = File(context.cacheDir, "zeko-whisper-chunk.wav")
    private var closed = false

    init {
        require(modelFile.isFile && modelFile.length() > 400_000_000L) {
            "Whisper Small English model is missing or incomplete"
        }
        model = runBlocking { Whisper.loadModel(context.applicationContext, modelFile.absolutePath) }
    }

    @Synchronized
    fun acceptAudio(pcm16: ByteArray): String? {
        check(!closed) { "Whisper recognizer is closed" }
        if (pcm16.isEmpty()) return null
        writeWav(workFile, pcm16, 16000, 1)
        return try {
            runBlocking {
                Whisper.transcribe(model, workFile.absolutePath, WhisperConfig(language = "en"))
            }.text.trim().takeIf { it.isNotBlank() }
        } finally {
            workFile.delete()
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val dataSize = pcm.size
        val byteRate = sampleRate * channels * 2
        FileOutputStream(file).use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) {
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
            }
            fun le32(value: Int) {
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
                out.write((value shr 16) and 0xff)
                out.write((value shr 24) and 0xff)
            }
            ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(channels)
            le32(sampleRate); le32(byteRate); le16(channels * 2); le16(16)
            ascii("data"); le32(dataSize); out.write(pcm)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            Whisper.releaseModel(model)
            workFile.delete()
        }
    }
}
