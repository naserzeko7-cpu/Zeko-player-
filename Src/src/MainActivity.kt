package com.zeko.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class VideoItem(val id: Long, val name: String, val durMs: Long, val uri: Uri)

private class CardHolder(val img: ImageView, val dur: TextView, val title: TextView) {
    var id: Long = -1L
}

class MainActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var grid: GridView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: Button
    private lateinit var sectionLabel: TextView
    private lateinit var playerView: PlayerView
    private lateinit var seekLabel: TextView
    private lateinit var watermark: TextView

    // Video Seek Preview
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImg: ImageView
    private lateinit var previewTime: TextView
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewTex: TextureView
    private var previewPlayer: ExoPlayer? = null
    private var lastPreviewSeek = 0L
    private var pendingPos = 0L
    private val seekRunnable = Runnable { previewPlayer?.seekTo(pendingPos) }

    private val accent = Color.parseColor("#FF6D00")
    private val bg = Color.parseColor("#0E0E12")
    private val cardBg = Color.parseColor("#1A1A22")
    private val REQ_PICK = 1
    private val REQ_PERM = 2

    private var videos: List<VideoItem> = emptyList()
    private val adapter = VideoAdapter()
    private val thumbCache = LruCache<Long, Bitmap>(200)
    private val ioExec = Executors.newFixedThreadPool(3)
    private val frameExec = Executors.newSingleThreadExecutor()

    @Volatile private var strip: Array<Bitmap?> = emptyArray()
    @Volatile private var stripDurMs = 0L
    @Volatile private var stripGen = 0
    private var lastShown: Bitmap? = null

    private var lastSeekTime = 0L
    private var lastSide = 0
    private var accumulated = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        root = FrameLayout(this)
        root.setBackgroundColor(bg)

        buildHome()
        buildPlayer()
        setContentView(root)

        refreshLibrary()
        intent?.data?.let { play(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.numColumns = columns()
        adapter.notifyDataSetChanged()
    }

    // ---------- Permissions + library ----------
    private fun permName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm(): Boolean =
        checkSelfPermission(permName()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) refreshLibrary()
    }

    private fun refreshLibrary() {
        if (hasPerm()) loadVideos() else updateEmpty()
    }

    private fun loadVideos() {
        ioExec.execute {
            val list = ArrayList<VideoItem>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        list.add(
                            VideoItem(
                                id,
                                c.getString(iName) ?: "Video",
                                c.getLong(iDur),
                                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
            }
            runOnUiThread {
                videos = list
                adapter.notifyDataSetChanged()
                updateEmpty()
            }
        }
    }

    private fun updateEmpty() {
        val granted = hasPerm()
        val showEmpty = !granted || videos.isEmpty()
        emptyBox.visibility = if (showEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (showEmpty) View.GONE else View.VISIBLE
        allowBtn.visibility = if (granted) View.GONE else View.VISIBLE
        emptyText.text = if (!granted) "Allow access to show the videos on your device"
        else "No videos found on this device"
        sectionLabel.text = "All videos  •  ${videos.size}"
    }

    private fun columns(): Int =
        (resources.configuration.screenWidthDp / 200).coerceIn(2, 6)

    private fun thumbHeight(): Int {
        val cols = columns()
        val w = resources.displayMetrics.widthPixels - dp(40) - (cols - 1) * dp(14)
        return (w / cols) * 9 / 16
    }

    // ---------- Home screen ----------
    private fun buildHome() {
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titles.addView(TextView(this).apply {
            text = "Zeko Player"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "By Zakariya"
            textSize = 13f
            setTextColor(accent)
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        header.addView(titles)
        header.addView(pill("Link", false, dp(100), dp(46)) { askLink() })
        header.addView(pill("Browse", true, dp(120), dp(46)) { pickFile() })

        sectionLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9A9AA8"))
            setPadding(dp(22), dp(8), dp(20), dp(10))
        }

        grid = GridView(this).apply {
            numColumns = columns()
            horizontalSpacing = dp(14)
            verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(20), dp(4), dp(20), dp(20))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> play(videos[pos].uri) }
        }

        emptyText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.parseColor("#9A9AA8"))
            gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), dp(16))
        }
        allowBtn = pill("Allow access", true, dp(220), dp(52)) {
            requestPermissions(arrayOf(permName()), REQ_PERM)
        }
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(emptyText)
            addView(allowBtn)
        }

        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))

        home.addView(header)
        home.addView(sectionLabel)
        home.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        updateEmpty()
    }

    private fun pill(label: String, filled: Boolean, w: Int, h: Int, onClick: () -> Unit): Button {
        val shape = GradientDrawable().apply {
            cornerRadius = (h / 2).toFloat()
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
            textSize = 15f
            background = shape
            stateListAnimator = null
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(w, h).apply { leftMargin = dp(10) }
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        startActivityForResult(i, REQ_PICK)
    }

    private fun askLink() {
        val et = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle("Play from link")
            .setView(et)
            .setPositiveButton("Play") { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty()) play(Uri.parse(u))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class VideoAdapter : BaseAdapter() {
        override fun getCount(): Int = videos.size
        override fun getItem(position: Int): Any = videos[position]
        override fun getItemId(position: Int): Long = videos[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = videos[position]
            val holder: CardHolder
            val card: View
            if (convertView == null) {
                val img = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.parseColor("#24242E"))
                }
                val dur = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#B3000000"))
                    }
                }
                val frame = FrameLayout(this@MainActivity)
                frame.addView(img, FrameLayout.LayoutParams(-1, thumbHeight()))
                frame.addView(
                    dur,
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                        setMargins(0, 0, dp(8), dp(8))
                    }
                )
                val title = TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(12))
                }
                val c = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(cardBg)
                    }
                    clipToOutline = true
                }
                c.addView(frame)
                c.addView(title)
                holder = CardHolder(img, dur, title)
                c.tag = holder
                card = c
            } else {
                card = convertView
                holder = card.tag as CardHolder
            }

            holder.img.layoutParams.height = thumbHeight()
            holder.id = item.id
            holder.title.text = item.name.substringBeforeLast('.')
            holder.dur.text = formatTime(item.durMs)

            val cached = thumbCache.get(item.id)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
            } else {
                holder.img.setImageDrawable(null)
                ioExec.execute {
                    val bmp = loadThumb(item)
                    if (bmp != null) {
                        thumbCache.put(item.id, bmp)
                        runOnUiThread { if (holder.id == item.id) holder.img.setImageBitmap(bmp) }
                    }
                }
            }
            return card
        }
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(item: VideoItem): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.loadThumbnail(item.uri, Size(400, 225), null)
        } else {
            MediaStore.Video.Thumbnails.getThumbnail(
                contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
            )
        }
    } catch (e: Exception) {
        null
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

    // ---------- Video Seek Preview (smooth) ----------
    private fun setupSeekPreview() {
        previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        previewTex = TextureView(this).apply { alpha = 0f }
        previewFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.BLACK)
            }
            clipToOutline = true
            addView(previewImg, FrameLayout.LayoutParams(-1, -1))
            addView(previewTex, FrameLayout.LayoutParams(-1, -1))
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
            addView(previewFrame)
            addView(previewTime)
        }
        root.addView(previewBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        val id = resources.getIdentifier("exo_progress", "id", packageName)
        val tb = playerView.findViewById<View>(id) as? TimeBar
        tb?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                previewTex.alpha = 0f
                previewBox.visibility = View.VISIBLE
                updatePreview(timeBar, position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                updatePreview(timeBar, position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                previewBox.visibility = View.GONE
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

        pendingPos = pos
        val nowMs = SystemClock.uptimeMillis()
        previewBox.removeCallbacks(seekRunnable)
        if (nowMs - lastPreviewSeek > 60) {
            lastPreviewSeek = nowMs
            previewPlayer?.seekTo(pos)
        } else {
            previewBox.postDelayed(seekRunnable, 70)
        }

        // show the nearest frame that is already prepared (instant, no waiting)
        val arr = strip
        val total = stripDurMs
        if (arr.isNotEmpty() && total > 0L) {
            val idx = ((pos.toFloat() / total) * (arr.size - 1)).roundToInt().coerceIn(0, arr.size - 1)
            var bmp: Bitmap? = null
            var d = 0
            while (bmp == null && d < arr.size) {
                bmp = arr.getOrNull(idx - d) ?: arr.getOrNull(idx + d)
                d++
            }
            if (bmp != null && bmp !== lastShown) {
                previewImg.setImageBitmap(bmp)
                lastShown = bmp
            }
        }
    }

    private fun preparePreviewPlayer(uri: Uri) {
        previewPlayer?.release()
        previewTex.alpha = 0f
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 500, 1000)
            .build()
        val pp = ExoPlayer.Builder(this).setLoadControl(lc).build()
        pp.volume = 0f
        pp.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        pp.setVideoTextureView(previewTex)
        pp.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                previewTex.alpha = 1f
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sizePreview(videoSize)
            }
        })
        pp.setMediaItem(MediaItem.fromUri(uri))
        pp.prepare()
        pp.playWhenReady = false
        previewPlayer = pp
    }

    private fun sizePreview(vs: VideoSize) {
        var w = vs.width.toFloat() * vs.pixelWidthHeightRatio
        var h = vs.height.toFloat()
        if (vs.unappliedRotationDegrees % 180 != 0) {
            val t = w; w = h; h = t
        }
        if (w <= 0f || h <= 0f) return
        val hh = dp(101)
        val ww = (hh * w / h).toInt().coerceIn(dp(100), dp(240))
        previewFrame.layoutParams = LinearLayout.LayoutParams(ww, hh)
    }

    private fun prepareStrip(uri: Uri) {
        val gen = ++stripGen
        strip = emptyArray()
        stripDurMs = 0L
        lastShown = null
        previewImg.setImageDrawable(null)

        frameExec.execute {
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "http" || uri.scheme == "https") {
                    r.setDataSource(uri.toString(), HashMap<String, String>())
                } else {
                    r.setDataSource(this@MainActivity, uri)
                }
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (dur <= 0L || gen != stripGen) return@execute

                val isNet = uri.scheme == "http" || uri.scheme == "https"
                val count = if (isNet) 30 else (dur / 4000L).toInt().coerceIn(30, 150)

                var vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 1280
                var vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 720
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) {
                    val t = vw; vw = vh; vh = t
                }
                if (vw <= 0) vw = 1280
                if (vh <= 0) vh = 720
                val dw = 320
                val dh = (dw * vh / vw).coerceIn(90, 568)

                val local = arrayOfNulls<Bitmap>(count)
                stripDurMs = dur
                strip = local

                // coarse pass first, then fill in details
                val seen = BooleanArray(count)
                val order = ArrayList<Int>()
                for (s in intArrayOf(8, 4, 2, 1)) {
                    var i = 0
                    while (i < count) {
                        if (!seen[i]) {
                            seen[i] = true
                            order.add(i)
                        }
                        i += s
                    }
                }
                for (i in order) {
                    if (gen != stripGen) break
                    val tUs = dur * 1000L * i / (count - 1).coerceAtLeast(1)
                    local[i] = grab(r, tUs, dw, dh)
                }
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        }
    }

    private fun grab(r: MediaMetadataRetriever, tUs: Long, dw: Int, dh: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
        } else {
            r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { Bitmap.createScaledBitmap(it, dw, dh, true) }
        }
    } catch (e: Exception) {
        null
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    // ---------- Playback ----------
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
        prepareStrip(uri)
        preparePreviewPlayer(uri)

        home.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        watermark.visibility = View.VISIBLE
        hideSystemBars()
    }

    private fun stopAndGoHome() {
        stripGen++
        previewPlayer?.release()
        previewPlayer = null
        player?.release()
        player = null
        playerView.player = null
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
        stripGen++
        previewPlayer?.release()
        previewPlayer = null
        player?.release()
        player = null
        frameExec.shutdown()
        ioExec.shutdown()
    }
}
