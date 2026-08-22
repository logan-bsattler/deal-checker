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
import android.widget.FrameLayout
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
        private const val TAG = "ScanService"

        @Volatile var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null

    private var bubble: View? = null
    private var overlay: View? = null
    private var scanning = false

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
        if (running) return START_STICKY

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == 0 || data == null) {
            bailOut("Screen capture permission missing — open Deal Checker and start again")
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection: ${e.message}"); null
        }
        if (p == null) {
            bailOut("Could not start screen capture — try starting the bubble again")
            return START_NOT_STICKY
        }

        // Only now does this app hold the android:project_media app op, which is what makes a
        // mediaProjection foreground service legal. Calling startForeground before this point
        // throws SecurityException on Android 14+.
        startForegroundNotification(mediaProjection = true)

        projection = p
        p.registerCallback(projectionCallback, main)

        startCapture()
        addBubble()
        running = true

        Thread {
            MedianCache.load(applicationContext)
            GameIndex.load(applicationContext)
        }.start()
        return START_STICKY
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
                for (block in text.textBlocks) for (l in block.lines) {
                    val bb: Rect = l.boundingBox ?: continue
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
                    if (overlay != null && lookupsInFlight <= 0) showResults(score())
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

    private fun showResults(results: List<Finding>) {
        removeOverlay()
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val m = metrics()

        val root = FrameLayout(this)
        root.setOnClickListener { removeOverlay() }

        val hv = HighlightView(this)
        hv.findings = results
        root.addView(hv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(px(14f), px(10f), px(14f), px(16f))
            isClickable = true
        }

        val buys = results.count { it.verdict.tier == Tier.BUY }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = when {
                results.isEmpty() -> "No board games found on screen"
                buys > 0 -> "$buys good buy" + (if (buys > 1) "s" else "") + " of ${results.size} found"
                else -> "${results.size} title" + (if (results.size > 1) "s" else "") + " found — nothing clears the bar"
            }
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(px(12f), 0, px(4f), 0)
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
        if (results.isEmpty()) {
            list.addView(ResultPanel.emptyCard(this,
                "Nothing matched. Scroll so the game titles are fully visible and tap the bubble again."))
        } else {
            for (f in results) list.addView(ResultPanel.buildCard(this, f))
        }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (m.h * 0.42f).toInt())
        }
        panel.addView(scroll)

        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val lp = WindowManager.LayoutParams(
            m.w, m.h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        overlay = root
        try { wm.addView(root, lp) } catch (e: Exception) { Log.e(TAG, "overlay: ${e.message}") }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    // ---------- lifecycle ----------

    /**
     * A service launched with startForegroundService must reach startForeground within seconds or
     * the system kills the process — so even the give-up path has to go foreground briefly, and it
     * uses specialUse because the projection op is exactly what we failed to get.
     */
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
