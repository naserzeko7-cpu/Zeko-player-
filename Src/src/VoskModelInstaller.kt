package com.zeko.player

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and safely extracts the small English Vosk model into app-private storage.
 * No audio is uploaded; this only downloads the public model archive.
 */
class VoskModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    fun modelDirectory(): File = File(context.filesDir, MODEL_NAME)

    fun isInstalled(): Boolean {
        val dir = modelDirectory()
        return File(dir, "am/final.mdl").isFile &&
            File(dir, "conf/model.conf").isFile
    }

    fun install(onProgress: (Int) -> Unit, onComplete: (Result<File>) -> Unit) {
        Thread {
            val archive = File(context.cacheDir, "$MODEL_NAME.zip")
            val staging = File(context.filesDir, "$MODEL_NAME.installing")
            try {
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("Model download failed: HTTP ${connection.responseCode}")
                    }
                    val total = connection.contentLengthLong
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var received = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                received += read
                                if (total > 0L) onProgress(((received * 100) / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                if (staging.exists()) staging.deleteRecursively()
                if (!staging.mkdirs()) throw IllegalStateException("Cannot create model folder")
                val stagingRoot = staging.canonicalPath + File.separator
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.removePrefix("$MODEL_NAME/")
                        if (relative.isNotEmpty()) {
                            val target = File(staging, relative)
                            val canonical = target.canonicalPath
                            if (!canonical.startsWith(stagingRoot)) {
                                throw SecurityException("Invalid model archive path")
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output -> zip.copyTo(output) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                val stagedModel = File(staging, "am/final.mdl")
                if (!stagedModel.isFile || !File(staging, "conf/model.conf").isFile) {
                    throw IllegalStateException("Downloaded archive is missing model files")
                }
                val destination = modelDirectory()
                if (destination.exists()) destination.deleteRecursively()
                if (!staging.renameTo(destination)) {
                    throw IllegalStateException("Cannot activate downloaded model")
                }
                archive.delete()
                onProgress(100)
                onComplete(Result.success(destination))
            } catch (error: Exception) {
                archive.delete()
                staging.deleteRecursively()
                onComplete(Result.failure(error))
            }
        }.start()
    }
}
