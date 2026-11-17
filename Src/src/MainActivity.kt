package com.zeko.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.content.ContentUris
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoItem(
    val id: Long, val name: String, val durMs: Long,
    val uri: Uri, val added: Long, val folder: String
)

class FolderItem(val name: String, val count: Int, val cover: VideoItem)

class CardHolder(
    val frame: FrameLayout, val img: ImageView, val dur: TextView,
    val title: TextView, val sub: TextView, val progress: ProgressBar
) {
    var id: Long = -1L
}

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var currentSubtitleLine: String = ""
    private var currentSubtitleStartPosition: Long = 0L
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false

    @Volatile private var liveTranslationEnabled = false
    @Volatile private var liveTranslationRequest = 0L
    private var liveAudioSink: Pcm16kTeeSink? = null
    private var speechRecognizer: WhisperChunkedSpeechRecognizer? = null
    private var madladTranslator: MadladArabicTranslator? = null
    private var speechModelReady = false
    private var translationModelReady = false
    private var liveTranslationOverlay: TextView? = null
    private var lastLiveSpeechText = ""
    private var lastLiveTranslationAt = 0L
    private val speechExec = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var grid: GridView
    private lateinit var foldersGrid: GridView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: Button
    private lateinit var resetFiltersBtn: Button
    private lateinit var sectionLabel: TextView
    private lateinit var continueSection: LinearLayout
    private lateinit var continueCard: TextView
    private lateinit var searchBar: EditText
    private lateinit var filterChips: List<TextView>
    private lateinit var sortChip: TextView
    private var filterMode = 0
    private lateinit var layoutBtn: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabFolders: TextView
    private lateinit var settingsPage: LinearLayout
    private lateinit var settingsList: LinearLayout

    private lateinit var playerView: PlayerView
    private lateinit var seekLabel: TextView
    private lateinit var hud: TextView
    private lateinit var watermark: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var speedBtn: TextView
    private lateinit var ratioBtn: TextView
    private lateinit var repeatBtn: TextView
    private lateinit var shuffleBtn: TextView
    private lateinit var sleepBtn: TextView
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerDeadlineMs = 0L
    private lateinit var controlsLayer: FrameLayout
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var playBtn: TextView
    private lateinit var lockOverlay: TextView
    private var controlsVisible = false
    private var locked = false
    private var scrubbing = false
    private var inPip = false
    private var orient = 0
    private val ui = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private var lastPositionSaveAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            val now = android.os.SystemClock.elapsedRealtime()
            if (player?.isPlaying == true && now - lastPositionSaveAt >= 5000L) {
                savePosition()
                lastPositionSaveAt = now
            }
            ui.postDelayed(this, 500)
        }
    }

    // Video Seek Preview
    private lateinit var previewBox: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewTex: TextureView
    private lateinit var previewImg: ImageView
    private lateinit var previewTime: TextView
    private var previewPlayer: ExoPlayer? = null
    private var lastPreviewSeek = 0L
    private var pendingPos = 0L
    private val seekRunnable = Runnable { previewPlayer?.seekTo(pendingPos) }

    private val accent = Color.parseColor("#FF6D00")
    private val bg = Color.parseColor("#0E0E12")
    private val navBg = Color.parseColor("#14141A")
    private val cardBg = Color.parseColor("#1A1A22")
    private val grayText = Color.parseColor("#9A9AA8")
    private val REQ_PICK = 1
    private val REQ_PERM = 2
    private val REQ_SUB = 3
    private val REQ_NOTIF = 4

    // library state
    private var videos: List<VideoItem> = emptyList()
    private var shown: List<VideoItem> = emptyList()
    private var folders: List<FolderItem> = emptyList()
    private var folderFilter: String? = null
    private var query: String = ""
    private var tab = 0

    private val adapter = VideoAdapter()
    private val folderAdapter = FolderAdapter()
    private val thumbCache = LruCache<Long, Bitmap>(250)
    private val ioExec = Executors.newFixedThreadPool(3)
    private val stripPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r).apply { priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var strip: AtomicReferenceArray<Bitmap>? = null
    @Volatile private var stripDurMs = 0L
    @Volatile private var stripGen = 0
    private var lastShown: Bitmap? = null

    private var lastSeekTime = 0L
    private var lastSide = 0
    private var accumulated = 0

    private var brightness = -1f
    private var volLevel = 0f
    private var ratioIdx = 0
    private var speed = 1f
    private var endDialogShown = false

    // ---------- Settings (saved on the device) ----------
    private val prefs by lazy { getSharedPreferences("zeko", MODE_PRIVATE) }

    private var seekSec: Int
        get() = prefs.getInt("seekSec", 10)
        set(v) { prefs.edit().putInt("seekSec", v).apply() }
    private var rememberPos: Boolean
        get() = prefs.getBoolean("rememberPos", true)
        set(v) { prefs.edit().putBoolean("rememberPos", v).apply() }
    private var gesturesOn: Boolean
        get() = prefs.getBoolean("gesturesOn", true)
        set(v) { prefs.edit().putBoolean("gesturesOn", v).apply() }
    private var keepOn: Boolean
        get() = prefs.getBoolean("keepOn", true)
        set(v) { prefs.edit().putBoolean("keepOn", v).apply() }
    private var watermarkOn: Boolean
        get() = prefs.getBoolean("watermarkOn", true)
        set(v) { prefs.edit().putBoolean("watermarkOn", v).apply() }
    private var previewOn: Boolean
        get() = prefs.getBoolean("previewOn", true)
        set(v) { prefs.edit().putBoolean("previewOn", v).apply() }
    private var sortMode: Int
        get() = prefs.getInt("sortMode", 0)
        set(v) { prefs.edit().putInt("sortMode", v).apply() }
    private var listMode: Boolean
        get() = prefs.getBoolean("listMode", false)
        set(v) { prefs.edit().putBoolean("listMode", v).apply() }
    private var bgPlay: Boolean
        get() = prefs.getBoolean("bgPlay", true)
        set(v) { prefs.edit().putBoolean("bgPlay", v).apply() }
    private var pipAuto: Boolean
        get() = prefs.getBoolean("pipAuto", true)
        set(v) { prefs.edit().putBoolean("pipAuto", v).apply() }
    private var subtitleSizeSp: Int
        get() = prefs.getInt("subtitleSizeSp", 20)
        set(v) { prefs.edit().putInt("subtitleSizeSp", v).apply() }
    private var savedPlaybackSpeed: Float
        get() = prefs.getFloat("playbackSpeed", 1f)
        set(v) { prefs.edit().putFloat("playbackSpeed", v).apply() }
    private var repeatMode: Int
        get() = prefs.getInt("repeatMode", Player.REPEAT_MODE_OFF)
        set(v) { prefs.edit().putInt("repeatMode", v).apply() }
    private var shuffleEnabled: Boolean
        get() = prefs.getBoolean("shuffleEnabled", false)
        set(v) { prefs.edit().putBoolean("shuffleEnabled", v).apply() }
    private val shufflePlayedUris = mutableSetOf<String>()
    private val shuffleHistory = mutableListOf<String>()
    private var shuffleHistoryIndex = -1

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = navBg

        root = FrameLayout(this)
        root.setBackgroundColor(bg)

        buildHome()
        buildSettingsPage()
        buildPlayer()
        setContentView(root)

        selectTab(0)
        refreshLibrary()
        intent?.data?.let { play(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.numColumns = columns()
        foldersGrid.numColumns = gridCols()
        adapter.notifyDataSetChanged()
        folderAdapter.notifyDataSetChanged()
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

    private fun sorted(list: List<VideoItem>): List<VideoItem> = when (sortMode.coerceIn(0, 4)) {
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.durMs }
        3 -> list.sortedByDescending { prefs.getLong("watched:" + it.uri, 0L) }
        4 -> list.sortedWith(
            compareByDescending<VideoItem> { prefs.getInt("plays:" + it.uri, 0) }
                .thenByDescending { prefs.getLong("watched:" + it.uri, 0L) }
        )
        else -> list.sortedByDescending { it.added }
    }

    private fun loadVideos() {
        ioExec.execute {
            val list = ArrayList<VideoItem>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, null
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iAdd = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val iFol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        list.add(
                            VideoItem(
                                id,
                                c.getString(iName) ?: "Video",
                                c.getLong(iDur),
                                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                c.getLong(iAdd),
                                c.getString(iFol) ?: "Other"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
            }
            runOnUiThread {
                videos = sorted(list)
                restorePlaybackQueue(videos)
                applyFilter()
                updateContinueWatching()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val ff = folderFilter
        shown = videos.filter { item ->
            val saved = prefs.getLong("p:" + item.uri, 0L)
            val plays = prefs.getInt("plays:" + item.uri, 0)
            val matchesState = when (filterMode) {
                1 -> plays == 0
                2 -> saved > 0L
                3 -> plays > 0 && saved == 0L
                else -> true
            }
            (ff == null || item.folder == ff) &&
                (q.isEmpty() || item.name.lowercase().contains(q)) &&
                matchesState
        }
        updateFilterChips()
        folders = videos.groupBy { it.folder }
            .map { FolderItem(it.key, it.value.size, it.value[0]) }
            .sortedBy { it.name.lowercase() }
        adapter.notifyDataSetChanged()
        folderAdapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        val granted = hasPerm()
        val noData = !granted || videos.isEmpty()
        val noResult = !noData && tab == 0 && shown.isEmpty()
        val showEmpty = noData || noResult
        emptyBox.visibility = if (showEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (!showEmpty && tab == 0) View.VISIBLE else View.GONE
        foldersGrid.visibility = if (!showEmpty && tab == 1) View.VISIBLE else View.GONE
        allowBtn.visibility = if (granted) View.GONE else View.VISIBLE
        resetFiltersBtn.visibility = if (noResult) View.VISIBLE else View.GONE
        emptyText.text = when {
            !granted -> "Allow access to show the videos on your device"
            noData -> "No videos found on this device"
            else -> "No videos match your current filters"
        }
        val ff = folderFilter
        sectionLabel.text = when {
            tab == 1 -> "Folders  •  ${folders.size}"
            ff != null -> "$ff  •  ${shown.size}     ✕"
            else -> "All videos  •  ${shown.size}"
        }
        layoutBtn.text = if (listMode) "▦" else "☰"
    }

    private fun gridCols(): Int =
        (resources.configuration.screenWidthDp / 200).coerceIn(2, 6)

    private fun columns(): Int =
        if (listMode) (resources.configuration.screenWidthDp / 420).coerceIn(1, 3)
        else gridCols()

    private fun thumbHeight(cols: Int): Int {
        val w = resources.displayMetrics.widthPixels - dp(40) - (cols - 1) * dp(14)
        return (w / cols) * 9 / 16
    }

    private fun selectTab(t: Int) {
        tab = t
        if (::filterChips.isInitialized) filterChips.forEach { it.isEnabled = (t == 0) }
        if (::sortChip.isInitialized) sortChip.isEnabled = (t == 0)
        tabVideos.setTextColor(if (t == 0) accent else grayText)
        tabFolders.setTextColor(if (t == 1) accent else grayText)
        updateEmpty()
    }

    // ---------- Home screen ----------
    private fun iconBtn(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun navItem(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(grayText)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }

    private fun buildHome() {
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(0), dp(0), dp(0))
            setBackgroundColor(bg)
        }

        fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }

        fun surface(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            }

        // Branded header
        val brandMark = TextView(this).apply {
            text = "Z"
            textSize = 25f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#FF8A32"), accent, Color.parseColor("#D84315"))
            ).apply { cornerRadius = dp(17).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("ZEKO PLAYER", 20f, Color.WHITE, true))
            addView(label("YOUR PERSONAL MEDIA SPACE", 9f, grayText, true))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(16), dp(14))
            addView(brandMark)
            addView(brandText, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(13)
            })
            addView(iconBtn("⌕") { toggleSearch() }.apply {
                textSize = 28f
                background = surface(cardBg, 15)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    leftMargin = dp(6)
                }
            })
            layoutBtn = iconBtn(if (listMode) "▦" else "☷") {
                listMode = !listMode
                grid.numColumns = columns()
                grid.adapter = adapter
                updateEmpty()
            }.apply {
                textSize = 20f
                background = surface(cardBg, 15)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    leftMargin = dp(8)
                }
            }
            addView(layoutBtn)
        }

        // Feature panel: gives the library a clear visual hierarchy.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(17), dp(18), dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(Color.parseColor("#342016"), Color.parseColor("#20202A"), Color.parseColor("#191923"))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#4A3A31"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(126)).apply {
                setMargins(dp(18), dp(2), dp(18), dp(16))
            }
        }
        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("YOUR LIBRARY", 10f, Color.parseColor("#FFB27C"), true))
            val count = label("  •  ${videos.size} VIDEOS", 10f, grayText, true)
            addView(count)
        }
        hero.addView(heroTop)
        hero.addView(label("Everything you love,\nin one place.", 22f, Color.WHITE, true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
        })
        hero.addView(label("Pick up where you left off.", 11f, Color.parseColor("#B9B7C3")).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        })

        searchBar = EditText(this).apply {
            hint = "Search your videos"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777783"))
            compoundDrawablePadding = dp(8)
            setPadding(dp(17), 0, dp(17), 0)
            background = surface(Color.parseColor("#1B1B25"), 15, Color.parseColor("#30303B"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(dp(18), 0, dp(18), dp(12))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    applyFilter()
                }
            })
        }

        sectionLabel = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener {
                if (tab == 0 && folderFilter != null) {
                    folderFilter = null
                    applyFilter()
                }
            }
        }

        grid = GridView(this).apply {
            numColumns = columns()
            horizontalSpacing = dp(14)
            verticalSpacing = dp(17)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(18), dp(2), dp(18), dp(20))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val queue = shown.toList()
                val v = queue[pos]
                activeQueue = queue.toMutableList()
                persistPlaybackQueue()
                play(v.uri, v.name.substringBeforeLast('.'))
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { showVideoInfo(it) }
                true
            }
        }

        foldersGrid = GridView(this).apply {
            numColumns = gridCols()
            horizontalSpacing = dp(14)
            verticalSpacing = dp(17)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(18), dp(2), dp(18), dp(20))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = this@MainActivity.folderAdapter
            visibility = View.GONE
            setOnItemClickListener { _, _, pos, _ ->
                folderFilter = folders[pos].name
                selectTab(0)
                applyFilter()
            }
        }

        emptyText = TextView(this).apply {
            textSize = 15f
            setTextColor(grayText)
            gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), dp(16))
        }
        allowBtn = pill("Grant video access", true, dp(230), dp(50)) {
            requestPermissions(arrayOf(permName()), REQ_PERM)
        }
        resetFiltersBtn = pill("Clear filters", false, dp(190), dp(46)) {
            filterMode = 0
            folderFilter = null
            query = ""
            searchBar.setText("")
            applyFilter()
        }.apply {
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(emptyText)
            addView(allowBtn)
            addView(resetFiltersBtn)
        }

        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(foldersGrid, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))

        tabVideos = navItem("VIDEOS") { selectTab(0) }.apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(13), 0, dp(13))
        }
        tabFolders = navItem("FOLDERS") { selectTab(1) }.apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(13), 0, dp(13))
        }
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#17171F"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#292933"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(62)).apply {
                setMargins(dp(14), dp(4), dp(14), dp(12))
            }
            addView(tabVideos)
            addView(tabFolders)
            addView(navItem("OPEN") { openMenu() }.apply { textSize = 11f })
            addView(navItem("WORDS") {
                startActivity(Intent(this@MainActivity, VocabularyActivity::class.java))
            }.apply { textSize = 11f })
            addView(navItem("SETTINGS") { openSettings() }.apply { textSize = 11f })
        }

        home.addView(header)
        home.addView(hero)
        home.addView(searchBar)
        home.addView(buildFilterChips())
        continueCard = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(Color.parseColor("#20202A"))
                setStroke(dp(1), Color.parseColor("#3B3B48"))
            }
            setOnClickListener {
                val last = prefs.getString("lastPlayedUri", null)
                val item = videos.firstOrNull { it.uri.toString() == last }
                if (item != null) play(item.uri, item.name.substringBeforeLast('.'))
            }
        }
        continueSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(10))
            addView(label("CONTINUE WATCHING", 10f, Color.parseColor("#FFB27C"), true))
            addView(continueCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            visibility = View.GONE
        }
        home.addView(continueSection)
        home.addView(sectionLabel)
        home.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        home.addView(bottomNav)
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateFilterChips() {
        if (!::filterChips.isInitialized) return
        val allCount = videos.size
        val unwatchedCount = videos.count { prefs.getInt("plays:" + it.uri, 0) == 0 }
        val inProgressCount = videos.count { prefs.getLong("p:" + it.uri, 0L) > 0L }
        val playedCount = videos.count {
            prefs.getInt("plays:" + it.uri, 0) > 0 &&
                prefs.getLong("p:" + it.uri, 0L) == 0L
        }
        val labels = arrayOf(
            "All · $allCount",
            "Unwatched · $unwatchedCount",
            "In progress · $inProgressCount",
            "Played · $playedCount"
        )
        filterChips.forEachIndexed { index, chip ->
            val selected = filterMode == index
            chip.setTextColor(if (selected) Color.WHITE else grayText)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (selected) Color.parseColor("#493020") else Color.parseColor("#1D1D27"))
                setStroke(dp(1), if (selected) accent else Color.parseColor("#30303B"))
            }
            chip.text = labels[index]
        }
        if (::sortChip.isInitialized) {
            val sortNames = arrayOf("Newest", "Name A–Z", "Longest", "Recent", "Most played")
            sortChip.text = "Sort: ${sortNames[sortMode.coerceIn(0, sortNames.lastIndex)]}  ▾"
        }
    }

    private fun showLibrarySortDialog() {
        val sortNames = arrayOf("Newest first", "Name (A-Z)", "Longest first", "Recently watched", "Most played")
        AlertDialog.Builder(this)
            .setTitle("Sort videos by")
            .setSingleChoiceItems(sortNames, sortMode.coerceIn(0, sortNames.lastIndex)) { dialog, which ->
                sortMode = which
                videos = sorted(videos)
                applyFilter()
                dialog.dismiss()
            }
            .show()
    }

    private fun buildFilterChips(): View {
        val labels = arrayOf("All", "Unwatched", "In progress", "Played")
        filterChips = labels.mapIndexed { index, name ->
            TextView(this).apply {
                text = name
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(9), dp(15), dp(9))
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener {
                    filterMode = index
                    applyFilter()
                }
            }
        }
        updateFilterChips()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), dp(10))
            filterChips.forEach { addView(it) }
            sortChip = TextView(this@MainActivity).apply {
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener { showLibrarySortDialog() }
            }
            addView(sortChip)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }
    }

    private fun toggleSearch() {
        if (searchBar.visibility == View.VISIBLE) {
            searchBar.setText("")
            searchBar.visibility = View.GONE
        } else {
            searchBar.visibility = View.VISIBLE
            searchBar.requestFocus()
        }
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

    private fun openMenu() {
        AlertDialog.Builder(this)
            .setTitle("Open")
            .setItems(arrayOf("Browse files on device", "Play from link")) { _, w ->
                if (w == 0) pickFile() else askLink()
            }
            .show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        startActivityForResult(i, REQ_PICK)
    }

    private fun pickSubtitleFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_SUB)
    }

    private fun replayCurrentSubtitle() {
        val p = player ?: return
        if (currentSubtitleLine.isBlank()) {
            toast("Wait for a subtitle line first")
            return
        }
        val target = (currentSubtitleStartPosition - 250L).coerceAtLeast(0L)
        p.seekTo(target)
        speed = 0.75f
        p.setPlaybackSpeed(speed)
        speedBtn.text = "0.75x"
        p.play()
        showHud("Replay  •  0.75x")
        scheduleHide()
    }

    private fun speakCurrentSubtitle() {
        val line = currentSubtitleLine.trim()
        if (line.isBlank()) {
            toast("No subtitle text to speak yet")
            return
        }
        val engine = speechEngine
        if (engine != null && speechReady) {
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "zeko-subtitle-speech")
            return
        }
        if (engine != null) {
            toast("Voice is still getting ready")
            return
        }
        speechEngine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = speechEngine
                if (tts != null) {
                    val result = tts.setLanguage(Locale.US)
                    speechReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    if (speechReady) {
                        tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "zeko-subtitle-speech")
                    } else {
                        toast("English voice data is not installed on this device")
                    }
                }
            } else {
                toast("Text-to-speech is unavailable")
            }
        }
    }

    private fun saveSubtitleWord() {
        val sentence = currentSubtitleLine.trim()
        if (sentence.isBlank()) {
            toast("No subtitle text to save yet")
            return
        }

        val wordInput = EditText(this).apply {
            hint = "Word or phrase"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            setText(sentence)
        }
        val meaningInput = EditText(this).apply {
            hint = "Meaning / translation"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val example = TextView(this).apply {
            text = "Subtitle context: $sentence"
            textSize = 13f
            setTextColor(grayText)
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(wordInput)
            addView(meaningInput, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
            addView(example)
        }

        AlertDialog.Builder(this)
            .setTitle("Save from subtitle")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val word = wordInput.text.toString().trim()
                val meaning = meaningInput.text.toString().trim()
                if (word.isBlank() || meaning.isBlank()) {
                    toast("Enter the word and its meaning")
                    return@setPositiveButton
                }
                val vocabularyPrefs = getSharedPreferences("zeko_vocabulary", MODE_PRIVATE)
                val entries = try {
                    JSONArray(vocabularyPrefs.getString("entries", "[]"))
                } catch (_: Exception) {
                    JSONArray()
                }
                entries.put(JSONObject().apply {
                    put("id", System.currentTimeMillis())
                    put("word", word)
                    put("meaning", meaning)
                    put("example", sentence)
                    put("savedAt", System.currentTimeMillis())
                })
                vocabularyPrefs.edit().putString("entries", entries.toString()).apply()
                toast("Saved to My Vocabulary")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSubtitle(sub: Uri) {
        val p = player ?: return
        val u = currentUri ?: return
        val name = (sub.lastPathSegment ?: "").lowercase()
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val cfg = MediaItem.SubtitleConfiguration.Builder(sub)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = MediaItem.Builder()
            .setUri(u)
            .setSubtitleConfigurations(listOf(cfg))
            .build()
        val pos = p.currentPosition
        val playing = p.playWhenReady
        p.setMediaItem(item, pos)
        p.prepare()
        p.playWhenReady = playing
        toast("Subtitles loaded")
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

    // ---------- Cards ----------
    private fun buildCard(row: Boolean): View {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#24242E"))
        }
        val dur = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#B3000000"))
            }
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#66000000"))
            visibility = View.GONE
        }
        val frame = FrameLayout(this)
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            dur,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(8), dp(10))
            }
        )
        frame.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))

        val title = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            textSize = 12f
            setTextColor(grayText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title)
        col.addView(sub)

        val card = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(cardBg)
            }
            clipToOutline = true
        }
        if (row) {
            card.orientation = LinearLayout.HORIZONTAL
            card.addView(frame, LinearLayout.LayoutParams(dp(150), dp(86)))
            col.gravity = Gravity.CENTER_VERTICAL
            col.setPadding(dp(14), dp(8), dp(12), dp(8))
            card.addView(col, LinearLayout.LayoutParams(0, dp(86), 1f))
        } else {
            card.orientation = LinearLayout.VERTICAL
            card.addView(frame, LinearLayout.LayoutParams(-1, thumbHeight(gridCols())))
            col.setPadding(dp(10), dp(8), dp(10), dp(12))
            card.addView(col)
        }
        card.tag = CardHolder(frame, img, dur, title, sub, progress)
        return card
    }

    private fun loadInto(h: CardHolder, item: VideoItem) {
        h.id = item.id
        val cached = thumbCache.get(item.id)
        if (cached != null) {
            h.img.setImageBitmap(cached)
            return
        }
        h.img.setImageDrawable(null)
        ioExec.execute {
            val bmp = loadThumb(item)
            if (bmp != null) {
                thumbCache.put(item.id, bmp)
                runOnUiThread { if (h.id == item.id) h.img.setImageBitmap(bmp) }
            }
        }
    }

    private inner class VideoAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (listMode) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = convertView ?: buildCard(listMode)
            val h = card.tag as CardHolder
            val item = shown[position]
            if (!listMode) {
                h.frame.layoutParams.height = thumbHeight(gridCols())
                h.frame.requestLayout()
            }
            h.title.text = item.name.substringBeforeLast('.')
            h.sub.text = item.folder
            h.dur.visibility = View.VISIBLE
            h.dur.text = formatTime(item.durMs)
            val saved = prefs.getLong("p:" + item.uri, 0L)
            if (saved > 0L && item.durMs > 0L) {
                h.progress.progress = (saved * 1000L / item.durMs).toInt().coerceIn(0, 1000)
                h.progress.visibility = View.VISIBLE
            } else {
                h.progress.visibility = View.GONE
            }
            loadInto(h, item)
            return card
        }
    }

    private fun showVideoInfo(item: VideoItem) {
        val plays = prefs.getInt("plays:" + item.uri, 0)
        val watched = prefs.getLong("watched:" + item.uri, 0L)
        val lastViewed = if (watched > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(Date(watched))
        } else {
            "Not watched yet"
        }
        val saved = prefs.getLong("p:" + item.uri, 0L)
        val resume = if (saved > 0L && item.durMs > 0L) {
            val percent = (saved * 100L / item.durMs).coerceIn(0L, 100L)
            "$percent% watched · ${formatTime(saved)} saved"
        } else {
            "No saved position"
        }
        val message = listOf(
            "Folder     ${item.folder}",
            "Duration   ${formatTime(item.durMs)}",
            "Played     $plays ${if (plays == 1) "time" else "times"}",
            "Last seen  $lastViewed",
            "Progress   $resume"
        ).joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(item.name.substringBeforeLast('.'))
            .setMessage(message)
            .setPositiveButton("Play") { _, _ ->
                val queue = shown.toList()
                activeQueue = queue.toMutableList()
                persistPlaybackQueue()
                play(item.uri, item.name.substringBeforeLast('.'))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private inner class FolderAdapter : BaseAdapter() {
        override fun getCount(): Int = folders.size
        override fun getItem(position: Int): Any = folders[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = convertView ?: buildCard(false)
            val h = card.tag as CardHolder
            val f = folders[position]
            h.frame.layoutParams.height = thumbHeight(gridCols())
            h.frame.requestLayout()
            h.title.text = f.name
            h.sub.text = "${f.count} videos"
            h.dur.visibility = View.GONE
            h.progress.visibility = View.GONE
            loadInto(h, f.cover)
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

    // ---------- Settings screen ----------
    private fun buildSettingsPage() {
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(18), dp(4))
            setOnClickListener { closeSettings() }
        }
        val title = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
            addView(back)
            addView(title)
        }
        settingsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(settingsList) }
        settingsPage.addView(header)
        settingsPage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(settingsPage, FrameLayout.LayoutParams(-1, -1))
    }

    private fun sectionTitle(t: String): View = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(accent)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(24), dp(22), dp(24), dp(6))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#22222B"))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(grayText)
        })
        return row
    }

    private fun switchRow(
        title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = sub
            textSize = 14f
            setTextColor(grayText)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(col)
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    private fun openSettings() {
        settingsList.removeAllViews()
        val steps = intArrayOf(5, 10, 15, 30)
        val sortNames = arrayOf("Newest first", "Name (A-Z)", "Longest first", "Recently watched", "Most played")

        settingsList.addView(sectionTitle("PLAYBACK"))
        settingsList.addView(settingRow("Double-tap skip", "$seekSec seconds") {
            val labels = steps.map { "$it seconds" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Double-tap skip")
                .setSingleChoiceItems(labels, steps.indexOf(seekSec)) { d, w ->
                    seekSec = steps[w]
                    d.dismiss()
                    openSettings()
                }
                .show()
        })
        settingsList.addView(divider())
        settingsList.addView(
            switchRow("Remember position", "Continue from where you stopped", rememberPos) {
                rememberPos = it
            }
        )
        settingsList.addView(divider())
        settingsList.addView(
            switchRow(
                "Brightness & volume gestures",
                "Swipe up/down: left side = brightness, right side = volume",
                gesturesOn
            ) { gesturesOn = it }
        )
        settingsList.addView(divider())
        settingsList.addView(
            switchRow("Keep screen on", "While a video is playing", keepOn) { keepOn = it }
        )

        settingsList.addView(divider())
        settingsList.addView(
            switchRow(
                "Background playback",
                "Keep playing when you leave the app or turn the screen off",
                bgPlay
            ) {
                bgPlay = it
                if (it) askNotifPermission()
            }
        )
        settingsList.addView(divider())
        settingsList.addView(
            switchRow(
                "Floating window (PiP)",
                "Switch to a small floating window when you press Home",
                pipAuto
            ) { pipAuto = it }
        )

        settingsList.addView(sectionTitle("SUBTITLES"))
        settingsList.addView(settingRow("Subtitle text size", "${subtitleSizeSp}sp · Applies to on-screen subtitles") {
            val sizes = intArrayOf(16, 20, 24, 28)
            val labels = arrayOf("Small · 16sp", "Normal · 20sp", "Large · 24sp", "Extra large · 28sp")
            AlertDialog.Builder(this)
                .setTitle("Subtitle text size")
                .setSingleChoiceItems(labels, sizes.indexOf(subtitleSizeSp).coerceAtLeast(0)) { d, w ->
                    subtitleSizeSp = sizes[w]
                    applySubtitleSize()
                    d.dismiss()
                    openSettings()
                }
                .show()
        })

        settingsList.addView(sectionTitle("SEEK BAR"))
        settingsList.addView(
            switchRow("Video Seek Preview", "Show a thumbnail while you drag the seek bar", previewOn) {
                previewOn = it
            }
        )

        settingsList.addView(sectionTitle("LIBRARY"))
        settingsList.addView(settingRow("Sort videos by", sortNames[sortMode.coerceIn(0, sortNames.lastIndex)]) {
            AlertDialog.Builder(this)
                .setTitle("Sort videos by")
                .setSingleChoiceItems(sortNames, sortMode.coerceIn(0, sortNames.lastIndex)) { d, w ->
                    sortMode = w
                    videos = sorted(videos)
                    applyFilter()
                    d.dismiss()
                    openSettings()
                }
                .show()
        })
        settingsList.addView(divider())
        settingsList.addView(settingRow("Clear saved positions", "Forget where you stopped in every video") {
            AlertDialog.Builder(this)
                .setTitle("Clear saved positions?")
                .setMessage("All saved resume points will be removed. Your watch activity stays intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    val e = prefs.edit()
                    for (k in prefs.all.keys) if (k.startsWith("p:")) e.remove(k)
                    e.apply()
                    updateContinueWatching()
                    adapter.notifyDataSetChanged()
                    toast("Saved positions cleared")
                }
                .show()
        })
        settingsList.addView(divider())
        settingsList.addView(settingRow("Clear viewing activity", "Reset recently watched and most played data") {
            AlertDialog.Builder(this)
                .setTitle("Clear viewing activity?")
                .setMessage("This resets play counts and recently watched dates for every video. Saved resume positions will not be affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    val e = prefs.edit()
                    for (k in prefs.all.keys) {
                        if (k.startsWith("watched:") || k.startsWith("plays:")) e.remove(k)
                    }
                    e.apply()
                    videos = sorted(videos)
                    applyFilter()
                    toast("Viewing activity reset")
                }
                .show()
        })

        settingsList.addView(sectionTitle("APPEARANCE"))
        settingsList.addView(
            switchRow("Show name on video", "Zeko Player • Zakariya", watermarkOn) { watermarkOn = it }
        )

        settingsList.addView(sectionTitle("ABOUT"))
        settingsList.addView(settingRow("ZEKO Player 1.1", "Designed for a focused video experience") { })

        home.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
    }

    private fun applySubtitleSize() {
        if (::playerView.isInitialized) {
            playerView.subtitleView?.setFixedTextSize(
                TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp.toFloat()
            )
        }
    }

    private fun closeSettings() {
        settingsPage.visibility = View.GONE
        home.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    // ---------- Player screen ----------
    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#F3F1F7"))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#D91C1D27"))
            setStroke(dp(1), Color.parseColor("#454550"))
        }
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#44FFFFFF")),
            shape,
            null
        )
        minHeight = dp(42)
        layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply {
            leftMargin = dp(5)
            rightMargin = dp(5)
        }
        setOnClickListener { onClick() }
    }

    private fun iconChip(label: String, iconRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        letterSpacing = 0.015f
        setTextColor(Color.parseColor("#F3F1F7"))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        val icon = getDrawable(iconRes)?.mutate()
        icon?.setTint(Color.parseColor("#FFB27C"))
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        compoundDrawablePadding = dp(5)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#D91C1D27"))
            setStroke(dp(1), Color.parseColor("#454550"))
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), shape, null)
        minHeight = dp(40)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun setPlayerGlyph(view: TextView, resource: Int) {
        val icon = getDrawable(resource)?.mutate()
        icon?.setTint(Color.WHITE)
        view.text = ""
        view.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
    }

    private fun modeChip(label: String, iconRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.04f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            val icon = getDrawable(iconRes)?.mutate()
            icon?.setTint(Color.WHITE)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(6)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#B51A1B24"))
                    setStroke(dp(1), Color.parseColor("#42434F"))
                }, null)
            minHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }

    private fun circleBtn(label: String, size: Int, textSp: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = textSp
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val isPrimary = label == "▶" || label == "Ⅱ" || label == "❚❚"
            val circleShape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(if (isPrimary) accent else Color.parseColor("#E6242530"))
                setStroke(dp(1), if (isPrimary) Color.parseColor("#FFFFB27C") else Color.parseColor("#555B5B68"))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
                circleShape,
                GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    setColor(Color.WHITE)
                }
            )
            elevation = if (label == "▶" || label == "Ⅱ" || label == "❚❚") dp(8).toFloat() else dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                leftMargin = dp(7)
                rightMargin = dp(7)
            }
            when (label) {
                "❚❚" -> setPlayerGlyph(this, R.drawable.ic_pause)
                "▶" -> setPlayerGlyph(this, R.drawable.ic_play)
                "−10s" -> setPlayerGlyph(this, R.drawable.ic_rewind)
                "+10s" -> setPlayerGlyph(this, R.drawable.ic_forward)
                "‹" -> setPlayerGlyph(this, R.drawable.ic_skip_previous)
                "›" -> setPlayerGlyph(this, R.drawable.ic_skip_next)
            }
            contentDescription = label
            setOnClickListener { onClick() }
        }

    private fun buildPlayer() {
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        applySubtitleSize()

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (brightness < 0f) {
                    val cur = window.attributes.screenBrightness
                    brightness = if (cur >= 0f) cur else systemBrightness()
                }
                val am = audio()
                volLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (locked || !gesturesOn || e1 == null) return false
                if (abs(distanceY) < abs(distanceX)) return false
                val delta = distanceY / playerView.height.coerceAtLeast(1)
                if (e1.x < playerView.width / 2f) adjustBrightness(delta) else adjustVolume(delta)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    showUnlock()
                    return true
                }
                val now = System.currentTimeMillis()
                if (lastSide != 0 && now - lastSeekTime < 700) {
                    seekBy(lastSide)
                } else if (controlsVisible) {
                    hideControls()
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = playerView.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-1)
                    e.x > w * 2f / 3f -> seekBy(1)
                    else -> togglePlay()
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

        hud = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            alpha = 0f
            visibility = View.GONE
        }

        watermark = TextView(this).apply {
            text = "Zeko Player  •  Zakariya"
            textSize = 10f
            setTextColor(Color.WHITE)
            alpha = 0.28f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }

        // ----- compact title bar + horizontally scrollable tool dock -----
        titleTv = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        speedBtn = iconChip("1.0×", R.drawable.ic_speed) { chooseSpeed(); scheduleHide() }
        ratioBtn = iconChip("FIT", R.drawable.ic_fit) { cycleRatio(); scheduleHide() }
        repeatBtn = modeChip("REPEAT OFF", R.drawable.ic_repeat) { cycleRepeat(); scheduleHide() }
        shuffleBtn = modeChip("SHUFFLE OFF", R.drawable.ic_shuffle) { toggleShuffle(); scheduleHide() }
        updateModeButtons()
        sleepBtn = iconChip("SLEEP", R.drawable.ic_timer) { showSleepTimer(); scheduleHide() }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "‹"
                textSize = 34f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(16), dp(3))
                setOnClickListener { stopAndGoHome() }
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            addView(titleTv)
            addView(iconChip("PIP", R.drawable.ic_pip) { enterPip() })
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconChip("AUDIO", R.drawable.ic_audio) { pickTrack(C.TRACK_TYPE_AUDIO, "Audio track") })
            addView(iconChip("SUBTITLES", R.drawable.ic_subtitles) { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles") })
            addView(iconChip("QUEUE", R.drawable.ic_queue) { showPlaybackQueue(); scheduleHide() })
            addView(speedBtn)
            addView(ratioBtn)
            addView(sleepBtn)
        }
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(toolRow)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#D9101017"))
                setStroke(dp(1), Color.parseColor("#383841"))
            }
            addView(titleLine)
            addView(toolScroll)
        }

        // ----- center: rewind / play / forward -----
        playBtn = circleBtn("❚❚", 60, 22f) { togglePlay() }
        val centerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#A612121A"))
                setStroke(dp(1), Color.parseColor("#333D3D48"))
            }
            addView(circleBtn("‹", 44, 17f) { playPreviousVideo(); scheduleHide() })
            addView(circleBtn("−10s", 44, 12f) { seekBy(-1); scheduleHide() })
            addView(playBtn.apply { layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            } })
            addView(circleBtn("+10s", 44, 12f) { seekBy(1); scheduleHide() })
            addView(circleBtn("›", 44, 17f) { playNextVideo(); scheduleHide() })
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(repeatBtn)
            addView(shuffleBtn)
        }
        val centerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(modeRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(9) })
            addView(centerRow)
        }

        // ----- bottom: time bar + actions -----
        posText = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(10), 0)
        }
        durText = TextView(this).apply {
            text = "--:--"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        timeBar = DefaultTimeBar(this).apply {
            setPlayedColor(accent)
            setScrubberColor(accent)
            setBufferedColor(Color.parseColor("#80FFFFFF"))
            setUnplayedColor(Color.parseColor("#40FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(tb: TimeBar, position: Long) {
                scrubbing = true
                ui.removeCallbacks(hideRunnable)
            }

            override fun onScrubMove(tb: TimeBar, position: Long) {
                posText.text = formatTime(position)
            }

            override fun onScrubStop(tb: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position)
                scheduleHide()
            }
        })
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(posText)
            addView(timeBar)
            addView(durText)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(chip("Lock") { lockScreen() })
            addView(chip("Rotate") { cycleOrientation(); scheduleHide() })
            addView(chip("Save word") { saveSubtitleWord() })
            addView(chip("Replay line") { replayCurrentSubtitle() })
            addView(chip("Speak") { speakCurrentSubtitle() })
        }
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionRow)
        }
        val bottomCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(15))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#D9101017"))
                setStroke(dp(1), Color.parseColor("#383841"))
            }
            addView(timeRow)
            addView(actionScroll)
        }

        val scrimTop = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#B3000000"), Color.TRANSPARENT)
            )
        }
        val scrimBottom = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
        }
        controlsLayer = FrameLayout(this).apply {
            visibility = View.GONE
            addView(scrimTop, FrameLayout.LayoutParams(-1, dp(130), Gravity.TOP))
            addView(scrimBottom, FrameLayout.LayoutParams(-1, dp(200), Gravity.BOTTOM))
            addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                leftMargin = dp(14); topMargin = dp(12); rightMargin = dp(14)
            })
            addView(centerPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(bottomCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(12)
            })
        }

        lockOverlay = TextView(this).apply {
            text = "Unlock"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#B3000000"))
                setStroke(dp(2), accent)
            }
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { unlockScreen() }
        }

        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(controlsLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            lockOverlay,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                leftMargin = dp(28)
            }
        )
        root.addView(
            seekLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END)
        )
        root.addView(
            hud,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(90)
            }
        )
        root.addView(
            watermark,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(12)
                bottomMargin = dp(154)
            }
        )
        liveTranslationOverlay = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            visibility = View.GONE
            maxLines = 2
        }
        root.addView(
            liveTranslationOverlay,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
                bottomMargin = dp(28)
            }
        )
        setupSeekPreview()
    }

    // ----- controls show / hide / progress -----
    private fun showControls() {
        if (locked || inPip) return
        controlsVisible = true
        controlsLayer.animate().cancel()
        controlsLayer.visibility = View.VISIBLE
        controlsLayer.alpha = 1f
        updateProgress()
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        ui.removeCallbacks(hideRunnable)
        controlsLayer.animate().cancel()
        controlsLayer.animate().alpha(0f).setDuration(200).withEndAction {
            if (!controlsVisible) controlsLayer.visibility = View.GONE
        }.start()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        if (controlsVisible && !scrubbing && player?.isPlaying == true) {
            ui.postDelayed(hideRunnable, 3500)
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        if (!controlsVisible || scrubbing) return
        val dur = p.duration
        val pos = p.currentPosition
        timeBar.setDuration(if (dur > 0L) dur else C.TIME_UNSET)
        timeBar.setPosition(pos)
        timeBar.setBufferedPosition(p.bufferedPosition)
        posText.text = formatTime(pos)
        durText.text = if (dur > 0L) formatTime(dur) else "--:--"
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
        scheduleHide()
    }

    private fun showSleepTimer() {
        if (player == null) {
            toast("Open a video first")
            return
        }
        val active = sleepTimerRunnable != null
        val options = arrayOf(
            "15 minutes",
            "30 minutes",
            "45 minutes",
            "60 minutes",
            "90 minutes",
            "120 minutes",
            if (active) "Cancel active timer" else "No active timer"
        )
        AlertDialog.Builder(this)
            .setTitle(if (active) "SLEEP TIMER · ACTIVE" else "SLEEP TIMER")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setSleepTimer(15)
                    1 -> setSleepTimer(30)
                    2 -> setSleepTimer(45)
                    3 -> setSleepTimer(60)
                    4 -> setSleepTimer(90)
                    5 -> setSleepTimer(120)
                    6 -> if (active) cancelSleepTimer(true)
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        cancelSleepTimer(false)
        sleepTimerDeadlineMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        val task = object : Runnable {
            override fun run() {
                val remaining = sleepTimerDeadlineMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    player?.pause()
                    sleepTimerRunnable = null
                    sleepTimerDeadlineMs = 0L
                    if (::sleepBtn.isInitialized) sleepBtn.text = "SLEEP"
                    toast("Sleep timer finished · playback paused")
                    return
                }
                val totalSeconds = (remaining + 999L) / 1000L
                val mins = totalSeconds / 60L
                val secs = totalSeconds % 60L
                if (::sleepBtn.isInitialized) {
                    sleepBtn.text = "SLEEP %02d:%02d".format(mins, secs)
                }
                ui.postDelayed(this, 1000L)
            }
        }
        sleepTimerRunnable = task
        task.run()
        toast("Playback will pause in $minutes minutes")
    }

    private fun cancelSleepTimer(showMessage: Boolean) {
        sleepTimerRunnable?.let { ui.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerDeadlineMs = 0L
        if (::sleepBtn.isInitialized) sleepBtn.text = "SLEEP"
        if (showMessage) toast("Sleep timer cancelled")
    }

    private fun lockScreen() {
        locked = true
        hideControls()
        showHud("Locked")
    }

    private fun unlockScreen() {
        locked = false
        lockOverlay.animate().cancel()
        lockOverlay.visibility = View.GONE
        showControls()
    }

    private fun showUnlock() {
        lockOverlay.visibility = View.VISIBLE
        lockOverlay.animate().cancel()
        lockOverlay.alpha = 1f
        lockOverlay.animate().alpha(0f).setStartDelay(2000).setDuration(300).withEndAction {
            lockOverlay.visibility = View.GONE
        }.start()
    }

    private fun cycleOrientation() {
        orient = (orient + 1) % 3
        requestedOrientation = when (orient) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showHud(arrayOf("Auto rotate", "Landscape", "Portrait")[orient])
    }

    // ----- floating window (PiP) -----
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Floating window needs Android 8 or newer")
            return
        }
        val p = player ?: return
        val vs = p.videoSize
        var w = vs.width
        var h = vs.height
        if (vs.unappliedRotationDegrees % 180 != 0) {
            val t = w; w = h; h = t
        }
        if (w <= 0 || h <= 0) {
            w = 16
            h = 9
        }
        val ratio = (w.toFloat() / h).coerceIn(0.43f, 2.35f)
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            toast("Floating window is not available (check Android settings)")
        }
    }

    // ----- background playback -----
    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun startBgService() {
        PlaybackHolder.player = player
        PlaybackHolder.title = titleTv.text.toString()
        val i = Intent(this, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
        }
    }

    private fun stopBgService() {
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
        }
    }

    // ----- gestures: brightness / volume -----
    private fun audio(): AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

    private fun systemBrightness(): Float = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) {
        0.5f
    }

    private fun adjustBrightness(delta: Float) {
        brightness = (brightness + delta * 1.3f).coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
        showHud("Brightness  ${(brightness * 100).roundToInt()}%")
    }

    private fun adjustVolume(delta: Float) {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volLevel = (volLevel + delta * 1.3f).coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (volLevel * max).roundToInt(), 0)
        showHud("Volume  ${(volLevel * 100).roundToInt()}%")
    }

    private fun showHud(t: String) {
        hud.text = t
        hud.visibility = View.VISIBLE
        hud.animate().cancel()
        hud.alpha = 1f
        hud.animate().alpha(0f).setStartDelay(800).setDuration(300).start()
    }

    // ----- speed / ratio / tracks -----
    private fun chooseSpeed() {
        val p = player ?: return
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = arrayOf(
            "0.25x", "0.5x", "0.75x", "1x (normal)", "1.25x",
            "1.5x", "1.75x", "2x", "2.5x", "3x"
        )
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, values.indexOfFirst { it == speed }.coerceAtLeast(0)) { d, w ->
                speed = values[w]
                savedPlaybackSpeed = speed
                p.setPlaybackSpeed(speed)
                speedBtn.text = if (speed == 1f) "1x" else "${speed}x"
                d.dismiss()
            }
            .show()
    }

    private fun repeatLabel(): String = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "ONE"
        Player.REPEAT_MODE_ALL -> "ALL"
        else -> "OFF"
    }

    private fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = Player.REPEAT_MODE_OFF
        updateModeButtons()
        showHud("Repeat ${repeatLabel().lowercase(Locale.ROOT)}")
    }

    private fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        shufflePlayedUris.clear()
        shuffleHistory.clear()
        shuffleHistoryIndex = -1
        currentUri?.let {
            shufflePlayedUris.add(it.toString())
            shuffleHistory.add(it.toString())
            shuffleHistoryIndex = 0
        }
        updateModeButtons()
        showHud(if (shuffleEnabled) "Shuffle on" else "Shuffle off")
    }

    private fun updateModeButtons() {
        if (::repeatBtn.isInitialized) {
            repeatBtn.text = "REPEAT " + repeatLabel()
            repeatBtn.alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.78f else 1f
            repeatBtn.setTextColor(if (repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else accent)
            repeatBtn.setCompoundDrawableTintList(ColorStateList.valueOf(if (repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else accent))
        }
        if (::shuffleBtn.isInitialized) {
            shuffleBtn.text = if (shuffleEnabled) "SHUFFLE ON" else "SHUFFLE OFF"
            shuffleBtn.alpha = if (shuffleEnabled) 1f else 0.78f
            shuffleBtn.setTextColor(if (shuffleEnabled) accent else Color.WHITE)
            shuffleBtn.setCompoundDrawableTintList(ColorStateList.valueOf(if (shuffleEnabled) accent else Color.WHITE))
        }
    }

    private fun cycleRatio() {
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        val names = arrayOf("Fit", "Zoom", "Fill")
        ratioIdx = (ratioIdx + 1) % modes.size
        playerView.resizeMode = modes[ratioIdx]
        ratioBtn.text = names[ratioIdx]
        showHud(names[ratioIdx])
    }

    private fun trackName(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        val label = f.label
        val lang = f.language
        if (!label.isNullOrBlank()) {
            parts.add(label)
        } else if (!lang.isNullOrBlank() && lang != "und") {
            parts.add(Locale(lang).displayLanguage)
        } else {
            parts.add("Track $n")
        }
        if (f.channelCount > 0) parts.add("${f.channelCount}ch")
        return parts.joinToString("  •  ")
    }

    private fun pickTrack(type: Int, title: String) {
        val p = player ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add("Off")
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, true)
                    .build()
            }
        }
        var n = 1
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val name = trackName(g.getTrackFormat(i), n++)
                labels.add(name)
                actions.add {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add("＋ Load subtitle file (.srt / .vtt)")
            actions.add { pickSubtitleFile() }
        } else if (labels.isEmpty()) {
            toast("No tracks available")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    // ---------- Video Seek Preview (instant) ----------
    private fun setupSeekPreview() {
        previewTex = TextureView(this).apply { alpha = 0f }
        previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.INVISIBLE
        }
        previewTime = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        previewFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.BLACK)
            }
            clipToOutline = true
            addView(previewTex, FrameLayout.LayoutParams(-1, -1))
            addView(previewImg, FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(101))
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

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                if (!previewOn) return
                previewTex.alpha = 0f
                previewBox.visibility = View.VISIBLE
                updatePreview(timeBar, position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                if (!previewOn) return
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

        // instant: always show the closest thumbnail that is already prepared
        var shownFrame = false
        val arr = strip
        val total = stripDurMs
        if (arr != null && total > 0L) {
            val n = arr.length()
            val idx = ((pos.toFloat() / total) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            var bmp: Bitmap? = null
            var d = 0
            while (bmp == null && d < n) {
                if (idx - d >= 0) bmp = arr.get(idx - d)
                if (bmp == null && idx + d < n) bmp = arr.get(idx + d)
                d++
            }
            if (bmp != null) {
                if (bmp !== lastShown) {
                    previewImg.setImageBitmap(bmp)
                    lastShown = bmp
                }
                previewImg.visibility = View.VISIBLE
                shownFrame = true
            }
        }

        // nothing prepared yet (first moments only): use the live preview player
        if (!shownFrame) {
            previewImg.visibility = View.INVISIBLE
            pendingPos = pos
            val nowMs = SystemClock.uptimeMillis()
            previewBox.removeCallbacks(seekRunnable)
            if (nowMs - lastPreviewSeek > 60) {
                lastPreviewSeek = nowMs
                previewPlayer?.seekTo(pos)
            } else {
                previewBox.postDelayed(seekRunnable, 70)
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

    private fun openRetriever(uri: Uri): MediaMetadataRetriever {
        val r = MediaMetadataRetriever()
        if (uri.scheme == "http" || uri.scheme == "https") {
            r.setDataSource(uri.toString(), HashMap<String, String>())
        } else {
            r.setDataSource(this, uri)
        }
        return r
    }

    private fun prepareStrip(uri: Uri) {
        val gen = ++stripGen
        strip = null
        stripDurMs = 0L
        lastShown = null
        previewImg.setImageDrawable(null)

        stripPool.execute {
            var dur = 0L
            var vw = 1280
            var vh = 720
            var rot = 0
            try {
                val r0 = openRetriever(uri)
                try {
                    dur = r0.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    vw = r0.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 1280
                    vh = r0.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 720
                    rot = r0.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                } finally {
                    try { r0.release() } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                return@execute
            }
            if (dur <= 0L || gen != stripGen) return@execute

            val isNet = uri.scheme == "http" || uri.scheme == "https"
            val count = if (isNet) 30 else (dur / 3000L).toInt().coerceIn(40, 240)
            if (rot == 90 || rot == 270) {
                val t = vw; vw = vh; vh = t
            }
            if (vw <= 0) vw = 1280
            if (vh <= 0) vh = 720
            val dw = 256
            val dh = (dw * vh / vw).coerceIn(72, 456)

            val local = AtomicReferenceArray<Bitmap>(count)
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

            val next = AtomicInteger(0)
            val workers = if (isNet) 1 else 3
            for (wk in 0 until workers) {
                stripPool.execute {
                    var rr: MediaMetadataRetriever? = null
                    try {
                        val r = openRetriever(uri)
                        rr = r
                        while (gen == stripGen) {
                            val k = next.getAndIncrement()
                            if (k >= order.size) break
                            val i = order[k]
                            val tUs = dur * 1000L * i / (count - 1).coerceAtLeast(1)
                            val b = grab(r, tUs, dw, dh)
                            if (b != null) local.set(i, b)
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { rr?.release() } catch (e: Exception) { }
                    }
                }
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
        accumulated += seekSec
        lastSeekTime = now
        lastSide = side

        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
        val target = (p.currentPosition + side * seekSec * 1000L).coerceIn(0L, dur)
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

    private fun updateContinueWatching() {
        if (!::continueSection.isInitialized || !::continueCard.isInitialized) return
        val last = prefs.getString("lastPlayedUri", null)
        val item = videos.firstOrNull { it.uri.toString() == last }
        if (item == null) {
            continueSection.visibility = View.GONE
            return
        }
        val position = prefs.getLong("p:" + item.uri, 0L)
        val duration = item.durMs
        val percent = if (duration > 0L) (position * 100L / duration).toInt().coerceIn(0, 100) else 0
        val remaining = if (duration > position && position > 0L) formatTime(duration - position) else formatTime(duration)
        val state = if (position > 0L) "RESUME  ·  $remaining LEFT" else "PLAY FROM START"
        continueCard.text = item.name.substringBeforeLast('.') + "\n" + state + "     " + percent + "%"
        continueSection.visibility = View.VISIBLE
    }

    private fun savePosition() {
        val p = player ?: return
        val u = currentUri ?: return
        if (!rememberPos) return
        val pos = p.currentPosition
        val dur = p.duration
        val e = prefs.edit()
        if (dur > 15000L && pos > 5000L && pos < dur - 8000L) e.putLong("p:" + u, pos)
        else e.remove("p:" + u)
        e.apply()
        updateContinueWatching()
    }

    private fun play(uri: Uri, title: String? = null) {
        endDialogShown = false
        savePosition()
        player?.release()
        currentUri = uri
        prefs.edit()
            .putString("lastPlayedUri", uri.toString())
            .putLong("watched:" + uri, System.currentTimeMillis())
            .putInt("plays:" + uri, prefs.getInt("plays:" + uri, 0) + 1)
            .apply()
        if (sortMode >= 3) {
            videos = sorted(videos)
            applyFilter()
        }
        updateContinueWatching()

        liveAudioSink?.enabled = false
        val pcmSink = Pcm16kTeeSink { pcm ->
            if (liveTranslationEnabled) {
                speechExec.execute {
                    try {
                        // Whisper transcribes local six-second audio chunks; only recognized text is sent to MADLAD.
                        val spoken = speechRecognizer?.acceptAudio(pcm)?.trim().orEmpty()
                        val now = android.os.SystemClock.elapsedRealtime()
                        val isNewText = spoken.length >= 4 && spoken != lastLiveSpeechText
                        val shouldTranslate = isNewText &&
                            (now - lastLiveTranslationAt >= 0L)
                        if (shouldTranslate && liveTranslationEnabled) {
                            lastLiveSpeechText = spoken
                            lastLiveTranslationAt = now
                            val requestId = ++liveTranslationRequest
                            madladTranslator?.takeIf { it.isReady() }?.translate(spoken)
                                ?.addOnSuccessListener { arabic ->
                                    runOnUiThread {
                                        if (liveTranslationEnabled && requestId == liveTranslationRequest &&
                                            !arabic.isNullOrBlank()
                                        ) {
                                            liveTranslationOverlay?.text = arabic
                                            liveTranslationOverlay?.visibility = View.VISIBLE
                                        }
                                    }
                                }
                                ?.addOnFailureListener { error ->
                                    runOnUiThread {
                                        if (liveTranslationEnabled && requestId == liveTranslationRequest) {
                                            toast("فشلت الترجمة: " + (error.localizedMessage ?: "خطأ اتصال"))
                                        }
                                    }
                                }
                        }
                    } catch (error: Exception) {
                        runOnUiThread { toast("تعذر التعرف على الكلام: " + (error.localizedMessage ?: "خطأ")) }
                    }
                }
            }
        }
        liveAudioSink = pcmSink
        pcmSink.enabled = liveTranslationEnabled
        val teeProcessor = TeeAudioProcessor(pcmSink)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(teeProcessor))
                    .build()
            }
        }
        val p = ExoPlayer.Builder(this, renderersFactory).build()
        player = p
        playerView.player = p
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        ratioIdx = 0
        ratioBtn.text = "Fit"
        speed = savedPlaybackSpeed
        speedBtn.text = if (speed == 1f) "1x" else "${speed}x"
        p.setPlaybackSpeed(speed)
        updateModeButtons()
        p.repeatMode = Player.REPEAT_MODE_OFF

        val start = if (rememberPos) prefs.getLong("p:" + uri, 0L) else 0L
        p.setMediaItem(MediaItem.fromUri(uri), start)
        currentSubtitleLine = ""
        p.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val line = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (line.isNotBlank() && line != currentSubtitleLine) {
                    currentSubtitleLine = line
                    currentSubtitleStartPosition = p.currentPosition
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefs.edit().remove("p:" + uri).apply()
                    if (!endDialogShown) {
                        endDialogShown = true
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> {
                                endDialogShown = false
                                p.seekTo(0L)
                                p.play()
                            }
                            Player.REPEAT_MODE_ALL -> playNextVideo(wrap = true)
                            else -> playNextVideo()
                        }
                    }
                }
            }
        })
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setPlayerGlyph(playBtn, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) scheduleHide() else ui.removeCallbacks(hideRunnable)
            }
        })
        p.prepare()
        p.playWhenReady = true
        if (start > 0L) toast("Resumed from " + formatTime(start))
        locked = false
        lockOverlay.visibility = View.GONE
        setPlayerGlyph(playBtn, R.drawable.ic_pause)
        showControls()
        ui.removeCallbacks(ticker)
        ui.post(ticker)

        titleTv.text = title
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Video"

        if (previewOn) {
            prepareStrip(uri)
            preparePreviewPlayer(uri)
        } else {
            stripGen++
            strip = null
            previewPlayer?.release()
            previewPlayer = null
        }

        home.visibility = View.GONE
        settingsPage.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        watermark.visibility = if (watermarkOn) View.VISIBLE else View.GONE
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        if (gesturesOn && !prefs.getBoolean("hintShown", false)) {
            prefs.edit().putBoolean("hintShown", true).apply()
            Toast.makeText(
                this,
                "Swipe up/down: left side = brightness, right side = volume.\n" +
                        "Tap the screen to show Speed / Audio / Subs.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (bgPlay && Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifAsked", false)) {
            prefs.edit().putBoolean("notifAsked", true).apply()
            askNotifPermission()
        }
    }


    // Keep the queue captured when playback starts; library searches must not
    // unexpectedly change which video plays next.
    private var activeQueue: MutableList<VideoItem>? = null

    private fun playbackQueue(): List<VideoItem> =
        activeQueue ?: if (shown.isNotEmpty()) shown else videos

    // Persist queue entries by MediaStore ID; rebuild their content URIs from the
    // current library scan so Android's media URI format is never guessed.
    private fun persistPlaybackQueue() {
        val queue = activeQueue ?: return
        val stored = JSONArray()
        queue.forEach { stored.put(it.id) }
        prefs.edit().putString("playbackQueue", stored.toString()).apply()
    }

    private fun restorePlaybackQueue(library: List<VideoItem>) {
        if (activeQueue != null) return
        val raw = prefs.getString("playbackQueue", null) ?: return
        try {
            val stored = JSONArray(raw)
            val byId = library.associateBy { it.id }
            val restored = ArrayList<VideoItem>(stored.length())
            for (i in 0 until stored.length()) {
                byId[stored.getLong(i)]?.let { restored.add(it) }
            }
            activeQueue = restored
            if (restored.size != stored.length()) persistPlaybackQueue()
        } catch (_: Exception) {
            prefs.edit().remove("playbackQueue").apply()
        }
    }

    private fun showPlaybackQueue() {
        val queue = activeQueue ?: (if (shown.isNotEmpty()) shown else videos).toMutableList().also { activeQueue = it }
        val dialog = AlertDialog.Builder(this)
            .setTitle("PLAYBACK QUEUE · ${queue.size}")
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("ADD VIDEOS", null)
            .create()

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        dialog.setView(scroll)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#171720")))
        fun rebuild() {
            list.removeAllViews()
            dialog.setTitle("PLAYBACK QUEUE · ${queue.size}")
            if (queue.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "Queue is empty"
                    textSize = 15f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                })
                return
            }
            queue.forEachIndexed { index, item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (item.uri == currentUri) Color.parseColor("#302C211B") else Color.parseColor("#20202A"))
                        setStroke(dp(1), if (item.uri == currentUri) Color.parseColor("#FFB27C") else Color.parseColor("#383844"))
                    }
                }
                val title = TextView(this).apply {
                    text = (if (item.uri == currentUri) "NOW PLAYING  ·  " else "") + item.name
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (item.uri == currentUri) Color.parseColor("#FFB27C") else Color.WHITE)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, dp(8))
                    setOnClickListener {
                        dialog.dismiss()
                        play(item.uri, item.name.substringBeforeLast('.'))
                    }
                }
                row.addView(title)
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                fun action(label: String, enabled: Boolean = true, click: () -> Unit) {
                    actions.addView(TextView(this).apply {
                        text = label
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(if (enabled) Color.parseColor("#F3F1F7") else Color.parseColor("#777780"))
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(Color.parseColor("#30303B"))
                        }
                        alpha = if (enabled) 1f else 0.45f
                        isEnabled = enabled
                        setOnClickListener { click() }
                    }, LinearLayout.LayoutParams(-2, dp(34)).apply {
                        leftMargin = dp(5)
                    })
                }
                action("UP", index > 0) {
                    val moved = queue.removeAt(index)
                    queue.add(index - 1, moved)
                    persistPlaybackQueue()
                    rebuild()
                }
                action("DOWN", index < queue.lastIndex) {
                    val moved = queue.removeAt(index)
                    queue.add(index + 1, moved)
                    persistPlaybackQueue()
                    rebuild()
                }
                action("REMOVE") {
                    queue.removeAt(index)
                    persistPlaybackQueue()
                    rebuild()
                }
                row.addView(actions)
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidates = videos.filterNot { candidate -> queue.any { it.uri == candidate.uri } }
            if (candidates.isEmpty()) {
                toast("All library videos are already in the queue")
            } else {
                val selected = BooleanArray(candidates.size)
                val names = candidates.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("ADD TO PLAYBACK QUEUE")
                    .setMultiChoiceItems(names, selected) { _, which, checked ->
                        selected[which] = checked
                    }
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("ADD") { _, _ ->
                        candidates.forEachIndexed { index, item ->
                            if (selected[index] && queue.none { it.uri == item.uri }) queue.add(item)
                        }
                        persistPlaybackQueue()
                        rebuild()
                        toast("Queue updated · ${queue.size} videos")
                    }
                    .show()
            }
        }
        rebuild()
    }

    private fun playNextVideo(wrap: Boolean = false) {
        val queue = playbackQueue()
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.uri == currentUri }
        val next: VideoItem? = if (shuffleEnabled && queue.size > 1) {
            // After going back in shuffle history, NEXT retraces that history first.
            val forwardUri = shuffleHistory.getOrNull(shuffleHistoryIndex + 1)
            val forwardItem = forwardUri?.let { uri ->
                queue.firstOrNull { it.uri.toString() == uri }
            }
            if (forwardItem != null) {
                shuffleHistoryIndex++
                forwardItem
            } else {
                if (shufflePlayedUris.isEmpty()) currentUri?.let { shufflePlayedUris.add(it.toString()) }
                var candidates = queue.filter {
                    it.uri != currentUri && it.uri.toString() !in shufflePlayedUris
                }
                if (candidates.isEmpty() && wrap) {
                    shufflePlayedUris.clear()
                    currentUri?.let { shufflePlayedUris.add(it.toString()) }
                    candidates = queue.filter { it.uri != currentUri }
                }
                candidates.randomOrNull()?.also { item ->
                    while (shuffleHistory.size > shuffleHistoryIndex + 1) {
                        shuffleHistory.removeAt(shuffleHistory.lastIndex)
                    }
                    shuffleHistory.add(item.uri.toString())
                    shuffleHistoryIndex = shuffleHistory.lastIndex
                    shufflePlayedUris.add(item.uri.toString())
                }
            }
        } else {
            val nextIndex = if (index < 0) 0 else index + 1
            if (nextIndex in queue.indices) queue[nextIndex]
            else if (wrap) queue.firstOrNull()
            else null
        }
        if (next == null) {
            showHud("End of queue")
            return
        }
        play(next.uri, next.name)
    }

    private fun playPreviousVideo() {
        // Match familiar media-player behavior: a quick Previous press restarts
        // the current video; near the beginning it navigates to the prior item.
        val activePlayer = player
        if (activePlayer != null && activePlayer.currentPosition > 3_000L) {
            activePlayer.seekTo(0L)
            showHud("Restarted video")
            return
        }
        val queue = playbackQueue()
        if (shuffleEnabled) {
            val previousUri = shuffleHistory.getOrNull(shuffleHistoryIndex - 1)
            val previous = previousUri?.let { uri -> queue.firstOrNull { it.uri.toString() == uri } }
            if (previous != null) {
                shuffleHistoryIndex--
                play(previous.uri, previous.name)
                return
            }
        }
        val index = queue.indexOfFirst { it.uri == currentUri }
        val previous = queue.getOrNull(index - 1) ?: return
        play(previous.uri, previous.name)
    }

    private fun requestMadladEndpoint() {
        val input = EditText(this).apply {
            hint = "http://192.168.1.20:8000"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("madladEndpoint", "").orEmpty())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("عنوان خادم MADLAD")
            .setMessage("أدخل عنوان الخادم الذي يشغّل نموذج MADLAD-400. يجب أن يكون التابلت والخادم على شبكة خاصة موثوقة، أو استخدم عنوان HTTPS آمن.")
            .setView(input)
            .setPositiveButton("حفظ واتصال") { _, _ ->
                val endpoint = input.text.toString().trim().trimEnd('/')
                if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
                    toast("أدخل عنواناً يبدأ بـ http:// أو https://")
                    return@setPositiveButton
                }
                prefs.edit().putString("madladEndpoint", endpoint).apply()
                toggleLiveTranslation()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- Live English speech -> Arabic subtitles ----------
    private fun toggleLiveTranslation() {
        if (liveTranslationEnabled) {
            liveTranslationEnabled = false
            liveTranslationRequest++
            liveAudioSink?.enabled = false
            liveTranslationOverlay?.visibility = View.GONE
            toast("تم إيقاف الترجمة الفورية")
            return
        }
        if (prefs.getString("madladEndpoint", "").isNullOrBlank()) {
            requestMadladEndpoint()
            return
        }
        if (speechModelReady && translationModelReady && speechRecognizer != null &&
            madladTranslator?.isReady() == true
        ) {
            enableLiveTranslation()
            return
        }

        val installer = WhisperModelInstaller(this)
        if (installer.isInstalled()) {
            prepareLiveModels(installer.modelDirectory())
            return
        }

        AlertDialog.Builder(this)
            .setTitle("إعداد الترجمة الفورية")
            .setMessage("سيتم تنزيل نموذج Whisper Small English بحجم يقارب 466MB. يعمل التعرف محلياً على الجهاز، وقد تتأخر الترجمة عدة ثوانٍ حسب سرعة التابلت.")
            .setPositiveButton("تنزيل وبدء") { _, _ ->
                val loading = AlertDialog.Builder(this)
                    .setTitle("تنزيل نموذج الصوت")
                    .setMessage("يرجى الانتظار...")
                    .setCancelable(false)
                    .create()
                loading.show()
                installer.install(
                    onProgress = { value ->
                        runOnUiThread {
                            if (!isFinishing) loading.setMessage("تنزيل النموذج: " + value + "%")
                        }
                    },
                    onComplete = { result ->
                        runOnUiThread {
                            loading.dismiss()
                            result.onSuccess { dir -> prepareLiveModels(dir) }
                                .onFailure { error ->
                                    toast("فشل تنزيل النموذج: " + (error.localizedMessage ?: "تحقق من الإنترنت"))
                                }
                        }
                    }
                )
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun prepareLiveModels(modelDirectory: File) {
        toast("جاري تجهيز التعرف الصوتي والترجمة...")
        speechExec.execute {
            try {
                val recognizer = WhisperChunkedSpeechRecognizer(this@MainActivity, File(modelDirectory, WhisperModelInstaller.MODEL_FILE))
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        recognizer.close()
                        return@runOnUiThread
                    }
                    try { speechRecognizer?.close() } catch (_: Exception) { }
                    speechRecognizer = recognizer
                    speechModelReady = true
                    val endpoint = prefs.getString("madladEndpoint", "").orEmpty()
                    madladTranslator?.close()
                    val translator = MadladArabicTranslator(
                        endpoint = endpoint,
                        onReady = {
                            runOnUiThread {
                                translationModelReady = true
                                if (speechModelReady) enableLiveTranslation()
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                translationModelReady = false
                                toast("تعذر الاتصال بمترجم MADLAD: " + error)
                            }
                        }
                    )
                    madladTranslator = translator
                    translator.prepare()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    toast("تعذر تشغيل نموذج الصوت: " + (error.localizedMessage ?: "خطأ"))
                }
            }
        }
    }

    private fun enableLiveTranslation() {
        if (!speechModelReady || !translationModelReady ||
            speechRecognizer == null || madladTranslator?.isReady() != true
        ) return
        val sink = liveAudioSink
        if (sink == null) {
            toast("افتح فيديو أولاً ثم فعّل الترجمة")
            return
        }
        liveTranslationEnabled = true
        lastLiveSpeechText = ""
        lastLiveTranslationAt = 0L
        liveTranslationRequest++
        sink.enabled = true
        liveTranslationOverlay?.text = "أستمع إلى الكلام الإنجليزي..."
        liveTranslationOverlay?.visibility = View.VISIBLE
        toast("الترجمة الفورية تعمل")
    }

    private fun stopAndGoHome() {
        savePosition()
        stripGen++
        previewPlayer?.release()
        previewPlayer = null
        player?.release()
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        player = null
        currentUri = null
        playerView.player = null
        previewBox.visibility = View.GONE
        liveTranslationEnabled = false
        liveAudioSink?.enabled = false
        liveAudioSink = null
        liveTranslationOverlay?.visibility = View.GONE
        speechExec.execute {
            try { speechRecognizer?.close() } catch (_: Exception) { }
            speechRecognizer = null
        }
        speechModelReady = false
        translationModelReady = false
        madladTranslator?.close()
        madladTranslator = null
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideRunnable)
        stopBgService()
        controlsVisible = false
        controlsLayer.visibility = View.GONE
        locked = false
        lockOverlay.visibility = View.GONE
        orient = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerView.visibility = View.GONE
        watermark.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = -1f
        window.attributes = lp
        brightness = -1f
        home.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerView.visibility == View.VISIBLE) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        when (requestCode) {
            REQ_PICK -> play(u)
            REQ_SUB -> loadSubtitle(u)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            playerView.visibility == View.VISIBLE -> stopAndGoHome()
            settingsPage.visibility == View.VISIBLE -> closeSettings()
            searchBar.visibility == View.VISIBLE -> toggleSearch()
            folderFilter != null -> {
                folderFilter = null
                applyFilter()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { play(it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipAuto && !inPip && playerView.visibility == View.VISIBLE && player?.isPlaying == true) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            controlsVisible = false
            controlsLayer.visibility = View.GONE
            watermark.visibility = View.GONE
            previewBox.visibility = View.GONE
        } else if (playerView.visibility == View.VISIBLE) {
            watermark.visibility = if (watermarkOn) View.VISIBLE else View.GONE
            showControls()
        }
    }

    override fun onPause() {
        super.onPause()
        val p = player
        if (bgPlay && p != null && playerView.visibility == View.VISIBLE && p.isPlaying) {
            startBgService()
        }
    }

    override fun onResume() {
        super.onResume()
        stopBgService()
        if (playerView.visibility == View.VISIBLE) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (!bgPlay) player?.pause()
    }

    override fun onDestroy() {
        liveTranslationEnabled = false
        liveAudioSink?.enabled = false
        try { speechRecognizer?.close() } catch (_: Exception) { }
        madladTranslator?.close()
        speechExec.shutdownNow()
        super.onDestroy()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideRunnable)
        stopBgService()
        cancelSleepTimer(false)
        stripGen++
        previewPlayer?.release()
        previewPlayer = null
        player?.release()
        player = null
        PlaybackHolder.player = null
        stripPool.shutdown()
        ioExec.shutdown()
    }
}
