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
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import java.util.Locale
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

class MainActivity : Activity() {

    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null

    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var grid: GridView
    private lateinit var foldersGrid: GridView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: Button
    private lateinit var sectionLabel: TextView
    private lateinit var searchBar: EditText
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
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
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

    private fun sorted(list: List<VideoItem>): List<VideoItem> = when (sortMode) {
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.durMs }
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
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val ff = folderFilter
        shown = videos.filter {
            (ff == null || it.folder == ff) && (q.isEmpty() || it.name.lowercase().contains(q))
        }
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
        emptyText.text = when {
            !granted -> "Allow access to show the videos on your device"
            noData -> "No videos found on this device"
            else -> "No videos match your search"
        }
        val ff = folderFilter
        sectionLabel.text = when {
            tab == 1 -> "Folders  •  ${folders.size}"
            ff != null -> "📁 $ff  •  ${shown.size}     ✕"
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

    private fun navItem(icon: String, label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = "$icon\n$label"
            textSize = 12f
            setTextColor(grayText)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }

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

        layoutBtn = iconBtn("☰") {
            listMode = !listMode
            grid.numColumns = columns()
            grid.adapter = adapter
            updateEmpty()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(10), dp(8))
            addView(titles)
            addView(iconBtn("🔍") { toggleSearch() })
            addView(layoutBtn)
        }

        searchBar = EditText(this).apply {
            hint = "Search videos"
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1C1C24"))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply {
                setMargins(dp(20), 0, dp(20), dp(8))
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
            setTextColor(grayText)
            setPadding(dp(22), dp(8), dp(20), dp(10))
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
            verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(20), dp(4), dp(20), dp(20))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val v = shown[pos]
                play(v.uri, v.name.substringBeforeLast('.'))
            }
        }

        foldersGrid = GridView(this).apply {
            numColumns = gridCols()
            horizontalSpacing = dp(14)
            verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(20), dp(4), dp(20), dp(20))
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
            textSize = 17f
            setTextColor(grayText)
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
        body.addView(foldersGrid, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))

        tabVideos = navItem("🎬", "Videos") { selectTab(0) }
        tabFolders = navItem("📁", "Folders") { selectTab(1) }
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(navBg)
            addView(tabVideos)
            addView(tabFolders)
            addView(navItem("➕", "Open") { openMenu() })
            addView(navItem("⚙", "Settings") { openSettings() })
        }

        home.addView(header)
        home.addView(searchBar)
        home.addView(sectionLabel)
        home.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        home.addView(bottomNav)
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
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
            h.title.text = "📁 " + f.name
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
        val sortNames = arrayOf("Newest first", "Name (A-Z)", "Longest first")

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

        settingsList.addView(sectionTitle("SEEK BAR"))
        settingsList.addView(
            switchRow("Video Seek Preview", "Show a thumbnail while you drag the seek bar", previewOn) {
                previewOn = it
            }
        )

        settingsList.addView(sectionTitle("LIBRARY"))
        settingsList.addView(settingRow("Sort videos by", sortNames[sortMode.coerceIn(0, 2)]) {
            AlertDialog.Builder(this)
                .setTitle("Sort videos by")
                .setSingleChoiceItems(sortNames, sortMode.coerceIn(0, 2)) { d, w ->
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
            val e = prefs.edit()
            for (k in prefs.all.keys) if (k.startsWith("p:")) e.remove(k)
            e.apply()
            adapter.notifyDataSetChanged()
            toast("Cleared")
        })

        settingsList.addView(sectionTitle("APPEARANCE"))
        settingsList.addView(
            switchRow("Show name on video", "Zeko Player • Zakariya", watermarkOn) { watermarkOn = it }
        )

        settingsList.addView(sectionTitle("ABOUT"))
        settingsList.addView(settingRow("Zeko Player 1.0", "Made by Zakariya") { })

        home.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
    }

    private fun closeSettings() {
        settingsPage.visibility = View.GONE
        home.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    // ---------- Player screen ----------
    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#40FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun circleBtn(label: String, size: Int, textSp: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = textSp
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.parseColor("#66000000"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
            setOnClickListener { onClick() }
        }

    private fun buildPlayer() {
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }

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
            textSize = 13f
            setTextColor(Color.WHITE)
            alpha = 0.55f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        // ----- top row: back, title, tools -----
        titleTv = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        speedBtn = chip("1x") { chooseSpeed(); scheduleHide() }
        ratioBtn = chip("Fit") { cycleRatio(); scheduleHide() }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(16), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "←"
                textSize = 28f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(2), dp(16), dp(2))
                setOnClickListener { stopAndGoHome() }
            })
            addView(titleTv)
            addView(chip("Audio") { pickTrack(C.TRACK_TYPE_AUDIO, "Audio track") })
            addView(chip("Subs") { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles") })
            addView(speedBtn)
            addView(chip("PiP") { enterPip() })
            addView(ratioBtn)
        }

        // ----- center: rewind / play / forward -----
        playBtn = circleBtn("❚❚", 84, 28f) { togglePlay() }
        val centerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(circleBtn("⏪", 60, 22f) { seekBy(-1); scheduleHide() })
            addView(playBtn)
            addView(circleBtn("⏩", 60, 22f) { seekBy(1); scheduleHide() })
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
            addView(chip("🔒  Lock") { lockScreen() })
            addView(chip("🔄  Rotate") { cycleOrientation(); scheduleHide() })
        }
        val bottomCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(18))
            addView(timeRow)
            addView(actionRow)
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
            addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(centerRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(bottomCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        lockOverlay = TextView(this).apply {
            text = "🔓  Unlock"
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
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(70)
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

    private fun lockScreen() {
        locked = true
        hideControls()
        showHud("🔒  Locked")
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
        showHud("☀  ${(brightness * 100).roundToInt()}%")
    }

    private fun adjustVolume(delta: Float) {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volLevel = (volLevel + delta * 1.3f).coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (volLevel * max).roundToInt(), 0)
        showHud("🔊  ${(volLevel * 100).roundToInt()}%")
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
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = arrayOf("0.5x", "0.75x", "1x (normal)", "1.25x", "1.5x", "2x")
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, values.indexOfFirst { it == speed }.coerceAtLeast(0)) { d, w ->
                speed = values[w]
                p.setPlaybackSpeed(speed)
                speedBtn.text = if (speed == 1f) "1x" else "${speed}x"
                d.dismiss()
            }
            .show()
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
    }

    private fun play(uri: Uri, title: String? = null) {
        savePosition()
        player?.release()
        currentUri = uri

        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        ratioIdx = 0
        ratioBtn.text = "Fit"
        speed = 1f
        speedBtn.text = "1x"

        val start = if (rememberPos) prefs.getLong("p:" + uri, 0L) else 0L
        p.setMediaItem(MediaItem.fromUri(uri), start)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefs.edit().remove("p:" + uri).apply()
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
                playBtn.text = if (isPlaying) "❚❚" else "▶"
                if (isPlaying) scheduleHide() else ui.removeCallbacks(hideRunnable)
            }
        })
        p.prepare()
        p.playWhenReady = true
        if (start > 0L) toast("Resumed from " + formatTime(start))
        locked = false
        lockOverlay.visibility = View.GONE
        playBtn.text = "❚❚"
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

    private fun stopAndGoHome() {
        savePosition()
        stripGen++
        previewPlayer?.release()
        previewPlayer = null
        player?.release()
        player = null
        currentUri = null
        playerView.player = null
        previewBox.visibility = View.GONE
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
        super.onDestroy()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideRunnable)
        stopBgService()
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
