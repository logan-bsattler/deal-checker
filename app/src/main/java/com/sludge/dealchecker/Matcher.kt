package com.sludge.dealchecker

import android.graphics.Rect

data class OcrLine(val text: String, val box: Rect)

data class Hit(
    val game: Game,
    val box: Rect,
    val matchedText: String,
    val confidence: Double,
    /** The full OCR line the match came from — what Oracle gets asked about. */
    val lineText: String = matchedText,
    /** True when the title was found inside a longer line, so it may be an expansion of it. */
    val partial: Boolean = false
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

    /**
     * Words that mean the line is an add-on, not the base game. The index holds ranked base games
     * only, so "Rush M.D. ICU Expansion" at $7.99 must never be scored against Rush M.D.'s $41.98
     * median — that reads as 81% off and is nothing of the kind.
     */
    private val ADDON = setOf(
        "expansion", "expansions", "exp", "promo", "promos", "pack", "packs", "minipack",
        "upgrade", "upgrades", "kit", "accessory", "accessories", "sleeves", "sleeve",
        "playmat", "playmats", "mat", "insert", "inserts", "organizer", "organiser",
        "miniatures", "minis", "meeples", "tokens", "dice", "bag", "bundle", "addon",
        "supplement", "scenario", "scenarios", "module", "modules", "deck", "booster",
        "replacement", "sticker", "stickers", "poster", "shirt", "puzzle"
    )

    /**
     * BGG names expansions "<Base>: <Something>". A base game that genuinely has a colon —
     * Brass: Birmingham — is in the index under its full name and matches as a whole line first,
     * so reaching here with a bare prefix before a separator means an expansion or a variant.
     */
    private val SEPARATOR = Regex("""\s*[:\u2013\u2014]\s*|\s+-\s+""")

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

    /**
     * Lines naming an add-on. They are not matched as games, but they are still product rows, so
     * they act as barriers: a price below "Rush M.D. ICU Expansion" belongs to the expansion and
     * must not travel upwards to the Rush M.D. heading above it.
     */
    fun addonBarriers(lines: List<OcrLine>): List<Rect> {
        val out = ArrayList<Rect>()
        for (l in lines) {
            val words = l.text.split(WORD_SPLIT)
            if (words.any { GameIndex.normalize(it) in ADDON }) { out.add(l.box); continue }
            // "<Known base game>: <something>" is a product row of its own, whatever it is called.
            val parts = l.text.split(SEPARATOR)
            if (parts.size >= 2 && parts[1].isNotBlank() &&
                GameIndex.exact(GameIndex.normalize(parts[0])) != null &&
                GameIndex.exact(GameIndex.normalize(l.text)) == null
            ) out.add(l.box)
        }
        return out
    }

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
                val wholeLine = size == words.size
                if (!acceptable(g, size, norm, wholeLine)) continue
                if (!wholeLine && isAddonLine(words, start, size, g)) continue
                if (!wholeLine && isPrefixBeforeSeparator(line.text, phrase)) continue
                return Hit(g, line.box, phrase, 1.0, line.text, !wholeLine)
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

    private fun isPrefixBeforeSeparator(lineText: String, phrase: String): Boolean {
        val parts = lineText.split(SEPARATOR)
        if (parts.size < 2 || parts[1].isBlank()) return false
        return GameIndex.normalize(parts[0]) == GameIndex.normalize(phrase)
    }

    /**
     * True when the leftover words around a sub-line match mark it as an add-on. Words already
     * inside the game's own title do not count — "Dominion: Guilds" is an expansion by nature, and
     * a game legitimately called "Booster Pack" should still match itself.
     */
    private fun isAddonLine(words: List<String>, start: Int, size: Int, g: Game): Boolean {
        val ownWords = g.name.split(WORD_SPLIT)
            .mapNotNull { GameIndex.normalize(it).takeIf { n -> n.isNotEmpty() } }
            .toSet()
        for (i in words.indices) {
            if (i >= start && i < start + size) continue
            val w = GameIndex.normalize(words[i])
            if (w.isEmpty() || w in ownWords) continue
            if (w in ADDON) return true
        }
        return false
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
