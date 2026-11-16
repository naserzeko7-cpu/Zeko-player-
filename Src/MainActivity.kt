package com.zeko.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import androidx.media3.ui.TimeBar
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var seekLabel: TextView
    private lateinit var watermark: TextView

    // Video Seek Preview
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImg: ImageView
    private lateinit var previewTime: TextView
    private var retriever: MediaMetadataRetriever? = null
    private val frameExec = Executors.newSingleThreadExecutor()
    @Volatile private var latestReq = -1L

    private val accent = Color.parseColor("#FF6D00")
    private val bg = Color.parseColor("#0E0E12")
    private val REQ_PICK = 1

    private var lastSeekTime = 0L
    private var lastSide = 0
    private var accumulated = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = FrameLayout(this)
        root.setBackgroundColor(bg)

        buildPlayer()
        buildHome()
        setContentView(root)

        intent?.data?.let { play(it) }
    }

    // ---------- Home screen ----------
    private fun buildHome() {
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "Zeko Player"
            textSize = 46f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "Made by Zakariya"
            textSize = 16f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(40))
        }

        val openBtn = pill("Open video from device", true) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            }
            startActivityForResult(i, REQ_PICK)
        }

        val urlBox = EditText(this).apply {
            hint = "Paste video link (https://...)"
            setHintTextColor(Color.parseColor("#777777"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#1C1C24"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(320), dp(56)).apply { topMargin = dp(28) }
        }

        val linkBtn = pill("Play link", false) {
            val u = urlBox.text.toString().trim()
            if (u.isNotEmpty()) play(Uri.parse(u))
        }

        home.addView(title)
        home.addView(sub)
        home.addView(openBtn)
        home.addView(urlBox)
        home.addView(linkBtn)
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): Button {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            if (filled) {
                setColor(accent)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), accent)
            }
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 17f
            background = shape
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(320), dp(56)).apply { topMargin = dp(14) }
        }
    }

    // ---------- Player screen ----------
    private fun buildPlayer() {
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            controllerShowTimeoutMs = 3000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // keep seeking if user is tapping fast after a double tap (like YouTube)
                val now = System.currentTimeMillis()
                if (lastSide != 0 && now - lastSeekTime < 700) {
                    seekBy(lastSide)
                } else if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = playerView.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-1)
                    e.x > w * 2f / 3f -> seekBy(1)
                    else -> player?.let { it.playWhenReady = !it.playWhenReady }
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

        seekLabel = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(40).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            alpha = 0f
            visibility = View.GONE
        }

        watermark = TextView(this).apply {
            text = "Zeko Player  •  Zakariya"
            textSize = 13f
            setTextColor(Color.WHITE)
            alpha = 0.55f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            seekLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END)
        )
        root.addView(
            watermark,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        )
        setupSeekPreview()
    }

    // ---------- Video Seek Preview ----------
    private fun setupSeekPreview() {
        previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(101))
        }
        previewTime = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6000000"))
                setStroke(dp(2), accent)
            }
            visibility = View.GONE
            addView(previewImg)
            addView(previewTime)
        }
        root.addView(previewBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        val id = resources.getIdentifier("exo_progress", "id", packageName)
        val tb = playerView.findViewById<View>(id) as? TimeBar
        tb?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                previewBox.visibility = View.VISIBLE
                updatePreview(timeBar, position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                updatePreview(timeBar, position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                previewBox.visibility = View.GONE
                latestReq = -1L
            }
        })
    }

    private fun updatePreview(tb: TimeBar, pos: Long) {
        val view = tb as View
        val dur = player?.duration ?: 0L
        if (dur <= 0L) return

        val tbLoc = IntArray(2)
        val rootLoc = IntArray(2)
        view.getLocationOnScreen(tbLoc)
        root.getLocationOnScreen(rootLoc)

        val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val cx = (tbLoc[0] - rootLoc[0]) + view.width * frac
        val w = if (previewBox.width > 0) previewBox.width else dp(190)
        val h = if (previewBox.height > 0) previewBox.height else dp(140)
        val maxX = (root.width - w - dp(8)).toFloat().coerceAtLeast(dp(8).toFloat())
        previewBox.x = (cx - w / 2f).coerceIn(dp(8).toFloat(), maxX)
        previewBox.y = (tbLoc[1] - rootLoc[1] - h - dp(12)).toFloat()
        previewTime.text = formatTime(pos)

        latestReq = pos
        frameExec.execute {
            if (latestReq != pos) return@execute
            val r = retriever ?: return@execute
            val bmp = try {
                r.getFrameAtTime(pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            }
            if (bmp != null && latestReq == pos) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true
                )
                runOnUiThread { if (latestReq == pos) previewImg.setImageBitmap(scaled) }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private fun prepareRetriever(uri: Uri) {
        previewImg.setImageDrawable(null)
        frameExec.execute {
            try { retriever?.release() } catch (e: Exception) { }
            retriever = null
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "http" || uri.scheme == "https") {
                    r.setDataSource(uri.toString(), HashMap<String, String>())
                } else {
                    r.setDataSource(this@MainActivity, uri)
                }
                retriever = r
            } catch (e: Exception) {
                try { r.release() } catch (e2: Exception) { }
            }
        }
    }

    private fun releaseRetriever() {
        frameExec.execute {
            try { retriever?.release() } catch (e: Exception) { }
            retriever = null
        }
    }

    private fun seekBy(side: Int) {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (now - lastSeekTime > 900 || side != lastSide) accumulated = 0
        accumulated += 10
        lastSeekTime = now
        lastSide = side

        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
        val target = (p.currentPosition + side * 10_000L).coerceIn(0L, dur)
        p.seekTo(target)

        val lp = seekLabel.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.CENTER_VERTICAL or (if (side > 0) Gravity.END else Gravity.START)
        lp.leftMargin = dp(60)
        lp.rightMargin = dp(60)
        seekLabel.layoutParams = lp
        seekLabel.text = if (side > 0) "${accumulated}s  ▶▶" else "◀◀  ${accumulated}s"
        seekLabel.visibility = View.VISIBLE
        seekLabel.animate().cancel()
        seekLabel.alpha = 1f
        seekLabel.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
    }

    private fun play(uri: Uri) {
        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        prepareRetriever(uri)

        home.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        watermark.visibility = View.VISIBLE
        hideSystemBars()
    }

    private fun stopAndGoHome() {
        player?.release()
        player = null
        playerView.player = null
        releaseRetriever()
        previewBox.visibility = View.GONE
        playerView.visibility = View.GONE
        watermark.visibility = View.GONE
        home.visibility = View.VISIBLE
        showSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { play(it) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerView.visibility == View.VISIBLE) stopAndGoHome() else super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        releaseRetriever()
        frameExec.shutdown()
    }
}
