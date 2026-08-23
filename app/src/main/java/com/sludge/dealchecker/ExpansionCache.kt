package com.sludge.dealchecker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * What Board Game Oracle says a product line actually is — base game, expansion, or accessory.
 *
 * The bundled index holds ranked base games only, so it has no way to know that "Terraforming Mars
 * Prelude" is an expansion rather than the base game with noise after it. Oracle does know, and the
 * answer never changes, so it is cached for a long time and keyed on the whole OCR line.
 */
object ExpansionCache {

    private const val FILE = "line-types.json"
    private const val TAG = "ExpansionCache"
    private const val TTL = 60L * 86_400_000L

    const val BASE = "boardgame"

    private data class Entry(val type: String, val at: Long)

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
                map[k] = Entry(e.getString("t"), e.optLong("a", 0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun key(line: String) = GameIndex.normalize(line)

    @Synchronized
    fun typeOf(line: String): String? {
        val e = map[key(line)] ?: return null
        if (System.currentTimeMillis() - e.at > TTL) return null
        return e.type
    }

    /** True only when Oracle has told us this line is NOT a base game. */
    fun isNotBaseGame(line: String): Boolean {
        val t = typeOf(line) ?: return false
        return t.isNotEmpty() && t != BASE
    }

    fun isKnown(line: String): Boolean = typeOf(line) != null

    @Synchronized
    fun put(ctx: Context, line: String, type: String?) {
        map[key(line)] = Entry(type ?: "", System.currentTimeMillis())
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, JSONObject().put("t", v.type).put("a", v.at))
            File(ctx.filesDir, FILE).writeText(o.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }
}
