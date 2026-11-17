package com.zeko.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var exo: ExoPlayer? = null
    private lateinit var page: LinearLayout
    private lateinit var screen: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var play: Button
    private lateinit var seek: SeekBar
    private lateinit var clock: TextView
    private lateinit var name: TextView
    private var dragging = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            openVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(12, 14, 19)
        window.navigationBarColor = Color.rgb(12, 14, 19)
        buildInterface()
        intent?.data?.let { openVideo(it) }
    }

    private fun d(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    private fun action(s: String, run: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 45, 57)))
        setOnClickListener { run() }
    }

    private fun buildInterface() {
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(8), d(14), d(10))
            setBackgroundColor(Color.rgb(12, 14, 19))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("ZEKO PLAYER", 21f).apply { setTypeface(null, 1); letterSpacing = .12f },
            LinearLayout.LayoutParams(0, d(54), 1f))
        top.addView(action("＋  Open video") { picker.launch(arrayOf("video/*")) })
        page.addView(top)
        page.addView(label("A cleaner way to watch.", 13f, Color.rgb(145, 154, 170)).apply {
            setPadding(0, 0, 0, d(12))
        })
        screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        screen.addView(pv, FrameLayout.LayoutParams(-1, -1))
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 21, 28))
            addView(label("▶", 46f, Color.rgb(255, 126, 54)).apply { gravity = Gravity.CENTER })
            addView(label("Choose a video to begin", 15f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            addView(label("MP4  ·  MKV  ·  MOV  ·  WEBM", 11f, Color.GRAY).apply { gravity = Gravity.CENTER })
        }
        screen.addView(empty, FrameLayout.LayoutParams(-1, -1))
        page.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        name = label("Nothing playing", 14f, Color.LTGRAY).apply { setPadding(0, d(12), 0, d(6)) }
        page.addView(name)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(8)); setBackgroundColor(Color.rgb(20, 23, 30))
        }
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 126, 54))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) { dragging = true }
                override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) {}
                override fun onStopTrackingTouch(b: SeekBar) {
                    val p = exo
                    if (p != null && p.duration > 0) p.seekTo(p.duration * b.progress / 1000L)
                    dragging = false
                }
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(-1, d(34)))
        val mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = label("00:00 / 00:00", 11f, Color.LTGRAY)
        mainRow.addView(clock, LinearLayout.LayoutParams(0, d(46), 1f))
        mainRow.addView(action("−10") { exo?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) } })
        play = action("▶") { toggle() }
        mainRow.addView(play)
        mainRow.addView(action("+10") { exo?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(it.duration.coerceAtLeast(0))) } })
        mainRow.addView(action("⛶") { pip() })
        controls.addView(mainRow)
        val more = LinearLayout(this).apply { gravity = Gravity.CENTER }
        more.addView(action("Repeat: Off") { repeat(this as Button) })
        more.addView(action("1.0×") { speed(this as Button) })
        more.addView(action("Audio / Subs") { tracks() })
        controls.addView(more)
        page.addView(controls, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(page)
    }

    private fun openVideo(uri: Uri) {
        if (exo == null) {
            exo = ExoPlayer.Builder(this).build()
            (screen.getChildAt(0) as PlayerView).player = exo
            exo?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) { play.text = if (value) "Ⅱ" else "▶" }
                override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                    name.text = metadata.title ?: "Now playing"
                }
            })
        }
        exo?.setMediaItem(MediaItem.fromUri(uri))
        exo?.prepare()
        exo?.play()
        name.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Now playing"
        play.text = "Ⅱ"
        screen.getChildAt(1).visibility = View.GONE
        page.removeCallbacks(progressTask)
        page.post(progressTask)
    }

    private val progressTask = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val duration = p.duration.coerceAtLeast(0)
            if (!dragging && duration > 0) seek.progress = (p.currentPosition * 1000L / duration).toInt()
            clock.text = fmt(p.currentPosition) + " / " + fmt(duration)
            page.postDelayed(this, 500)
        }
    }
    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
    private fun toggle() {
        val p = exo
        if (p == null) { picker.launch(arrayOf("video/*")); return }
        if (p.isPlaying) p.pause() else p.play()
        play.text = if (p.isPlaying) "Ⅱ" else "▶"
    }
    private fun repeat(b: Button) {
        val p = exo ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        b.text = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat: One"
            Player.REPEAT_MODE_ALL -> "Repeat: All"
            else -> "Repeat: Off"
        }
    }
    private fun speed(b: Button) {
        val p = exo ?: return
        val next = when {
            p.playbackParameters.speed < 1.25f -> 1.5f
            p.playbackParameters.speed < 1.75f -> 2f
            p.playbackParameters.speed >= 2f -> .75f
            else -> 1f
        }
        p.setPlaybackSpeed(next)
        b.text = next.toString() + "×"
    }
    private fun tracks() {
        val p = exo ?: return
        val groups = p.currentTracks.groups
        val options = mutableListOf<String>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        groups.forEachIndexed { gi, g ->
            if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO || g.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val kind = if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
                    options.add(kind + " · " + (f.language ?: "Unknown"))
                    pairs.add(gi to ti)
                }
            }
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "No alternate tracks in this video", Toast.LENGTH_SHORT).show()
            return
        }
        val all = arrayOf("Auto / Default") + options.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Audio and subtitles")
            .setItems(all) { _, index ->
                if (index == 0) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
                else {
                    val pair = pairs[index - 1]
                    val g = groups[pair.first]
                    val override = androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, listOf(pair.second))
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().addOverride(override).build()
                }
            }.show()
    }
    private fun pip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            catch (_: Exception) {}
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exo?.isPlaying == true) pip()
    }
    override fun onPictureInPictureModeChanged(active: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(active, config)
        controls.visibility = if (active) View.GONE else View.VISIBLE
        name.visibility = if (active) View.GONE else View.VISIBLE
    }
    override fun onDestroy() {
        page.removeCallbacks(progressTask)
        (screen.getChildAt(0) as? PlayerView)?.player = null
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
