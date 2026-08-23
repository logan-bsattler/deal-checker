package com.sludge.dealchecker

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One store's listing for a game. */
data class Offer(
    val store: String,
    val listingName: String,
    val price: Double,
    val inStock: Boolean,
    val shipping: String?
)

/** Everything Oracle knows about a game, as far as this app cares. */
data class OracleGame(
    val key: String,
    val slug: String,
    val title: String,
    val year: Int?,
    val bggId: Int?,
    val median: Double?,
    val lowest: Double?,
    val lowestStore: String?,
    val low30: Double?,
    val low52: Double?,
    val highest: Double?,
    val offerCount: Int,
    val minPlayers: Int?,
    val maxPlayers: Int?,
    val minTime: Int?,
    val maxTime: Int?,
    val minAge: Int?,
    val publisher: String?
) {
    val pageUrl: String get() = "https://www.boardgameoracle.com/boardgame/price/$key/$slug"
}

/**
 * Cross-store prices from Board Game Oracle — the same source the deal tracker uses.
 *
 * Their site is a tRPC app, so there is a plain JSON API behind it:
 *   boardgame.list?input={"region":"us","q":"<name>"}   → candidates (title, year, key, type)
 *   boardgame.get?input={"region":"us","key":"<key>"}   → bgg_id, price_stats, player/time detail
 *   price.list?input={"key":"<key>","region":"us"}      → every merchant's current offer
 *
 * boardgame.get returns the BGG id, which is what makes this trustworthy: a candidate is only
 * accepted when its bgg_id equals the one in our index. Name matching alone is not good enough —
 * an Oracle search for "Terra Nova" returns both the 2006 and 2022 games, and the tracker has been
 * bitten by picking the wrong one before.
 */
object Oracle {

    private const val BASE = "https://www.boardgameoracle.com/api/trpc/"
    private const val TAG = "Oracle"
    private const val MAX_CANDIDATES = 4

    /**
     * A median drawn from one or two listings is not a market price. Bruxelles 1897 has shown a
     * $9.00 "median" off two offers — measuring a discount against that would be nonsense.
     */
    private const val MIN_OFFERS = 3

    /** @return the cross-store median, or null when Oracle has too little to say. */
    fun medianFor(game: Game): Double? {
        val g = resolve(game) ?: return null
        return if (g.offerCount >= MIN_OFFERS) g.median else null
    }

    /** Search, then confirm by BGG id. */
    fun resolve(game: Game): OracleGame? {
        return try {
            val candidates = search(game.name)
            if (candidates.isEmpty()) return null
            val ordered = candidates.sortedWith(
                compareBy(
                    { if (GameIndex.normalize(it.title) == game.norm) 0 else 1 },
                    { yearGap(it.year, game.year) }
                )
            )
            for (c in ordered.take(MAX_CANDIDATES)) {
                val d = byKey(c.key) ?: continue
                if (d.bggId != null && d.bggId.toString() == game.id) return d
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed for ${game.name}: ${e.message}")
            null
        }
    }

    fun byKey(key: String): OracleGame? {
        val body = get("boardgame.get", JSONObject().put("region", "us").put("key", key)) ?: return null
        val d = body.optJSONObject("result")?.optJSONObject("data") ?: return null
        return parseGame(d)
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

    /** Every merchant currently listing this game, cheapest first. */
    fun offers(key: String): List<Offer> {
        val body = get("price.list", JSONObject().put("key", key).put("region", "us")) ?: return emptyList()
        val items = body.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("items")
            ?: return emptyList()
        val out = ArrayList<Offer>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val m = o.optJSONObject("merchant")
            out.add(
                Offer(
                    store = m?.optString("short_name")?.ifEmpty { null }
                        ?: m?.optString("name")?.ifEmpty { null }
                        ?: o.optString("merchantSlug"),
                    listingName = o.optString("name"),
                    price = o.optDouble("price", 0.0),
                    inStock = o.optString("availability") == "in_stock",
                    shipping = m?.optJSONObject("shipping")?.optString("short")?.ifEmpty { null }
                )
            )
        }
        return out.filter { it.price > 0 }.sortedBy { it.price }
    }

    // ---------- parsing ----------

    private fun parseGame(d: JSONObject): OracleGame {
        val s = d.optJSONObject("price_stats")
        val det = d.optJSONObject("detail")
        val pubs = det?.optJSONArray("publisher")
        return OracleGame(
            key = d.optString("key"),
            slug = d.optString("slug"),
            title = d.optString("title"),
            year = num(d, "year_published")?.toInt(),
            bggId = num(d, "bgg_id")?.toInt(),
            median = s?.let { num(it, "discount_median_compare_price") }?.takeIf { it > 0 },
            lowest = s?.let { num(it, "lowest_price") },
            lowestStore = s?.optString("lowest_store_name")?.ifEmpty { null },
            low30 = s?.let { num(it, "lowest_30d") },
            low52 = s?.let { num(it, "lowest_52w") },
            highest = s?.let { num(it, "highest_price") },
            offerCount = s?.optInt("offer_count", 0) ?: 0,
            minPlayers = det?.let { num(it, "min_players") }?.toInt(),
            maxPlayers = det?.let { num(it, "max_players") }?.toInt(),
            minTime = det?.let { num(it, "min_play_time") }?.toInt(),
            maxTime = det?.let { num(it, "max_play_time") }?.toInt(),
            minAge = det?.let { num(it, "min_age") }?.toInt(),
            publisher = firstName(pubs)
        )
    }

    private fun num(o: JSONObject, k: String): Double? =
        if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null

    private fun firstName(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("name")?.ifEmpty { null }
    }

    private fun yearGap(a: Int?, b: String): Int {
        val y = b.toIntOrNull() ?: return 99
        if (a == null) return 98
        return Math.abs(a - y)
    }

    private data class Candidate(val title: String, val year: Int?, val key: String)

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
