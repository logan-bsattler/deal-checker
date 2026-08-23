package com.sludge.dealchecker

import android.content.Context
import kotlin.math.roundToInt

enum class Tier { BUY, NEAR, PASS, OWNED, NO_BASELINE, NO_PRICE }

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
 *
 * Only the *baseline* is negotiable, and it is ranked by how much it deserves to be trusted:
 *   1. the bundled cross-store median — a real market price
 *   2. a labelled list price on the page ("MSRP", "was") — usually inflated
 *   3. an unlabelled higher price sitting next to the sale price — probably the strike-through
 *   4. the store's own "-62%" badge — their arithmetic, against their own list price
 * The card always names which one was used, because a 60% discount off invented MSRP is not a deal.
 */
object Rules {

    /** The deal tracker's own bars, and what "Reset" restores. */
    const val DEFAULT_RATING = 7.0
    const val DEFAULT_RANK = 2500
    const val DEFAULT_DISCOUNT = 50

    private const val PREFS = "dealchecker"

    var MIN_RATING = DEFAULT_RATING
        private set
    var MAX_RANK = DEFAULT_RANK
        private set
    var MIN_DISCOUNT = DEFAULT_DISCOUNT
        private set

    /** The near-miss band tracks the buy bar rather than being set separately. */
    val NEAR_DISCOUNT: Int get() = maxOf(5, MIN_DISCOUNT - 15)

    val isDefault: Boolean
        get() = MIN_RATING == DEFAULT_RATING && MAX_RANK == DEFAULT_RANK && MIN_DISCOUNT == DEFAULT_DISCOUNT

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        MIN_RATING = p.getFloat("minRating", DEFAULT_RATING.toFloat()).toDouble()
        MAX_RANK = p.getInt("maxRank", DEFAULT_RANK)
        MIN_DISCOUNT = p.getInt("minDiscount", DEFAULT_DISCOUNT)
    }

    fun save(ctx: Context, rating: Double, rank: Int, discount: Int) {
        MIN_RATING = rating
        MAX_RANK = rank
        MIN_DISCOUNT = discount
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("minRating", rating.toFloat())
            .putInt("maxRank", rank)
            .putInt("minDiscount", discount)
            .apply()
    }

    /** One line describing the bars in force, used on the main screen and in the panel. */
    fun summary(): String =
        "BGG ≥ %.1f, rank < %,d, ≥ %d%% off the median, not already owned".format(MIN_RATING, MAX_RANK, MIN_DISCOUNT)

    fun evaluate(g: Game, ev: PriceEvidence?): Verdict {
        val price = ev?.price
        if (GameIndex.isOwned(g)) {
            return Verdict(Tier.OWNED, "Already yours", "In your BGG collection", null, null, price, null)
        }

        val median = GameIndex.medianFor(g)
        var baseline: Double? = null
        var basis: String? = null
        var discount: Int? = null

        if (median != null) {
            baseline = median
            basis = "cross-store median"
        } else if (price != null && ev?.list != null) {
            baseline = ev.list
            basis = if (ev.listIsLabelled) "the list price on the page" else "the struck-through price on the page"
        }

        if (price != null && baseline != null && baseline > 0) {
            discount = ((1 - price / baseline) * 100).roundToInt()
        } else if (ev?.badgePercent != null) {
            discount = ev.badgePercent
            basis = "the store's own %-off badge"
        }

        val fails = ArrayList<String>(2)
        if (g.rating < MIN_RATING) fails.add("rated %.1f".format(g.rating))
        if (g.rank <= 0 || g.rank >= MAX_RANK) fails.add(if (g.rank <= 0) "unranked" else "rank #${g.rank}")

        if (fails.isNotEmpty()) {
            return Verdict(Tier.PASS, "Pass", fails.joinToString(", "), discount, basis, price, baseline)
        }
        if (discount == null) {
            return if (price == null)
                Verdict(Tier.NO_PRICE, "Pedigree clears", "no price found next to it", null, null, null, null)
            else
                Verdict(Tier.NO_BASELINE, "Pedigree clears", "nothing to measure the price against", null, null, price, null)
        }
        return when {
            discount >= MIN_DISCOUNT -> Verdict(Tier.BUY, "Good buy", "$discount% off $basis", discount, basis, price, baseline)
            discount >= NEAR_DISCOUNT -> Verdict(Tier.NEAR, "Near miss", "only $discount% off (want $MIN_DISCOUNT%)", discount, basis, price, baseline)
            else -> Verdict(Tier.PASS, "Pass", "only $discount% off $basis", discount, basis, price, baseline)
        }
    }
}
