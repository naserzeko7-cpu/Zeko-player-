package com.zeko.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.Player
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
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
    private val apiKey: String
) {
    private val subtitlesList = mutableListOf<SubtitleItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                updateSubtitle(player.currentPosition)
            }
            handler.postDelayed(this, 300)
        }
    }

    init {
        handler.post(updateRunnable)
    }

    private fun updateSubtitle(currentPositionMs: Long) {
        val currentSub = subtitlesList.find { 
            currentPositionMs in it.startTimeMs..it.endTimeMs 
        }
        subtitleTextView.text = currentSub?.text ?: ""
    }

    fun processAndTranslateAudio(audioFile: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/*".toMediaTypeOrNull()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("language", "ar")
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

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBodyString = response.body?.string()
                    if (responseBodyString != null) {
                        parseWhisperResponse(responseBodyString)
                    }
                }
            }
        })
    }

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

    fun release() {
        handler.removeCallbacks(updateRunnable)
    }
}
