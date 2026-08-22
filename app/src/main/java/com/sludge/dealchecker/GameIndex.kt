package com.sludge.dealchecker

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** One ranked BoardGameGeek title. */
data class Game(
    val id: String,
    val name: String,
    val year: String,
    val rank: Int,
    val rating: Double,
    val users: Int,
    val norm: String
)

/**
 * Everything the verdict needs, loaded once and held for the life of the process:
 *  - the ranked BGG index (games.tsv.gz, ~31k titles)
 *  - Ben's owned collection (owned.json, from the deal tracker's OWNED array)
 *  - cross-store median prices (medians.json, from the tracker's ALL_DEALS rows)
 *
 * Medians can be refreshed at runtime; a downloaded copy in filesDir wins over the bundled asset.
 */
object GameIndex {

    private const val TAG = "GameIndex"
    private const val GAMES_ASSET = "games.tsv"

    val games = ArrayList<Game>(32000)
    private val byNorm = HashMap<String, Int>(48000)
    private val byToken = HashMap<String, MutableList<Int>>(48000)
    private val byAffix = HashMap<String, MutableList<Int>>(8000)

    private val ownedNorms = HashSet<String>()
    private val medians = HashMap<String, Double>()

    @Volatile var ready = false; private set
    @Volatile var loadError: String? = null; private set
    var medianSource = "bundled"; private set
    var ownedCount = 0; private set

    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "and", "or", "to", "in", "on", "for", "with", "game",
        "board", "edition", "new", "sale", "off", "free", "add", "cart", "price", "shipping",
        "buy", "now", "from", "by", "your", "you", "all", "out", "stock", "save", "deal", "deals"
    )

    fun normalize(s: String): String {
        val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) {
            if (c.code in 0x0300..0x036F) continue
            val lc = Character.toLowerCase(c)
            if (lc in 'a'..'z' || lc in '0'..'9') sb.append(lc)
        }
        var out = sb.toString()
        // drop a leading "the", matching the deal tracker's normName()
        val lower = s.trim().lowercase()
        if (lower.startsWith("the ")) {
            val alt = normalize(s.trim().substring(4))
            if (alt.length >= 3) out = alt
        }
        return out
    }

    @Synchronized
    fun load(ctx: Context) {
        if (ready) return
        val t0 = System.currentTimeMillis()
        try {
            loadGames(ctx)
            loadOwned(ctx)
            loadMedians(ctx)
        } catch (e: Throwable) {
            // A broken asset must not take the whole process down with it.
            loadError = e.javaClass.simpleName + ": " + e.message
            Log.e(TAG, "load failed", e)
            return
        }
        ready = true
        Log.i(TAG, "loaded ${games.size} games, $ownedCount owned, ${medians.size} medians in ${System.currentTimeMillis() - t0}ms")
    }

    private fun loadGames(ctx: Context) {
        val present = try { ctx.assets.list("")?.toList() ?: emptyList() } catch (e: Exception) { listOf("<list failed>") }
        if (!present.contains(GAMES_ASSET)) {
            throw java.io.FileNotFoundException("$GAMES_ASSET missing; assets present: $present")
        }
        BufferedReader(InputStreamReader(ctx.assets.open(GAMES_ASSET), Charsets.UTF_8), 1 shl 16).use { r ->
            r.readLine() // header
            var line = r.readLine()
            while (line != null) {
                val p = line.split('\t')
                if (p.size >= 7) {
                    val g = Game(
                        id = p[0],
                        name = p[1],
                        year = p[2],
                        rank = p[3].toIntOrNull() ?: 0,
                        rating = p[4].toDoubleOrNull() ?: 0.0,
                        users = p[5].toIntOrNull() ?: 0,
                        norm = p[6]
                    )
                    val idx = games.size
                    games.add(g)
                    // rows arrive rank-ascending, so the first writer of a norm is the better-ranked game
                    if (!byNorm.containsKey(g.norm)) byNorm[g.norm] = idx
                    for (tok in tokensOf(g.name)) {
                        byToken.getOrPut(tok) { ArrayList(4) }.add(idx)
                    }
                    // Head/tail trigrams of well-known titles, so a single mangled word
                    // ("Wlngspan") still finds its way home through the fuzzy pass.
                    if (g.users >= 2000 && g.norm.length in 6..18) {
                        byAffix.getOrPut("p" + g.norm.substring(0, 3)) { ArrayList(4) }.add(idx)
                        byAffix.getOrPut("s" + g.norm.substring(g.norm.length - 3)) { ArrayList(4) }.add(idx)
                    }
                }
                line = r.readLine()
            }
        }
    }

    private fun tokensOf(name: String): List<String> {
        val out = ArrayList<String>(4)
        for (raw in name.split(Regex("[^A-Za-z0-9]+"))) {
            if (raw.length < 4) continue
            val t = normalize(raw)
            if (t.length >= 4 && t !in STOPWORDS) out.add(t)
        }
        return out
    }

    private fun loadOwned(ctx: Context) {
        try {
            val txt = ctx.assets.open("owned.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val n = normalize(arr.getString(i))
                if (n.isNotEmpty()) ownedNorms.add(n)
            }
            ownedCount = arr.length()
        } catch (e: Exception) {
            Log.w(TAG, "owned.json: ${e.message}")
        }
    }

    private fun loadMedians(ctx: Context) {
        medians.clear()
        val local = File(ctx.filesDir, "medians.json")
        val txt = try {
            if (local.exists()) {
                medianSource = "downloaded " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(local.lastModified()))
                local.readText()
            } else {
                medianSource = "bundled"
                ctx.assets.open("medians.json").bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "medians: ${e.message}"); return
        }
        try {
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                val n = normalize(row.getString(0))
                val v = row.getDouble(1)
                if (n.isNotEmpty() && v > 0) medians[n] = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "medians parse: ${e.message}")
        }
    }

    fun reloadMedians(ctx: Context) {
        loadMedians(ctx)
    }

    fun medianCount(): Int = medians.size
    fun learnedCount(): Int = MedianCache.size()

    /** Bundled median first — it was verified by hand — then anything learned from Oracle. */
    fun medianFor(g: Game): Double? = medians[g.norm] ?: MedianCache.get(g.norm)

    /** A game worth asking Oracle about: no median yet, and not asked recently. */
    fun needsLookup(g: Game): Boolean =
        medians[g.norm] == null && !MedianCache.isKnown(g.norm) && !isOwned(g)
    fun isOwned(g: Game): Boolean = ownedNorms.contains(g.norm)

    fun exact(norm: String): Game? = byNorm[norm]?.let { games[it] }

    /** Well-known games sharing the query's first or last three characters. */
    fun affixCandidates(norm: String): List<Game> {
        if (norm.length < 6) return emptyList()
        val seen = HashSet<Int>()
        val out = ArrayList<Game>(32)
        for (key in listOf("p" + norm.substring(0, 3), "s" + norm.substring(norm.length - 3))) {
            val ids = byAffix[key] ?: continue
            if (ids.size > 400) continue
            for (i in ids) if (seen.add(i)) out.add(games[i])
        }
        return out
    }

    /** Candidate games that share a rare-ish token with the query, for fuzzy scoring. */
    fun candidatesFor(words: List<String>): List<Game> {
        val seen = HashSet<Int>()
        val out = ArrayList<Game>(64)
        for (w in words) {
            if (w.length < 4 || w in STOPWORDS) continue
            val ids = byToken[w] ?: continue
            if (ids.size > 300) continue        // too common to be a useful signal
            for (i in ids) if (seen.add(i)) out.add(games[i])
        }
        return out
    }
}
