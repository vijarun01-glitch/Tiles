package com.matte.launcher

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.TextClock
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class App(val label: String, val component: ComponentName, val user: UserHandle, val icon: Drawable) {
    internal val matteArt by lazy(LazyThreadSafetyMode.NONE) { MatteAppArt.forApp(icon) }
}

/** Legacy tiles auto-pack; edited tiles retain an exact column and row. */
class Item(var type: String, var key: String, var sz: String, var wid: Int,
           var column: Int = 0, var fixedColumn: Boolean = false, var row: Int? = null,
           var styleColor: Int? = null, var styleImage: String? = null) {
    val transitionName = "tile-${View.generateViewId()}"
}

private class LiveFolder(val id: String, var name: String, val apps: MutableList<Item> = ArrayList())

internal class Slot(
    val id: String, val icon: Int, val label: String, val tone: TileTone,
    val pkgs: List<String>, val intents: List<Intent>, val keys: List<String>
)

class TileHost(c: Context) : FrameLayout(c) {
    var face: View? = null
    var liveHeader: View? = null
    var badgeInk: Int = Color.WHITE
    var draggableItem: Item? = null
    var flipFront: FrameLayout? = null
    var flipBack: View? = null
    var showingActions = false
}

/** Round button shown on a tile in resize mode. kind 0 = unpin (x), 1 = arrow rotated by rot degrees. */
internal class EditBtn(c: Context, private val u: Float, private val kind: Int, private val rot: Float,
                      private val colors: TileColors) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.FILL
        p.color = colors.background
        c.drawCircle(r, r, r, p)
        p.color = colors.foreground
        p.style = Paint.Style.STROKE
        p.strokeWidth = u * 1.7f
        p.strokeCap = Paint.Cap.ROUND
        val a = r * .42f
        c.save()
        c.rotate(rot, r, r)
        if (kind == 0) {
            c.drawLine(r - a, r - a, r + a, r + a, p)
            c.drawLine(r - a, r + a, r + a, r - a, p)
        } else {
            c.drawLine(r - a, r, r + a, r, p)
            c.drawLine(r + a, r, r + a * .25f, r - a * .75f, p)
            c.drawLine(r + a, r, r + a * .25f, r + a * .75f, p)
        }
        c.restore()
    }
}

/** The flat matte face of a tile, drawn exactly like the HTML sample (unit u = screen width / 390). */
class TileView(
    c: Context, private val u: Float, private val kind: Int, iconRes: Int,
    private val label: String?, bg: Int, private val fg: Int
) : View(c) {
    companion object { const val ICON = 0; const val LETTER = 1; const val GLANCE = 2; const val WX = 3 }

    init { setBackgroundColor(bg) }

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ic: Drawable? = if (iconRes != 0) c.getDrawable(iconRes)?.mutate() else null
    private val cloud: Drawable? =
        if (kind == GLANCE || kind == WX) c.getDrawable(R.drawable.t_cloud)?.mutate() else null
    var glyph: Drawable? = null
    var compactIcon = false
    var glyphScale = 2.2f
    val isAppIcon get() = kind == ICON || kind == LETTER
    var badgeSpace = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var iconMotion = 0f
        set(value) {
            field = value
            invalidate()
        }
    var alt = false
    var back: List<Pair<String, Boolean>> = emptyList()
    var l1 = ""
    var l2 = ""
    var l3 = ""

    private fun icon(c: Canvas, d: Drawable?, cx: Float, cy: Float, s: Float) {
        if (d == null) return
        d.setTint(fg)
        d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        d.draw(c)
    }

    private class Fitted(var src: String, var size: Float, var ls: Float, var maxW: Float, var out: String)
    private val fitted = HashMap<Int, Fitted>()
    private val labelUpper = label?.uppercase()

    /** Ellipsizing measures text, so it is redone only when the text, size or width changes. */
    private fun text(c: Canvas, id: Int, s: String, x: Float, y: Float, size: Float, ls: Float, a: Int, maxW: Float) {
        p.color = fg
        p.alpha = a
        p.textSize = size
        p.letterSpacing = ls
        p.textAlign = Paint.Align.LEFT
        val hit = fitted[id]
        val out = if (hit != null && hit.src == s && hit.size == size && hit.ls == ls && hit.maxW == maxW) hit.out
        else TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString().also {
            if (hit == null) fitted[id] = Fitted(s, size, ls, maxW, it)
            else { hit.src = s; hit.size = size; hit.ls = ls; hit.maxW = maxW; hit.out = it }
        }
        c.drawText(out, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.typeface = Typeface.MONOSPACE
        if (alt && back.isNotEmpty()) {
            var total = 0f
            for (b in back) total += if (b.second) u * 27.5f else u * 17f
            var y = (h - total) / 2
            for ((bi, b) in back.withIndex()) {
                val lh = if (b.second) u * 27.5f else u * 17f
                text(c, 10 + bi, b.first, u * 14, y + lh * .78f, if (b.second) u * 22 else u * 13, 0f,
                    if (b.second) 255 else 217, w - u * 28)
                y += lh
            }
        } else {
            val base = min(w, h)
            val isz = if (base > u * 120) u * 46 else min(u * 34, base * .5f)
            // Keep even oversized monochrome artwork inside its own area above the label.
            val iconBottom = if (label != null && isAppIcon) (h - u * 28).coerceAtLeast(u * 8) else h
            val iconY = if (isAppIcon) iconBottom / 2 + u * 3 - u * 3 * iconMotion else h / 2
            val contentLimit = min((w - u * 18).coerceAtLeast(1f), (iconBottom - u * 10).coerceAtLeast(1f))
            val availableIcon = if (compactIcon) min(contentLimit, u * 54) else contentLimit
            val iconSize = min(isz * (1f + .08f * iconMotion), availableIcon * .78f)
            val iconCanvas = if (isAppIcon && iconMotion != 0f) c.save() else -1
            if (iconCanvas != -1) c.scale(1f - .24f * iconMotion, 1f, w / 2, iconY)
            when (kind) {
                ICON -> icon(c, ic, w / 2, iconY, iconSize)
                LETTER -> {
                    if (glyph != null) icon(c, glyph, w / 2, iconY, min(iconSize * glyphScale, availableIcon * .88f))
                    else {
                        p.color = fg; p.alpha = 255; p.textSize = iconSize; p.letterSpacing = 0f
                        p.textAlign = Paint.Align.CENTER
                        c.drawText(l1, w / 2, iconY + iconSize * .35f, p)
                    }
                }
                GLANCE -> {
                    text(c, 1, l1, u * 18, h / 2 - u * 1, u * 24, -.01f, 255, w - u * 80)
                    text(c, 2, l2.uppercase(), u * 18, h / 2 + u * 17, u * 11, .14f, 204, w - u * 80)
                    icon(c, cloud, w - u * 37, h / 2, u * 38)
                }
                WX -> {
                    text(c, 3, l1, u * 16, h / 2 + u * 3, u * 68, -.04f, 255, w - u * 24)
                    text(c, 4, l2, u * 16, h / 2 + u * 33, u * 17, 0f, 255, w - u * 32)
                    text(c, 5, l3.uppercase(), u * 16, h / 2 + u * 51, u * 11, .12f, 204, w - u * 32)
                    icon(c, cloud, w - u * 31, u * 31, u * 34)
                }
            }
            if (iconCanvas != -1) c.restoreToCount(iconCanvas)
        }
        if (labelUpper != null) text(c, 0, labelUpper, u * 9, h - u * 9, u * 9.5f, .14f, 255,
            (w - u * 14 - badgeSpace).coerceAtLeast(0f))
    }
}

class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_SOLID_COLOR = 0xFF62666E.toInt()
        private val APP_TONES = listOf(TileTone.PAPER, TileTone.CHARCOAL, TileTone.DARK,
            TileTone.SECONDARY, TileTone.CHARCOAL, TileTone.SECONDARY)
        val GRID_IDS = listOf("maps", "music", "notes", "mail", "calendar", "files", "calculator",
            "people", "clock", "recorder", "web", "settings")
        val DOCK_IDS = listOf("phone", "messaging", "camera", "photos")
        const val REQ_PICK = 11
        const val REQ_CONF = 12
        const val REQ_LAYOUT_EXPORT = 13
        const val REQ_LAYOUT_IMPORT = 14
        const val REQ_TILE_IMAGE = 15
        private val TILE_IMAGE_NAME = Regex("tile_[0-9a-f-]{36}\\.jpg")
        const val HOST_ID = 1024
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        private const val MAX_TILE_COLUMN = 255
        private const val APP_LAUNCH_TURN_MS = 425
        private const val FOLDER_OPEN_TURN_MS = 320
        private const val DRAWER_OPEN_MS = 440L
        private const val HOME_DURATION_KEY = "return_home_duration_ms"
        private const val IDLE_INTERVAL_MIN_KEY = "idle_tile_min_steps"
        private const val IDLE_INTERVAL_MAX_KEY = "idle_tile_max_steps"
        private const val IDLE_INTERVAL_STEP_MS = 500L
        private const val IDLE_INTERVAL_MAX_STEPS = 120
    }

    private lateinit var la: LauncherApps
    private lateinit var awm: AppWidgetManager
    private lateinit var widgetHost: AppWidgetHost
    private var apps: List<App> = emptyList()
    private var homeComponents: List<ComponentName?> = emptyList()
    private val items = ArrayList<Item>()
    private val folders = linkedMapOf<String, LiveFolder>()
    private lateinit var folderPan: FolderPager
    private lateinit var folderOverlay: FrameLayout
    private lateinit var folderTrack: LinearLayout
    private var folderOpen = false
    private var dragCandidate: TileHost? = null
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var activeDrag: TileDrag? = null
    private var folderEdit: Item? = null
    private var folderPageToShow: Int? = null
    private val folderViews = ArrayList<TileHost>()
    private val folderTilesTops = HashMap<String, Int>()
    private val folderPreviewViews = ArrayList<TileHost>()
    private val folderBadges = ArrayList<HomeBadge>()
    private fun turnViews(): List<View> = if (folderOpen) folderViews else animViews
    private val placedPositions = HashMap<Item, HomeGrid.Position>()
    private var occupiedColumns = 0
    private val slots = LinkedHashMap<String, Slot>()
    private lateinit var wxSlot: Slot

    private lateinit var root: FrameLayout
    private lateinit var home: FrameLayout
    private lateinit var homeTiles: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homePan: HorizontalScrollView
    private lateinit var grid: FrameLayout
    private lateinit var dock: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerScroll: ScrollView
    private lateinit var drawerList: LinearLayout
    private lateinit var query: EditText
    private var rows = ArrayList<Pair<App, View>>()
    private var drawerOpen = false
    private var onPick: ((App) -> Unit)? = null
    private var menuView: View? = null

    private val animViews = ArrayList<View>()
    private data class HomeBadge(val packageName: String, val label: TextView, val face: TileView?)
    private val homeBadges = ArrayList<HomeBadge>()
    private val notificationObserver: () -> Unit = { refreshNotificationBadges() }
    private var homeAnimator: ValueAnimator? = null
    private var launchAnimator: ValueAnimator? = null
    private val swayingTiles = LinkedHashSet<TileHost>()
    private var swipeAnimator: ValueAnimator? = null
    private var swipeFingerOnCanvas = false
    private var swipeInProgress = false
    private var flyingTiles: List<TileHost> = emptyList()
    private var directAppHandoff = false
    private val restoreFlight = Runnable {
        if (resumed && hasWindowFocus()) resetFlyingTiles()
    }
    private var started = false
    private var resumed = false
    private lateinit var lockAnimation: LockTurnController
    private var needsEntrance = true
    private var folderEntrancePending = false
    private val homePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (folderOpen) folderPageToShow?.let { x ->
            if (folderPan.width > 0) {
                folderPan.scrollTo(x, 0)
                folderPageToShow = null
            }
        }
        if (folderEntrancePending && folderOpen && folderPageToShow == null && folderPan.width > 0) {
            folderEntrancePending = false
            needsEntrance = false
            val page = folderTrack.getChildAt(folderPan.scrollX / folderPageWidth())
            val title = page?.findViewWithTag<View>("folder-title")
            animateHomeEntrance(FOLDER_OPEN_TURN_MS, title)
        }
        if (needsEntrance && started && home.width > 0 && home.height > 0 && root.windowVisibility == View.VISIBLE) {
            if (locked()) {
                // Hold the tiles in their starting pose behind the lock screen; unlocking plays the turn.
                if (!unlockStaged && homeAnimator == null && !folderOpen && ValueAnimator.areAnimatorsEnabled()) stageForUnlock()
            } else {
                // Consume the return before the first visible draw. Focus may arrive much later.
                needsEntrance = false
                if (!touching && drawer.visibility != View.VISIBLE && menuView == null && editItem == null && folderEdit == null) {
                    animateHomeEntrance()
                }
            }
        }
        true
    }
    private var editItem: Item? = null
    private var glanceT: TileView? = null
    private var wxT: TileView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val armFolderDrag = Runnable {
        val candidate = dragCandidate
        val item = candidate?.draggableItem
        if (folderOpen && touching && candidate != null && item != null && candidate.isAttachedToWindow && menuView == null) {
            candidate.cancelLongPress()
            candidate.parent?.requestDisallowInterceptTouchEvent(true)
            if (startTileDrag(candidate, item)) {
                dragCandidate = null
                swallow = true
                val now = android.os.SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        }
    }
    private val settleSwipe = Runnable { settleSwipeTiles() }
    private val idleTick = Runnable { animateIdleTile() }
    private val largeFlipAnimators = HashMap<TileHost, ValueAnimator>()
    private val largeFlipTick = Runnable { flipLargeTiles() }
    private val idleAnimators = HashMap<TileHost, ValueAnimator>()
    private var lastIdleTile: TileHost? = null
    private var touching = false
    private var pendingWidget = -1
    private var pendingWidgetColumn = 0
    private var pendingWidgetReplacement: Item? = null
    private val alarmLabels = ArrayList<TextView>()
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshAlarmLabels()
    }

    private var wxTemp: Int? = null
    private var wxDesc: String? = null
    private var wxHL: String? = null
    private var wxBack: List<Pair<String, Boolean>> = emptyList()
    private var evTitle: String? = null
    private var evSub: String? = null
    private var eventRequest = 0

    private var downX = 0f
    private var downY = 0f
    private var swallow = false

    private val prefs by lazy { getSharedPreferences("matte", MODE_PRIVATE) }
    private var launcherTheme = LauncherTheme.MATTE
    private var solidTiles: TileColors? = null
    private var customBackground: Int? = null
    private var useWallpaper = true
    private var customTiles = false
    private var iconPack = true
    private val tileBitmaps = HashMap<String, Bitmap?>()
    private var pendingStyleItem: Item? = null
    private var unlockStaged = false
    private var multiPick: ((List<App>) -> Unit)? = null
    private var generalSelect = false
    private val selectedApps = LinkedHashMap<String, App>()
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var selectionAdd: TextView
    private lateinit var selectionHome: TextView
    private lateinit var selectionFolder: TextView
    private val colors get() = if (solidTiles == null) launcherTheme.colors else LauncherTheme.GRAY.colors
    private fun tileColors(tone: TileTone) = solidTiles ?: colors[tone]
    private fun homeBackground() = customBackground ?: colors.background
    private fun rootColor() = if (useWallpaper) Color.TRANSPARENT else homeBackground()
    private fun folderOverlayColor() = if (useWallpaper) 0xB3000000.toInt() else homeBackground() or Color.BLACK
    private fun drawerColor() = if (useWallpaper) (colors.background and 0xFFFFFF) or 0xEE000000.toInt() else colors.background
    private fun homeInk() = if (useWallpaper) Color.WHITE else TileColors.solid(homeBackground() or Color.BLACK).foreground
    private var u = 1f
    private var tile = 0
    private var gap = 0
    private fun px(v: Float) = (v * u).toInt()

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherTheme = LauncherTheme.fromPreference(prefs.getString("color_theme", null))
        if (prefs.contains("home_background")) customBackground = prefs.getInt("home_background", colors.background)
        useWallpaper = prefs.getBoolean("use_wallpaper", !prefs.contains("home_background"))
        customTiles = prefs.getBoolean("custom_tiles", false)
        iconPack = prefs.getBoolean("icon_pack", true)
        if (prefs.getBoolean("solid_mode", false)) {
            solidTiles = TileColors.solid(prefs.getInt("solid_color", DEFAULT_SOLID_COLOR))
        }
        u = resources.displayMetrics.widthPixels / 390f
        gap = px(6f)
        tile = (resources.displayMetrics.widthPixels - px(24f) - gap * 3) / 4
        la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        awm = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, HOST_ID)
        defineSlots()
        try {
            folders.putAll(readFolders(JSONArray(prefs.getString("folders", "[]") ?: "[]")))
        } catch (_: Exception) { }
        loadItems()
        buildUi()
        root.viewTreeObserver.addOnPreDrawListener(homePreDrawListener)
        reload()
        lockAnimation = LockTurnController(this,
            tiles = { turnViews().filterIsInstance<TileHost>() },
            enabled = { prefs.getBoolean("lock_depth", true) },
            buttonHeight = { lockButtonHeightPercent() / 100f },
            canvasWidth = { home.width },
            prepare = {
                clearHomeMotion()
                clearSwipeMotion()
                cancelLaunchMotion()
                stopLive()
            },
            restored = { if (resumed && hasWindowFocus()) startLive() },
            wake = { unlocked -> onDeviceWake(unlocked) })
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) = post(setOfNotNull(p))
            override fun onPackageAdded(p: String?, u: UserHandle?) = post(setOfNotNull(p))
            override fun onPackageChanged(p: String?, u: UserHandle?) = post(setOfNotNull(p))
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = post(p.orEmpty().toSet())
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = post(p.orEmpty().toSet())
            private fun post(packages: Set<String>) = runOnUiThread { reload(packages) }
        })
        if (!prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CALENDAR), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadWeather()
        loadEvent()
    }

    private fun locked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (e: Exception) { false }

    override fun onStart() {
        super.onStart()
        started = true
        TileNotificationCounts.observe(notificationObserver)
        refreshNotificationBadges()
        root.invalidate()
        try { widgetHost.startListening() } catch (e: Exception) { }
        val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(alarmReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(alarmReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        if (::lockAnimation.isInitialized) lockAnimation.onResumed()
        cancelLaunchMotion()
        resumed = true
        loadWeather()
        loadEvent()
        if (folderOpen) rebuildFolderPages()
        applyData()
        refreshAlarmLabels()
        if (useWallpaper) applyHomeBackground()
        root.invalidate()
        startLive()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(armFolderDrag)
        folderPan.stopPaging()
        touching = false
        dragCandidate = null
        activeDrag?.let { resetDragPreview(it, true) }
        activeDrag = null
        clearSwipeMotion()
        cancelLaunchMotion(keepFinishedFlight = true)
        TransitionManager.endTransitions(grid)
        stopLive()
        if (::lockAnimation.isInitialized) lockAnimation.onPaused()
    }

    override fun onStop() {
        super.onStop()
        started = false
        TileNotificationCounts.remove(notificationObserver)
        needsEntrance = true
        clearHomeMotion()
        cancelLaunchMotion()
        unregisterReceiver(alarmReceiver)
        try { widgetHost.stopListening() } catch (e: Exception) { }
        if (drawerOpen || drawer.visibility == View.VISIBLE) closeDrawer(immediate = true)
        closeMenu()
        if (editItem != null) { editItem = null; rebuildHome() }
        if (folderEdit != null) { folderEdit = null; rebuildFolderPages() }
    }

    override fun onDestroy() {
        if (::lockAnimation.isInitialized) lockAnimation.dispose()
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(homePreDrawListener)
        clearHomeMotion()
        cancelLaunchMotion()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestSmoothDisplay()
            if (launchAnimator == null) resetFlyingTiles()
        } else {
            clearSwipeMotion()
            stopIconMotion()
            cancelLaunchMotion(keepFinishedFlight = true)
        }
        if (::lockAnimation.isInitialized) lockAnimation.onFocusChanged(hasFocus)
    }

    @Suppress("DEPRECATION")
    private fun requestSmoothDisplay() {
        val display = window.decorView.display ?: return
        val current = display.mode
        // Fastest mode at the current resolution, so the panel never switches resolution to gain Hz.
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate in 60f..120.5f }
            .maxByOrNull { it.refreshRate } ?: return
        val params = window.attributes
        if (params.preferredDisplayModeId != best.modeId || params.preferredRefreshRate != best.refreshRate) {
            params.preferredDisplayModeId = best.modeId
            params.preferredRefreshRate = best.refreshRate
            window.attributes = params
        }
        if (Build.VERSION.SDK_INT >= 35) {
            root.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    /** Rasterize tiles once while they rotate, instead of redrawing them every frame. */
    private fun setTileLayers(tiles: Collection<View>, hardware: Boolean) {
        val type = if (hardware) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        for (tile in tiles) if (tile.layerType != type) tile.setLayerType(type, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (launchAnimator != null) cancelLaunchMotion()
        else if (menuView != null) closeMenu()
        else if (drawerOpen) closeDrawer()
        else if (folderEdit != null) { folderEdit = null; rebuildFolderPages() }
        else if (folderOpen) closeFolder()
        else if (editItem != null) { editItem = null; rebuildHome() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        cancelLaunchMotion()
        closeMenu()
        val wasDrawer = drawerOpen || drawer.visibility == View.VISIBLE
        if (wasDrawer) closeDrawer(immediate = true)
        if (wasDrawer && started && intent?.hasCategory(Intent.CATEGORY_HOME) == true) needsEntrance = true
        if (folderOpen && intent?.hasCategory(Intent.CATEGORY_HOME) == true) closeFolder()
        // Home can receive another intent while already visible. Keep any entrance in progress.
        if (homeAnimator == null) homeScroll.scrollTo(0, 0)
        root.invalidate()
    }

    /** Home: swipe up = app search with keyboard, swipe down = system notification shade. In search: swipe down closes. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(armFolderDrag)
                if (folderOpen) clearHomeMotion()
                if (::lockAnimation.isInitialized && lockAnimation.active) { lockAnimation.reset(); startLive() }
                cancelLaunchMotion()
                clearSwipeMotion()
                val bounds = Rect()
                swipeFingerOnCanvas = !folderOpen && !drawerOpen && menuView == null && editItem == null &&
                    homePan.getGlobalVisibleRect(bounds) && bounds.contains(e.rawX.toInt(), e.rawY.toInt())
                touching = true
                stopIconMotion()
                scheduleIdleTick()
                downX = e.x
                downY = e.y
                dragDownRawX = e.rawX
                dragDownRawY = e.rawY
                swallow = false
                dragCandidate = if (!customTiles && !drawerOpen && menuView == null && (folderOpen || editItem == null)) {
                    turnViews().filterIsInstance<TileHost>().lastOrNull { host ->
                        val item = host.draggableItem
                        item != null && item.type != "widget" && host.getGlobalVisibleRect(bounds) &&
                            bounds.contains(e.rawX.toInt(), e.rawY.toInt())
                    }
                } else null
                if (folderOpen && dragCandidate != null) handler.postDelayed(armFolderDrag,
                    ViewConfiguration.getLongPressTimeout() * 3L / 4L)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(armFolderDrag)
                touching = false
                swipeFingerOnCanvas = false
                dragCandidate = null
            }
            MotionEvent.ACTION_MOVE -> if (!swallow) {
                val candidate = dragCandidate
                val dragSlop = ViewConfiguration.get(this).scaledTouchSlop
                if (candidate != null && max(abs(e.x - downX), abs(e.y - downY)) > dragSlop) {
                    handler.removeCallbacks(armFolderDrag)
                    dragCandidate = null
                    val item = candidate.draggableItem
                    if (item != null && e.eventTime - e.downTime >= ViewConfiguration.getLongPressTimeout() * 3L / 4L &&
                        startTileDrag(candidate, item)) {
                        closeMenu()
                        clearHomeMotion()
                        clearSwipeMotion()
                        swallow = true
                        val cancel = MotionEvent.obtain(e)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }
                if (menuView != null) return super.dispatchTouchEvent(e)
                val dy = e.y - downY
                val vertical = abs(dy) > abs(e.x - downX) * 1.5f
                val up = !folderOpen && !drawerOpen && editItem == null && vertical && dy < -u * 70 &&
                    (!swipeFingerOnCanvas || !homeScroll.canScrollVertically(1))
                val down = drawerOpen && vertical && dy > u * 90 && !drawerScroll.canScrollVertically(-1)
                val shade = !folderOpen && !drawerOpen && vertical && dy > u * 70 && !homeScroll.canScrollVertically(-1)
                if (shade) {
                    swallow = true
                    val c = MotionEvent.obtain(e)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                    expandShade()
                    return true
                }
                if (up || down) {
                    swallow = true
                    val c = MotionEvent.obtain(e)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                    if (up) openDrawer(null, null) else closeDrawer()
                    return true
                }
            }
        }
        if (swallow) return true
        return super.dispatchTouchEvent(e)
    }

    // ------------------------------------------------------------ data

    private fun mainCat(cat: String) = Intent(Intent.ACTION_MAIN).addCategory(cat)

    private fun defineSlots() {
        fun add(id: String, icon: Int, label: String, tone: TileTone, pkgs: List<String>, intents: List<Intent>, keys: List<String>) {
            slots[id] = Slot(id, icon, label, tone, pkgs, intents, keys)
        }
        add("maps", R.drawable.t_map, "maps", TileTone.CHARCOAL, listOf("com.google.android.apps.maps"),
            listOf(mainCat(Intent.CATEGORY_APP_MAPS), Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))), listOf("maps"))
        add("music", R.drawable.t_music, "music", TileTone.PAPER, listOf("com.google.android.apps.youtube.music", "com.spotify.music"),
            listOf(mainCat(Intent.CATEGORY_APP_MUSIC)), listOf("music"))
        add("notes", R.drawable.t_note, "notes", TileTone.SECONDARY, listOf("com.google.android.keep"), emptyList(), listOf("keep", "note"))
        add("mail", R.drawable.t_mail, "mail", TileTone.PAPER, listOf("com.google.android.gm"),
            listOf(mainCat(Intent.CATEGORY_APP_EMAIL)), listOf("mail"))
        add("calendar", R.drawable.t_cal, "calendar", TileTone.PAPER, listOf("com.google.android.calendar"),
            listOf(mainCat(Intent.CATEGORY_APP_CALENDAR)), listOf("calendar"))
        add("files", R.drawable.t_folder, "files", TileTone.SECONDARY, listOf("com.google.android.apps.nbu.files", "com.google.android.documentsui"),
            emptyList(), listOf("files"))
        add("calculator", R.drawable.t_calc, "calc", TileTone.DARK, listOf("com.google.android.calculator"),
            listOf(mainCat(Intent.CATEGORY_APP_CALCULATOR)), listOf("calc"))
        add("people", R.drawable.t_people, "people", TileTone.CHARCOAL, listOf("com.google.android.contacts"),
            listOf(mainCat(Intent.CATEGORY_APP_CONTACTS)), listOf("contacts"))
        add("clock", R.drawable.t_clock, "clock", TileTone.PAPER, listOf("com.google.android.deskclock"),
            listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS)), listOf("clock"))
        add("recorder", R.drawable.t_mic, "voice", TileTone.SIGNAL, listOf("com.google.android.apps.recorder"), emptyList(), listOf("record"))
        add("web", R.drawable.t_globe, "web", TileTone.SECONDARY, emptyList(),
            listOf(mainCat(Intent.CATEGORY_APP_BROWSER), Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))), listOf("chrome", "browser"))
        add("settings", R.drawable.t_gear, "settings", TileTone.DARK, listOf("com.android.settings"),
            listOf(Intent(Settings.ACTION_SETTINGS)), listOf("settings"))
        add("phone", R.drawable.t_phone, "", TileTone.PAPER, listOf("com.google.android.dialer"),
            listOf(Intent(Intent.ACTION_DIAL)), listOf("phone"))
        add("messaging", R.drawable.t_chat, "", TileTone.CHARCOAL, listOf("com.google.android.apps.messaging"),
            listOf(mainCat(Intent.CATEGORY_APP_MESSAGING)), listOf("messag"))
        add("camera", R.drawable.t_camera, "", TileTone.DARK, listOf("com.google.android.GoogleCamera"),
            listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), listOf("camera"))
        add("photos", R.drawable.t_image, "", TileTone.SECONDARY, listOf("com.google.android.apps.photos"),
            listOf(mainCat(Intent.CATEGORY_APP_GALLERY)), listOf("photos", "gallery"))
        wxSlot = Slot("wx", 0, "weather", TileTone.ACCENT, listOf("com.google.android.apps.weather"), emptyList(), listOf("weather"))
    }

    private fun resolve(s: Slot): App? {
        val saved = prefs.getString("slot_" + s.id, null)
        if (saved != null) apps.firstOrNull { it.component.flattenToString() == saved }?.let { return it }
        for (pk in s.pkgs) apps.firstOrNull { it.component.packageName == pk }?.let { return it }
        for (i in s.intents) {
            val found = try { packageManager.queryIntentActivities(i, 0) } catch (e: Exception) { emptyList() }
            for (ri in found) {
                val pk = ri.activityInfo.packageName
                apps.firstOrNull { it.component.packageName == pk }?.let { return it }
            }
        }
        for (k in s.keys) apps.firstOrNull { it.label.lowercase().contains(k) }?.let { return it }
        return null
    }

    private fun loadItems() {
        items.clear()
        prefs.getString("items", null)?.let { saved ->
            try {
                items.addAll(readItems(JSONArray(saved)).filter { it.type != "folder" || it.key in folders })
                return
            } catch (_: Exception) { }
        }
        items.add(Item("glance", "", "g", -1))
        items.add(Item("wx", "", "m", -1))
        for (id in GRID_IDS) items.add(Item("slot", id, "s", -1))
        for (id in folders.keys) items.add(Item("folder", id, "m", -1))
    }

    private fun readItems(data: JSONArray): List<Item> = List(data.length()) { index ->
        val value = data.getJSONObject(index)
        val legacyColumn = value.optInt("page", 0).coerceIn(0, 63) * HomeGrid.COLUMNS_PER_VIEWPORT
        Item(value.getString("t"), value.optString("k"), value.getString("s"), value.optInt("w", -1),
            value.optInt("column", legacyColumn).coerceIn(0, MAX_TILE_COLUMN),
            value.optBoolean("fixedColumn", false),
            if (value.has("row")) value.getInt("row").coerceIn(0, 10000) else null,
            if (value.has("tc")) value.getInt("tc") else null,
            value.optString("ti").takeIf { TILE_IMAGE_NAME.matches(it) })
    }

    private fun writeItems(tiles: List<Item>): JSONArray = JSONArray().apply {
        for (item in tiles) put(JSONObject().put("t", item.type).put("k", item.key).put("s", item.sz)
            .put("w", item.wid).put("column", item.column).put("fixedColumn", item.fixedColumn)
            .apply {
                item.row?.let { put("row", it) }
                item.styleColor?.let { put("tc", it) }
                item.styleImage?.let { put("ti", it) }
            })
    }

    private fun readFolders(data: JSONArray): Map<String, LiveFolder> = linkedMapOf<String, LiveFolder>().apply {
        for (index in 0 until data.length()) {
            val value = data.getJSONObject(index)
            val id = value.getString("id")
            val name = value.getString("name").trim()
            require(id.isNotEmpty() && id !in this && name.isNotEmpty() && name.length <= 60)
            val members = readItems(value.getJSONArray("apps"))
            require(members.all { it.type == "app" && ComponentName.unflattenFromString(it.key) != null } &&
                members.distinctBy { it.key }.size == members.size)
            put(id, LiveFolder(id, name, members.toMutableList()))
        }
    }

    private fun writeFolders(): JSONArray = JSONArray().apply {
        for (folder in folders.values) put(JSONObject().put("id", folder.id).put("name", folder.name)
            .put("apps", writeItems(folder.apps)))
    }

    private fun saveItems(editor: android.content.SharedPreferences.Editor = prefs.edit()) {
        editor.putString("items", writeItems(items).toString())
            .putString("folders", writeFolders().toString()).apply()
    }

    private fun reload(changedPackages: Set<String>? = null) {
        apps = la.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != packageName }
            .map { App(it.label.toString(), it.componentName, it.user, it.getIcon(0)) }
            .sortedBy { it.label.lowercase() }
        val bindings = resolveHomeComponents()
        if (changedPackages == null || bindings != homeComponents ||
            bindings.any { it != null && it.packageName in changedPackages } ||
            folders.values.any { folder -> folder.apps.any { item ->
                ComponentName.unflattenFromString(item.key)?.let { it.packageName in changedPackages } == true
            } }) {
            rebuildHome(bindings)
        }
        rebuildDrawer()
    }

    private fun resolveHomeComponents(): List<ComponentName?> = buildList {
        for (item in items) {
            add(when (item.type) {
                "glance" -> resolve(slots.getValue("calendar"))?.component
                "wx" -> resolve(wxSlot)?.component
                "slot" -> resolve(slots[item.key] ?: wxSlot)?.component
                "widget" -> if (item.wid >= 0) awm.getAppWidgetInfo(item.wid)?.provider else null
                "folder" -> null
                else -> apps.firstOrNull { it.component.flattenToString() == item.key }?.component
            })
        }
        for (id in DOCK_IDS) add(resolve(slots.getValue(id))?.component)
    }

    // ------------------------------------------------------------ ui

    private fun label(t: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        typeface = Typeface.MONOSPACE
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(rootColor())
            setOnApplyWindowInsetsListener { v, i ->
                if (Build.VERSION.SDK_INT >= 35) {
                    val bars = i.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(0, bars.top, 0, bars.bottom)
                    if (::drawer.isInitialized) {
                        val keyboard = i.getInsets(WindowInsets.Type.ime())
                        drawer.setPadding(px(12f), px(10f), px(12f), max(0, keyboard.bottom - bars.bottom))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom)
                }
                i
            }
        }
        setContentView(root)
        applyHomeBackground()

        home = FrameLayout(this)
        homeTiles = FrameLayout(this)
        home.addView(homeTiles, FrameLayout.LayoutParams(-1, -1))
        val dockBottomMargin = px(12f)
        grid = FrameLayout(this).apply {
            setOnLongClickListener { if (editItem == null) homeMenu(); true }
            setOnClickListener { if (editItem != null) { editItem = null; rebuildHome() } }
        }
        enableTileDrop(grid, px(12f), px(10f), tile + gap) { item, column, row ->
            val index = items.indexOf(item)
            if (index >= 0) {
                val target = HomeGrid.Position(column.coerceIn(0, MAX_TILE_COLUMN), row)
                applyPositions(HomeGrid.dragPlacement(layoutRequests(), index, target, items.map { placedPositions.getValue(it) }))
                saveItems()
                rebuildHome()
                revealTile(item)
            }
        }
        homeScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }
        homePan = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(homeScroll, FrameLayout.LayoutParams(pageWidth(), -1))
            setOnScrollChangeListener { _, x, _, previousX, _ -> swayOnSwipe(x, x - previousX) }
        }
        homeTiles.addView(homePan, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = tile + gap + dockBottomMargin })

        dock = FrameLayout(this)
        homeTiles.addView(dock, FrameLayout.LayoutParams(-1, tile, Gravity.BOTTOM).apply { bottomMargin = dockBottomMargin })

        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        buildFolderPanorama()

        // app search
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(drawerColor())
            setPadding(px(12f), px(10f), px(12f), 0)
            visibility = View.INVISIBLE
            isClickable = true
            // Holds focus so the search field (and keyboard) only activate when tapped.
            isFocusableInTouchMode = true
        }
        query = EditText(this).apply {
            setHintTextColor(colors.mutedText); setTextColor(colors.text); typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 15)
            setBackgroundColor(colors[TileTone.CHARCOAL].background); setSingleLine(); setPadding(px(14f), 0, px(14f), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filter() }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, action, key ->
                val submit = if (key == null) action == EditorInfo.IME_ACTION_GO
                    else key.keyCode == KeyEvent.KEYCODE_ENTER && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0
                if (submit && drawerOpen && hasFocus() && resumed && text.isNotBlank()) {
                    rows.firstOrNull { it.second.visibility == View.VISIBLE }?.second?.performClick()
                }
                true
            }
        }
        drawer.addView(query, LinearLayout.LayoutParams(-1, px(56f)))
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(drawerList, FrameLayout.LayoutParams(-1, -2))
        }
        drawer.addView(drawerScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.panel)
            setPadding(0, px(6f), px(8f), px(6f))
            visibility = View.GONE
        }
        selectionCount = label("", 13f, colors.text).apply {
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine()
        }
        selectionAdd = barButton("Add") { finishSelection(null) }
        selectionHome = barButton("To home") { finishSelection(true) }
        selectionFolder = barButton("To folder") { finishSelection(false) }
        selectionBar.addView(selectionCount, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(12f) })
        for (button in listOf(selectionAdd, selectionHome, selectionFolder, barButton("Cancel") { exitSelection() })) {
            selectionBar.addView(button, LinearLayout.LayoutParams(-2, -1).apply { marginStart = px(6f) })
        }
        drawer.addView(selectionBar, LinearLayout.LayoutParams(-1, px(50f)).apply {
            topMargin = gap
            bottomMargin = px(8f)
        })
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun span(sz: String): Pair<Int, Int> = TileSize.fromKey(sz).let { it.width to it.height }

    private fun layoutRequests(): List<HomeGrid.Request> = items.map {
        val (width, height) = span(it.sz)
        HomeGrid.Request(it.column, width, height, it.fixedColumn, it.row)
    }

    private fun rebuildHome(bindings: List<ComponentName?> = resolveHomeComponents()) {
        homeComponents = bindings
        stopLive()
        clearSwipeMotion()
        clearHomeMotion()
        cancelLaunchMotion()
        grid.removeAllViews()
        dock.removeAllViews()
        animViews.clear()
        folderPreviewViews.clear()
        homeBadges.clear()
        glanceT = null
        wxT = null
        val step = tile + gap
        alarmLabels.clear()
        placedPositions.clear()
        occupiedColumns = 0
        var rowsUsed = 0
        val requests = layoutRequests()
        val positions = HomeGrid.pack(requests)
        for ((index, it) in items.withIndex()) {
            val (col, row) = positions[index]
            val request = requests[index]
            placedPositions[it] = positions[index]
            occupiedColumns = max(occupiedColumns, col + request.width)
            rowsUsed = max(rowsUsed, row + request.height)
            val w = request.width * step - gap
            val h = request.height * step - gap
            val v = itemView(it, w, h)
            (v as? TileHost)?.draggableItem = it
            if (v is TileHost) applyTileImage(v, it)
            v.transitionName = it.transitionName
            if (v is TileHost && it.type != "widget") bindNotificationBadge(v, bindings.getOrNull(index))
            if (editItem != null) editDecor(v, it)
            grid.addView(v, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = px(12f) + col * step
                topMargin = px(10f) + row * step
            })
            animViews.add(v)
        }
        grid.minimumHeight = px(10f) + rowsUsed * step + px(4f)
        val canvasWidth = HomeGrid.width(pageWidth(), px(12f), tile, gap, occupiedColumns)
        homeScroll.minimumWidth = canvasWidth
        grid.minimumWidth = canvasWidth
        homeScroll.layoutParams = homeScroll.layoutParams.apply { width = canvasWidth }

        for ((i, id) in DOCK_IDS.withIndex()) {
            val s = slots[id]!!
            val app = resolve(s)
            val (face, background) = appFace(app, false, s)
            val host = tileHost(face, background)
            bindNotificationBadge(host, app?.component)
            host.setOnClickListener { if (app != null) launch(host, app) else pickFor(s) }
            host.setOnLongClickListener { appMenu(app, null, s, false); true }
            dock.addView(host, FrameLayout.LayoutParams(tile, tile).apply { leftMargin = px(12f) + i * step })
            animViews.add(host)
        }
        applyData()
        refreshAlarmLabels()
        refreshNotificationBadges()
        if (resumed && hasWindowFocus()) startLive()
    }

    private fun styleTile(view: View, background: Int) {
        val rounded = prefs.getBoolean("rounded_corners", false)
        if (rounded) {
            view.background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = u * 11f
            }
        } else {
            view.setBackgroundColor(background)
        }
        view.clipToOutline = rounded
    }

    private fun tileHost(face: View, bg: Int): TileHost {
        val h = TileHost(this)
        styleTile(h, bg)
        h.badgeInk = TileColors.solid(bg).foreground
        h.addView(face, FrameLayout.LayoutParams(-1, -1))
        h.face = face
        h.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.pivotY = v.height / 2f
                    v.animate().setStartDelay(0).rotationX(6f).scaleY(.96f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().setStartDelay(0).rotationX(0f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        return h
    }

    private fun bindNotificationBadge(host: TileHost, component: ComponentName?,
                                      badges: MutableList<HomeBadge> = homeBadges, small: Boolean = false) {
        if (component == null) return
        val badge = label("", if (small) 9f else 18f, host.badgeInk).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setSingleLine()
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        (host.flipFront ?: host).addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = px(if (small) 2f else 9f)
            bottomMargin = px(if (small) 1f else 6f)
        })
        badges.add(HomeBadge(component.packageName, badge, if (small) null else host.face as? TileView))
    }

    private fun refreshNotificationBadges() {
        for ((pkg, badge, face) in homeBadges + folderBadges) {
            val count = TileNotificationCounts.count(pkg)
            val text = when {
                count == 0 -> ""
                count >= 100 -> "99+"
                else -> count.toString()
            }
            if (badge.text.toString() == text) continue
            badge.text = text
            badge.contentDescription = when {
                count == 0 -> null
                count == 1 -> "1 notification"
                else -> "$text notifications"
            }
            badge.visibility = if (count == 0) View.GONE else View.VISIBLE
            face?.badgeSpace = if (count == 0) 0f else badge.paint.measureText(text) + px(8f)
        }
    }

    private fun matteArt(app: App?): MatteAppArt? =
        if (launcherTheme == LauncherTheme.MATTE && solidTiles == null) app?.matteArt else null

    private fun appTileColors(app: App?, slot: Slot? = null): TileColors {
        matteArt(app)?.let { return tileColors(it.tone) }
        if (slot != null) return tileColors(slot.tone)
        val key = app?.component?.packageName ?: "?"
        val index = (abs(key.hashCode().toLong()) % APP_TONES.size).toInt()
        return tileColors(APP_TONES[index])
    }

    private fun appFace(app: App?, withLabel: Boolean, slot: Slot? = null, missingLabel: String = "missing",
                        colorsOverride: TileColors? = null): Pair<TileView, Int> {
        val art = matteArt(app)
        val pack = if (iconPack && app != null) AppIconPack.forApp(this, app.label, app.component.packageName) else null
        val tileColors = colorsOverride ?: appTileColors(app, slot)
        val useSlotIcon = slot != null && art == null && pack == null
        val text = if (!withLabel) null else if (useSlotIcon) slot?.label else app?.label ?: missingLabel
        val style = if (useSlotIcon) TileView.ICON else TileView.LETTER
        val t = TileView(this, u, style, if (useSlotIcon) slot?.icon ?: 0 else 0, text,
            tileColors.background, tileColors.foreground)
        t.l1 = (app?.label ?: "?").take(1).uppercase()
        if (pack != null) {
            t.glyph = pack
            t.glyphScale = 1.2f
        } else if (art != null) {
            t.glyph = art.glyph(this)
            t.glyphScale = art.glyphScale
        } else if (!useSlotIcon && app != null && Build.VERSION.SDK_INT >= 33 && app.icon is AdaptiveIconDrawable) {
            t.glyph = app.icon.monochrome?.constantState?.newDrawable()?.mutate()
        }
        return Pair(t, tileColors.background)
    }

    private fun itemView(it: Item, w: Int, h: Int): View {
        when (it.type) {
            "folder" -> return folderTile(it, folders.getValue(it.key))
            "glance" -> {
                val tileColors = styleOverride(it) ?: tileColors(TileTone.CHARCOAL)
                val face = TileView(this, u, TileView.GLANCE, 0, null, tileColors.background, tileColors.foreground)
                glanceT = face
                val app = slots["calendar"]?.let { s -> resolve(s) }
                val host = tileHost(face, tileColors.background)
                host.setOnClickListener { if (app != null) launch(host, app) }
                host.setOnLongClickListener { _ -> itemMenu(it); true }
                return host
            }
            "wx" -> {
                val tileColors = styleOverride(it) ?: tileColors(TileTone.ACCENT)
                val face = TileView(this, u, TileView.WX, 0, "weather", tileColors.background, tileColors.foreground)
                wxT = face
                val app = resolve(wxSlot)
                val host = tileHost(face, tileColors.background)
                host.setOnClickListener {
                    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
                    else if (app != null) launch(host, app)
                    else safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")))
                }
                host.setOnLongClickListener { _ -> itemMenu(it); true }
                return host
            }
            "slot" -> {
                val s = slots[it.key] ?: wxSlot
                val app = resolve(s)
                val over = styleOverride(it)
                if (span(it.sz).first >= 2 && span(it.sz).second >= 2) {
                    return largeAppTile(it, app, s, over ?: appTileColors(app, s))
                }
                val (face, background) = appFace(app, true, s, colorsOverride = over)
                val host = tileHost(face, background)
                host.setOnClickListener { if (app != null) launch(host, app) else pickFor(s) }
                host.setOnLongClickListener { _ -> itemMenu(it); true }
                return host
            }
            "widget" -> {
                val box = TileHost(this)
                styleTile(box, solidTiles?.background ?: colors.panel)
                val info = if (it.wid >= 0) awm.getAppWidgetInfo(it.wid) else null
                if (info != null) {
                    val hv = widgetHost.createView(applicationContext, it.wid, info)
                    hv.setPadding(0, 0, 0, 0)
                    val d = resources.displayMetrics.density
                    try {
                        @Suppress("DEPRECATION")
                        hv.updateAppWidgetSize(null, (w / d).toInt(), (h / d).toInt(), (w / d).toInt(), (h / d).toInt())
                    } catch (e: Exception) { }
                    hv.setOnLongClickListener { _ -> itemMenu(it); true }
                    box.addView(hv, FrameLayout.LayoutParams(-1, -1))
                } else {
                    box.addView(label("tap to reconnect widget", 11f, colors.mutedText),
                        FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    box.setOnClickListener { _ -> addWidget(it) }
                }
                box.setOnLongClickListener { _ -> itemMenu(it); true }
                return box
            }
            else -> {
                val app = apps.firstOrNull { a -> a.component.flattenToString() == it.key }
                val over = styleOverride(it)
                if (app != null && span(it.sz).first >= 2 && span(it.sz).second >= 2) {
                    return largeAppTile(it, app, null, over ?: appTileColors(app))
                }
                val fb = appFace(app, true, missingLabel = it.key.substringBefore('/').substringAfterLast('.'), colorsOverride = over)
                val host = tileHost(fb.first, fb.second)
                host.setOnClickListener { if (app != null) launch(host, app) }
                host.setOnLongClickListener { _ -> itemMenu(it); true }
                return host
            }
        }
    }

    private fun canStyle(item: Item) = item.type != "widget" && item.type != "folder"

    /** Colors for a tile with a custom color or image; null when styling is off or unset. */
    private fun styleOverride(item: Item): TileColors? {
        if (!customTiles || !canStyle(item)) return null
        val image = item.styleImage
        if (image != null && tileBitmap(image) != null) return TileColors(Color.TRANSPARENT, Color.WHITE)
        return item.styleColor?.let { TileColors.solid(it or Color.BLACK) }
    }

    private fun tileBitmap(name: String): Bitmap? = tileBitmaps.getOrPut(name) {
        try { openFileInput(name).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
    }

    private fun applyTileImage(host: TileHost, item: Item) {
        if (!customTiles || !canStyle(item)) return
        val bitmap = item.styleImage?.let { tileBitmap(it) } ?: return
        styleTile(host, Color.BLACK)
        host.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 0, FrameLayout.LayoutParams(-1, -1))
        // Darken slightly so labels stay readable on any photo.
        host.addView(View(this).apply { setBackgroundColor(0x59000000) }, 1, FrameLayout.LayoutParams(-1, -1))
    }

    private fun deleteTileImage(name: String?) {
        if (name == null) return
        tileBitmaps.remove(name)
        try { deleteFile(name) } catch (_: Exception) { }
    }

    private fun styleChanged() {
        saveItems()
        rebuildHome()
        if (folderOpen) rebuildFolderPages()
    }

    private fun addStyleEntries(e: MutableList<Pair<String, () -> Unit>>, item: Item) {
        e.add(entry("tile color") {
            showColorPicker("Tile color", item.styleColor ?: DEFAULT_SOLID_COLOR, "Tile preview") { color ->
                deleteTileImage(item.styleImage)
                item.styleImage = null
                item.styleColor = color
                styleChanged()
            }
        })
        e.add(entry("tile image") {
            pendingStyleItem = item
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            try {
                startActivityForResult(intent, REQ_TILE_IMAGE)
            } catch (_: android.content.ActivityNotFoundException) {
                pendingStyleItem = null
                Toast.makeText(this, "Android file picker is unavailable", Toast.LENGTH_LONG).show()
            }
        })
        if (item.styleColor != null || item.styleImage != null) e.add(entry("reset tile style") {
            deleteTileImage(item.styleImage)
            item.styleImage = null
            item.styleColor = null
            styleChanged()
        })
    }

    private fun saveTileImage(item: Item, uri: Uri) {
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("Unreadable image")
                val name = "tile_${UUID.randomUUID()}.jpg"
                openFileOutput(name, MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    deleteTileImage(item.styleImage)
                    tileBitmaps[name] = bitmap
                    item.styleImage = name
                    item.styleColor = null
                    styleChanged()
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not use that image", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun toggleIconPack() {
        iconPack = !iconPack
        prefs.edit().putBoolean("icon_pack", iconPack).apply()
        rebuildHome()
        rebuildDrawer()
        if (folderOpen) rebuildFolderPages()
    }

    private fun toggleCustomTiles() {
        customTiles = !customTiles
        prefs.edit().putBoolean("custom_tiles", customTiles).apply()
        editItem = null
        folderEdit = null
        Toast.makeText(this, if (customTiles) "Long-press a tile to set its color or image. Move and resize are off."
            else "Original tiles. Move and resize are on.", Toast.LENGTH_LONG).show()
        rebuildHome()
        if (folderOpen) rebuildFolderPages()
    }

    private fun largeAppTile(item: Item, app: App?, slot: Slot?, tileColors: TileColors): TileHost {
        val packageName = app?.component?.packageName
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10f), px(10f), px(10f), px(8f))
        }
        val frontFace = appFace(app, true, slot, colorsOverride = tileColors).first.apply { compactIcon = true }
        val front = FrameLayout(this).apply { addView(frontFace, FrameLayout.LayoutParams(-1, -1)) }
        val host = tileHost(front, tileColors.background)
        host.face = frontFace
        host.flipFront = front
        host.flipBack = content
        content.visibility = View.GONE
        host.addView(content, FrameLayout.LayoutParams(-1, -1))
        host.setOnLongClickListener { itemMenu(item); true }
        host.setOnClickListener {
            if (app != null) launch(host, app) else if (slot != null) pickFor(slot)
        }
        if (packageName != GEMINI_PACKAGE &&
            (item.key == "clock" || packageName == "com.google.android.deskclock" || packageName == "com.android.deskclock")) {
            val clock = TextClock(this).apply {
                format12Hour = DateFormat.getBestDateTimePattern(Locale.getDefault(), "hm")
                format24Hour = DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm")
                gravity = Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 30)
                setTextColor(tileColors.foreground)
                typeface = Typeface.MONOSPACE
                setSingleLine()
            }
            val alarm = label("", 11.5f, tileColors.foreground).apply {
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            content.addView(clock, LinearLayout.LayoutParams(-1, px(48f)))
            content.addView(alarm, LinearLayout.LayoutParams(-1, px(26f)))
            host.liveHeader = clock
            alarmLabels.add(alarm)
            val show = { openClockAction(AlarmClock.ACTION_SHOW_ALARMS, host, app) }
            host.setOnClickListener { show() }
            addTileActionRow(content, tileColors, listOf(entry("+ Alarm") {
                openClockAction(AlarmClock.ACTION_SET_ALARM, host, app)
            }, entry("Alarms") { show() }))
            return host
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val glyph = appFace(app, false, slot, colorsOverride = tileColors).first
        header.addView(glyph, LinearLayout.LayoutParams(px(35f), px(38f)))
        val title = label(app?.label ?: slot?.label ?: "App", 15f, tileColors.foreground).apply {
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(6f) })
        content.addView(header, LinearLayout.LayoutParams(-1, px(45f)))
        host.liveHeader = header
        val shortcuts = if (app != null) shortcutsFor(app).filter { it.isEnabled } else emptyList()
        if (packageName == GEMINI_PACKAGE) {
            addTileActionRow(content, tileColors, listOf(entry("New chat") {
                runTileLaunch(host) { openGeminiTool(shortcuts, listOf("new chat", "new conversation"), "app") }
            }))
            addTileActionRow(content, tileColors, listOf(entry("Image") {
                runTileLaunch(host) { openGeminiTool(shortcuts, listOf("create image", "generate image", "images"), "images") }
            }, entry("Video") {
                runTileLaunch(host) { openGeminiTool(shortcuts, listOf("create video", "generate video", "videos"), "videos") }
            }))
        } else if (app != null) {
            addTileActionRow(content, tileColors, listOf(entry("Open") { launch(host, app) }))
            for (shortcut in shortcuts.take(if (span(item.sz).second >= 4) 4 else 1)) {
                val actionName = shortcut.shortLabel?.toString() ?: continue
                addTileActionRow(content, tileColors, listOf(entry(actionName) {
                    runTileLaunch(host) { startTileShortcut(shortcut) }
                }))
            }
        } else if (slot != null) {
            addTileActionRow(content, tileColors, listOf(entry("Choose app") { pickFor(slot) }))
        }
        return host
    }

    private fun addTileActionRow(parent: LinearLayout, tileColors: TileColors, actions: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((index, action) in actions.withIndex()) {
            val button = label(action.first, 11f, tileColors.foreground).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(px(3f), 0, px(3f), 0)
                background = GradientDrawable().apply {
                    setColor(tileColors.background)
                    setStroke(max(1, px(1f)), (tileColors.foreground and 0x00FFFFFF) or 0x77000000)
                    cornerRadius = px(3f).toFloat()
                }
                setOnClickListener { action.second() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = px(5f)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, px(35f)).apply { topMargin = px(5f) })
    }

    private fun refreshAlarmLabels() {
        if (alarmLabels.isEmpty()) return
        val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        val text = if (next == null) "No upcoming alarm" else {
            val date = Date(next)
            val day = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
            "Next alarm · $day ${DateFormat.getTimeFormat(this).format(date)}"
        }
        for (label in alarmLabels) label.text = text
    }

    private fun openClockAction(action: String, host: TileHost, app: App?) {
        runTileLaunch(host) {
            if (!safeStart(Intent(action)) && app != null) openMainApp(app)
        }
    }

    private fun startTileShortcut(shortcut: ShortcutInfo): Boolean = try {
        la.startShortcut(shortcut, null, appOpenAnimation())
        true
    } catch (e: Exception) { false }

    private fun openGeminiTool(shortcuts: List<ShortcutInfo>, names: List<String>, path: String) {
        val shortcut = shortcuts.firstOrNull { shortcut ->
            val name = shortcut.shortLabel?.toString()?.lowercase(Locale.ROOT)
            name != null && name in names
        }
        if (shortcut != null && startTileShortcut(shortcut)) return
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/$path")))
    }

    private fun appFor(item: Item): App? =
        apps.firstOrNull { it.component.flattenToString() == item.key }

    private fun buildFolderPanorama() {
        folderOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(folderOverlayColor())
            setOnClickListener { closeFolder() }
        }
        folderTrack = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folderPan = FolderPager(this).apply {
            canDragTile = { folderOpen && activeDrag != null }
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isFillViewport = true
            addView(folderTrack, FrameLayout.LayoutParams(-2, -1))
        }
        folderOverlay.addView(folderPan, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        root.addView(folderOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun folderPageWidth(): Int = pageWidth()
    private fun folderCellSize(): Int = ((folderPageWidth() - px(24f) - gap * 3) / 4).coerceAtLeast(1)

    private fun folderTile(item: Item, folder: LiveFolder): TileHost {
        val accent = tileColors(TileTone.ACCENT)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val positions = HomeGrid.pack(folderRequests(folder))
        val preview = folder.apps.zip(positions)
            .sortedWith(compareBy({ it.second.row }, { it.second.column }))
            .mapNotNull { (item, _) -> appFor(item) }.take(9)
        repeat(3) { y ->
            val row = LinearLayout(this)
            repeat(3) { x ->
                val app = preview.getOrNull(y * 3 + x)
                val square = if (app != null) {
                    val (face, background) = appFace(app, false)
                    TileHost(this).apply {
                        setBackgroundColor(background)
                        this.face = face
                        badgeInk = TileColors.solid(background).foreground
                        addView(face, FrameLayout.LayoutParams(-1, -1))
                        bindNotificationBadge(this, app.component, small = true)
                        folderPreviewViews.add(this)
                    }
                } else View(this).apply { setBackgroundColor(0x14000000) }
                row.addView(square, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (x > 0) leftMargin = max(1, px(1f))
                })
            }
            content.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (y > 0) topMargin = max(1, px(1f))
            })
        }
        content.addView(label(folder.name, 14f, accent.foreground).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8f), 0, px(8f), 0)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, px(28f)))
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return tileHost(content, accent.background).apply {
            contentDescription = "${folder.name}, ${folder.apps.size} apps"
            setOnClickListener { openFolder(folder) }
            setOnLongClickListener {
                menu(folder.name, listOf(
                    entry("add app") { openDrawerMulti("add apps to ${folder.name}") { addFolderApps(folder, it) } },
                    entry("move folder tile") { editItem = item; rebuildHome() },
                    entry("rename folder") { askFolderName(folder.name) { folder.name = it; updateFolders() } },
                    entry("remove folder") { requestTileRemoval(item) }
                ).filter { !customTiles || it.first != "move folder tile" })
                true
            }
        }
    }

    private fun openFolder(folder: LiveFolder) {
        clearSwipeMotion()
        clearHomeMotion()
        stopIconMotion()
        folderEdit = null
        folderOpen = true
        home.visibility = View.VISIBLE
        folderOverlay.animate().cancel()
        folderOverlay.alpha = 1f
        folderOverlay.setBackgroundColor(folderOverlayColor())
        homeTiles.visibility = if (useWallpaper) View.INVISIBLE else View.VISIBLE
        folderOverlay.visibility = View.VISIBLE
        folderTilesTops.clear()
        folderPan.layoutParams = (folderPan.layoutParams as FrameLayout.LayoutParams).apply {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
        folderPageToShow = folders.keys.indexOf(folder.id) * folderPageWidth()
        rebuildFolderPages()
        folderEntrancePending = true
        needsEntrance = false
        root.invalidate()
    }

    private fun closeFolder() {
        folderEntrancePending = false
        handler.removeCallbacks(armFolderDrag)
        folderPan.stopPaging()
        clearHomeMotion()
        stopIconMotion()
        folderOpen = false
        folderEdit = null
        folderPageToShow = null
        folderOverlay.animate().cancel()
        folderOverlay.visibility = View.GONE
        folderTrack.removeAllViews()
        folderViews.clear()
        folderBadges.clear()
        home.visibility = View.VISIBLE
        homeTiles.visibility = View.VISIBLE
        needsEntrance = true
        root.invalidate()
    }

    private fun folderRequests(folder: LiveFolder): List<HomeGrid.Request> = folder.apps.map {
        val (width, height) = span(it.sz)
        val clamped = width.coerceAtMost(4)
        HomeGrid.Request(if (!it.fixedColumn && it.row == null) 0 else it.column.coerceIn(0, 4 - clamped),
            clamped, height, it.fixedColumn, it.row)
    }

    private fun rebuildFolderPages() {
        if (!folderOpen) return
        clearHomeMotion()
        stopIconMotion()
        if (folderPageToShow == null) folderPageToShow = folderPan.scrollX
        val scrolls = (0 until folderTrack.childCount).mapNotNull { index ->
            val page = folderTrack.getChildAt(index) as? FrameLayout ?: return@mapNotNull null
            val scroll = page.findViewWithTag<ScrollView>("folder-scroll") ?: return@mapNotNull null
            (page.tag as? String ?: return@mapNotNull null) to scroll.scrollY
        }.toMap()
        folderTrack.removeAllViews()
        alarmLabels.removeAll { !it.isAttachedToWindow }
        folderTrack.setPadding(0, 0, 0, 0)
        folderViews.clear()
        folderBadges.clear()
        val step = folderCellSize() + gap
        folderOverlay.setBackgroundColor(folderOverlayColor())
        val ink = homeInk()
        val viewportHeight = root.height.coerceAtLeast(px(360f))
        for (folder in folders.values) {
            val pageGrid = FrameLayout(this).apply {
                setOnLongClickListener { folderOptions(folder); true }
                setOnClickListener {
                    if (folderEdit != null) { folderEdit = null; rebuildFolderPages() }
                    else closeFolder()
                }
            }
            val title = label(folder.name, 160f, ink).apply {
                tag = "folder-title"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                includeFontPadding = false
                setSingleLine()
                ellipsize = null
                setHorizontallyScrolling(true)
                setOnLongClickListener { folderOptions(folder); true }
            }
            val titleHeight = kotlin.math.ceil((title.paint.fontMetrics.descent - title.paint.fontMetrics.ascent).toDouble()).toInt() + px(8f)
            val requests = folderRequests(folder)
            val positions = HomeGrid.pack(requests)
            val contentHeight = positions.indices.maxOfOrNull { (positions[it].row + requests[it].height) * step - gap }
                ?: px(30f)
            val layout = FolderLayout.place(viewportHeight, titleHeight, contentHeight, px(64f), px(20f), px(16f))
            val scrollTop = titleHeight + px(20f)
            val tileTop = (layout.tilesTop - scrollTop).coerceAtLeast(0)
            val oldTileTop = folderTilesTops.put(folder.id, tileTop)
            val pageFrame = FrameLayout(this).apply { tag = folder.id }
            // Keep the header's slide inside its own area while tiles turn in place.
            val header = FrameLayout(this).apply {
                outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                clipToOutline = true
                addView(title, FrameLayout.LayoutParams(-1, -1))
            }
            pageFrame.addView(header, FrameLayout.LayoutParams(-1, titleHeight).apply {
                leftMargin = px(12f)
                rightMargin = px(12f)
                topMargin = layout.titleTop
            })
            var bottom = tileTop
            for ((index, item) in folder.apps.withIndex()) {
                val request = requests[index]
                val position = positions[index]
                val w = request.width * step - gap
                val h = request.height * step - gap
                val host = itemView(item, w, h) as TileHost
                host.draggableItem = item
                applyTileImage(host, item)
                host.transitionName = item.transitionName
                val app = appFor(item)
                if (app == null) host.contentDescription = item.key.substringBefore('/')
                bindNotificationBadge(host, app?.component, folderBadges)
                if (folderEdit != null) editDecor(host, item, selected = folderEdit,
                    select = { folderEdit = it; rebuildFolderPages() },
                    remove = { removeFolderApp(folder, item) },
                    resize = { anchor -> showTileSizes(anchor, item, 4) { size ->
                        val target = position.copy(column = min(position.column, 4 - size.width))
                        placeFolderApp(folder, item, target, size)
                    } })
                pageGrid.addView(host, FrameLayout.LayoutParams(w, h).apply {
                    leftMargin = px(12f) + position.column * step
                    topMargin = tileTop + position.row * step
                })
                bottom = max(bottom, tileTop + position.row * step + h)
                folderViews.add(host)
            }
            if (folder.apps.isEmpty()) pageGrid.addView(label("Hold here to add apps", 13f, ink),
                FrameLayout.LayoutParams(-2, -2).apply { leftMargin = px(12f); topMargin = tileTop })
            pageGrid.minimumHeight = bottom + gap
            val page = ScrollView(this).apply {
                tag = "folder-scroll"
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(pageGrid, FrameLayout.LayoutParams(-1, -2))
            }
            enableFolderDrop(pageGrid, folder, tileTop, step)
            pageFrame.addView(page, FrameLayout.LayoutParams(-1, -1).apply { topMargin = scrollTop })
            folderTrack.addView(pageFrame, LinearLayout.LayoutParams(folderPageWidth(), -1))
            scrolls[folder.id]?.let { y -> page.post { page.scrollTo(0, y) } }
            if (oldTileTop != null && oldTileTop != tileTop) {
                val shift = (oldTileTop - tileTop).toFloat()
                for (i in 0 until pageGrid.childCount) {
                    val child = pageGrid.getChildAt(i)
                    val lp = child.layoutParams as FrameLayout.LayoutParams
                    if (lp.topMargin < tileTop) continue
                    child.translationY = shift
                    child.animate().translationY(0f).setDuration(280)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
                }
            }
        }
        refreshNotificationBadges()
        refreshAlarmLabels()
    }

    private fun folderOptions(folder: LiveFolder) {
        menu(folder.name, listOf(
            entry("add app") { openDrawerMulti("add apps to ${folder.name}") { addFolderApps(folder, it) } },
            entry("rename folder") { askFolderName(folder.name) { folder.name = it; updateFolders() } }
        ))
    }

    private fun folderAppMenu(folder: LiveFolder, item: Item) {
        val e = ArrayList<Pair<String, () -> Unit>>()
        if (!customTiles) e.add(entry("move / resize tile") { folderEdit = item; rebuildFolderPages() })
        else if (canStyle(item)) addStyleEntries(e, item)
        e.add(entry("add apps to folder") { openDrawerMulti("add apps to ${folder.name}") { addFolderApps(folder, it) } })
        e.add(entry("remove from folder") { removeFolderApp(folder, item) })
        menu(appFor(item)?.label ?: item.key.substringBefore('/'), e)
    }

    private fun askFolderName(current: String = "", onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            hint = "Folder name"
            setText(current)
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (current.isEmpty()) "Create folder" else "Rename folder")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) input.error = "Enter a folder name"
            else { dialog.dismiss(); onName(name) }
        }
    }

    private fun createFolder(column: Int, members: List<App> = emptyList()) {
        askFolderName { name ->
            val folder = LiveFolder(UUID.randomUUID().toString(), name)
            folders[folder.id] = folder
            val item = Item("folder", folder.id, "m", -1, column)
            items.add(item)
            if (members.isEmpty()) updateFolders() else addFolderApps(folder, members)
            revealTile(item)
        }
    }

    private fun chooseFolderFor(app: App) = chooseFolderForApps(listOf(app))

    private fun chooseFolderForApps(list: List<App>) {
        val choices = folders.values.toList()
        if (choices.isEmpty()) { createFolder(currentColumn(), list); return }
        val names = (choices.map { it.name } + "Create folder…").toTypedArray()
        val what = if (list.size == 1) list.first().label else "${list.size} apps"
        AlertDialog.Builder(this).setTitle("Add $what to folder")
            .setItems(names) { _, which ->
                val folder = choices.getOrNull(which)
                if (folder != null) addFolderApps(folder, list) else createFolder(currentColumn(), list)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addFolderApps(folder: LiveFolder, list: List<App>) {
        var added = 0
        for (app in list) {
            val key = app.component.flattenToString()
            if (folder.apps.any { it.key == key }) continue
            folder.apps.add(Item("app", key, "s", -1))
            added++
        }
        if (added < list.size) {
            Toast.makeText(this, "${list.size - added} already in ${folder.name}", Toast.LENGTH_SHORT).show()
        }
        if (added > 0) updateFolders()
    }

    private fun addFolderApp(folder: LiveFolder, app: App) {
        val key = app.component.flattenToString()
        if (folder.apps.any { it.key == key }) {
            Toast.makeText(this, "${app.label} is already in ${folder.name}", Toast.LENGTH_SHORT).show()
            return
        }
        folder.apps.add(Item("app", key, "s", -1))
        updateFolders()
    }

    private fun removeFolderApp(folder: LiveFolder, item: Item) {
        folder.apps.remove(item)
        deleteTileImage(item.styleImage)
        if (folderEdit === item) folderEdit = null
        updateFolders()
    }

    private fun placeFolderApp(folder: LiveFolder, item: Item, target: HomeGrid.Position, size: TileSize? = null) {
        val index = folder.apps.indexOf(item)
        if (index < 0) return
        val current = HomeGrid.pack(folderRequests(folder))
        if (size != null) item.sz = size.key
        val moved = if (size == null) HomeGrid.dragPlacement(folderRequests(folder), index, target, current)
            else HomeGrid.relocate(folderRequests(folder), index, target, current)
        for ((i, app) in folder.apps.withIndex()) {
            app.column = moved[i].column
            app.row = moved[i].row
            app.fixedColumn = true
        }
        updateFolders()
    }

    private fun updateFolders() {
        saveItems()
        rebuildHome()
        if (folderOpen) rebuildFolderPages()
    }

    // ------------------------------------------------------------ app search

    private fun rebuildDrawer() {
        drawerList.removeAllViews()
        rows = ArrayList()
        val sq = px(46f)
        for (app in apps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val fb = appFace(app, false)
            val host = tileHost(fb.first, fb.second)
            row.addView(host, LinearLayout.LayoutParams(sq, sq))
            row.addView(label(app.label.lowercase(), 16f, colors.text).apply { setSingleLine() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(14f) })
            row.addView(label("✓", 18f, colors.text).apply { tag = "check"; visibility = View.GONE },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(10f) })
            row.setOnClickListener {
                if (multiActive()) { toggleSelect(app, row); return@setOnClickListener }
                val pick = onPick
                if (pick != null) { closeDrawer(); pick(app) } else launch(host, app)
            }
            row.setOnLongClickListener {
                if (multiActive()) toggleSelect(app, row) else appMenu(app, null, null, true)
                true
            }
            drawerList.addView(row, LinearLayout.LayoutParams(-1, sq).apply { bottomMargin = gap })
            rows.add(Pair(app, row))
            if (app.component.flattenToString() in selectedApps) showSelected(row, true)
        }
        filter()
    }

    private fun multiActive() = multiPick != null || generalSelect

    private fun barButton(text: String, action: () -> Unit): TextView {
        // Read the theme outside apply: inside a TextView, `colors` would resolve to the view's own member.
        val fill = colors[TileTone.CHARCOAL].background
        val radius = px(3f).toFloat()
        return label(text, 13f, colors.text).apply {
            gravity = Gravity.CENTER
            setSingleLine()
            setPadding(px(12f), 0, px(12f), 0)
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius
            }
            setOnClickListener { action() }
        }
    }

    private fun showSelected(row: View, on: Boolean) {
        row.findViewWithTag<View>("check")?.visibility = if (on) View.VISIBLE else View.GONE
        row.setBackgroundColor(if (on) 0x22FFFFFF else Color.TRANSPARENT)
    }

    private fun toggleSelect(app: App, row: View) {
        val key = app.component.flattenToString()
        if (selectedApps.remove(key) == null) selectedApps[key] = app
        showSelected(row, key in selectedApps)
        updateSelectionBar()
    }

    private fun clearSelection() {
        selectedApps.clear()
        for (r in rows) showSelected(r.second, false)
    }

    private fun updateSelectionBar() {
        if (!::selectionBar.isInitialized) return
        val on = multiActive()
        selectionBar.visibility = if (on) View.VISIBLE else View.GONE
        selectionCount.text = "${selectedApps.size} selected"
        val enabled = if (selectedApps.isEmpty()) .4f else 1f
        selectionAdd.visibility = if (multiPick != null) View.VISIBLE else View.GONE
        selectionHome.visibility = if (generalSelect) View.VISIBLE else View.GONE
        selectionFolder.visibility = if (generalSelect) View.VISIBLE else View.GONE
        selectionAdd.alpha = enabled
        selectionHome.alpha = enabled
        selectionFolder.alpha = enabled
    }

    private fun startGeneralSelect(app: App) {
        multiPick = null
        generalSelect = true
        selectedApps.clear()
        val row = rows.firstOrNull { it.first === app }?.second
        if (row != null) toggleSelect(app, row) else updateSelectionBar()
    }

    private fun exitSelection() {
        if (multiPick != null) { closeDrawer(); return }
        generalSelect = false
        clearSelection()
        updateSelectionBar()
    }

    /** toHome: true = pin to Home, false = add to a folder, null = hand the apps to the caller. */
    private fun finishSelection(toHome: Boolean?) {
        val chosen = apps.filter { it.component.flattenToString() in selectedApps }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
            return
        }
        val done = multiPick
        closeDrawer()
        when {
            done != null -> done(chosen)
            toHome == true -> pinApps(chosen, currentColumn())
            toHome == false -> chooseFolderForApps(chosen)
        }
    }

    private fun filter() {
        val q = query.text.toString().trim().lowercase()
        for (r in rows) r.second.visibility = if (q.isEmpty() || r.first.label.lowercase().contains(q)) View.VISIBLE else View.GONE
        drawerScroll.scrollTo(0, 0)
    }

    private fun openDrawer(hint: String?, pick: ((App) -> Unit)?) {
        onPick = pick
        multiPick = null
        generalSelect = false
        clearSelection()
        drawerOpen = true
        query.hint = hint ?: "search apps"
        query.setText("")
        drawer.animate().cancel()
        if (drawer.visibility != View.VISIBLE) drawer.translationY = root.height.toFloat()
        drawer.visibility = View.VISIBLE
        // The keyboard appears only when the search field is tapped.
        query.clearFocus()
        drawer.requestFocus()
        updateSelectionBar()
        drawer.animate().translationY(0f).setDuration(DRAWER_OPEN_MS).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun openDrawerMulti(hint: String, done: (List<App>) -> Unit) {
        openDrawer(hint, null)
        multiPick = done
        updateSelectionBar()
    }

    private fun closeDrawer(immediate: Boolean = false) {
        drawerOpen = false
        onPick = null
        multiPick = null
        generalSelect = false
        clearSelection()
        updateSelectionBar()
        hideKeyboard()
        drawer.animate().cancel()
        if (immediate) {
            drawer.visibility = View.INVISIBLE
            drawer.translationY = 0f
        } else {
            drawer.animate().translationY(root.height.toFloat()).setDuration(220)
                .withEndAction {
                    drawer.visibility = View.INVISIBLE
                    drawer.translationY = 0f
                }.start()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        if (drawer.visibility == View.VISIBLE) drawer.requestFocus()
    }

    private fun pickFor(s: Slot) {
        openDrawer("choose app for " + (if (s.label.isEmpty()) s.id else s.label)) { app ->
            prefs.edit().putString("slot_" + s.id, app.component.flattenToString()).apply()
            rebuildHome()
        }
    }

    // ------------------------------------------------------------ menus (long press)

    private fun menu(title: String, entries: List<Pair<String, () -> Unit>>, swatches: List<List<Int>> = emptyList()) {
        closeMenu()
        val dimBg = FrameLayout(this)
        dimBg.setBackgroundColor(0xB0000000.toInt())
        dimBg.setOnClickListener { closeMenu() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.panel)
            setPadding(px(18f), px(14f), px(18f), px(10f))
            isClickable = true
        }
        panel.addView(label(title.uppercase(), 10f, colors.mutedText).apply { letterSpacing = .16f; setSingleLine() })
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((index, e) in entries.withIndex()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val text = label(e.first, 16f, colors.text).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text, LinearLayout.LayoutParams(0, -1, 1f))
            for (color in swatches.getOrNull(index).orEmpty()) {
                val sample = View(this).apply { setBackgroundColor(color) }
                row.addView(sample, LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginStart = px(4f) })
            }
            row.setOnClickListener { closeMenu(); e.second() }
            options.addView(row, LinearLayout.LayoutParams(-1, px(46f)))
        }
        val optionsHeight = min(px(46f) * entries.size, (root.height - px(85f)).coerceAtLeast(px(46f)))
        panel.addView(ScrollView(this).apply { addView(options) }, LinearLayout.LayoutParams(-1, optionsHeight))
        dimBg.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(dimBg, FrameLayout.LayoutParams(-1, -1))
        panel.translationY = (px(46f) * entries.size + px(42f)).toFloat()
        panel.animate().translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        menuView = dimBg
    }

    private fun closeMenu() {
        menuView?.let { root.removeView(it) }
        menuView = null
    }

    private fun entry(name: String, f: () -> Unit): Pair<String, () -> Unit> = Pair(name, f)

    private fun homeMenu() {
        val rounded = prefs.getBoolean("rounded_corners", false)
        menu("home", listOf(
            entry("all apps") { openDrawer(null, null) },
            entry("add apps here") { addApps(currentColumn(), fixed = false) },
            entry("add apps to the right") {
                addApps(occupiedColumns.coerceAtMost(MAX_TILE_COLUMN), fixed = true)
            },
            entry("create folder") { createFolder(currentColumn()) },
            entry("add widget") { addWidget() },
            entry("export layout") { selectLayoutFile(export = true) },
            entry("import layout") { selectLayoutFile(export = false) },
            entry("colors: ${if (solidTiles != null) "solid" else launcherTheme.label}") { colorMenu() },
            entry(if (customTiles) "custom tile style: on (move/resize off)" else "custom tile style: off") { toggleCustomTiles() },
            entry(if (iconPack) "app icons: custom" else "app icons: original") { toggleIconPack() },
            entry(if (rounded) "tile corners: rounded" else "tile corners: square") {
                prefs.edit().putBoolean("rounded_corners", !rounded).apply()
                rebuildHome()
                rebuildDrawer()
            },
            entry("motion") { motionMenu() },
            entry("notification badges") { openNotificationAccess() },
            entry("reset layout") {
                for (it in items) if (it.type == "widget" && it.wid >= 0) widgetHost.deleteAppWidgetId(it.wid)
                prefs.edit().remove("items").apply(); loadItems(); rebuildHome()
            }
        ))
    }

    private fun openNotificationAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Open Notification access in Android Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun motionMenu() {
        val idleEnabled = prefs.getBoolean("idle_tile_motion", true)
        val flip = flipOnLaunch()
        menu("motion", listOf(
            entry("preview return home") { previewHomeMotion() },
            entry("preview lock animation") { lockAnimation.preview() },
            entry(if (prefs.getBoolean("lock_depth", true)) "lock animation: on" else "lock animation: off") {
                prefs.edit().putBoolean("lock_depth", !prefs.getBoolean("lock_depth", true)).apply()
                lockAnimation.reset()
                motionMenu()
            },
            entry("lock button height: ${lockButtonHeightPercent()}%") { lockButtonHeightDialog() },
            entry("return home duration: ${homeReturnDurationMs()} ms") { homeDurationDialog() },
            entry(if (flip) "open app: tile turn" else "open app: slide") {
                prefs.edit().putBoolean("flip_on_launch", !flip).apply()
                motionMenu()
            },
            entry(if (idleEnabled) "idle icon motion: on" else "idle icon motion: off") {
                prefs.edit().putBoolean("idle_tile_motion", !idleEnabled).apply()
                startLive()
            },
            entry("live tile interval") { idleIntervalDialog() },
            entry("preview icon motion") {
                if (checkMotionEnabled()) {
                    clearHomeMotion()
                    stopIconMotion()
                    visibleMotionTiles().filter { it.hasAppMotion() }.randomOrNull()?.let(::animateIcon)
                    scheduleIdleTick()
                }
            }
        ))
    }

    private fun homeReturnDurationMs(): Int = prefs.getInt(HOME_DURATION_KEY, HomeMotion.DEFAULT_TOTAL_MS)
        .coerceIn(HomeMotion.MIN_TOTAL_MS, HomeMotion.MAX_TOTAL_MS)

    private fun lockButtonHeightPercent() = prefs.getInt("lock_button_height", 28).coerceIn(10, 90)

    private fun lockButtonHeightDialog() {
        val value = label("", 16f, colors.text).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val slider = SeekBar(this).apply {
            min = 10
            max = 90
            progress = lockButtonHeightPercent()
            contentDescription = "Lock button height along the right edge"
        }
        val positionPreview = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                val h = height.toFloat() - px(12f)
                val w = h * 0.48f
                val left = (width - w) / 2f
                val top = px(6f).toFloat()
                paint.color = colors.mutedText
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = px(1.5f).toFloat()
                canvas.drawRoundRect(left, top, left + w, top + h, px(8f).toFloat(), px(8f).toFloat(), paint)
                paint.color = colors.text
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(left + w, top + h * slider.progress / 100f, px(4f).toFloat(), paint)
            }
        }
        fun updatePosition() {
            value.text = "${slider.progress}% down from the top"
            positionPreview.invalidate()
        }
        updatePosition()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = updatePosition()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.panel)
            setPadding(px(24f), px(12f), px(24f), px(12f))
            addView(label("Match the dot to your lock button on the right edge.", 14f, colors.text))
            addView(positionPreview, LinearLayout.LayoutParams(-1, px(160f)))
            addView(value)
            addView(slider)
        }
        AlertDialog.Builder(this)
            .setTitle("Lock button position")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & preview") { _, _ ->
                prefs.edit().putInt("lock_button_height", slider.progress).apply()
                root.postDelayed({ if (resumed && hasWindowFocus()) lockAnimation.preview() }, 150L)
            }
            .show()
    }

    private fun homeDurationDialog() {
        val value = label("", 16f, colors.text).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val slider = SeekBar(this).apply {
            max = (HomeMotion.MAX_TOTAL_MS - HomeMotion.MIN_TOTAL_MS) / HomeMotion.DURATION_STEP_MS
            progress = (homeReturnDurationMs() - HomeMotion.MIN_TOTAL_MS) / HomeMotion.DURATION_STEP_MS
            contentDescription = "Total Return Home animation duration"
        }
        fun durationMs(): Int = HomeMotion.MIN_TOTAL_MS + slider.progress * HomeMotion.DURATION_STEP_MS
        value.text = "${durationMs()} ms"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "${durationMs()} ms"
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.panel)
            setPadding(px(24f), px(18f), px(24f), px(12f))
            addView(value, LinearLayout.LayoutParams(-1, -2))
            addView(slider, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Return Home duration")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putInt(HOME_DURATION_KEY, durationMs()).apply()
            }
            .show()
    }

    private fun idleIntervalRange(): IntRange {
        val oldSeconds = prefs.getInt("idle_tile_interval_seconds", 10).coerceIn(3, 60)
        val oldSteps = prefs.getInt("idle_tile_interval_steps", oldSeconds * 2)
        val minimum = prefs.getInt(IDLE_INTERVAL_MIN_KEY, oldSteps).coerceIn(1, IDLE_INTERVAL_MAX_STEPS - 1)
        val maximum = prefs.getInt(IDLE_INTERVAL_MAX_KEY, minimum + 12)
            .coerceIn(minimum + 1, IDLE_INTERVAL_MAX_STEPS)
        return minimum..maximum
    }

    private fun intervalLabel(steps: Int): String = when {
        steps % 2 == 0 -> "${steps / 2}"
        else -> "${steps / 2}.5"
    }

    private fun idleIntervalDialog() {
        val initial = idleIntervalRange()
        val minimum = SeekBar(this).apply {
            min = 1
            max = IDLE_INTERVAL_MAX_STEPS - 1
            progress = initial.first
            contentDescription = "Minimum time between live tile starts"
        }
        val maximum = SeekBar(this).apply {
            min = 2
            max = IDLE_INTERVAL_MAX_STEPS
            progress = initial.last
            contentDescription = "Maximum time between live tile starts"
        }
        val minLabel = label("", 16f, colors.text)
        val maxLabel = label("", 16f, colors.text)
        fun updateLabels() {
            minLabel.text = "Minimum ${intervalLabel(minimum.progress)} s"
            maxLabel.text = "Maximum ${intervalLabel(maximum.progress)} s"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (minimum.progress >= maximum.progress) {
                    if (bar === minimum) maximum.progress = progress + 1 else minimum.progress = progress - 1
                }
                updateLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        }
        minimum.setOnSeekBarChangeListener(listener)
        maximum.setOnSeekBarChangeListener(listener)
        updateLabels()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.panel)
            setPadding(px(24f), px(18f), px(24f), px(12f))
            addView(minLabel)
            addView(minimum)
            addView(maxLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
            addView(maximum)
            addView(Button(this@MainActivity).apply {
                text = "0.5–2 s"
                setOnClickListener {
                    minimum.progress = 1
                    maximum.progress = 4
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Live tile interval")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putInt(IDLE_INTERVAL_MIN_KEY, minimum.progress)
                    .putInt(IDLE_INTERVAL_MAX_KEY, maximum.progress).apply()
                scheduleIdleTick()
            }
            .show()
    }

    private fun checkMotionEnabled(): Boolean {
        if (ValueAnimator.areAnimatorsEnabled()) return true
        Toast.makeText(this, "Android animations are turned off", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun previewHomeMotion() {
        if (!checkMotionEnabled()) return
        clearHomeMotion()
        needsEntrance = true
        root.postInvalidateOnAnimation()
    }

    private fun colorMenu() {
        menu("colors", listOf(
            entry("background color") { backgroundMenu() },
            entry("solid color") { solidColorMenu() },
            entry("themes") { themeMenu() }
        ))
    }

    private fun backgroundMenu() {
        val presets = listOf("Black" to 0xFF000000.toInt(), "Charcoal" to 0xFF222428.toInt(),
            "Light gray" to 0xFFE6E8EB.toInt())
        val entries = listOf(
            entry(if (useWallpaper) "device wallpaper ✓" else "device wallpaper") { setWallpaperBackground() },
            entry("use theme background") { setHomeBackground(null) }) +
            presets.map { (name, color) ->
                entry(if (!useWallpaper && customBackground == color) "$name ✓" else name) { setHomeBackground(color) }
            } + entry("pick a custom color") {
                showColorPicker("Home background", homeBackground(), "Background preview") { setHomeBackground(it) }
            }
        val swatches = listOf(emptyList<Int>(), listOf(colors.background)) + presets.map { listOf(it.second) } +
            listOf(listOf(homeBackground()))
        menu("background color", entries, swatches)
    }

    private fun setHomeBackground(color: Int?) {
        customBackground = color
        useWallpaper = false
        prefs.edit().apply {
            if (color == null) remove("home_background") else putInt("home_background", color)
            putBoolean("use_wallpaper", false)
        }.apply()
        applyHomeBackground()
    }

    private fun setWallpaperBackground() {
        useWallpaper = true
        prefs.edit().putBoolean("use_wallpaper", true).apply()
        applyHomeBackground()
    }

    private fun applyHomeBackground() {
        val background = homeBackground()
        root.setBackgroundColor(rootColor())
        if (useWallpaper) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if (::folderOverlay.isInitialized) folderOverlay.setBackgroundColor(folderOverlayColor())
        if (::drawer.isInitialized) drawer.setBackgroundColor(drawerColor())
        val darkIcons = if (useWallpaper) wallpaperPrefersDarkIcons()
            else TileColors.solid(background).foreground == Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkIcons) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility and flags.inv()) or
                if (darkIcons) flags else 0
        }
    }

    /** Dark status-bar icons when the device wallpaper is light (API 27+); otherwise light icons. */
    private fun wallpaperPrefersDarkIcons(): Boolean {
        if (Build.VERSION.SDK_INT < 27) return false
        return try {
            val wc = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            wc != null && TileColors.solid(wc.primaryColor.toArgb()).foreground == Color.BLACK
        } catch (_: Exception) { false }
    }

    private fun solidColorMenu() {
        val presets = listOf("Gray" to DEFAULT_SOLID_COLOR, "Blue" to 0xFF2F6FBB.toInt(),
            "Red" to 0xFFC43D4B.toInt(), "Green" to 0xFF2D8267.toInt(), "Black" to 0xFF17191E.toInt())
        val entries = presets.map { (name, color) ->
            entry(if (solidTiles?.background == color) "$name ✓" else name) { selectSolidColor(color) }
        } + entry("pick a custom color") { showSolidColorPicker() }
        val current = prefs.getInt("solid_color", DEFAULT_SOLID_COLOR)
        menu("solid color", entries, presets.map { listOf(it.second) } + listOf(listOf(current)))
    }

    private fun showSolidColorPicker() {
        showColorPicker("Solid tile color", prefs.getInt("solid_color", DEFAULT_SOLID_COLOR), "Tile preview", ::selectSolidColor)
    }

    private fun showColorPicker(title: String, color: Int, previewLabel: String, apply: (Int) -> Unit) {
        val picker = SolidColorPicker(this, color, previewLabel)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(picker) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val color = picker.selectedColor() ?: return@setOnClickListener
            apply(color)
            dialog.dismiss()
        }
    }

    private fun selectSolidColor(color: Int) {
        solidTiles = TileColors.solid(color)
        prefs.edit().putBoolean("solid_mode", true).putInt("solid_color", color).apply()
        applyColors()
    }

    private fun themeMenu() {
        val themes = LauncherTheme.entries
        val entries = themes.map { theme ->
            entry(if (solidTiles == null && theme == launcherTheme) "${theme.label} ✓" else theme.label) {
                launcherTheme = theme
                solidTiles = null
                prefs.edit().putString("color_theme", theme.name).putBoolean("solid_mode", false).apply()
                applyColors()
            }
        }
        val tones = listOf(TileTone.PAPER, TileTone.CHARCOAL, TileTone.SECONDARY, TileTone.ACCENT, TileTone.SIGNAL)
        menu("color theme", entries, themes.map { theme -> tones.map { theme.colors[it].background } })
    }

    private fun applyColors() {
        applyHomeBackground()
        drawer.setBackgroundColor(drawerColor())
        selectionBar.setBackgroundColor(colors.panel)
        query.setTextColor(colors.text)
        query.setHintTextColor(colors.mutedText)
        query.setBackgroundColor(colors[TileTone.CHARCOAL].background)
        rebuildHome()
        rebuildDrawer()
    }

    private fun pageWidth(): Int = max(1, resources.displayMetrics.widthPixels)

    private fun currentColumn(): Int =
        (homePan.scrollX.toFloat() / max(1, tile + gap)).roundToInt().coerceIn(0, MAX_TILE_COLUMN)

    private fun revealColumn(column: Int) {
        val observer = homePan.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                homePan.viewTreeObserver.removeOnPreDrawListener(this)
                homePan.smoothScrollTo(column * (tile + gap), 0)
                return true
            }
        })
        homePan.postInvalidateOnAnimation()
    }

    private fun addApps(column: Int, fixed: Boolean) {
        openDrawerMulti("select apps to add") { chosen ->
            pinApps(chosen, column, fixed)
            revealColumn(column)
        }
    }

    private fun pinApp(app: App, column: Int, fixed: Boolean = false) = pinApps(listOf(app), column, fixed)

    private fun pinApps(list: List<App>, column: Int, fixed: Boolean = false) {
        list.forEachIndexed { index, app ->
            // Fixed placements fan out over four columns so a batch lands as a block.
            val col = if (fixed) (column + index % HomeGrid.COLUMNS_PER_VIEWPORT).coerceAtMost(MAX_TILE_COLUMN) else column
            items.add(Item("app", app.component.flattenToString(), "s", -1, col, fixed))
        }
        saveItems()
        rebuildHome()
    }

    private fun applyPositions(positions: List<HomeGrid.Position>) {
        items.forEachIndexed { index, item ->
            item.column = positions[index].column
            item.row = positions[index].row
            item.fixedColumn = true
        }
    }

    private fun revealTile(item: Item) {
        grid.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                val position = placedPositions[item] ?: return true
                val (width, height) = span(item.sz)
                val left = px(12f) + position.column * (tile + gap)
                val top = px(10f) + position.row * (tile + gap)
                val right = left + width * (tile + gap) - gap
                val bottom = top + height * (tile + gap) - gap
                val x = when {
                    width * (tile + gap) > homePan.width || left < homePan.scrollX -> left - px(12f)
                    right > homePan.scrollX + homePan.width -> right - homePan.width + px(12f)
                    else -> homePan.scrollX
                }
                val y = when {
                    height * (tile + gap) > homeScroll.height || top < homeScroll.scrollY -> top - px(10f)
                    bottom > homeScroll.scrollY + homeScroll.height -> bottom - homeScroll.height + px(10f)
                    else -> homeScroll.scrollY
                }
                homePan.smoothScrollTo(x.coerceAtLeast(0), 0)
                homeScroll.smoothScrollTo(0, y.coerceAtLeast(0))
                return true
            }
        })
        grid.postInvalidateOnAnimation()
    }

    private fun shortcutsFor(app: App): List<ShortcutInfo> = try {
        if (!la.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery()
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            q.setPackage(app.component.packageName)
            la.getShortcuts(q, app.user) ?: emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun itemMenu(it: Item) {
        if (folderOpen) {
            val folder = folders.values.firstOrNull { folder -> folder.apps.contains(it) }
            if (folder != null) { folderAppMenu(folder, it); return }
        }
        val s = if (it.type == "slot") slots[it.key] else null
        val app: App? = when (it.type) {
            "slot" -> if (s != null) resolve(s) else null
            "app" -> apps.firstOrNull { a -> a.component.flattenToString() == it.key }
            "glance" -> slots["calendar"]?.let { x -> resolve(x) }
            "wx" -> resolve(wxSlot)
            else -> null
        }
        appMenu(app, it, s, false)
    }

    /** Long press: the app's own shortcuts, app info, uninstall (as on Pixel), then resize / change / remove. */
    private fun appMenu(app: App?, it: Item?, s: Slot?, fromDrawer: Boolean) {
        val e = ArrayList<Pair<String, () -> Unit>>()
        if (app != null) {
            for (sc in shortcutsFor(app).take(4)) {
                e.add(entry((sc.shortLabel ?: "shortcut").toString().lowercase()) {
                    try { la.startShortcut(sc, null, null) } catch (x: Exception) { }
                })
            }
            e.add(entry("app info") {
                try { la.startAppDetailsActivity(app.component, app.user, null, null) } catch (x: Exception) { }
            })
            e.add(entry("uninstall") {
                safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName)))
            })
            if (!fromDrawer) e.add(entry("add to folder") { chooseFolderFor(app) })
            if (fromDrawer) e.add(entry("pin to home") {
                pinApp(app, currentColumn())
                closeDrawer()
            })
            if (fromDrawer && onPick == null && !multiActive()) e.add(entry("select multiple") { startGeneralSelect(app) })
        }
        if (it != null && !customTiles) e.add(entry(if (canResize(it)) "move / resize tile" else "move tile") {
            editItem = it
            rebuildHome()
        })
        if (it != null && customTiles && canStyle(it)) addStyleEntries(e, it)
        if (s != null) e.add(entry("change app") { pickFor(s) })
        if (it != null) e.add(entry("remove from home") { removeItem(it) })
        menu(app?.label ?: it?.type ?: s?.id ?: "tile", e)
    }

    private fun requestTileRemoval(item: Item) {
        val folder = folders[item.key].takeIf { item.type == "folder" }
        if (folder == null) {
            removeItem(item)
            return
        }
        AlertDialog.Builder(this).setMessage("Remove ${folder.name} from Home?")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> removeItem(item) }.show()
    }

    private fun removeItem(it: Item) {
        if (it.type == "widget" && it.wid >= 0) widgetHost.deleteAppWidgetId(it.wid)
        if (it.type == "folder") folders.remove(it.key)?.apps?.forEach { a -> deleteTileImage(a.styleImage) }
        deleteTileImage(it.styleImage)
        items.remove(it)
        if (editItem === it) editItem = null
        saveItems()
        rebuildHome()
    }

    private fun canResize(item: Item) = item.type in setOf("slot", "app", "widget")

    private fun showTileSizes(anchor: View, item: Item, maxWidth: Int = 8,
                              choose: (TileSize) -> Unit = { resizeTile(item, it) }) {
        val selectedSize = TileSize.fromKey(item.sz)
        val popup = PopupMenu(this, anchor)
        val sizes = TileSize.choices.filter { it.width <= maxWidth }
        sizes.forEachIndexed { index, size ->
            popup.menu.add(0, index, index, size.label).apply {
                isCheckable = true
                isChecked = size == selectedSize
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { choice ->
            sizes.getOrNull(choice.itemId)?.let(choose)
            true
        }
        popup.show()
    }

    private fun resizeTile(item: Item, size: TileSize) {
        if (TileSize.fromKey(item.sz) == size) return
        val index = items.indexOf(item)
        val position = placedPositions[item] ?: return
        if (index < 0) return
        val current = items.map { placedPositions.getValue(it) }
        val resize = ChangeBounds().setDuration(270).setInterpolator(DecelerateInterpolator(1.6f))
        for (tile in items) resize.addTarget(tile.transitionName)
        TransitionManager.beginDelayedTransition(grid, resize)
        item.sz = size.key
        applyPositions(HomeGrid.relocate(layoutRequests(), index, position, current))
        saveItems()
        rebuildHome()
        revealTile(item)
    }

    private fun enableFolderDrop(target: FrameLayout, folder: LiveFolder, top: Int, pitch: Int) {
        val left = px(12f)
        val hosts = (0 until target.childCount).mapNotNull { target.getChildAt(it) as? TileHost }
        val oldMinHeight = target.minimumHeight
        var marker: TextView? = null
        var lastPosition: HomeGrid.Position? = null
        var currentDrag: TileDrag? = null
        var pointerX = 0f
        var pointerY = 0f
        var scrollY = 0
        fun clearPreview() {
            marker?.let { target.removeView(it) }; marker = null; lastPosition = null
            hosts.forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
            target.minimumHeight = oldMinHeight
        }
        fun incoming(item: Item): HomeGrid.Request {
            val size = span(item.sz)
            return HomeGrid.Request(0, size.first.coerceAtMost(4), size.second, true, 0)
        }
        fun preview(drag: TileDrag, x: Float, y: Float): HomeGrid.Position {
            val request = incoming(drag.item)
            val destination = HomeGrid.Position(((x - drag.x - left) / pitch).roundToInt().coerceIn(0, 4 - request.width),
                ((y - drag.y - top) / pitch).roundToInt().coerceIn(0, 4095))
            if (destination == lastPosition) return destination
            lastPosition = destination
            val selected = folder.apps.indexOf(drag.item).takeIf { it >= 0 }
            val duplicate = selected == null && folder.apps.any { it.key == drag.item.key }
            val requests = folderRequests(folder)
            val original = HomeGrid.pack(requests)
            val placement = FolderDrop.place(requests, selected, request, destination)
            val swap = selected != null && requests.indices.any {
                it != selected && original[it] == destination && placement[it] == original[selected]
            }
            val hint = marker ?: TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 12)
                background = GradientDrawable().apply {
                    setColor(0x33FFFFFF); setStroke(px(2f).coerceAtLeast(1), Color.WHITE)
                    cornerRadius = px(10f).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                target.addView(this, FrameLayout.LayoutParams(request.width * pitch - gap, request.height * pitch - gap))
                marker = this
            }
            hint.text = when { duplicate -> "Already in folder"; swap -> "Swap"; else -> "Place here" }
            hint.layoutParams = (hint.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = left + destination.column * pitch
                topMargin = top + destination.row * pitch
            }
            if (!duplicate) for ((index, tile) in hosts.withIndex()) {
                if (tile === drag.host) continue
                tile.animate().translationX((placement[index].column - original[index].column) * pitch.toFloat())
                    .translationY((placement[index].row - original[index].row) * pitch.toFloat())
                    .setDuration(180).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
            }
            val allRequests = if (selected == null) requests + request else requests
            val bottom = placement.indices.maxOf { top + (placement[it].row + allRequests[it].height) * pitch }
            target.minimumHeight = max(oldMinHeight, bottom + pitch)
            return destination
        }
        val edgeScroll = object : Runnable {
            override fun run() {
                val drag = currentDrag ?: return
                val pane = target.parent as? ScrollView ?: return
                if (!target.isAttachedToWindow || scrollY == 0) return
                val old = pane.scrollY
                pane.scrollBy(0, scrollY)
                pointerY += pane.scrollY - old
                preview(drag, pointerX, pointerY)
                if (pane.scrollY != old) target.postOnAnimation(this)
            }
        }
        target.setOnDragListener { _, event ->
            val drag = event.localState as? TileDrag ?: return@setOnDragListener false
            val source = folders.values.firstOrNull { drag.item in it.apps } ?: return@setOnDragListener false
            target.removeCallbacks(edgeScroll)
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> { drag.host.alpha = .18f }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    currentDrag = drag
                    pointerX = event.x; pointerY = event.y
                    preview(drag, pointerX, pointerY)
                    val pane = target.parent as? ScrollView
                    val viewport = Rect(); val loc = IntArray(2)
                    if (pane?.getGlobalVisibleRect(viewport) == true) {
                        target.getLocationOnScreen(loc)
                        val y = loc[1] + event.y
                        scrollY = when {
                            y < viewport.top + px(38f) -> -px(9f).coerceAtLeast(1)
                            y > viewport.bottom - px(38f) -> px(9f).coerceAtLeast(1)
                            else -> 0
                        }
                        if (scrollY != 0) target.postOnAnimation(edgeScroll)
                    }
                }
                DragEvent.ACTION_DROP -> {
                    val destination = preview(drag, event.x, event.y)
                    val selected = folder.apps.indexOf(drag.item).takeIf { it >= 0 }
                    if (selected == null && folder.apps.any { it.key == drag.item.key }) {
                        Toast.makeText(this, "App is already in this folder", Toast.LENGTH_SHORT).show()
                        return@setOnDragListener false
                    }
                    val requests = folderRequests(folder)
                    val original = HomeGrid.pack(requests)
                    val placement = FolderDrop.place(requests, selected, incoming(drag.item), destination)
                    val unchanged = selected != null && destination == original[selected]
                    target.post {
                        // Finish before rebuilding pages: detached views may not receive DRAG_ENDED.
                        clearPreview()
                        resetDragPreview(drag, true)
                        if (activeDrag === drag) activeDrag = null
                        touching = false
                        swallow = false
                        folderPan.stopPaging()
                        if (resumed) startLive()
                        if (folderOpen && drag.item in source.apps) {
                            if (unchanged && folderEdit == null) folderAppMenu(folder, drag.item)
                            else {
                                if (selected == null) { source.apps.remove(drag.item); folder.apps.add(drag.item) }
                                folder.apps.forEachIndexed { index, item ->
                                    item.column = placement[index].column; item.row = placement[index].row; item.fixedColumn = true
                                }
                                folderPageToShow = folders.keys.indexOf(folder.id) * folderPageWidth()
                                updateFolders()
                            }
                        }
                    }
                }
                DragEvent.ACTION_DRAG_EXITED -> { currentDrag = null; clearPreview() }
                DragEvent.ACTION_DRAG_ENDED -> {
                    currentDrag = null; clearPreview()
                    drag.host.alpha = 1f
                    if (activeDrag === drag) {
                        activeDrag = null; touching = false; swallow = false
                        if (resumed) startLive()
                    }
                }
            }
            true
        }
    }

    private fun enableTileDrop(
        target: FrameLayout, left: Int, top: Int, pitch: Int, move: (Item, Int, Int) -> Unit
    ) {
        var scrollX = 0
        var scrollY = 0
        var pointerX = 0f
        var pointerY = 0f
        val location = IntArray(2)
        val viewport = Rect()
        val edge = px(38f)
        var currentDrag: TileDrag? = null

        fun preview(drag: TileDrag, x: Float, y: Float): HomeGrid.Position {
            val selected = drag.hosts.indexOf(drag.host)
            val requests = drag.hosts.map { tile ->
                val lp = tile.layoutParams as FrameLayout.LayoutParams
                HomeGrid.Request((lp.leftMargin - left) / pitch, (lp.width + gap) / pitch,
                    (lp.height + gap) / pitch, true, ((lp.topMargin - top) / pitch).coerceAtLeast(0))
            }
            val original = requests.map { HomeGrid.Position(it.column, it.row ?: 0) }
            val maxColumn = if (target === grid) MAX_TILE_COLUMN else (4 - requests[selected].width).coerceAtLeast(0)
            val destination = HomeGrid.Position(((x - drag.x - left) / pitch).roundToInt().coerceIn(0, maxColumn),
                ((y - drag.y - top) / pitch).roundToInt().coerceIn(0, 4095))
            if (destination == drag.preview) return destination
            drag.preview = destination
            val placement = HomeGrid.dragPlacement(requests, selected, destination, original)
            val swap = requests.indices.any { it != selected && original[it] == destination && placement[it] == original[selected] }
            val marker = drag.marker ?: TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 12)
                background = GradientDrawable().apply {
                    setColor(0x33FFFFFF)
                    setStroke(px(2f).coerceAtLeast(1), Color.WHITE)
                    cornerRadius = px(10f).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                target.addView(this, FrameLayout.LayoutParams(drag.host.width, drag.host.height))
                drag.marker = this
            }
            marker.text = if (swap) "Swap" else "Place here"
            marker.layoutParams = (marker.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = left + destination.column * pitch
                topMargin = top + destination.row * pitch
            }
            for ((index, tile) in drag.hosts.withIndex()) {
                if (tile === drag.host) continue
                tile.animate().translationX((placement[index].column - original[index].column) * pitch.toFloat())
                    .translationY((placement[index].row - original[index].row) * pitch.toFloat())
                    .setDuration(180).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
            }
            val bottom = placement.indices.maxOf { top + (placement[it].row + requests[it].height) * pitch }
            target.minimumHeight = max(drag.oldMinHeight, bottom + pitch * 2)
            return destination
        }

        val edgeScroll = object : Runnable {
            override fun run() {
                val drag = currentDrag ?: return
                if (!target.isAttachedToWindow) return
                val vertical = target.parent as? ScrollView
                val horizontal = if (target === grid) homePan else null
                val oldX = horizontal?.scrollX ?: 0
                val oldY = vertical?.scrollY ?: 0
                horizontal?.scrollBy(scrollX, 0)
                vertical?.scrollBy(0, scrollY)
                pointerX += (horizontal?.scrollX ?: 0) - oldX
                pointerY += (vertical?.scrollY ?: 0) - oldY
                preview(drag, pointerX, pointerY)
                if (oldX != (horizontal?.scrollX ?: 0) || oldY != (vertical?.scrollY ?: 0))
                    target.postOnAnimation(this)
            }
        }
        target.setOnDragListener { _, event ->
            val drag = event.localState as? TileDrag ?: return@setOnDragListener false
            if (drag.grid !== target) return@setOnDragListener false
            target.removeCallbacks(edgeScroll)
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    currentDrag = drag
                    drag.host.alpha = .18f
                    target.minimumHeight = max(target.minimumHeight, target.height + pitch * 2)
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    pointerX = event.x
                    pointerY = event.y
                    preview(drag, pointerX, pointerY)
                    val pane = target.parent as? ScrollView
                    if (pane?.getGlobalVisibleRect(viewport) == true) {
                        target.getLocationOnScreen(location)
                        val x = location[0] + event.x
                        val y = location[1] + event.y
                        val speed = px(9f).coerceAtLeast(1)
                        scrollX = if (target !== grid) 0 else when {
                            x < viewport.left + edge -> -speed
                            x > viewport.right - edge -> speed
                            else -> 0
                        }
                        scrollY = when {
                            y < viewport.top + edge -> -speed
                            y > viewport.bottom - edge -> speed
                            else -> 0
                        }
                        if (scrollX != 0 || scrollY != 0) target.postOnAnimation(edgeScroll)
                    }
                }
                DragEvent.ACTION_DROP -> {
                    val destination = preview(drag, event.x, event.y)
                    target.post { if (target.isAttachedToWindow) move(drag.item, destination.column, destination.row) }
                }
                DragEvent.ACTION_DRAG_EXITED -> resetDragPreview(drag, false)
                DragEvent.ACTION_DRAG_ENDED -> {
                    currentDrag = null
                    resetDragPreview(drag, true)
                    activeDrag = null
                    touching = false
                    if (resumed) startLive()
                }
            }
            true
        }
    }

    private class TileDrag(val grid: FrameLayout, val item: Item, val host: TileHost,
                           val hosts: List<TileHost>, val x: Float, val y: Float) {
        val oldMinHeight = grid.minimumHeight
        var preview: HomeGrid.Position? = null
        var marker: TextView? = null
    }

    private fun resetDragPreview(drag: TileDrag, finished: Boolean) {
        drag.marker?.let { drag.grid.removeView(it) }
        drag.marker = null
        drag.preview = null
        for (tile in drag.hosts) {
            tile.animate().cancel()
            tile.translationX = 0f
            tile.translationY = 0f
        }
        if (finished) {
            drag.host.alpha = 1f
            drag.grid.minimumHeight = drag.oldMinHeight
        }
    }

    private fun startTileDrag(host: FrameLayout, item: Item): Boolean {
        val tile = host as? TileHost ?: return false
        if (customTiles) return false
        val parent = tile.parent as? FrameLayout ?: return false
        if (!tile.isShown || activeDrag != null) return false
        stopLive()
        clearHomeMotion()
        clearSwipeMotion()
        tile.animate().cancel()
        tile.rotationX = 0f
        tile.scaleX = 1f
        tile.scaleY = 1f
        val hosts = (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? TileHost }
            .filter { it.draggableItem != null }
        val loc = IntArray(2)
        tile.getLocationOnScreen(loc)
        val drag = TileDrag(parent, item, tile, hosts,
            (dragDownRawX - loc[0]).coerceIn(0f, tile.width.toFloat()),
            (dragDownRawY - loc[1]).coerceIn(0f, tile.height.toFloat()))
        activeDrag = drag
        val started = tile.startDragAndDrop(ClipData.newPlainText("Matte Launcher tile", ""),
            object : View.DragShadowBuilder(tile) {
                override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                    super.onProvideShadowMetrics(size, touch)
                    touch.set(drag.x.toInt(), drag.y.toInt())
                }
            }, drag, 0)
        if (!started) { activeDrag = null; startLive() }
        else tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return started
    }

    private fun editDecor(
        v: View, item: Item, selected: Item? = editItem, select: (Item) -> Unit = { editItem = it; rebuildHome() },
        remove: () -> Unit = { requestTileRemoval(item) }, resize: (View) -> Unit = { showTileSizes(it, item) }
    ) {
        val host = v as? FrameLayout ?: return
        val cover = View(this).apply {
            contentDescription = "Hold and drag to move tile"
            setOnClickListener { if (selected !== item) select(item) }
        }
        host.addView(cover, FrameLayout.LayoutParams(-1, -1))
        host.setOnLongClickListener(null)
        cover.setOnLongClickListener { startTileDrag(host, item) }
        if (selected !== item) { host.scaleX = .94f; host.scaleY = .94f; return }
        val size = px(28f)
        val margin = px(3f)
        fun button(kind: Int, rotation: Float, gravity: Int, description: String, action: (View) -> Unit) {
            val control = EditBtn(this, u, kind, rotation, colors[TileTone.PAPER]).apply {
                contentDescription = description
                isFocusable = true
                setOnClickListener { action(it) }
            }
            host.addView(control, FrameLayout.LayoutParams(size, size, gravity).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
        button(0, 0f, Gravity.TOP or Gravity.END, "Remove tile") { remove() }
        if (canResize(item)) button(1, 45f, Gravity.BOTTOM or Gravity.END, "Choose tile size", resize)
    }

    private fun selectLayoutFile(export: Boolean) {
        val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (export) "application/json" else "*/*")
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "MatteLauncher-layout.json")
        try {
            startActivityForResult(intent, if (export) REQ_LAYOUT_EXPORT else REQ_LAYOUT_IMPORT)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Android file picker is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLayout(uri: Uri) {
        val localId = prefs.getString("layout_install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("layout_install_id", it).apply()
        }
        val bindings = JSONObject()
        for (id in slots.keys) prefs.getString("slot_" + id, null)?.let { bindings.put(id, it) }
        val data = JSONObject().put("format", "matte-launcher-layout").put("version", 1)
            .put("installation", localId).put("tiles", writeItems(items))
            .put("folders", writeFolders()).put("bindings", bindings)
        try {
            val output = contentResolver.openOutputStream(uri, "w") ?: error("No output stream")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(data.toString(2)) }
            Toast.makeText(this, "Layout exported", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not write layout backup", Toast.LENGTH_LONG).show()
        }
    }

    private fun importLayout(uri: Uri) {
        val restored: Triple<List<Item>, Map<String, LiveFolder>, Map<String, String>>
        try {
            val input = contentResolver.openInputStream(uri) ?: error("No input stream")
            val raw = input.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val chunk = CharArray(4096)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(text.length + count <= 2_000_000)
                    text.append(chunk, 0, count)
                }
                text.toString()
            }
            val data = JSONObject(raw)
            require(data.getString("format") == "matte-launcher-layout" && data.getInt("version") == 1)
            val importedFolders = readFolders(data.getJSONArray("folders"))
            val importedTiles = readItems(data.getJSONArray("tiles"))
            val folderTiles = importedTiles.filter { it.type == "folder" }
            require(folderTiles.size == importedFolders.size && folderTiles.map { it.key }.toSet() == importedFolders.keys)
            val ownedWidgets = widgetHost.appWidgetIds.toSet()
            val local = data.getString("installation") == prefs.getString("layout_install_id", null)
            val reusedWidgets = HashSet<Int>()
            for (item in importedTiles) when (item.type) {
                "glance", "wx" -> Unit
                "slot" -> require(item.key in slots)
                "app" -> require(ComponentName.unflattenFromString(item.key) != null)
                "folder" -> item.sz = "m"
                "widget" -> if (!local || item.wid !in ownedWidgets ||
                    awm.getAppWidgetInfo(item.wid) == null || !reusedWidgets.add(item.wid)) item.wid = -1
                else -> error("Unknown tile type")
            }
            val bindings = data.getJSONObject("bindings")
            val importedBindings = slots.keys.mapNotNull { key ->
                if (!bindings.has(key)) return@mapNotNull null
                val component = bindings.getString(key)
                require(ComponentName.unflattenFromString(component) != null)
                key to component
            }.toMap()
            restored = Triple(importedTiles, importedFolders, importedBindings)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not read a valid Matte Launcher layout", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Restore layout?")
            .setMessage("The backup will replace the current Home tiles, folders and app assignments.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                val (tiles, savedFolders, bindings) = restored
                if (folderOpen) closeFolder()
                editItem = null
                items.clear()
                items.addAll(tiles)
                folders.clear()
                folders.putAll(savedFolders)
                val editor = prefs.edit()
                for (id in slots.keys) {
                    val binding = bindings[id]
                    if (binding == null) editor.remove("slot_" + id) else editor.putString("slot_" + id, binding)
                }
                saveItems(editor)
                homePan.scrollTo(0, 0)
                homeScroll.scrollTo(0, 0)
                rebuildHome()
                Toast.makeText(this, "Layout restored", Toast.LENGTH_SHORT).show()
            }.show()
    }

    // ------------------------------------------------------------ widgets

    private fun addWidget(replace: Item? = null) {
        try {
            pendingWidgetReplacement = replace
            pendingWidgetColumn = currentColumn()
            pendingWidget = widgetHost.allocateAppWidgetId()
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget)
            i.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList<android.os.Parcelable>())
            i.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList<android.os.Parcelable>())
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            if (pendingWidget >= 0) widgetHost.deleteAppWidgetId(pendingWidget)
            pendingWidget = -1
            pendingWidgetReplacement = null
            Toast.makeText(this, "widget picker not available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LAYOUT_EXPORT || requestCode == REQ_LAYOUT_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let { uri ->
                if (requestCode == REQ_LAYOUT_EXPORT) exportLayout(uri) else importLayout(uri)
            }
            return
        }
        if (requestCode == REQ_TILE_IMAGE) {
            val item = pendingStyleItem
            pendingStyleItem = null
            if (resultCode == RESULT_OK && item != null) data?.data?.let { saveTileImage(item, it) }
            return
        }
        if (requestCode != REQ_PICK && requestCode != REQ_CONF) return
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) ?: pendingWidget
        if (id < 0) return
        if (resultCode != RESULT_OK) {
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetReplacement = null
            pendingWidget = -1
            return
        }
        if (requestCode == REQ_PICK) {
            val info = awm.getAppWidgetInfo(id)
            if (info?.configure != null) {
                try { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONF, null); return } catch (e: Exception) { }
            }
        }
        val replacement = pendingWidgetReplacement
        if (replacement != null && replacement in items) replacement.wid = id
        else items.add(Item("widget", "", "w", id, pendingWidgetColumn))
        pendingWidget = -1
        pendingWidgetReplacement = null
        saveItems()
        rebuildHome()
    }

    // ------------------------------------------------------------ launch + return

    private fun center(v: View) {
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
    }

    private fun clearHomeMotion() {
        val animator = homeAnimator
        homeAnimator = null
        animator?.cancel()
        if (animator != null || unlockStaged) {
            unlockStaged = false
            resetHomeTransforms()
        }
    }

    /** Screen on while locked, or unlocked: queue the same tile turn that plays when returning Home. */
    private fun onDeviceWake(unlocked: Boolean) {
        if (!started || (!unlocked && !locked())) return
        needsEntrance = true
        root.invalidate()
    }

    private fun stageForUnlock() {
        turnTiles = prepareTurnstile()
        if (turnTiles.isEmpty()) return
        unlockStaged = true
        renderHomeArrival(0f)
    }

    private class TurnTile(val view: TileHost, val timing: HomeMotion.TurnTiming)
    private var turnTiles: List<TurnTile> = emptyList()
    private var slidingFolderTitle: View? = null

    private fun resetHomeTransforms() {
        setTileLayers(turnTiles.map { it.view }, false)
        for (tile in turnTiles) {
            tile.view.rotationY = 0f
            tile.view.alpha = 1f
            center(tile.view)
        }
        turnTiles = emptyList()
        slidingFolderTitle?.translationY = 0f
        slidingFolderTitle = null
    }

    /** Stage visible tiles before drawing, then turn each around the fixed screen edge. */
    private fun animateHomeEntrance(durationMs: Int = homeReturnDurationMs(), folderTitle: View? = null) {
        if (home.width == 0 || !ValueAnimator.areAnimatorsEnabled()) return
        clearHomeMotion()
        clearSwipeMotion()
        stopIconMotion()
        turnTiles = prepareTurnstile(durationMs = durationMs)
        if (turnTiles.isEmpty() && folderTitle == null) return
        val totalMs = turnTiles.maxOfOrNull { it.timing.finishMs } ?: durationMs.toLong()
        slidingFolderTitle = folderTitle
        val titleTravel = folderTitle?.height?.toFloat() ?: 0f
        val titleEasing = android.view.animation.AccelerateDecelerateInterpolator()
        folderTitle?.translationY = titleTravel
        setTileLayers(turnTiles.map { it.view }, true)
        renderHomeArrival(0f)
        homeAnimator = ValueAnimator.ofFloat(0f, totalMs.toFloat()).apply {
            duration = totalMs
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val elapsedMs = animator.animatedValue as Float
                renderHomeArrival(elapsedMs)
                folderTitle?.translationY = titleTravel * (1f - titleEasing.getInterpolation(elapsedMs / totalMs))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (homeAnimator !== animation) return
                    homeAnimator = null
                    resetHomeTransforms()
                }
            })
        }
        homeAnimator?.start()
    }

    private fun prepareTurnstile(selected: TileHost? = null, durationMs: Int = homeReturnDurationMs()): List<TurnTile> {
        val bounds = Rect()
        val loc = IntArray(2)
        val visible = turnViews().filterIsInstance<TileHost>().mapNotNull { view ->
            if (!view.isShown || !view.getGlobalVisibleRect(bounds)) return@mapNotNull null
            view.getLocationOnScreen(loc)
            TileTurnGeometry.prepare(view, loc[0], home.width)
            view to HomeMotion.VisibleTile(bounds.right, bounds.bottom, view.parent === dock)
        }
        val pitch = if (folderOpen) folderCellSize() + gap else tile + gap
        val selectedIndex = visible.indexOfFirst { it.first === selected }.takeIf { it >= 0 }
        val timings = HomeMotion.turnTimings(visible.map { it.second }, pitch, durationMs, selectedIndex)
        return visible.mapIndexed { index, (view) -> TurnTile(view, timings[index]) }
    }

    private fun renderHomeArrival(elapsedMs: Float) {
        for (tile in turnTiles) {
            tile.view.alpha = if (elapsedMs < tile.timing.startMs) 0f else 1f
            tile.view.rotationY = HomeMotion.turnAngle(elapsedMs, tile.timing)
        }
    }

    private fun swayOnSwipe(scrollX: Int, delta: Int) {
        if (delta == 0 || (!swipeFingerOnCanvas && !swipeInProgress) || !resumed ||
            drawerOpen || homeAnimator != null || launchAnimator != null || !ValueAnimator.areAnimatorsEnabled()) return
        swipeInProgress = true
        swipeAnimator?.cancel()
        swipeAnimator = null
        val left = scrollX
        val right = scrollX + homePan.width
        val direction = if (delta > 0) -1f else 1f
        val phase = scrollX.toFloat() / max(1, tile)
        for (index in 0 until grid.childCount) {
            val icon = grid.getChildAt(index) as? TileHost ?: continue
            if (!icon.hasAppMotion() || icon.right <= left || icon.left >= right ||
                icon.bottom <= homeScroll.scrollY || icon.top >= homeScroll.scrollY + homeScroll.height) continue
            if (swayingTiles.add(icon)) center(icon)
            icon.rotation = direction * (1f + kotlin.math.sin(phase * 3f + index * .7f) * .65f)
        }
        handler.removeCallbacks(settleSwipe)
        handler.postDelayed(settleSwipe, 85L)
    }

    private fun settleSwipeTiles() {
        swipeInProgress = false
        if (swayingTiles.isEmpty()) return
        val positions = swayingTiles.map { it to it.rotation }
        swipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 330L
            interpolator = LinearInterpolator()
            addUpdateListener { frame ->
                val remaining = HomeMotion.swipeSettle(frame.animatedValue as Float)
                for ((tile, angle) in positions) tile.rotation = angle * remaining
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (swipeAnimator !== animation) return
                    swipeAnimator = null
                    swayingTiles.clear()
                }
            })
        }
        swipeAnimator?.start()
    }

    private fun clearSwipeMotion() {
        handler.removeCallbacks(settleSwipe)
        val previous = swipeAnimator
        swipeAnimator = null
        previous?.cancel()
        for (tile in swayingTiles) tile.rotation = 0f
        swayingTiles.clear()
        swipeInProgress = false
        swipeFingerOnCanvas = false
    }

    private fun flipOnLaunch() = prefs.getBoolean("flip_on_launch", true)

    private fun launch(host: TileHost, app: App) = runTileLaunch(host) { openMainApp(app) }

    private fun openMainApp(app: App) {
        closeMenu()
        hideKeyboard()
        try {
            if (app.user == Process.myUserHandle()) {
                // LauncherApps.startMainActivity does not expose Intent flags. Use a normal
                // launcher Intent for this profile so Android receives NO_ANIMATION itself.
                val intent = Intent.makeMainActivity(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(withLaunchFlags(intent), appOpenAnimation())
            } else {
                la.startMainActivity(app.component, app.user, null, appOpenAnimation())
            }
            suppressLegacyAppTransition()
            if (drawerOpen) closeDrawer(immediate = true)
        } catch (e: Exception) {
            clearHomeMotion()
            resetFlyingTiles()
            Toast.makeText(this, "Unable to open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runTileLaunch(host: TileHost, open: () -> Unit) {
        if (!resumed || !hasWindowFocus() || !host.isShown || launchAnimator != null) return
        if (!flipOnLaunch()) {
            open()
            return
        }
        if (drawerOpen || !ValueAnimator.areAnimatorsEnabled()) {
            openDirectly(open)
            return
        }
        clearHomeMotion()
        stopIconMotion()
        val flights = prepareTurnstile(selected = host, durationMs = APP_LAUNCH_TURN_MS)
        if (flights.isEmpty()) {
            openDirectly(open)
            return
        }
        flyingTiles = flights.map { it.view }
        setTileLayers(flyingTiles, true)
        val total = flights.maxOf { it.timing.finishMs }.toFloat()
        launchAnimator = ValueAnimator.ofFloat(total, -1f).apply {
            duration = total.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { frame ->
                val elapsed = frame.animatedValue as Float
                for (tile in flights) {
                    tile.view.rotationY = HomeMotion.turnAngle(elapsed, tile.timing)
                    tile.view.alpha = if (elapsed < tile.timing.startMs) 0f else 1f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (launchAnimator !== animation) return
                    launchAnimator = null
                    if (resumed && hasWindowFocus() && host.isAttachedToWindow && !locked()) {
                        // Keep the last flight pose until Android starts drawing the next activity.
                        root.postDelayed(restoreFlight, 450)
                        openDirectly(open)
                    } else {
                        resetFlyingTiles()
                    }
                }
            })
        }
        launchAnimator?.start()
    }

    private fun openDirectly(open: () -> Unit) {
        directAppHandoff = true
        try {
            open()
        } finally {
            directAppHandoff = false
        }
    }

    private fun cancelLaunchMotion(keepFinishedFlight: Boolean = false) {
        val animator = launchAnimator
        launchAnimator = null
        animator?.cancel()
        if (keepFinishedFlight && animator == null) return
        resetFlyingTiles()
    }

    private fun resetFlyingTiles() {
        root.removeCallbacks(restoreFlight)
        resetTileFlightPose(flyingTiles)
        flyingTiles = emptyList()
    }

    private fun resetTileFlightPose(tiles: List<TileHost>) {
        setTileLayers(tiles, false)
        for (tile in tiles) {
            tile.rotationY = 0f
            tile.translationX = 0f
            tile.translationY = 0f
            tile.alpha = 1f
            center(tile)
        }
    }

    private fun shouldSkipAppTransition() = directAppHandoff || flipOnLaunch()

    private fun withLaunchFlags(intent: Intent): Intent {
        if (shouldSkipAppTransition()) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return intent
    }

    private fun suppressLegacyAppTransition() {
        if (shouldSkipAppTransition()) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun appOpenAnimation(): Bundle = if (shouldSkipAppTransition()) {
        ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
    } else {
        ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.home_exit).toBundle()
    }

    /** Swipe down on home: open the system's own notification shade. */
    private fun expandShade() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) { }
    }

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(withLaunchFlags(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), appOpenAnimation())
        suppressLegacyAppTransition()
        true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------ occasional tile motion

    private fun flip(t: TileView) {
        t.animate().setStartDelay(0).scaleY(0f).setDuration(170).withEndAction {
            t.alt = !t.alt
            t.invalidate()
            t.animate().setStartDelay(0).scaleY(1f).setDuration(170).start()
        }.start()
    }

    private fun scheduleIdleTick() {
        handler.removeCallbacks(idleTick)
        if (resumed && prefs.getBoolean("idle_tile_motion", true) && ValueAnimator.areAnimatorsEnabled()) {
            val range = idleIntervalRange()
            val delay = Random.nextLong(range.first * IDLE_INTERVAL_STEP_MS, range.last * IDLE_INTERVAL_STEP_MS + 1L)
            handler.postDelayed(idleTick, delay)
        }
    }

    private fun animateIdleTile() {
        scheduleIdleTick()
        if (!resumed || !hasWindowFocus() || root.windowVisibility != View.VISIBLE || touching || swipeInProgress ||
            drawer.visibility == View.VISIBLE || menuView != null || editItem != null || homeAnimator != null ||
            folderEdit != null || activeDrag != null || launchAnimator != null ||
            flyingTiles.isNotEmpty() || locked()) return
        val visible = visibleMotionTiles()
        val previewBounds = Rect()
        val previews = if (folderOpen) emptyList() else folderPreviewViews.filter {
            it.getGlobalVisibleRect(previewBounds) && it !in idleAnimators
        }
        val icons = visible.filter { it.flipFront == null && it.hasAppMotion() && it !in idleAnimators } + previews
        val information = visible.filterNot { it.hasAppMotion() }.mapNotNull { it.face as? TileView }
        val informationTile = information.firstOrNull { it.alt }
            ?: if (icons.isEmpty() || Random.nextInt(8) == 0) information.randomOrNull() else null
        if (informationTile != null) {
            flip(informationTile)
            return
        }
        val icon = icons.filter { it !== lastIdleTile }.randomOrNull() ?: icons.firstOrNull()
        if (icon != null) animateIcon(icon)
    }

    private fun TileHost.hasAppMotion(): Boolean = liveHeader != null || (face as? TileView)?.isAppIcon == true

    private fun visibleMotionTiles(): List<TileHost> {
        val bounds = Rect()
        return turnViews().filterIsInstance<TileHost>().filter { tile ->
            (tile.hasAppMotion() || (tile.face as? TileView)?.back?.isNotEmpty() == true) &&
                tile.getGlobalVisibleRect(bounds) && bounds.height() >= min(tile.height, homePan.height) * .75f &&
                bounds.width() >= min(tile.width, homePan.width) * .75f
        }
    }

    private fun animateIcon(tile: TileHost) {
        if (tile in idleAnimators) return
        lastIdleTile = tile
        val animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = if (tile.liveHeader != null) 830L else 720L
            addUpdateListener { frame -> renderIdlePose(tile, frame.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (idleAnimators[tile] !== animation) return
                    idleAnimators.remove(tile)
                    renderIdlePose(tile, 0f)
                }
            })
        }
        idleAnimators[tile] = animator
        animator.start()
    }

    private fun stopIconMotion() {
        val running = idleAnimators.toMap()
        idleAnimators.clear()
        for ((tile, animator) in running) {
            animator.cancel()
            renderIdlePose(tile, 0f)
        }
    }

    private fun renderIdlePose(tile: TileHost, motion: Float) {
        val header = tile.liveHeader
        if (header != null) {
            header.rotationX = -22f * motion
            header.translationY = -px(4f) * motion
        } else {
            (tile.face as? TileView)?.iconMotion = motion
        }
    }

    private fun startLive() {
        stopLive()
        scheduleIdleTick()
        if (resumed) handler.postDelayed(largeFlipTick, 4500L)
    }

    private fun stopLargeFlips() {
        handler.removeCallbacks(largeFlipTick)
        val running = largeFlipAnimators.toMap()
        largeFlipAnimators.clear()
        for ((tile, animator) in running) {
            animator.cancel()
            tile.flipFront?.rotationX = 0f
            tile.flipBack?.rotationX = 0f
            tile.flipFront?.visibility = if (tile.showingActions) View.GONE else View.VISIBLE
            tile.flipBack?.visibility = if (tile.showingActions) View.VISIBLE else View.GONE
        }
    }

    private fun flipLargeTiles() {
        if (!resumed) return
        handler.postDelayed(largeFlipTick, 5500L)
        if (!hasWindowFocus() || touching || activeDrag != null || drawerOpen || menuView != null ||
            editItem != null || folderEdit != null || homeAnimator != null || launchAnimator != null ||
            !ValueAnimator.areAnimatorsEnabled()) return
        val bounds = Rect()
        val tiles = turnViews().filterIsInstance<TileHost>().filter {
            it.flipFront != null && it.getGlobalVisibleRect(bounds) && bounds.width() > it.width / 2 &&
                bounds.height() > it.height / 2
        }
        for ((index, tile) in tiles.withIndex()) {
            if (tile in largeFlipAnimators) continue
            val front = tile.flipFront ?: continue
            val back = tile.flipBack ?: continue
            val showActions = !tile.showingActions
            val outgoing = if (showActions) front else back
            val incoming = if (showActions) back else front
            front.cameraDistance = tile.width * 10f
            back.cameraDistance = tile.width * 10f
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 680L
                startDelay = (index % 6) * 90L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { frame ->
                    val t = frame.animatedValue as Float
                    if (t < .5f) {
                        outgoing.visibility = View.VISIBLE
                        incoming.visibility = View.GONE
                        outgoing.rotationX = -180f * t
                    } else {
                        outgoing.visibility = View.GONE
                        incoming.visibility = View.VISIBLE
                        incoming.rotationX = 180f * (1f - t)
                        tile.showingActions = showActions
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (largeFlipAnimators.remove(tile) == null) return
                        tile.showingActions = showActions
                        outgoing.rotationX = 0f
                        incoming.rotationX = 0f
                        outgoing.visibility = View.GONE
                        incoming.visibility = View.VISIBLE
                    }
                })
            }
            largeFlipAnimators[tile] = animator
            animator.start()
        }
    }

    private fun stopLive() {
        stopLargeFlips()
        handler.removeCallbacks(idleTick)
        stopIconMotion()
        lastIdleTile = null
        for (t in listOfNotNull(glanceT, wxT)) {
            t.animate().cancel()
            t.scaleY = 1f
            if (t.alt) {
                t.alt = false
                t.invalidate()
            }
        }
    }

    // ------------------------------------------------------------ glance + weather data

    private fun applyData() {
        val now = Date()
        glanceT?.apply {
            l1 = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now).lowercase()
            l2 = if (wxTemp != null) "$wxDesc · $wxTemp°" else SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
            back = if (evTitle != null) listOf(Pair(evTitle!!, true), Pair(evSub ?: "", false)) else emptyList()
            invalidate()
        }
        wxT?.apply {
            l1 = if (wxTemp != null) "$wxTemp°" else "--°"
            l2 = wxDesc ?: "no data"
            l3 = wxHL ?: "tap to allow"
            back = wxBack
            invalidate()
        }
    }

    private fun wmo(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "showers"
        85, 86 -> "snow"
        else -> if (code >= 95) "storm" else "cloudy"
    }

    private fun loadWeather() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var loc: Location? = null
                for (provider in lm.getProviders(true)) {
                    val last = try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
                    if (last != null && (loc == null || last.time > loc.time)) loc = last
                }
                val here = loc ?: return@Thread
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${here.latitude}&longitude=${here.longitude}" +
                        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
                        "&hourly=temperature_2m&forecast_days=1&timezone=auto")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val j = JSONObject(body)
                val cur = j.getJSONObject("current")
                val t = cur.getDouble("temperature_2m").roundToInt()
                val d = wmo(cur.getInt("weather_code"))
                val daily = j.getJSONObject("daily")
                val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
                val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
                val hours = j.getJSONObject("hourly").getJSONArray("temperature_2m")
                val b = ArrayList<Pair<String, Boolean>>()
                b.add(Pair("forecast", false))
                for (hr in intArrayOf(12, 15, 18)) if (hr < hours.length())
                    b.add(Pair("$hr:00 · ${hours.getDouble(hr).roundToInt()}°", true))
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    wxTemp = t; wxDesc = d; wxHL = "h $hi° · l $lo°"; wxBack = b
                    applyData()
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun loadEvent() {
        val request = ++eventRequest
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            evTitle = null
            evSub = null
            applyData()
            return
        }
        Thread {
            val event = queryNextEvent()
            runOnUiThread {
                if (request != eventRequest || isDestroyed) return@runOnUiThread
                evTitle = event?.first
                evSub = event?.second
                applyData()
            }
        }.start()
    }

    private fun queryNextEvent(): Pair<String, String>? {
        try {
            val now = System.currentTimeMillis()
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, now + 24L * 3600 * 1000)
            val cur = contentResolver.query(b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC")
            cur?.use {
                while (it.moveToNext()) {
                    if (it.getInt(3) != 0 || it.getLong(1) < now) continue
                    val title = (it.getString(0) ?: "event").lowercase()
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.getLong(1)))
                    val place = it.getString(2)
                    val subtitle = if (place.isNullOrEmpty()) time else "$time · ${place.lowercase()}"
                    return title to subtitle
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
