package com.sludge.dealchecker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

data class Finding(val hit: Hit, val verdict: Verdict)

object Palette {
    fun color(t: Tier): Int = when (t) {
        Tier.BUY -> Color.parseColor("#4CD964")
        Tier.NEAR -> Color.parseColor("#E9C46A")
        Tier.PASS -> Color.parseColor("#9AA0A6")
        Tier.OWNED -> Color.parseColor("#5AB0F2")
        Tier.NO_BASELINE -> Color.parseColor("#B39DDB")
        Tier.NO_PRICE -> Color.parseColor("#8A9094")
    }

    fun label(t: Tier): String = when (t) {
        Tier.BUY -> "GOOD BUY"
        Tier.NEAR -> "NEAR MISS"
        Tier.PASS -> "PASS"
        Tier.OWNED -> "OWNED"
        Tier.NO_BASELINE -> "NO BASELINE"
        Tier.NO_PRICE -> "NO PRICE"
    }
}

/** Draws a coloured box around every recognised title, with a verdict chip above it. */
class HighlightView(ctx: Context) : View(ctx) {

    var findings: List<Finding> = emptyList()
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(10.5f)
        isFakeBoldText = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        for (f in findings) {
            val c = Palette.color(f.verdict.tier)
            val b = f.hit.box
            val r = RectF(
                (b.left - dp(4f)), (b.top - dp(3f)),
                (b.right + dp(4f)), (b.bottom + dp(3f))
            )
            fill.color = (c and 0x00FFFFFF) or 0x26000000
            canvas.drawRoundRect(r, dp(6f), dp(6f), fill)
            stroke.color = c
            canvas.drawRoundRect(r, dp(6f), dp(6f), stroke)

            val txt = Palette.label(f.verdict.tier) +
                (f.verdict.discount?.let { "  ${it}%" } ?: "")
            val tw = chipText.measureText(txt)
            val chipH = dp(18f)
            var cx = r.left
            var cy = r.top - chipH - dp(3f)
            if (cy < 0) cy = r.bottom + dp(3f)
            if (cx + tw + dp(12f) > width) cx = width - tw - dp(12f)
            chipBg.color = c
            canvas.drawRoundRect(
                RectF(cx, cy, cx + tw + dp(12f), cy + chipH), dp(4f), dp(4f), chipBg
            )
            canvas.drawText(txt, cx + dp(6f), cy + chipH - dp(5f), chipText)
        }
    }
}

/** Builds the scrollable verdict list that sits at the bottom of the screen. */
object ResultPanel {

    fun buildCard(ctx: Context, f: Finding): View {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(px(12f), px(10f), px(12f), px(10f))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = px(8f) }
        card.layoutParams = lp

        val g = f.hit.game
        val v = f.verdict

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chip = TextView(ctx).apply {
            text = Palette.label(v.tier)
            setTextColor(Color.BLACK)
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(px(7f), px(2f), px(7f), px(3f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 4f * d
                setColor(Palette.color(v.tier))
            }
        }
        header.addView(chip)

        val title = TextView(ctx).apply {
            text = "  ${g.name}" + if (g.year.isNotBlank()) " (${g.year})" else ""
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        card.addView(header)

        val stats = TextView(ctx).apply {
            val bits = ArrayList<String>()
            bits.add("BGG %.1f".format(g.rating))
            bits.add("rank #${g.rank}")
            v.price?.let { bits.add("seen $" + "%.2f".format(it)) }
            v.baseline?.let { bits.add("vs $" + "%.2f".format(it)) }
            v.discount?.let { bits.add("$it% off") }
            text = bits.joinToString("   ")
            setTextColor(Color.parseColor("#CFD3D6"))
            textSize = 12f
            setPadding(0, px(5f), 0, 0)
        }
        card.addView(stats)

        val why = TextView(ctx).apply {
            text = v.headline + " — " + v.reason +
                (if (v.basis != null && v.tier != Tier.OWNED) "" else "")
            setTextColor(Palette.color(v.tier))
            textSize = 12f
            setPadding(0, px(3f), 0, 0)
        }
        card.addView(why)

        if (f.hit.confidence < 0.999) {
            card.addView(TextView(ctx).apply {
                text = "matched \"${f.hit.matchedText}\" (fuzzy ${(f.hit.confidence * 100).toInt()}%)"
                setTextColor(Color.parseColor("#8A9094"))
                textSize = 10f
                setPadding(0, px(2f), 0, 0)
            })
        }
        return card
    }

    fun emptyCard(ctx: Context, msg: String): View {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = msg
            setTextColor(Color.parseColor("#CFD3D6"))
            textSize = 13f
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        }
    }
}
