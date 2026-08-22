package com.sludge.dealchecker

import android.graphics.Rect
import kotlin.math.abs

/** A dollar amount read off the screen. [isList] marks one labelled as MSRP / was / compare-at. */
data class PriceTag(val value: Double, val box: Rect, val isList: Boolean)

/** A discount the store printed itself: "-62%", "Save 55%", "40% off". */
data class DiscountTag(val percent: Int, val box: Rect)

/** Everything money-ish that could be attached to one title. */
data class PriceEvidence(
    val price: Double?,
    val list: Double?,
    val listIsLabelled: Boolean,
    val badgePercent: Int?
)

/**
 * Reads prices, list prices and discount badges off the screen and attaches them to titles.
 *
 * Two things matter more than precision here. First, stores print the discount themselves — a
 * "-62%" badge is usable even when no baseline price exists anywhere. Second, association runs
 * price-to-nearest-title rather than title-scans-a-band: on a grid of products that stops one
 * tile's price being claimed by its neighbour, which in turn makes it safe to search a wide radius
 * on a product page where the price sits well below the heading.
 */
object PriceFinder {

    private val RE_PRICE = Regex("""\$\s?([0-9]{1,3}(?:,[0-9]{3})*|[0-9]{1,5})(?:[.,]([0-9]{2}))?""")
    private val RE_PERCENT = Regex("""(\d{1,2})\s?%""")
    private val RE_LIST_LABEL = Regex(
        """\b(msrp|list price|list|was|reg\.?|regular|retail|compare at|compare|orig\.?|originally|value)\b""",
        RegexOption.IGNORE_CASE
    )
    private val RE_SAVINGS = Regex("""\b(you save|save)\b""", RegexOption.IGNORE_CASE)
    /** Amounts that belong to the page, not to a product: shipping thresholds, cart totals. */
    private val RE_NOT_A_PRICE = Regex(
        """\b(free shipping|orders? over|ships? free|spend|minimum|subtotal|total|coupon|gift card|shipping|delivery|financing|per month|/mo)\b""",
        RegexOption.IGNORE_CASE
    )
    private val RE_NOT_A_DISCOUNT = Regex(
        """\b(out of|stars?|rating|complete|cotton|battery|charged|off\s+coupon\s+code)\b""",
        RegexOption.IGNORE_CASE
    )

    fun pricesIn(lines: List<OcrLine>): List<PriceTag> {
        val out = ArrayList<PriceTag>()
        for (l in lines) {
            if (RE_NOT_A_PRICE.containsMatchIn(l.text)) continue
            val isList = RE_LIST_LABEL.containsMatchIn(l.text)
            // "You save $41.02" is a savings amount, not a price — it must not become the sale price.
            val isSavings = RE_SAVINGS.containsMatchIn(l.text) && !isList
            for (m in RE_PRICE.findAll(l.text)) {
                val whole = m.groupValues[1].replace(",", "")
                val cents = m.groupValues[2]
                val v = (whole.toDoubleOrNull() ?: continue) + (if (cents.isEmpty()) 0.0 else cents.toDouble() / 100.0)
                if (v < 1.0 || v > 2000.0) continue
                if (isSavings) continue
                out.add(PriceTag(v, l.box, isList))
            }
        }
        return out
    }

    fun discountsIn(lines: List<OcrLine>): List<DiscountTag> {
        val out = ArrayList<DiscountTag>()
        for (l in lines) {
            if (RE_NOT_A_DISCOUNT.containsMatchIn(l.text)) continue
            val looksLikeDiscount = l.text.contains('-') ||
                l.text.contains("off", true) || RE_SAVINGS.containsMatchIn(l.text)
            if (!looksLikeDiscount) continue
            for (m in RE_PERCENT.findAll(l.text)) {
                val p = m.groupValues[1].toIntOrNull() ?: continue
                if (p in 15..95) out.add(DiscountTag(p, l.box))
            }
        }
        return out
    }

    /**
     * Attaches each money token to the title it most plausibly belongs to, then folds each title's
     * tokens into one piece of evidence. Returns a map keyed by game id.
     */
    fun attach(
        hits: List<Hit>,
        prices: List<PriceTag>,
        badges: List<DiscountTag>,
        barriers: List<Rect>,
        screenW: Int,
        screenH: Int
    ): Map<String, PriceEvidence> {
        if (hits.isEmpty()) return emptyMap()

        val byHit = HashMap<String, MutableList<PriceTag>>()
        val badgeByHit = HashMap<String, MutableList<Int>>()

        for (p in prices) {
            nearestHit(p.box, hits, barriers, screenW, screenH)?.let {
                byHit.getOrPut(it.game.id) { ArrayList(4) }.add(p)
            }
        }
        for (b in badges) {
            nearestHit(b.box, hits, barriers, screenW, screenH)?.let {
                badgeByHit.getOrPut(it.game.id) { ArrayList(2) }.add(b.percent)
            }
        }

        val out = HashMap<String, PriceEvidence>()
        for (h in hits) {
            val tags = byHit[h.game.id].orEmpty()
            val badge = badgeByHit[h.game.id]?.maxOrNull()
            val sales = tags.filter { !it.isList }.map { it.value }
            val listed = tags.filter { it.isList }.map { it.value }

            // A lone labelled price with nothing else nearby is just the price.
            val price = sales.minOrNull() ?: if (listed.size == 1) listed[0] else null
            var list: Double? = null
            var labelled = false
            val bestListed = listed.maxOrNull()
            if (price != null && bestListed != null && bestListed > price * 1.05) {
                list = bestListed
                labelled = true
            } else if (price != null) {
                val hi = sales.maxOrNull()
                if (hi != null && hi > price * 1.15) list = hi
            }
            out[h.game.id] = PriceEvidence(price, list, labelled, badge)
        }
        return out
    }

    /**
     * Nearest title to a money token. Being below a title is the normal layout, so distance
     * upwards is penalised heavily — that keeps a tile's price from binding to the title beneath it.
     */
    private fun nearestHit(box: Rect, hits: List<Hit>, barriers: List<Rect>, screenW: Int, screenH: Int): Hit? {
        val maxDist = 0.20f * screenH
        var best: Hit? = null
        var bestScore = Float.MAX_VALUE
        val cx = box.centerX()
        val cy = box.centerY()
        for (h in hits) {
            val b = h.box
            val dy = when {
                cy > b.bottom -> (cy - b.bottom).toFloat()
                cy < b.top -> (b.top - cy).toFloat() * 2.5f
                else -> 0f
            }
            val dx = if (cx in b.left..b.right) 0f else minOf(abs(cx - b.left), abs(cx - b.right)).toFloat()
            if (dx > maxOf(0.45f * screenW, b.width().toFloat())) continue
            if (blocked(box, b, barriers)) continue
            val score = dy + dx * 0.6f
            if (score < maxDist && score < bestScore) { bestScore = score; best = h }
        }
        return best
    }

    /** True when an add-on row sits between the money token and this title. */
    private fun blocked(price: Rect, title: Rect, barriers: List<Rect>): Boolean {
        if (barriers.isEmpty()) return false
        val lo = minOf(price.centerY(), title.centerY())
        val hi = maxOf(price.centerY(), title.centerY())
        for (bar in barriers) {
            val by = bar.centerY()
            if (by <= lo || by >= hi) continue
            val overlaps = bar.right > minOf(price.left, title.left) &&
                bar.left < maxOf(price.right, title.right)
            if (overlaps) return true
        }
        return false
    }
}
