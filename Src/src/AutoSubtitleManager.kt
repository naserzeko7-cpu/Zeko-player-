package com.zeko.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.Player
import org.json.JSONObject
import java.io.File

data class SubtitleItem(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

class AutoSubtitleManager(
    private val context: Context,
    private val player: Player,
    private val subtitleTextView: TextView
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

    fun loadSubtitlesFromJson(jsonResponse: String) {
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
