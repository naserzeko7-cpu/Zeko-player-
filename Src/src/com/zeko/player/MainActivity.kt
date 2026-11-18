package com.zeko.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

private data class VideoFile(val id: Long, val uri: Uri, val title: String, val duration: Long, val size: Long)

class MainActivity : ComponentActivity() {
    private val blue = Color.rgb(42, 139, 255)
    private val bg = Color.rgb(9, 16, 29)
    private val panel = Color.rgb(19, 32, 51)
    private val muted = Color.rgb(151, 169, 192)
    private val videos = mutableListOf<VideoFile>()
    private val queue = mutableListOf<VideoFile>()
    private var exo: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var playView: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeView: TextView
    private var dragging = false
    private var filter = "All videos"
    private var query = ""

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadVideos() else permissionScreen()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            queue.clear()
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                queue.add(VideoFile(0, uri, uri.lastPathSegment ?: "Video", 0, 0))
            }
            playAt(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        root = FrameLayout(this)
        setContentView(root)
        if (hasVideoPermission()) loadVideos() else askPermission()
        intent?.data?.let { uri ->
            queue.clear()
            queue.add(VideoFile(0, uri, uri.lastPathSegment ?: "Video", 0, 0))
            playAt(0)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasVideoPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }
    private fun askPermission() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        permission.launch(p)
    }
    private fun shape(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun txt(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(value: String, primary: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = value; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(15), dp(10), dp(15), dp(10))
        background = shape(if (primary) blue else Color.rgb(28, 45, 68), 24, if (primary) blue else Color.rgb(54, 78, 109))
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }

    private fun loadVideos() {
        videos.clear()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE)
        try {
            contentResolver.query(base, columns, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val key = c.getLong(id)
                    videos.add(VideoFile(key, Uri.withAppendedPath(base, key.toString()),
                        c.getString(name) ?: "Untitled video", c.getLong(duration), c.getLong(size)))
                }
            }
        } catch (_: Exception) {}
        showLibrary()
    }

    private fun permissionScreen() {
        root.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(28), dp(28), dp(28)); setBackgroundColor(bg) }
        box.addView(txt("ZEKO PLAYER", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        box.addView(txt("Allow video access to build your personal library.", 14f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20)) })
        box.addView(action("Allow access", true) { askPermission() })
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showLibrary() {
        root.removeCallbacks(progressTask)
        root.removeAllViews()
        root.setBackgroundColor(bg)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            fillViewPort = true
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(34))
        }
        scroll.addView(body)
        root.addView(scroll)

        // Compact editorial header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(txt("ZEKO", 25f, Color.WHITE, true).apply { letterSpacing = .08f })
        brand.addView(txt("PLAYER  /  YOUR MEDIA LIBRARY", 9f, muted, true).apply { letterSpacing = .12f; setPadding(0, dp(3), 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⌕", false) { searchDialog() })
        header.addView(Space(this), LinearLayout.LayoutParams(dp(8), 1))
        header.addView(action("＋", true) { picker.launch(arrayOf("video/*")) })
        body.addView(header)
        body.addView(txt(if (videos.isEmpty()) "Your next watch starts here." else "Pick up where your curiosity takes you.", 13f, muted)
            .apply { setPadding(0, dp(8), 0, dp(20)) })

        val allItems = filteredVideos()
        val featured = allItems.firstOrNull()
        val hero = FrameLayout(this).apply {
            background = shape(Color.rgb(17, 34, 56), 24, Color.rgb(45, 83, 130))
            clipToOutline = true
        }
        if (featured != null) {
            thumbnail(featured, 900, 560)?.let { bmp ->
                hero.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .68f
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(5, 12, 23), Color.argb(205, 5, 12, 23), Color.argb(35, 5, 12, 23)))
        }, FrameLayout.LayoutParams(-1, -1))
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(21), dp(20), dp(21), dp(20))
        }
        heroContent.addView(txt("YOUR PERSONAL CINEMA", 9f, Color.rgb(117, 190, 255), true).apply { letterSpacing = .16f })
        heroContent.addView(txt(featured?.title ?: "A better place\nfor your videos.", 22f, Color.WHITE, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(9), 0, dp(7))
        })
        heroContent.addView(txt(if (featured == null) "Bring your videos together in one place." else
            durationText(featured.duration) + "   •   " + fileSize(featured.size), 11f, Color.rgb(211, 225, 243)))
        heroContent.addView(action(if (featured == null) "＋  Add your videos" else "▶   Watch now", true) {
            if (featured == null) picker.launch(arrayOf("video/*")) else playVideo(featured)
        }.apply { setPadding(dp(18), dp(11), dp(18), dp(11)) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1))
        body.addView(hero, LinearLayout.LayoutParams(-1, dp(226)).apply { bottomMargin = dp(22) })

        // Filter chips
        body.addView(txt("EXPLORE", 10f, muted, true).apply { letterSpacing = .15f; setPadding(0, 0, 0, dp(10)) })
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All videos", "Recently added", "Long videos", "Short clips").forEach { item ->
            chips.addView(action(item, filter == item) { filter = item; showLibrary() },
                LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        }
        chipScroll.addView(chips)
        body.addView(chipScroll, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(23) })

        val section = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sectionText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sectionText.addView(txt(if (filter == "All videos") "Your library" else filter, 21f, Color.WHITE, true))
        sectionText.addView(txt("${allItems.size} videos  ·  Stored on this device", 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
        section.addView(sectionText, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(action("↻", false) { loadVideos() })
        body.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })

        if (allItems.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(20), dp(34), dp(20), dp(34)); background = shape(panel, 22)
            }
            empty.addView(txt(if (videos.isEmpty()) "Your library is waiting." else "No matching videos", 18f, Color.WHITE, true)
                .apply { gravity = Gravity.CENTER })
            empty.addView(txt(if (videos.isEmpty()) "Add videos from your device to see them here." else "Try another search or category.", 12f, muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(17)) })
            empty.addView(action(if (videos.isEmpty()) "＋  Add videos" else "Clear search", true) {
                if (videos.isEmpty()) picker.launch(arrayOf("video/*")) else { query = ""; filter = "All videos"; showLibrary() }
            })
            body.addView(empty)
        } else {
            val screenWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
            val columns = if (screenWidth >= 700) 3 else 2
            val gap = dp(12)
            val cardWidth = ((screenWidth - 40 - (if (columns == 3) 24 else 12)) / columns).coerceAtLeast(dp(130))
            allItems.chunked(columns).forEach { group ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                group.forEach { video ->
                    row.addView(poster(video, cardWidth), LinearLayout.LayoutParams(cardWidth, -2).apply { marginEnd = gap })
                }
                if (group.size < columns) repeat(columns - group.size) {
                    row.addView(Space(this), LinearLayout.LayoutParams(cardWidth, 1))
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(17) })
            }
        }
    }

    private fun filteredVideos(): List<VideoFile> {
        var result = videos.toList()
        if (query.isNotBlank()) result = result.filter { it.title.contains(query, true) }
        return when (filter) {
            "Long videos" -> result.filter { it.duration >= 20 * 60 * 1000L }
            "Short clips" -> result.filter { it.duration < 5 * 60 * 1000L }
            else -> result
        }
    }
    private fun searchDialog() {
        val field = EditText(this).apply { hint = "Search your videos"; setHintTextColor(muted); setTextColor(Color.WHITE); setSingleLine(true); setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(panel, 14); setText(query) }
        android.app.AlertDialog.Builder(this).setTitle("Search library").setView(field)
            .setPositiveButton("Search") { _, _ -> query = field.text.toString(); showLibrary() }
            .setNegativeButton("Clear") { _, _ -> query = ""; showLibrary() }.show()
    }

    private fun addRail(parent: LinearLayout, heading: String, sub: String, items: List<VideoFile>) {
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(txt(heading, 20f, Color.WHITE, true))
        title.addView(txt(sub, 11f, muted).apply { setPadding(0, dp(4), 0, dp(12)) })
        parent.addView(title)
        if (items.isEmpty()) {
            parent.addView(txt("Nothing to show here yet.", 13f, muted).apply { setPadding(0, 0, 0, dp(20)) })
            return
        }
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { row.addView(poster(it)) }
        horizontal.addView(row)
        parent.addView(horizontal, LinearLayout.LayoutParams(-1, dp(258)).apply { bottomMargin = dp(24) })
    }

    private fun poster(video: VideoFile, cardWidth: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { playVideo(video) }
        }
        val artHeight = (cardWidth * .62f).toInt()
        val art = FrameLayout(this).apply {
            background = shape(Color.rgb(22, 39, 60), 16, Color.rgb(48, 72, 102))
            clipToOutline = true
        }
        thumbnail(video, 640, 400)?.let { bmp ->
            art.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        }
        art.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(155, 2, 8, 17)))
        }, FrameLayout.LayoutParams(-1, -1))
        art.addView(txt("▶", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(Color.argb(195, 7, 22, 40), 28, Color.argb(210, 107, 174, 255))
        }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        art.addView(txt(durationText(video.duration), 10f, Color.WHITE, true).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5)); background = shape(Color.argb(205, 3, 9, 17), 8)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(8), dp(8))
        })
        card.addView(art, LinearLayout.LayoutParams(cardWidth, artHeight))
        card.addView(txt(video.title, 12f, Color.WHITE, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(9), dp(2), dp(4))
        }, LinearLayout.LayoutParams(cardWidth, dp(43)))
        card.addView(txt(fileSize(video.size), 10f, muted).apply { setPadding(dp(2), 0, 0, 0) })
        return card
    }

    private fun thumbnail(video: VideoFile, w: Int, h: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(video.uri, Size(w, h), null)
        else MediaStore.Video.Thumbnails.getThumbnail(contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } catch (_: Exception) { null }

    private fun durationText(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0); val hours = sec / 3600; val mins = (sec % 3600) / 60; val seconds = sec % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, mins, seconds) else String.format(Locale.US, "%02d:%02d", mins, seconds)
    }
    private fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "Local video"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
    }

    private fun playVideo(video: VideoFile) {
        queue.clear(); queue.addAll(videos)
        playAt(queue.indexOfFirst { it.uri == video.uri }.coerceAtLeast(0))
    }
    private fun playAt(index: Int) {
        if (queue.isEmpty()) return
        if (exo == null) exo = ExoPlayer.Builder(this).build()
        showPlayer()
        playerView.player = exo
        exo?.setMediaItems(queue.map { MediaItem.fromUri(it.uri) }, index.coerceIn(0, queue.lastIndex), 0L)
        exo?.prepare(); exo?.play(); updatePlayer()
        root.removeCallbacks(progressTask); root.post(progressTask)
    }
    private fun showPlayer() {
        root.removeAllViews()
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(Color.BLACK); setKeepContentOnPlayerReset(true) }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)); background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(220, 3, 9, 18), Color.TRANSPARENT)) }
        top.addView(action("‹") { exo?.pause(); showLibrary() })
        titleView = txt("Now playing", 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(12), 0, 0, 0) }
        top.addView(titleView, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(action("CC") { chooseTracks() })
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(14), dp(17), dp(17)); background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(240, 3, 9, 18), Color.TRANSPARENT)) }
        seek = SeekBar(this).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(blue); thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {}
                override fun onStopTrackingTouch(s: SeekBar) { val p = exo; if (p != null && p.duration > 0) p.seekTo(p.duration * s.progress / 1000L); dragging = false }
            })
        }
        bottom.addView(seek, LinearLayout.LayoutParams(-1, dp(32)))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeView = txt("00:00 / 00:00", 11f, muted)
        row.addView(timeView, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(action("↶ 10") { seekBy(-10000) }); row.addView(Space(this), LinearLayout.LayoutParams(dp(6), 1))
        playView = action("▶", true) { toggle() }; row.addView(playView)
        row.addView(Space(this), LinearLayout.LayoutParams(dp(6), 1)); row.addView(action("10 ↷") { seekBy(10000) })
        row.addView(Space(this), LinearLayout.LayoutParams(dp(6), 1)); row.addView(action("Next") { next() })
        bottom.addView(row)
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, 0) }
        tools.addView(action("Queue") { showQueue() }); tools.addView(Space(this), LinearLayout.LayoutParams(dp(7), 1))
        tools.addView(action("Speed") { cycleSpeed() }); tools.addView(Space(this), LinearLayout.LayoutParams(dp(7), 1))
        tools.addView(action("Fit") { cycleResize() }); tools.addView(Space(this), LinearLayout.LayoutParams(dp(7), 1))
        tools.addView(action("PiP") { enterPip() })
        bottom.addView(tools)
        frame.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(frame)
        exo?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (::playView.isInitialized) playView.text = if (isPlaying) "Ⅱ" else "▶" }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updatePlayer() }
        })
    }
    private fun updatePlayer() {
        val item = exo?.currentMediaItem
        titleView.text = item?.mediaMetadata?.title ?: item?.localConfiguration?.uri?.lastPathSegment ?: "Now playing"
        playView.text = if (exo?.isPlaying == true) "Ⅱ" else "▶"
    }
    private val progressTask = object : Runnable {
        override fun run() {
            val p = exo ?: return
            if (!::seek.isInitialized || root.childCount == 0) return
            val d = p.duration.coerceAtLeast(0)
            if (!dragging && d > 0) seek.progress = (p.currentPosition * 1000L / d).toInt()
            timeView.text = durationText(p.currentPosition) + " / " + durationText(d)
            root.postDelayed(this, 500)
        }
    }
    private fun toggle() { val p = exo ?: return; if (p.isPlaying) p.pause() else p.play(); playView.text = if (p.isPlaying) "Ⅱ" else "▶" }
    private fun seekBy(delta: Long) { val p = exo ?: return; p.seekTo((p.currentPosition + delta).coerceIn(0L, p.duration.coerceAtLeast(0))) }
    private fun next() { val p = exo ?: return; if (p.hasNextMediaItem()) p.seekToNextMediaItem() else if (queue.isNotEmpty()) p.seekTo(0, 0L) }
    private fun showQueue() {
        android.app.AlertDialog.Builder(this).setTitle("Up next").setItems(queue.map { it.title }.toTypedArray()) { _, i -> exo?.seekTo(i, 0L); exo?.play() }.setNegativeButton("Close", null).show()
    }
    private fun cycleSpeed() {
        val p = exo ?: return
        val speed = when { p.playbackParameters.speed < 1.2f -> 1.25f; p.playbackParameters.speed < 1.4f -> 1.5f; p.playbackParameters.speed < 1.8f -> 2f; else -> 1f }
        p.setPlaybackSpeed(speed); Toast.makeText(this, "$speed×", Toast.LENGTH_SHORT).show()
    }
    private fun cycleResize() {
        playerView.resizeMode = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }
    private fun chooseTracks() {
        val p = exo ?: return; val groups = p.currentTracks.groups
        val names = mutableListOf<String>(); val refs = mutableListOf<Pair<Int, Int>>()
        groups.forEachIndexed { gi, group ->
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO || group.type == androidx.media3.common.C.TRACK_TYPE_TEXT)
                for (ti in 0 until group.length) { names.add((if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle") + " · " + (group.getTrackFormat(ti).language ?: "Unknown")); refs.add(gi to ti) }
        }
        android.app.AlertDialog.Builder(this).setTitle("Audio and subtitles").setItems(arrayOf("Auto / Default") + names.toTypedArray()) { _, i ->
            if (i == 0) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
            else { val pair = refs[i - 1]; val g = groups[pair.first]; val override = androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, listOf(pair.second)); p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().addOverride(override).build() }
        }.show()
    }
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) {}
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (exo?.isPlaying == true && ::playerView.isInitialized) enterPip() }
    override fun onPictureInPictureModeChanged(active: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(active, config)
        if (active && root.childCount > 0) (root.getChildAt(0) as? FrameLayout)?.let { frame ->
            for (i in 0 until frame.childCount) if (frame.getChildAt(i) !is PlayerView) frame.getChildAt(i).visibility = View.GONE
        }
    }
    override fun onDestroy() { root.removeCallbacks(progressTask); exo?.release(); exo = null; super.onDestroy() }
}
