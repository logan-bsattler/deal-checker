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

        findViewById<TextView>(R.id.rules).text =
            "A good buy means BGG rating ≥ ${Rules.MIN_RATING}, BGG rank under ${Rules.MAX_RANK}, " +
            "at least ${Rules.MIN_DISCOUNT}% off the cross-store median, and not already in your collection. " +
            "Same bars as the deal tracker."

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
