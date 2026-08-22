package com.sludge.dealchecker

import kotlin.math.roundToInt

enum class Tier { BUY, NEAR, PASS, OWNED, UNKNOWN }

data class Verdict(
    val tier: Tier,
    val headline: String,
    val reason: String,
    val discount: Int?,
    val basis: String?,
    val price: Double?,
    val baseline: Double?
)

/**
 * The board-game-deal-tracker's own bars, unchanged:
 *   BGG rating >= 7.0, BGG rank < 2,500, >= 50% off the cross-store median, and not already owned.
 * A 35-49% discount is shown as a near miss rather than silently dropped, the same way the
 * dashboard keeps near misses visible.
 */
object Rules {
    const val MIN_RATING = 7.0
    const val MAX_RANK = 2500
    const val MIN_DISCOUNT = 50
    const val NEAR_DISCOUNT = 35

    fun evaluate(g: Game, price: Double?, listPrice: Double?): Verdict {
        if (GameIndex.isOwned(g)) {
            return Verdict(Tier.OWNED, "Already yours", "In your BGG collection", null, null, price, null)
        }

        val median = GameIndex.medianFor(g)
        var baseline: Double? = null
        var basis: String? = null
        if (median != null) {
            baseline = median
            basis = "cross-store median"
        } else if (price != null && listPrice != null && listPrice > price * 1.05) {
            baseline = listPrice
            basis = "on-screen list price (MSRP, often inflated)"
        }

        val discount = if (price != null && baseline != null && baseline > 0)
            ((1 - price / baseline) * 100).roundToInt() else null

        val fails = ArrayList<String>(2)
        if (g.rating < MIN_RATING) fails.add("rated %.1f".format(g.rating))
        if (g.rank <= 0 || g.rank >= MAX_RANK) fails.add(if (g.rank <= 0) "unranked" else "rank #${g.rank}")

        if (fails.isNotEmpty()) {
            return Verdict(Tier.PASS, "Pass", fails.joinToString(", "), discount, basis, price, baseline)
        }
        if (discount == null) {
            return Verdict(
                Tier.UNKNOWN, "Pedigree clears",
                if (price == null) "no price found on screen" else "no price baseline for this title",
                null, basis, price, baseline
            )
        }
        return when {
            discount >= MIN_DISCOUNT -> Verdict(Tier.BUY, "Good buy", "$discount% off the $basis", discount, basis, price, baseline)
            discount >= NEAR_DISCOUNT -> Verdict(Tier.NEAR, "Near miss", "only $discount% off (want ${MIN_DISCOUNT}%)", discount, basis, price, baseline)
            else -> Verdict(Tier.PASS, "Pass", "only $discount% off the $basis", discount, basis, price, baseline)
        }
    }
}
