package com.zeko.player

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable

/**
 * Offline English speech recognizer.
 *
 * The caller supplies mono PCM 16-bit audio at 16 kHz. Audio capture and
 * model download are intentionally managed separately by the app.
 */
class LocalSpeechRecognizer(
    modelDirectory: String,
    sampleRateHz: Float = 16000.0f
) : Closeable {

    private val model = Model(modelDirectory)
    private val recognizer = Recognizer(model, sampleRateHz)
    private var closed = false

    @Synchronized
    fun acceptAudio(pcm16: ByteArray, length: Int = pcm16.size): String? {
        check(!closed) { "Speech recognizer is closed" }
        require(length in 1..pcm16.size) { "Invalid audio length" }

        return if (recognizer.acceptWaveForm(pcm16, length)) {
            parseText(recognizer.result)
        } else {
            parsePartial(recognizer.partialResult)
        }
    }

    @Synchronized
    fun finish(): String {
        if (closed) return ""
        return parseText(recognizer.finalResult)
    }

    private fun parseText(json: String): String =
        JSONObject(json).optString("text", "").trim()

    private fun parsePartial(json: String): String =
        JSONObject(json).optString("partial", "").trim()

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            recognizer.close()
            model.close()
        }
    }
}
