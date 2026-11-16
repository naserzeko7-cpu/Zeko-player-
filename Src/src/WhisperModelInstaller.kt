package com.zeko.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the public Whisper.cpp small.en model for fully on-device English ASR. */
class WhisperModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_NAME = "whisper-small-en"
        const val MODEL_FILE = "ggml-small.en.bin"
        private const val MINIMUM_BYTES = 400_000_000L
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE?download=true"
    }

    fun modelDirectory(): File = File(context.filesDir, MODEL_NAME)

    fun isInstalled(): Boolean {
        val model = File(modelDirectory(), MODEL_FILE)
        return model.isFile && model.length() >= MINIMUM_BYTES
    }

    fun install(onProgress: (Int) -> Unit, onComplete: (Result<File>) -> Unit) {
        Thread {
            val staging = File(context.filesDir, "$MODEL_NAME.installing")
            try {
                if (staging.exists()) staging.deleteRecursively()
                if (!staging.mkdirs()) throw IllegalStateException("Cannot create model folder")
                val target = File(staging, MODEL_FILE)
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 120000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ZekoPlayer/1.0")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("Whisper model download failed: HTTP ${connection.responseCode}")
                    }
                    val total = connection.contentLengthLong
                    var downloaded = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (total > 0) onProgress((downloaded * 100 / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (target.length() < MINIMUM_BYTES) {
                    throw IllegalStateException("Whisper model download is incomplete")
                }
                val destination = modelDirectory()
                if (destination.exists()) destination.deleteRecursively()
                if (!staging.renameTo(destination)) throw IllegalStateException("Cannot activate Whisper model")
                onProgress(100)
                onComplete(Result.success(destination))
            } catch (error: Exception) {
                staging.deleteRecursively()
                onComplete(Result.failure(error))
            }
        }.start()
    }
}
