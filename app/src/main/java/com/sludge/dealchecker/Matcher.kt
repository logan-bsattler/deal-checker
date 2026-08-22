package com.sludge.dealchecker

import android.graphics.Rect

data class OcrLine(val text: String, val box: Rect)

data class Hit(
    val game: Game,
    val box: Rect,
    val matchedText: String,
    val confidence: Double
)

/**
 * Turns OCR lines into board-game hits.
 *
 * Strategy: for every line, try the longest contiguous run of words that resolves to a ranked BGG
 * title by exact normalised name; failing that, fall back to a token-indexed fuzzy match. The
 * acceptance rules exist to stop single common words ("Crossing", "Arctic", "Home") from matching
 * obscure titles on a page that has nothing to do with them.
 */
object Matcher {

    private const val MAX_WINDOW = 8
    private const val FUZZY_MIN = 0.88

    /** Site furniture that collides with real (obscure) BGG titles — never a game on the page. */
    private val PAGE_CHROME = setOf(
        "checkout", "privacy", "terms", "contact", "wishlist", "cart",
        "account", "login", "logout", "register", "signin", "signup",
        "search", "filter", "filters", "sort", "home", "menu",
        "help", "support", "reviews", "review", "shipping", "returns",
        "refund", "sale", "clearance", "categories", "category", "collections",
        "collection", "brands", "brand", "blog", "news", "events",
        "about", "careers", "sitemap", "newsletter", "giftcards", "giftcard",
        "bestsellers", "bestseller", "featured", "trending", "popular", "preorder",
        "preorders", "instock", "soldout", "outofstock", "quickview", "notifyme",
        "comparesimilar", "addtocart", "buynow", "viewall", "seeall", "showmore",
        "loadmore", "continue", "checkoutnow", "subtotal", "total", "quantity",
        "description", "details", "specifications", "shippingpolicy", "privacypolicy", "termsofservice",
        "contactus", "aboutus", "myaccount", "orderhistory", "trackorder"
    )

    // Unicode-aware: "Cóatl" must stay one word, not split into "C" and "atl".
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}'&:.\u2013-]+")

    fun match(lines: List<OcrLine>): List<Hit> {
        val hits = HashMap<String, Hit>()
        for (line in lines) {
            val hit = matchLine(line) ?: continue
            val prev = hits[hit.game.id]
            if (prev == null || area(hit.box) > area(prev.box)) hits[hit.game.id] = hit
        }
        return hits.values.sortedBy { it.box.top }
    }

    private fun area(r: Rect) = r.width().toLong() * r.height().toLong()

    private fun matchLine(line: OcrLine): Hit? {
        val words = line.text.split(WORD_SPLIT).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val maxW = minOf(MAX_WINDOW, words.size)
        for (size in maxW downTo 1) {
            for (start in 0..(words.size - size)) {
                val phrase = words.subList(start, start + size).joinToString(" ")
                val norm = GameIndex.normalize(phrase)
                if (norm.length < 3 || norm in PAGE_CHROME) continue
                val g = GameIndex.exact(norm) ?: continue
                if (!acceptable(g, size, norm, size == words.size)) continue
                return Hit(g, line.box, phrase, 1.0)
            }
        }

        // Fuzzy pass — OCR drops or mangles a character often enough that exact matching alone
        // misses real titles on glossy store pages.
        val normWords = words.map { GameIndex.normalize(it) }.filter { it.length >= 4 }
        val whole = GameIndex.normalize(line.text)
        if (whole.length < 8 || whole in PAGE_CHROME) return null
        val single = normWords.size < 2
        val probe = if (single) listOf(whole) else normWords

        var best: Game? = null
        var bestScore = 0.0
        val pool = if (single) GameIndex.affixCandidates(whole) else GameIndex.candidatesFor(probe)
        for (g in pool) {
            if (g.norm.length < 6) continue
            val lenRatio = g.norm.length.toDouble() / whole.length
            if (lenRatio < 0.6 || lenRatio > 1.6) continue
            val s = similarity(whole, g.norm)
            if (s > bestScore) { bestScore = s; best = g }
        }
        // A fuzzy match has to land on a game people have actually heard of, or page furniture
        // like "Strategy Games" starts resolving to obscure near-namesakes.
        val minSim = if (single) 0.86 else FUZZY_MIN
        val minUsers = if (single) 2000 else 500
        if (best != null && bestScore >= minSim && best.users >= minUsers && best.rank <= 15000 &&
            acceptable(best, 2, best.norm, true)
        ) {
            return Hit(best, line.box, line.text, bestScore)
        }
        return null
    }

    /**
     * Guards against false positives. A window covering the whole OCR line is trusted readily,
     * since that is how store pages print titles; a window buried inside a longer line has to be
     * long, or the game well known, before it counts.
     */
    private fun acceptable(g: Game, wordCount: Int, norm: String, wholeLine: Boolean): Boolean {
        // A very short name is only believed as an entire line, and only for a household title.
        if (norm.length <= 3) return wholeLine && g.rank in 1..300 && g.users >= 20000
        // A line that is nothing but the title is the strong case — store pages print them that
        // way — so single-word titles like Mezo or Otys are accepted here and nowhere else.
        if (wholeLine) return if (norm.length <= 5) g.users >= 300 else g.users >= 150
        if (norm.length <= 5) return g.rank in 1..600 && g.users >= 5000
        if (wordCount == 1 && norm.length < 9) return g.users >= 1000
        if (g.users < 50) return wordCount >= 2 && norm.length >= 10
        return true
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val d = levenshtein(a, b)
        val m = maxOf(a.length, b.length)
        if (m == 0) return 0.0
        return 1.0 - d.toDouble() / m
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur.copyOf()
        }
        return prev[b.length]
    }
}
