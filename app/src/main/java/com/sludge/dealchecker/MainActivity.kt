package com.sludge.dealchecker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val REQ_PROJECTION = 7001
    private val PREFS = "dealchecker"
    private val KEY_URL = "medianUrl"

    private lateinit var status: TextView
    private lateinit var dbInfo: TextView
    private lateinit var urlField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showCrashIfAny()

        status = findViewById(R.id.status)
        dbInfo = findViewById(R.id.dbInfo)
        urlField = findViewById(R.id.medianUrl)

        Rules.load(applicationContext)
        setUpThresholds()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        urlField.setText(prefs.getString(KEY_URL, ""))

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (canDrawOverlays()) {
                toast("Already allowed")
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        val live = findViewById<CheckBox>(R.id.liveLookup)
        live.isChecked = prefs.getBoolean(ScanService.KEY_LIVE, true)
        live.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(ScanService.KEY_LIVE, on).apply()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startBubble() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
            status.text = "Bubble stopped."
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            val url = urlField.text.toString().trim()
            prefs.edit().putString(KEY_URL, url).apply()
            if (url.isEmpty()) { toast("Paste a medians.json URL first"); return@setOnClickListener }
            refreshMedians(url)
        }

        Thread {
            MedianCache.load(applicationContext)
            GameIndex.load(applicationContext)
            runOnUiThread { showDbInfo() }
        }.start()
    }

    // Slider positions map to values: rating 6.0–8.5 by 0.1, rank 250–10,000 by 250,
    // discount 20–80% by 5.
    private fun ratingOf(p: Int) = Math.round((6.0 + p * 0.1) * 10.0) / 10.0
    private fun rankOf(p: Int) = (p + 1) * 250
    private fun discountOf(p: Int) = 20 + p * 5

    private fun setUpThresholds() {
        val rules = findViewById<TextView>(R.id.rules)
        val lblRating = findViewById<TextView>(R.id.lblRating)
        val lblRank = findViewById<TextView>(R.id.lblRank)
        val lblDiscount = findViewById<TextView>(R.id.lblDiscount)
        val barRating = findViewById<SeekBar>(R.id.barRating)
        val barRank = findViewById<SeekBar>(R.id.barRank)
        val barDiscount = findViewById<SeekBar>(R.id.barDiscount)

        fun redraw() {
            lblRating.text = "BGG rating at least %.1f".format(ratingOf(barRating.progress))
            lblRank.text = "BGG rank better than #%,d".format(rankOf(barRank.progress))
            lblDiscount.text = "At least ${discountOf(barDiscount.progress)}% off the baseline" +
                "   ·   near miss from ${maxOf(5, discountOf(barDiscount.progress) - 15)}%"
            rules.text = Rules.summary() +
                if (Rules.isDefault) "\nSame bars as the deal tracker." else "\nCustomised — the tracker uses 7.0 / 2,500 / 50%."
        }

        fun commit() {
            Rules.save(
                applicationContext,
                ratingOf(barRating.progress),
                rankOf(barRank.progress),
                discountOf(barDiscount.progress)
            )
            redraw()
        }

        fun positions() {
            barRating.progress = Math.round((Rules.MIN_RATING - 6.0) / 0.1).toInt().coerceIn(0, barRating.max)
            barRank.progress = (Rules.MAX_RANK / 250 - 1).coerceIn(0, barRank.max)
            barDiscount.progress = ((Rules.MIN_DISCOUNT - 20) / 5).coerceIn(0, barDiscount.max)
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = redraw()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = commit()
        }
        barRating.setOnSeekBarChangeListener(listener)
        barRank.setOnSeekBarChangeListener(listener)
        barDiscount.setOnSeekBarChangeListener(listener)

        findViewById<Button>(R.id.btnResetRules).setOnClickListener {
            Rules.save(applicationContext, Rules.DEFAULT_RATING, Rules.DEFAULT_RANK, Rules.DEFAULT_DISCOUNT)
            positions()
            redraw()
            toast("Back to the tracker's bars")
        }

        positions()
        redraw()
    }

    /** A sideloaded build has no adb attached, so the last stack trace is shown in the app. */
    private fun showCrashIfAny() {
        val f = File(filesDir, "last-crash.txt")
        val box = findViewById<TextView>(R.id.crashBox)
        val clear = findViewById<Button>(R.id.btnClearCrash)
        if (!f.exists()) return
        box.text = "LAST CRASH\n\n" + f.readText().take(6000)
        box.visibility = android.view.View.VISIBLE
        clear.visibility = android.view.View.VISIBLE
        clear.setOnClickListener {
            f.delete()
            box.visibility = android.view.View.GONE
            clear.visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        showDbInfo()
    }

    private fun showDbInfo() {
        GameIndex.loadError?.let {
            dbInfo.text = "Index failed to load — $it"
            return
        }
        dbInfo.text = if (GameIndex.ready)
            "${GameIndex.games.size} ranked BGG titles · ${GameIndex.ownedCount} games you own · " +
                "${GameIndex.medianCount()} bundled medians (${GameIndex.medianSource}) + " +
                "${GameIndex.learnedCount()} learned from Oracle\n" +
                "Overlay permission: ${if (canDrawOverlays()) "granted" else "NOT granted"}"
        else "Loading the BGG index…"
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun startBubble() {
        if (!canDrawOverlays()) {
            toast("Grant the overlay permission first")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7002)
            }
        }
        // Clear any half-started service first — a leftover instance holds a spent consent token.
        startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("startActivityForResult is fine for a single one-shot consent dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            status.text = "Screen capture declined — the bubble can't scan without it."
            return
        }
        val svc = Intent(this, ScanService::class.java)
            .setAction(ScanService.ACTION_START)
            .putExtra(ScanService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScanService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        status.text = "Bubble running. Go browse — tap it to scan. Drag it to move it."
        moveTaskToBack(true)
    }

    private fun refreshMedians(url: String) {
        status.text = "Fetching medians…"
        Thread {
            val msg = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.requestMethod = "GET"
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                // sanity-check before overwriting the working copy
                val arr = org.json.JSONArray(body)
                if (arr.length() == 0) throw IllegalStateException("empty list")
                arr.getJSONArray(0).getDouble(1)
                File(filesDir, "medians.json").writeText(body)
                GameIndex.reloadMedians(applicationContext)
                "Loaded ${arr.length()} medians."
            } catch (e: Exception) {
                "Refresh failed: ${e.message}"
            }
            runOnUiThread { status.text = msg; showDbInfo() }
        }.start()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
