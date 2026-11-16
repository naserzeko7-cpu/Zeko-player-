Gjiih:
import os

code_content = """package com.zeko.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.Player
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SubtitleItem(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

class AutoSubtitleManager(
    private val context: Context,
    private val player: Player,
    private val subtitleTextView: TextView,
    private val apiKey: String // مفتاح الـ API الخاص بـ OpenAI
) {
    private val subtitlesList = mutableListOf<SubtitleItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                updateSubtitle(player.currentPosition)
            }
            handler.postDelayed(this, 300) // تحديث كل 300 جزء من الثانية للمزامنة الدقيقة
        }
    }

    init {
        // بدء مراقبة وقت التشغيل لعرض الترجمة بالتزامن مع الفيديو
        handler.post(updateRunnable)
    }

    // تحديث النص الظاهر على الشاشة بناءً على وقت الفيديو الحالي
    private fun updateSubtitle(currentPositionMs: Long) {
        val currentSub = subtitlesList.find { 
            currentPositionMs in it.startTimeMs..it.endTimeMs 
        }
        subtitleTextView.text = currentSub?.text ?: ""
    }

    // دالة إرسال ملف الصوت واستخراج الترجمة وترجمتها للعربية
    fun processAndTranslateAudio(audioFile: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/*".toMediaTypeOrNull()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("language", "ar") // تحديد اللغة العربية للترجمة الفورية
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Response, response: Response) {
                if (response.isSuccessful) {
                    response.body?.string()?.let { parseWhisperResponse(it) }
                }
            }
        })
    }

    // تحليل نتيجة الـ JSON وتحويلها لقائمة أوقات ونصوص متسلسلة
    private fun parseWhisperResponse(jsonResponse: String) {
        try {
            val jsonObject = JSONObject(jsonResponse)
            val segments = jsonObject.getJSONArray("segments")
            
            subtitlesList.clear()
            for (i in 0 until segments.length()) {
                val segment = segments.getJSONObject(i)
                val startSec = segment.getDouble("start")
                val endSec = segment.getDouble("end")
                val text = segment.getString("text").trim()

                subtitlesList.add(
                    SubtitleItem(
                        startTimeMs = (startSec * 1000).toLong(),
                        endTimeMs = (endSec * 1000).toLong(),
                        text = text
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // تنظيف الموارد وإيقاف الـ Handler لمنع استهلاك الذاكرة
    fun release() {
        handler.removeCallbacks(updateRunnable)
    }
}
"""

file_path = "AutoSubtitleManager.kt"
with open(file_path, "w", encoding="utf-8") as f:
    f.write(code_content)
