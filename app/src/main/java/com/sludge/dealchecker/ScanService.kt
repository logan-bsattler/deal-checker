package com.sludge.dealchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * The always-there bubble. Holds the capture session, runs a scan when tapped, and paints the
 * verdicts back over whatever you were looking at.
 */
class ScanService : Service() {

    companion object {
        const val ACTION_START = "com.sludge.dealchecker.START"
        const val ACTION_STOP = "com.sludge.dealchecker.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val KEY_LIVE = "liveLookup"
        private const val CHANNEL = "dealchecker"
        private const val NOTIF_ID = 42

        /** How often to ask whether the screen moved. Cheap — it is one buffer poll. */
        private const val MOTION_POLL = 220L
        /** Quiet time after the last movement before the screen is re-read. */
        private const val SETTLE_MS = 550L
        /** Ignore frames this long after we change our own overlay, so we do not chase ourselves. */
        private const val MUTE_MS = 400L
        private const val TAG = "ScanService"

        @Volatile var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null

    private var bubble: View? = null
    private var scanning = false

    private var highlightWin: HighlightView? = null
    private var panelWin: View? = null
    private var panelList: LinearLayout? = null
    private var panelHeadline: TextView? = null
    private var panelRect: Rect? = null

    private var watching = false
    private var lastMotionAt = 0L
    private var ignoreFramesUntil = 0L

    // Kept so an Oracle lookup landing after the scan can re-score without grabbing the screen again.
    private var lastHits: List<Hit> = emptyList()
    private var lastEvidence: Map<String, PriceEvidence> = emptyMap()
    private val lookupPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var lookupsInFlight = 0

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system")
            main.post { stopEverything() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == 0 || data == null) {
            bailOut("Screen capture permission missing — open Deal Checker and start again")
            return START_NOT_STICKY
        }

        // These two steps each claim to need the other first, and which one the platform actually
        // enforces varies by version: going foreground first can fail because the project_media app
        // op is not held yet, while getMediaProjection can fail because no mediaProjection service
        // is running yet. So try the typed service first, fall back to untyped, and upgrade after.
        val notes = StringBuilder()
        var typed = false
        try {
            startForegroundNotification(mediaProjection = true)
            typed = true
        } catch (e: Exception) {
            notes.append("typed FGS refused: ${e.javaClass.simpleName}: ${e.message}\n")
            try {
                startForegroundNotification(mediaProjection = false)
            } catch (e2: Exception) {
                notes.append("untyped FGS refused too: ${e2.javaClass.simpleName}: ${e2.message}\n")
            }
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            notes.append("getMediaProjection: ${e.javaClass.simpleName}: ${e.message}\n")
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (p == null) {
            recordStartupProblem(notes.toString())
            bailOut("Screen capture refused — open Deal Checker to see why")
            return START_NOT_STICKY
        }

        if (!typed) {
            // The projection now exists, so the app op should be held and the type should stick.
            try {
                startForegroundNotification(mediaProjection = true)
                typed = true
            } catch (e: Exception) {
                notes.append("type upgrade refused: ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        if (!typed) {
            recordStartupProblem(notes.toString())
            try { p.stop() } catch (_: Exception) {}
            bailOut("Screen capture refused — open Deal Checker to see why")
            return START_NOT_STICKY
        }
        if (notes.isNotEmpty()) recordStartupProblem(notes.toString() + "recovered: bubble started anyway\n")

        projection = p
        p.registerCallback(projectionCallback, main)

        startCapture()
        addBubble()
        running = true

        Thread {
            MedianCache.load(applicationContext)
            GameIndex.load(applicationContext)
        }.start()
        // Not sticky: the consent token is single-use, so a system restart would come back with a
        // spent token and fail. Better to be plainly gone and let the user start the bubble again.
        return START_NOT_STICKY
    }

    // ---------- capture ----------

    private data class Metrics(val w: Int, val h: Int, val density: Int)

    private fun metrics(): Metrics {
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Metrics(b.width(), b.height(), dm.densityDpi)
        } else {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(real)
            Metrics(real.widthPixels, real.heightPixels, real.densityDpi)
        }
    }

    private fun startCapture() {
        val m = metrics()
        capture?.release()
        val p = projection ?: return
        capture = ScreenCapture(p, m.w, m.h, m.density, main).also { it.start() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeOverlay()
        main.postDelayed({ if (running) startCapture() }, 300)
    }

    // ---------- bubble ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addBubble() {
        if (bubble != null) return
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()

        val v = TextView(this).apply {
            text = "BG"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
        }

        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = metrics().h / 3
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    if (dragged) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) scan() else snapToEdge(v, lp, size)
                    true
                }
                else -> false
            }
        }

        bubble = v
        try { wm.addView(v, lp) } catch (e: Exception) { Log.e(TAG, "addBubble: ${e.message}") }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams, size: Int) {
        val w = metrics().w
        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    // ---------- scanning ----------

    private fun scan() {
        if (scanning) return
        if (!GameIndex.ready) { toast("Still loading the BGG index…"); return }
        scanning = true
        removeOverlay()
        bubble?.visibility = View.GONE
        // let the compositor push a frame without our own chrome in it
        main.postDelayed({ grabAndRead(0) }, 220)
    }

    private fun grabAndRead(attempt: Int) {
        val bmp = capture?.grab()
        if (bmp == null) {
            if (attempt < 3) { main.postDelayed({ grabAndRead(attempt + 1) }, 180); return }
            bubble?.visibility = View.VISIBLE
            scanning = false
            toast("Couldn't grab the screen — try again")
            return
        }
        bubble?.visibility = View.VISIBLE
        analyse(bmp)
    }

    private fun analyse(bmp: Bitmap) {
        val image = InputImage.fromBitmap(bmp, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                val mine = panelRect
                for (block in text.textBlocks) for (l in block.lines) {
                    val bb: Rect = l.boundingBox ?: continue
                    // Our own panel prints game names and dollar amounts; reading them back in
                    // would let the app score its own output.
                    if (mine != null && Rect.intersects(mine, bb)) continue
                    lines.add(OcrLine(l.text, bb))
                }
                val hits = Matcher.match(lines)
                val prices = PriceFinder.pricesIn(lines)
                val badges = PriceFinder.discountsIn(lines)
                val barriers = Matcher.addonBarriers(lines)
                val evidence = PriceFinder.attach(hits, prices, badges, barriers, bmp.width, bmp.height)
                lastHits = hits
                lastEvidence = evidence
                bmp.recycle()
                showResults(score())
                scanning = false
                requestMedians(hits)
            }
            .addOnFailureListener { e ->
                bmp.recycle()
                scanning = false
                toast("Text recognition failed: ${e.message}")
            }
    }

    private fun score(): List<Finding> =
        lastHits.map { Finding(it, Rules.evaluate(it.game, lastEvidence[it.game.id])) }
            .sortedWith(compareBy({ tierOrder(it.verdict.tier) }, { it.hit.box.top }))

    /**
     * Asks Oracle for the medians we are missing, then re-scores in place. The panel is already on
     * screen by this point, so a verdict can visibly firm up from NO BASELINE to a real percentage
     * a second or two later.
     */
    private fun requestMedians(hits: List<Hit>) {
        if (!prefs().getBoolean(KEY_LIVE, true)) return
        val wanted = hits.map { it.game }
            .filter { it.rating >= Rules.MIN_RATING && it.rank in 1 until Rules.MAX_RANK }
            .filter { GameIndex.needsLookup(it) }
            .distinctBy { it.id }
        if (wanted.isEmpty()) return
        lookupsInFlight += wanted.size
        for (g in wanted) {
            lookupPool.execute {
                val m = Oracle.medianFor(g)
                MedianCache.put(applicationContext, g.norm, m)
                main.post {
                    lookupsInFlight--
                    if (panelWin != null && lookupsInFlight <= 0) showResults(score())
                }
            }
        }
    }

    private fun prefs() = getSharedPreferences("dealchecker", Context.MODE_PRIVATE)

    private fun tierOrder(t: Tier) = when (t) {
        Tier.BUY -> 0
        Tier.NEAR -> 1
        Tier.NO_BASELINE -> 2
        Tier.NO_PRICE -> 3
        Tier.PASS -> 4
        Tier.OWNED -> 5
    }

    // ---------- results overlay ----------

    /**
     * Two separate windows on purpose. The highlight layer is FLAG_NOT_TOUCHABLE so every touch
     * falls through to whatever you were browsing — the page keeps scrolling normally. Only the
     * panel at the bottom takes input.
     */
    private fun showResults(results: List<Finding>) {
        val m = metrics()
        showHighlights(results, m)
        showPanel(results, m)
        startMotionWatch()
    }

    private fun showHighlights(results: List<Finding>, m: Metrics) {
        muteFrames()
        highlightWin?.let { it.findings = results; return }
        val hv = HighlightView(this)
        hv.findings = results
        val lp = WindowManager.LayoutParams(
            m.w, m.h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        highlightWin = hv
        try { wm.addView(hv, lp) } catch (e: Exception) { Log.e(TAG, "highlights: ${e.message}") }
    }

    private fun showPanel(results: List<Finding>, m: Metrics) {
        muteFrames()
        if (panelWin != null) { fillPanel(results, m); return }

        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(px(14f), px(10f), px(14f), px(16f))
            isClickable = true
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val headline = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(headline)
        head.addView(TextView(this).apply {
            text = "RESCAN"
            setTextColor(Color.parseColor("#4CD964"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(px(10f), 0, px(10f), 0)
            setOnClickListener { rescan() }
        })
        head.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(px(8f), 0, px(4f), 0)
            setOnClickListener { removeOverlay() }
        })
        panel.addView(head)

        panel.addView(TextView(this).apply {
            text = "BGG ≥ ${Rules.MIN_RATING}, rank < ${Rules.MAX_RANK}, ≥ ${Rules.MIN_DISCOUNT}% off the median, not already owned"
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 10f
            setPadding(0, px(2f), 0, px(8f))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (m.h * 0.38f).toInt()
            )
        }
        panel.addView(scroll)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        panelWin = panel
        panelList = list
        panelHeadline = headline
        try { wm.addView(panel, lp) } catch (e: Exception) { Log.e(TAG, "panel: ${e.message}") }
        fillPanel(results, m)

        // The panel covers part of the screen, and its own text reads like a store page — game
        // names, dollar amounts. A rescan must not read our own verdicts back in as evidence.
        panel.post {
            val loc = IntArray(2)
            panel.getLocationOnScreen(loc)
            panelRect = Rect(loc[0], loc[1], loc[0] + panel.width, loc[1] + panel.height)
        }
    }

    private fun fillPanel(results: List<Finding>, m: Metrics) {
        val buys = results.count { it.verdict.tier == Tier.BUY }
        panelHeadline?.text = when {
            results.isEmpty() -> "No board games found on screen"
            buys > 0 -> "$buys good buy" + (if (buys > 1) "s" else "") + " of ${results.size} found"
            else -> "${results.size} title" + (if (results.size > 1) "s" else "") + " found — nothing clears the bar"
        }
        val list = panelList ?: return
        list.removeAllViews()
        if (results.isEmpty()) {
            list.addView(
                ResultPanel.emptyCard(
                    this,
                    "Nothing matched. Scroll so the game titles are fully visible — the boxes redraw when you stop."
                )
            )
        } else {
            for (f in results) list.addView(ResultPanel.buildCard(this, f))
        }
    }

    private fun removeOverlay() {
        watching = false
        main.removeCallbacks(motionWatcher)
        highlightWin?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelWin?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        highlightWin = null
        panelWin = null
        panelList = null
        panelHeadline = null
        panelRect = null
        lastMotionAt = 0L
    }

    // ---------- keeping the boxes honest while you scroll ----------

    /**
     * A mirrored virtual display only produces a frame when the screen actually changes, so
     * "is there a new frame?" is a free scroll detector. Our own overlay changes produce frames
     * too, hence the short mute window after we touch the UI ourselves.
     */
    private fun muteFrames() {
        ignoreFramesUntil = android.os.SystemClock.uptimeMillis() + MUTE_MS
    }

    private fun startMotionWatch() {
        if (watching) return
        watching = true
        main.postDelayed(motionWatcher, MOTION_POLL)
    }

    private val motionWatcher = object : Runnable {
        override fun run() {
            if (panelWin == null) { watching = false; return }
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= ignoreFramesUntil) {
                if (capture?.hasNewFrame() == true) {
                    lastMotionAt = now
                    // Boxes are pinned to pixels from the old frame. The moment anything moves they
                    // are lying, so they go rather than mislabel a row.
                    highlightWin?.let { if (it.findings.isNotEmpty()) it.findings = emptyList() }
                } else if (lastMotionAt != 0L && now - lastMotionAt >= SETTLE_MS) {
                    lastMotionAt = 0L
                    rescan()
                }
            }
            main.postDelayed(this, MOTION_POLL)
        }
    }

    /** Re-reads the screen in place, keeping the panel open. */
    private fun rescan() {
        if (scanning || panelWin == null) return
        scanning = true
        val bmp = capture?.grab()
        if (bmp == null) { scanning = false; return }
        analyse(bmp)
    }
    // ---------- lifecycle ----------

    /**
     * A service launched with startForegroundService must reach startForeground within seconds or
     * the system kills the process — so even the give-up path has to go foreground briefly, and it
     * uses specialUse because the projection op is exactly what we failed to get.
     */
    /** Sideloaded builds have no adb, so start-up failures go where the app can show them. */
    private fun recordStartupProblem(text: String) {
        if (text.isBlank()) return
        try {
            java.io.File(filesDir, "last-crash.txt").writeText(
                "STARTUP PROBLEM\nandroid: ${Build.VERSION.SDK_INT} on ${Build.MODEL}\n\n$text"
            )
        } catch (_: Exception) {}
    }

    private fun bailOut(message: String) {
        startForegroundNotification(mediaProjection = false)
        toast(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(mediaProjection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Deal Checker", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Deal Checker is watching")
            .setContentText("Tap the BG bubble to scan the screen")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = when {
                mediaProjection -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // specialUse only exists from API 34; below that an untyped foreground is fine,
                // because the app-op requirement this path exists to dodge arrived in 34 too.
                Build.VERSION.SDK_INT >= 34 -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> 0
            }
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopEverything() {
        running = false
        removeOverlay()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        capture?.release(); capture = null
        try { lookupPool.shutdownNow() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        removeOverlay()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        capture?.release()
        try { projection?.stop() } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        main.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}
