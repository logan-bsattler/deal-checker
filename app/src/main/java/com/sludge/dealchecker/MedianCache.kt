package com.sludge.dealchecker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Medians the app has learned from Board Game Oracle, kept on disk so coverage grows as you browse.
 *
 * Negative results are cached too, and deliberately: a game Oracle does not track would otherwise
 * be looked up again on every single scan.
 */
object MedianCache {

    private const val FILE = "medians-learned.json"
    private const val TAG = "MedianCache"
    private const val DAY_MS = 86_400_000L
    private const val TTL_HIT = 7 * DAY_MS
    private const val TTL_MISS = 3 * DAY_MS

    private data class Entry(val median: Double, val at: Long)

    private val map = HashMap<String, Entry>()
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                map[k] = Entry(e.getDouble("m"), e.optLong("t", 0L))
            }
            Log.i(TAG, "loaded ${map.size} learned medians")
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    @Synchronized
    fun get(norm: String, now: Long = System.currentTimeMillis()): Double? {
        val e = map[norm] ?: return null
        val ttl = if (e.median > 0) TTL_HIT else TTL_MISS
        if (now - e.at > ttl) return null
        return if (e.median > 0) e.median else null
    }

    /** True when a fresh entry exists, hit or miss — i.e. do not look this up again. */
    @Synchronized
    fun isKnown(norm: String, now: Long = System.currentTimeMillis()): Boolean {
        val e = map[norm] ?: return false
        val ttl = if (e.median > 0) TTL_HIT else TTL_MISS
        return now - e.at <= ttl
    }

    @Synchronized
    fun put(ctx: Context, norm: String, median: Double?) {
        map[norm] = Entry(median ?: -1.0, System.currentTimeMillis())
        save(ctx)
    }

    @Synchronized
    fun size(): Int = map.count { it.value.median > 0 }

    private fun save(ctx: Context) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, JSONObject().put("m", v.median).put("t", v.at))
            File(ctx.filesDir, FILE).writeText(o.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }
}
