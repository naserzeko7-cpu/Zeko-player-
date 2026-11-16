package com.zeko.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the public Apache-2.0 Sherpa-ONNX streaming English Zipformer model. */
class SherpaModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
        private data class ModelFile(val name: String, val minimumBytes: Long)
        private val modelFiles = listOf(
            ModelFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 60_000_000L),
            ModelFile("decoder-epoch-99-avg-1-chunk-16-left-128.onnx", 1_000_000L),
            ModelFile("joiner-epoch-99-avg-1-chunk-16-left-128.onnx", 200_000L),
            ModelFile("tokens.txt", 1_000L)
        )
        private const val MODEL_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/"
    }

    fun modelDirectory(): File = File(context.filesDir, MODEL_NAME)

    fun isInstalled(): Boolean {
        val dir = modelDirectory()
        return modelFiles.all {
            File(dir, it.name).isFile && File(dir, it.name).length() >= it.minimumBytes
        }
    }

    fun install(onProgress: (Int) -> Unit, onComplete: (Result<File>) -> Unit) {
        Thread {
            val staging = File(context.filesDir, "$MODEL_NAME.installing")
            try {
                if (staging.exists()) staging.deleteRecursively()
                if (!staging.mkdirs()) throw IllegalStateException("Cannot create model folder")
                modelFiles.forEachIndexed { index, modelFile ->
                    val connection = (URL(MODEL_BASE_URL + modelFile.name + "?download=true")
                        .openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30000
                        readTimeout = 60000
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "ZekoPlayer/1.0")
                    }
                    try {
                        connection.connect()
                        if (connection.responseCode !in 200..299) {
                            throw IllegalStateException("Model download failed: HTTP ${connection.responseCode}")
                        }
                        val target = File(staging, modelFile.name)
                        connection.inputStream.use { input ->
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (target.length() < modelFile.minimumBytes) {
                            throw IllegalStateException("Downloaded model file is incomplete: ${modelFile.name}")
                        }
                    } finally {
                        connection.disconnect()
                    }
                    onProgress(((index + 1) * 90 / modelFiles.size).coerceIn(1, 90))
                }
                val destination = modelDirectory()
                if (destination.exists()) destination.deleteRecursively()
                if (!staging.renameTo(destination)) {
                    throw IllegalStateException("Cannot activate downloaded model")
                }
                onProgress(100)
                onComplete(Result.success(destination))
            } catch (error: Exception) {
                staging.deleteRecursively()
                onComplete(Result.failure(error))
            }
        }.start()
    }
}
