package com.sludge.dealchecker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tap-through view for one finding: what the game is, then where to buy it.
 *
 * Everything in the top half comes from the bundled index and the scan that just ran, so it draws
 * instantly. The store table needs Oracle, so it fills in underneath a moment later.
 */
object DetailSheet {

    fun build(
        ctx: Context,
        finding: Finding,
        onClose: () -> Unit,
        onOpenOracle: () -> Unit,
        onOpenBgg: () -> Unit
    ): Pair<View, LinearLayout> {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val g = finding.hit.game
        val v = finding.verdict

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(px(16f), px(12f), px(16f), px(16f))
            isClickable = true
        }

        // ---- header ----
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            text = "‹ BACK"
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClose() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(ctx).apply {
            text = Palette.label(v.tier)
            setTextColor(Color.BLACK)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(7f), px(2f), px(7f), px(3f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 4f * d
                setColor(Palette.color(v.tier))
            }
        })
        root.addView(head)

        root.addView(TextView(ctx).apply {
            text = g.name + if (g.year.isNotBlank()) "  (${g.year})" else ""
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(8f), 0, px(2f))
        })

        root.addView(TextView(ctx).apply {
            text = v.headline + " — " + v.reason
            setTextColor(Palette.color(v.tier))
            textSize = 13f
            setPadding(0, 0, 0, px(10f))
        })

        // ---- what the game is (offline) ----
        root.addView(sectionLabel(ctx, "THE GAME"))
        root.addView(row(ctx, "BGG rating", "%.2f".format(g.rating) + "  ·  ${fmt(g.users)} ratings"))
        root.addView(row(ctx, "BGG rank", "#${fmt(g.rank)}"))
        if (GameIndex.isOwned(g)) root.addView(row(ctx, "Collection", "you own this"))

        // Filled in once Oracle answers.
        val gameExtra = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gameExtra)

        // ---- price context from the scan ----
        root.addView(sectionLabel(ctx, "THIS LISTING"))
        root.addView(row(ctx, "On the page", v.price?.let { money(it) } ?: "no price found"))
        if (v.baseline != null) root.addView(row(ctx, "Measured against", money(v.baseline) + "  (${v.basis})"))
        if (v.discount != null) root.addView(row(ctx, "Discount", "${v.discount}%"))

        // ---- store table, filled in async ----
        val stores = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stores)
        stores.addView(sectionLabel(ctx, "EVERY STORE"))
        stores.addView(TextView(ctx).apply {
            text = "Asking Board Game Oracle…"
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 12f
            setPadding(0, px(4f), 0, 0)
        })

        // ---- links ----
        val links = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(14f), 0, 0)
        }
        links.addView(linkButton(ctx, "Board Game Oracle", onOpenOracle))
        links.addView(linkButton(ctx, "BoardGameGeek", onOpenBgg))
        root.addView(links)

        // The caller keeps a handle on the store section so it can be refilled when Oracle answers.
        root.setTag(R.id.detail_extra_tag, gameExtra)
        return Pair(root, stores)
    }

    /** Replaces the placeholder rows once Oracle has answered. */
    fun fillFromOracle(ctx: Context, root: View, stores: LinearLayout, og: OracleGame?, offers: List<Offer>) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val extra = root.getTag(R.id.detail_extra_tag) as? LinearLayout
        if (og != null && extra != null) {
            extra.removeAllViews()
            val players = when {
                og.minPlayers != null && og.maxPlayers != null && og.minPlayers != og.maxPlayers ->
                    "${og.minPlayers}–${og.maxPlayers}"
                og.minPlayers != null -> "${og.minPlayers}"
                else -> null
            }
            val time = when {
                og.minTime != null && og.maxTime != null && og.minTime != og.maxTime -> "${og.minTime}–${og.maxTime} min"
                og.minTime != null -> "${og.minTime} min"
                else -> null
            }
            players?.let { extra.addView(row(ctx, "Players", it)) }
            time?.let { extra.addView(row(ctx, "Play time", it)) }
            og.minAge?.let { extra.addView(row(ctx, "Age", "$it+")) }
            og.publisher?.let { extra.addView(row(ctx, "Publisher", it)) }
        }

        stores.removeAllViews()
        stores.addView(sectionLabel(ctx, "EVERY STORE"))

        if (og == null) {
            stores.addView(note(ctx, "Board Game Oracle does not have this one, or could not be reached."))
            return
        }

        og.lowest?.let { lo ->
            stores.addView(row(ctx, "Cheapest now", money(lo) + (og.lowestStore?.let { "  at $it" } ?: "")))
        }
        og.median?.let { stores.addView(row(ctx, "Cross-store median", money(it) + "  from ${og.offerCount} offers")) }
        og.low30?.let { stores.addView(row(ctx, "Lowest, 30 days", money(it))) }
        og.low52?.let { lo ->
            val flag = if (og.lowest != null && og.lowest <= lo + 0.001) "  ← at its floor" else ""
            stores.addView(row(ctx, "Lowest, 52 weeks", money(lo) + flag))
        }

        if (offers.isEmpty()) {
            stores.addView(note(ctx, "No live listings right now."))
            return
        }

        stores.addView(TextView(ctx).apply {
            text = "${offers.size} listing" + (if (offers.size > 1) "s" else "")
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 10f
            setPadding(0, px(8f), 0, px(2f))
        })

        for (o in offers) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(4f), 0, px(4f))
            }
            line.addView(TextView(ctx).apply {
                text = o.store + (if (o.inStock) "" else "  (out of stock)")
                setTextColor(if (o.inStock) Color.WHITE else Color.parseColor("#8A9094"))
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(ctx).apply {
                text = money(o.price)
                setTextColor(if (o.inStock) Color.WHITE else Color.parseColor("#8A9094"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            stores.addView(line)
            o.shipping?.let {
                stores.addView(TextView(ctx).apply {
                    text = it
                    setTextColor(Color.parseColor("#6E7478"))
                    textSize = 10f
                    setPadding(0, 0, 0, px(2f))
                })
            }
        }
    }

    // ---------- small builders ----------

    private fun money(v: Double) = "$" + "%.2f".format(v)

    private fun fmt(n: Int) = "%,d".format(n)

    private fun sectionLabel(ctx: Context, text: String): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#6E7478"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        }
    }

    private fun note(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 12f
        }
    }

    private fun row(ctx: Context, label: String, value: String): View {
        val d = ctx.resources.displayMetrics.density
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
        }
        line.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#8A9094"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        line.addView(TextView(ctx).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 12f
        })
        return line
    }

    private fun linkButton(ctx: Context, text: String, onClick: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#4CD964"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(Color.parseColor("#1FFFFFFF"))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * d).toInt() }
        }
    }
}
