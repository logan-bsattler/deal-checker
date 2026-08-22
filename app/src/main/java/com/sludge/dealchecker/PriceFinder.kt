package com.sludge.dealchecker

import android.graphics.Rect
import kotlin.math.abs

data class PriceTag(val value: Double, val box: Rect)

/**
 * Pulls dollar amounts out of the OCR and attaches them to the nearest game title.
 *
 * Store pages almost always print the sale price directly under or beside the title, often next to
 * a struck-through list price. OCR gives no strike-through information, so when two prices sit in
 * the same neighbourhood the lower one is taken as what you'd pay and the higher one as the list
 * price the store is comparing against.
 */
object PriceFinder {

    private val RE = Regex("""\$\s?([0-9]{1,3}(?:,[0-9]{3})*|[0-9]{1,5})(?:[.,]([0-9]{2}))?""")

    fun pricesIn(lines: List<OcrLine>): List<PriceTag> {
        val out = ArrayList<PriceTag>()
        for (l in lines) {
            for (m in RE.findAll(l.text)) {
                val whole = m.groupValues[1].replace(",", "")
                val cents = m.groupValues[2]
                val v = (whole.toDoubleOrNull() ?: continue) + (if (cents.isEmpty()) 0.0 else cents.toDouble() / 100.0)
                if (v < 1.0 || v > 2000.0) continue
                out.add(PriceTag(v, l.box))
            }
        }
        return out
    }

    /** @return sale price to nearest, and a higher "was" price when one sits alongside it. */
    fun forHit(hit: Hit, prices: List<PriceTag>, screenW: Int, screenH: Int): Pair<Double?, Double?> {
        val b = hit.box
        val vTop = b.top - 0.06f * screenH
        val vBot = b.bottom + 0.22f * screenH
        val pad = (b.width() * 0.6f).toInt().coerceAtLeast((0.12f * screenW).toInt())
        val hMin = b.left - pad
        val hMax = b.right + pad

        val near = prices.filter {
            val cy = it.box.centerY()
            val cx = it.box.centerX()
            cy >= vTop && cy <= vBot && cx >= hMin && cx <= hMax
        }
        if (near.isEmpty()) return Pair(null, null)

        val nearest = near.minByOrNull {
            val dy = abs(it.box.centerY() - b.centerY()).toDouble()
            val dx = abs(it.box.centerX() - b.centerX()).toDouble()
            dy + dx * 0.35
        }!!

        val values = near.map { it.value }.distinct().sorted()
        val sale = values.first()
        val top = values.last()

        // Only trust the cluster when the nearest price is part of it; otherwise fall back to the
        // single nearest amount, which is the safest guess on a crowded page.
        val price = if (abs(nearest.value - sale) < 0.01 || near.size <= 3) sale else nearest.value
        val list = if (top > price * 1.15) top else null
        return Pair(price, list)
    }
}
