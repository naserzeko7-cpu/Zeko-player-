package com.zeko.player

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for the user's own MADLAD-400 translation server.
 * Only recognized text is sent; video/audio are never uploaded.
 */
class MadladArabicTranslator(
    endpoint: String,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val baseUrl = endpoint.trim().trimEnd('/')
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun prepare() {
        executor.execute {
            try {
                val connection = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                val body = try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("خادم الترجمة غير متاح: HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val health = JSONObject(body)
                if (!health.optBoolean("model_loaded", false)) {
                    throw IllegalStateException("الخادم متصل لكن نموذج MADLAD لم يجهز بعد")
                }
                if (!closed.get()) {
                    ready.set(true)
                    onReady()
                }
            } catch (error: Exception) {
                if (!closed.get()) onError(error.localizedMessage ?: "تعذر الاتصال بخادم MADLAD")
            }
        }
    }

    fun translate(text: String): Task<String> {
        require(text.isNotBlank()) { "Text must not be blank" }
        check(ready.get() && !closed.get()) { "MADLAD server is not ready" }
        val source = TaskCompletionSource<String>()
        executor.execute {
            try {
                val connection = (URL("$baseUrl/translate").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val response = try {
                    val payload = JSONObject().put("text", text.trim()).toString()
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val detail = try { JSONObject(body).optString("detail") } catch (_: Exception) { body }
                        throw IllegalStateException("فشل طلب الترجمة: HTTP $code $detail")
                    }
                    JSONObject(body).optString("translation").trim().also {
                        if (it.isBlank()) throw IllegalStateException("الخادم أعاد ترجمة فارغة")
                    }
                } finally {
                    connection.disconnect()
                }
                if (!closed.get()) source.setResult(response)
                else source.setException(IllegalStateException("Translator closed"))
            } catch (error: Exception) {
                if (!closed.get()) source.setException(error)
            }
        }
        return source.task
    }

    fun isReady(): Boolean = ready.get() && !closed.get()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            ready.set(false)
            executor.shutdownNow()
        }
    }
}
