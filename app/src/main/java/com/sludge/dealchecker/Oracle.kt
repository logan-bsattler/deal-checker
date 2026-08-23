package com.sludge.dealchecker

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cross-store medians from Board Game Oracle — the same source the deal tracker uses.
 *
 * Their site is a tRPC app, so there is a plain JSON API behind it:
 *   boardgame.list?input={"region":"us","q":"<name>"}   → candidates (title, year, key)
 *   boardgame.get?input={"region":"us","key":"<key>"}   → bgg_id + price_stats
 *
 * The second call returns the BGG id, which is what makes this trustworthy: a candidate is only
 * accepted when its bgg_id equals the one in our index. Name matching alone is not good enough —
 * an Oracle search for "Terra Nova" returns both the 2006 and 2022 games, and the tracker has been
 * bitten by picking the wrong one before.
 */
object Oracle {

    private const val BASE = "https://www.boardgameoracle.com/api/trpc/"
    private const val TAG = "Oracle"
    private const val MAX_CANDIDATES = 4

    /**
     * A median drawn from one or two listings is not a market price. Bruxelles 1897 currently shows
     * a $9.00 "median" off two offers — measuring a discount against that would be nonsense.
     */
    private const val MIN_OFFERS = 3

    /** @return the cross-store median, or null if Oracle has no priced listing for this game. */
    fun medianFor(game: Game): Double? {
        return try {
            val candidates = search(game.name)
            if (candidates.isEmpty()) return null
            // Try the most plausible candidates first: exact title, then closest year.
            val ordered = candidates.sortedWith(
                compareBy(
                    { if (GameIndex.normalize(it.title) == game.norm) 0 else 1 },
                    { yearGap(it.year, game.year) }
                )
            )
            for (c in ordered.take(MAX_CANDIDATES)) {
                val d = detail(c.key) ?: continue
                if (d.bggId != null && d.bggId.toString() == game.id) {
                    return if (d.offers >= MIN_OFFERS) d.median else null
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "lookup failed for ${game.name}: ${e.message}")
            null
        }
    }

    private fun yearGap(a: Int?, b: String): Int {
        val y = b.toIntOrNull() ?: return 99
        if (a == null) return 98
        return Math.abs(a - y)
    }

    /**
     * What Oracle's best match for a whole product line is: "boardgame", "boardgameexpansion",
     * "boardgameaccessory", or null when it does not recognise the line at all.
     */
    fun typeOfLine(lineText: String): String? {
        return try {
            val body = get("boardgame.list", JSONObject().put("region", "us").put("q", lineText))
            val items = body?.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("items")
            if (items == null || items.length() == 0) return null
            items.optJSONObject(0)?.optString("type")?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "typeOfLine failed for $lineText: ${e.message}")
            null
        }
    }

    private data class Candidate(val title: String, val year: Int?, val key: String)
    private data class Detail(val bggId: Int?, val median: Double?, val offers: Int)

    private fun search(name: String): List<Candidate> {
        val body = get("boardgame.list", JSONObject().put("region", "us").put("q", name)) ?: return emptyList()
        val items = body.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Candidate>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("type") != "boardgame") continue
            val key = it.optString("key")
            if (key.isEmpty()) continue
            out.add(
                Candidate(
                    it.optString("title"),
                    if (it.has("year_published") && !it.isNull("year_published")) it.optInt("year_published") else null,
                    key
                )
            )
        }
        return out
    }

    private fun detail(key: String): Detail? {
        val body = get("boardgame.get", JSONObject().put("region", "us").put("key", key)) ?: return null
        val d = body.optJSONObject("result")?.optJSONObject("data") ?: return null
        val bgg = if (d.has("bgg_id") && !d.isNull("bgg_id")) d.optInt("bgg_id") else null
        val stats = d.optJSONObject("price_stats")
        val median = stats?.let {
            if (it.has("discount_median_compare_price") && !it.isNull("discount_median_compare_price"))
                it.optDouble("discount_median_compare_price") else null
        }
        val offers = stats?.optInt("offer_count", 0) ?: 0
        return Detail(bgg, median?.takeIf { it > 0 }, offers)
    }

    private fun get(procedure: String, input: JSONObject): JSONObject? {
        val url = BASE + procedure + "?input=" + URLEncoder.encode(input.toString(), "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            c.setRequestProperty("accept", "application/json")
            c.setRequestProperty("user-agent", "DealChecker/1.0 (personal use)")
            if (c.responseCode != 200) {
                Log.w(TAG, "$procedure -> HTTP ${c.responseCode}")
                return null
            }
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "$procedure failed: ${e.message}")
            null
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
        }
    }
}
