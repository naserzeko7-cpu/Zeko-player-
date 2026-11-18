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
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
    private var playerVisible = false
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (::playView.isInitialized) playView.text = if (isPlaying) "Ⅱ" else "▶"
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (::titleView.isInitialized && ::playView.isInitialized) updatePlayer()
        }
    }

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
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(root)
        showLoadingScreen("Preparing your media library…")
        try {
            if (hasVideoPermission()) loadVideos() else askPermission()
            intent?.data?.let { uri ->
                queue.clear()
                queue.add(VideoFile(0, uri, uri.lastPathSegment ?: "Video", 0, 0))
                playAt(0)
            }
        } catch (error: Throwable) {
            showErrorScreen("Startup failed", error)
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

    private fun showLoadingScreen(message: String) {
        root.removeAllViews()
        root.setBackgroundColor(bg)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        content.addView(txt("ZEKO PLAYER", 25f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .08f
        })
        content.addView(txt(message, 13f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showErrorScreen(stage: String, error: Throwable) {
        try {
            root.removeAllViews()
            root.setBackgroundColor(bg)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(28), dp(24), dp(28))
            }
            content.addView(txt("ZEKO PLAYER", 24f, Color.WHITE, true))
            content.addView(txt(stage, 18f, Color.rgb(255, 170, 105), true).apply {
                setPadding(0, dp(18), 0, dp(8))
            })
            content.addView(txt(error.javaClass.simpleName + ": " + (error.message ?: "No error message"), 13f, Color.WHITE).apply {
                setPadding(0, 0, 0, dp(14))
            })
            content.addView(action("Try again", true) {
                if (hasVideoPermission()) loadVideos() else askPermission()
            })
            val details = TextView(this).apply {
                text = error.stackTraceToString()
                textSize = 10f
                setTextColor(muted)
                setPadding(0, dp(16), 0, dp(16))
            }
            val detailScroll = ScrollView(this).apply { addView(details) }
            content.addView(detailScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(content, FrameLayout.LayoutParams(-1, -1))
        } catch (_: Throwable) {
            root.removeAllViews()
            root.setBackgroundColor(Color.BLACK)
        }
    }

    private fun loadVideos() {
        showLoadingScreen("Scanning videos on this device…")
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
        } catch (error: Throwable) {
            showErrorScreen("Could not read the video library", error)
            return
        }
        try {
            showLibrary()
        } catch (error: Throwable) {
            showErrorScreen("Could not display the video library", error)
        }
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
        exo?.removeListener(playerListener)
        playerVisible = false
        root.removeAllViews()
        root.setBackgroundColor(bg)

        val metrics = resources.displayMetrics
        val screenWidth = (metrics.widthPixels / metrics.density).toInt()
        val wide = screenWidth >= 700
        val horizontalPad = if (wide) 30 else 20

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPad), dp(20), dp(horizontalPad), dp(40))
        }
        scroll.addView(body)
        root.addView(scroll)

        // Cinematic brand bar
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brandMark = TextView(this).apply {
            text = "Z"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = shape(blue, 14)
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(txt("ZEKO PLAYER", 17f, Color.WHITE, true).apply { letterSpacing = .09f })
        brand.addView(txt("YOUR PERSONAL CINEMA", 8f, muted, true).apply {
            letterSpacing = .15f
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⌕", false) { searchDialog() })
        header.addView(Space(this), LinearLayout.LayoutParams(dp(9), 1))
        header.addView(action("＋  Add", true) { picker.launch(arrayOf("video/*")) })
        body.addView(header)
        body.addView(View(this).apply { setBackgroundColor(Color.rgb(34, 51, 73)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(19); bottomMargin = dp(19) })

        val allItems = filteredVideos()
        val featured = allItems.firstOrNull()
        val hero = FrameLayout(this).apply {
            background = shape(Color.rgb(15, 31, 51), 25, Color.rgb(44, 83, 130))
            clipToOutline = true
        }
        if (featured != null) {
            val heroImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
            loadThumbnailInto(heroImage, featured, 1100, 700)
        }
        hero.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(245, 5, 12, 23), Color.argb(185, 5, 12, 23), Color.argb(20, 5, 12, 23))
            )
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(120, 4, 10, 18), Color.TRANSPARENT)
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (wide) 34 else 23), dp(22), dp(22), dp(22))
        }
        heroContent.addView(txt("✦  FEATURED IN YOUR LIBRARY", 9f, Color.rgb(119, 190, 255), true).apply {
            letterSpacing = .13f
        })
        heroContent.addView(txt(featured?.title ?: "Every great story\nstarts with play.", if (wide) 29f else 23f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, dp(8))
        })
        heroContent.addView(txt(
            if (featured == null) "Your videos, beautifully organised in one place."
            else "${durationText(featured.duration)}   •   ${fileSize(featured.size)}",
            11f, Color.rgb(211, 225, 243)
        ))
        heroContent.addView(action(if (featured == null) "＋  Import videos" else "▶  Play featured", true) {
            if (featured == null) picker.launch(arrayOf("video/*")) else playVideo(featured)
        }.apply { setPadding(dp(19), dp(12), dp(19), dp(12)) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1))
        body.addView(hero, LinearLayout.LayoutParams(-1, dp(if (wide) 270 else 238)).apply {
            bottomMargin = dp(25)
        })

        // Quick discovery categories
        body.addView(txt("BROWSE YOUR COLLECTION", 10f, muted, true).apply {
            letterSpacing = .15f
            setPadding(0, 0, 0, dp(12))
        })
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "All videos" to "▦",
            "Recently added" to "◷",
            "Long videos" to "▤",
            "Short clips" to "▷"
        ).forEach { (name, glyph) ->
            val selected = filter == name
            val chip = action("$glyph   $name", selected) {
                filter = name
                showLibrary()
            }.apply {
                textSize = 12f
                setPadding(dp(15), dp(11), dp(15), dp(11))
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, dp(43)).apply {
                marginEnd = dp(8)
            })
        }
        chipScroll.addView(chips)
        body.addView(chipScroll, LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(25)
        })

        val section = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sectionText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sectionText.addView(txt(if (filter == "All videos") "Your library" else filter, 22f, Color.WHITE, true))
        sectionText.addView(txt(
            "${allItems.size} ${if (allItems.size == 1) "video" else "videos"}  ·  ON THIS DEVICE",
            10f, muted
        ).apply { letterSpacing = .04f; setPadding(0, dp(5), 0, 0) })
        section.addView(sectionText, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(action("↻", false) { loadVideos() })
        body.addView(section, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(17)
        })

        if (allItems.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(38), dp(22), dp(38))
                background = shape(panel, 22, Color.rgb(37, 59, 84))
            }
            empty.addView(txt(if (videos.isEmpty()) "Your cinema is ready." else "No videos found", 18f, Color.WHITE, true)
                .apply { gravity = Gravity.CENTER })
            empty.addView(txt(
                if (videos.isEmpty()) "Import videos from your device and they’ll appear here."
                else "Try another category or clear your search.",
                12f, muted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(18))
            })
            empty.addView(action(if (videos.isEmpty()) "＋  Import videos" else "Clear filters", true) {
                if (videos.isEmpty()) picker.launch(arrayOf("video/*"))
                else { query = ""; filter = "All videos"; showLibrary() }
            })
            body.addView(empty)
        } else {
            val columns = when {
                screenWidth >= 1000 -> 4
                screenWidth >= 650 -> 3
                else -> 2
            }
            val gap = dp(13)
            val availablePx = metrics.widthPixels - dp(horizontalPad * 2)
            val cardWidth = ((availablePx - gap * (columns - 1)) / columns).coerceAtLeast(dp(130))
            allItems.chunked(columns).forEach { group ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                group.forEachIndexed { index, video ->
                    row.addView(poster(video, cardWidth), LinearLayout.LayoutParams(cardWidth, -2).apply {
                        if (index < group.lastIndex) marginEnd = gap
                    })
                }
                repeat(columns - group.size) {
                    row.addView(Space(this), LinearLayout.LayoutParams(cardWidth, 1))
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(20)
                })
            }
        }
    }

    private fun filteredVideos(): List<VideoFile> {
        var result = videos.toList()
        if (query.isNotBlank()) result = result.filter { it.title.contains(query, true) }
        return when (filter) {
            "Recently added" -> result.take(20)
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

    private fun poster(video: VideoFile, cardWidth: Int = dp(190)): View {
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
        val posterImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.addView(posterImage, FrameLayout.LayoutParams(-1, -1))
        loadThumbnailInto(posterImage, video, 640, 400)
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

    private fun loadThumbnailInto(target: ImageView, video: VideoFile, w: Int, h: Int) {
        val key = video.uri.toString()
        target.tag = key
        thumbnailCache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)
        thumbnailExecutor.execute {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(video.uri, Size(w, h), null)
                else MediaStore.Video.Thumbnails.getThumbnail(contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } catch (_: Exception) { null }
            if (bitmap != null) thumbnailCache.put(key, bitmap)
            mainHandler.post {
                if (target.tag == key && bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

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
        exo?.setMediaItems(queue.map { video ->
            MediaItem.Builder()
                .setUri(video.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).build())
                .build()
        }, index.coerceIn(0, queue.lastIndex), 0L)
        exo?.prepare(); exo?.play(); updatePlayer()
        root.removeCallbacks(progressTask); root.post(progressTask)
    }
    private fun showPlayer() {
        playerVisible = true
        root.removeCallbacks(progressTask)
        root.removeAllViews()
        val metrics = resources.displayMetrics
        val screenWidth = (metrics.widthPixels / metrics.density).toInt()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wide = screenWidth >= 700 || landscape

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // Top glass-style navigation and media title
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (wide) 22 else 14), dp(10), dp(if (wide) 22 else 14), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(225, 3, 9, 18), Color.argb(95, 3, 9, 18), Color.TRANSPARENT)
            )
        }
        top.addView(action("‹", false) { exo?.pause(); showLibrary() }.apply {
            textSize = 25f
            setPadding(dp(13), dp(7), dp(13), dp(7))
        })
        titleView = txt("Now playing", 14f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(13), 0, dp(8), 0)
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(action("CC", false) { chooseTracks() }.apply {
            textSize = 11f
            setPadding(dp(13), dp(10), dp(13), dp(10))
        })
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Hybrid playback controls: prominent center play with precise skip controls
        val center = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(Color.argb(105, 3, 10, 20), 34, Color.argb(70, 190, 215, 245))
        }
        center.addView(action("Previous", false) { previous() }.apply {
            textSize = 11f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        center.addView(Space(this), LinearLayout.LayoutParams(dp(7), 1))
        center.addView(action("↶ 10", false) { seekBy(-10000) }.apply {
            textSize = 12f
            setPadding(dp(12), dp(13), dp(12), dp(13))
        })
        center.addView(Space(this), LinearLayout.LayoutParams(dp(9), 1))
        playView = action("▶", true) { toggle() }.apply {
            textSize = 22f
            setPadding(dp(22), dp(17), dp(22), dp(17))
            background = shape(blue, 32)
        }
        center.addView(playView)
        center.addView(Space(this), LinearLayout.LayoutParams(dp(9), 1))
        center.addView(action("10 ↷", false) { seekBy(10000) }.apply {
            textSize = 12f
            setPadding(dp(12), dp(13), dp(12), dp(13))
        })
        center.addView(Space(this), LinearLayout.LayoutParams(dp(7), 1))
        center.addView(action("Next", false) { next() }.apply {
            textSize = 11f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        frame.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Bottom timeline and secondary tools
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (wide) 24 else 16), dp(13), dp(if (wide) 24 else 16), dp(if (landscape) 9 else 15))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(238, 3, 9, 18), Color.argb(150, 3, 9, 18), Color.TRANSPARENT)
            )
        }
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(100, 210, 225, 245))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    val p = exo
                    if (p != null && p.duration > 0) p.seekTo(p.duration * s.progress / 1000L)
                    dragging = false
                }
            })
        }
        bottom.addView(seek, LinearLayout.LayoutParams(-1, dp(30)))
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeView = txt("00:00 / 00:00", 11f, Color.rgb(207, 220, 237), true)
        info.addView(timeView, LinearLayout.LayoutParams(0, dp(28), 1f))
        info.addView(txt("ZEKO PLAYER", 9f, muted, true).apply { letterSpacing = .12f })
        bottom.addView(info)

        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            action("Queue") { showQueue() },
            action("Speed") { cycleSpeed() },
            action("Fit") { cycleResize() },
            action("PiP") { enterPip() }
        ).forEach { control ->
            tools.addView(control, LinearLayout.LayoutParams(-2, dp(37)).apply {
                marginEnd = dp(8)
            })
        }
        toolScroll.addView(tools)
        bottom.addView(toolScroll, LinearLayout.LayoutParams(-1, dp(40)).apply {
            topMargin = dp(5)
        })
        frame.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(frame)

        exo?.removeListener(playerListener)
        exo?.addListener(playerListener)
        updatePlayer()
        root.post(progressTask)
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
    private fun previous() {
        val p = exo ?: return
        if (p.currentPosition > 3000L) p.seekTo(0L)
        else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
        else if (queue.isNotEmpty()) p.seekTo(queue.lastIndex, 0L)
    }
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
        if (active && root.childCount > 0) {
            (root.getChildAt(0) as? FrameLayout)?.let { frame ->
                for (i in 0 until frame.childCount) if (frame.getChildAt(i) !is PlayerView) frame.getChildAt(i).visibility = View.GONE
            }
        } else if (playerVisible && exo != null && ::root.isInitialized) {
            showPlayer()
            playerView.player = exo
            updatePlayer()
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        if (playerVisible) {
            showPlayer()
            playerView.player = exo
            updatePlayer()
            root.post(progressTask)
        } else if (videos.isNotEmpty()) {
            showLibrary()
        }
    }

    override fun onDestroy() {
        root.removeCallbacks(progressTask)
        exo?.removeListener(playerListener)
        exo?.release()
        exo = null
        thumbnailExecutor.shutdownNow()
        thumbnailCache.evictAll()
        super.onDestroy()
    }
}
