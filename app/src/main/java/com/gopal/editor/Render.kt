package com.gopal.editor

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the spoken word is emphasised. */
enum class Highlight { NONE, KARAOKE, BOX, WORD }

data class CaptionTemplate(
    val id: String, val en: String, val hi: String,
    val font: String,                 // bundled font file name in res/font
    val sizeK: Float = 1f,            // relative text size
    val fill: Int = Color.WHITE,
    val stroke: Int = Color.BLACK,
    val strokeK: Float = 0f,          // outline width as a fraction of text size
    val shadow: Boolean = false,
    val box: Int = 0,                 // background box colour (0 = none)
    val glow: Int = 0,                // neon glow colour (0 = none)
    val hl: Highlight = Highlight.NONE,
    val hlColor: Int = 0xFFFFE600.toInt(),
    val yK: Float = 0.80f,            // vertical centre of the caption block (0 = top, 1 = bottom)
    val upper: Boolean = false
)

object Templates {
    val all = listOf(
        CaptionTemplate("classic", "Classic", "क्लासिक", "mukta_extrabold", 1f, strokeK = 0.12f, shadow = true),
        CaptionTemplate("reels_bold", "Reels Bold", "रील्स बोल्ड", "mukta_extrabold", 1.22f, fill = 0xFFFFE600.toInt(), strokeK = 0.16f, shadow = true, yK = 0.72f, upper = true),
        CaptionTemplate("karaoke", "Karaoke", "कराओके", "mukta_extrabold", 1.05f, strokeK = 0.12f, shadow = true, hl = Highlight.KARAOKE, hlColor = 0xFF2EE86A.toInt()),
        CaptionTemplate("highlight_box", "Word Box", "वर्ड बॉक्स", "poppins_bold", 0.95f, strokeK = 0.06f, hl = Highlight.BOX, hlColor = 0xFF7C4DFF.toInt(), yK = 0.78f),
        CaptionTemplate("word_pop", "Word Pop", "वर्ड पॉप", "mukta_extrabold", 1.7f, strokeK = 0.14f, shadow = true, hl = Highlight.WORD, yK = 0.58f),
        CaptionTemplate("boxed", "Boxed", "बॉक्स", "mukta_bold", 0.9f, box = 0xCC000000.toInt(), yK = 0.82f),
        CaptionTemplate("neon", "Neon", "नियॉन", "baloo2", 1.1f, fill = Color.WHITE, glow = 0xFF00E5FF.toInt(), stroke = 0xFF00B8D4.toInt(), strokeK = 0.05f),
        CaptionTemplate("minimal", "Minimal", "मिनिमल", "poppins_semibold", 0.8f, shadow = true, yK = 0.88f),
    )
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Pure caption geometry (unit-tested): sizes in pixels of the output frame. */
object CaptionGeometry {
    const val BASE = 0.058f          // text size = 5.8% of the frame's short side × template size
    const val MAX_W = 0.86f          // caption block width limit
    const val SAFE = 0.06f           // keep 6% top/bottom safe margin

    fun textPx(w: Int, h: Int, sizeK: Float): Float = max(12f, min(w, h) * BASE * sizeK)
    fun maxWidth(w: Int): Int = (w * MAX_W).roundToInt()
    /** Top of a block of height [blockH] centred at yK, pushed inside the safe area. */
    fun blockTop(h: Int, blockH: Float, yK: Float): Float {
        val top = h * yK - blockH / 2f
        return top.coerceIn(h * SAFE, max(h * SAFE, h * (1 - SAFE) - blockH))
    }
}

private val FONT_IDS = mapOf(
    "mukta_regular" to R.font.mukta_regular, "mukta_bold" to R.font.mukta_bold, "mukta_extrabold" to R.font.mukta_extrabold,
    "notosansdevanagari" to R.font.notosansdevanagari, "poppins_regular" to R.font.poppins_regular, "poppins_semibold" to R.font.poppins_semibold,
    "poppins_bold" to R.font.poppins_bold, "anton_regular" to R.font.anton_regular, "baloo2" to R.font.baloo2
)

class FontBank(private val ctx: Context) {
    private val cache = HashMap<String, Typeface>()
    fun get(name: String): Typeface = cache.getOrPut(name) {
        val id = FONT_IDS[name] ?: 0
        (if (id != 0) runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull() else null) ?: Typeface.DEFAULT_BOLD
    }
}

/** Draws one caption frame. Used by BOTH the live preview and the export overlay, so they match. */
class CaptionRenderer(private val fonts: FontBank) {
    private val fillP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boxP = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(cv: Canvas, w: Int, h: Int, c: Caption?, tpl: CaptionTemplate, t: Long) {
        if (c == null || c.text.isBlank()) return
        val active = CaptionBuilder.activeWord(c, t)
        val words = c.words.ifEmpty { CaptionBuilder.evenWords(Segment(c.startMs, c.endMs, c.text)) }
        var text = (if (tpl.hl == Highlight.WORD) words.getOrNull(active)?.text ?: c.text else c.text).trim()
        if (tpl.upper) text = text.uppercase()
        var size = CaptionGeometry.textPx(w, h, tpl.sizeK)
        if (tpl.hl == Highlight.WORD) {   // pop-in: starts 18% bigger and settles in 140 ms
            val ws = words.getOrNull(active)?.startMs ?: c.startMs
            val k = ((t - ws).coerceAtLeast(0) / 140f).coerceAtMost(1f)
            size *= 1f + 0.18f * (1f - k)
        }
        val tf = fonts.get(tpl.font)
        listOf(fillP, strokeP, glowP).forEach { it.typeface = tf; it.textSize = size }
        val maxW = CaptionGeometry.maxWidth(w)

        // word ranges inside `text` (for highlight spans/boxes)
        val ranges = wordRanges(text, if (tpl.hl == Highlight.WORD) 1 else words.size)
        val spanned = SpannableString(text)
        if (tpl.hl == Highlight.KARAOKE && active in ranges.indices) {
            val r = ranges[active]
            spanned.setSpan(ForegroundColorSpan(tpl.hlColor), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fillP.color = tpl.fill
        fillP.style = Paint.Style.FILL
        if (tpl.shadow) fillP.setShadowLayer(size * 0.10f, 0f, size * 0.06f, 0xAA000000.toInt()) else fillP.clearShadowLayer()
        val fillL = layout(spanned, fillP, maxW)
        val lw = (0 until fillL.lineCount).maxOf { fillL.getLineWidth(it) }
        val bh = fillL.height.toFloat()
        val top = CaptionGeometry.blockTop(h, bh, tpl.yK)
        val left = (w - maxW) / 2f

        cv.save()
        cv.translate(left, top)
        if (tpl.box != 0) {
            boxP.color = tpl.box
            val pad = size * 0.35f
            val cx = maxW / 2f
            cv.drawRoundRect(RectF(cx - lw / 2 - pad, -pad * 0.6f, cx + lw / 2 + pad, bh + pad * 0.6f), size * 0.3f, size * 0.3f, boxP)
        }
        if (tpl.hl == Highlight.BOX && active in ranges.indices) {
            val r = ranges[active]
            val line = fillL.getLineForOffset(r.first)
            val x0 = fillL.getPrimaryHorizontal(r.first); val x1 = fillL.getPrimaryHorizontal(r.last + 1).let { if (it <= x0) fillL.getLineRight(line) else it }
            boxP.color = tpl.hlColor
            val pad = size * 0.14f
            cv.drawRoundRect(RectF(x0 - pad, fillL.getLineTop(line).toFloat(), x1 + pad, fillL.getLineBottom(line).toFloat()), size * 0.2f, size * 0.2f, boxP)
        }
        if (tpl.glow != 0) {
            glowP.color = tpl.glow
            glowP.style = Paint.Style.FILL
            glowP.maskFilter = BlurMaskFilter(size * 0.35f, BlurMaskFilter.Blur.NORMAL)
            layout(text, glowP, maxW).draw(cv)
        }
        if (tpl.strokeK > 0f) {
            strokeP.color = tpl.stroke
            strokeP.style = Paint.Style.STROKE
            strokeP.strokeJoin = Paint.Join.ROUND
            strokeP.strokeWidth = size * tpl.strokeK * 2f
            layout(text, strokeP, maxW).draw(cv)
        }
        fillL.draw(cv)
        cv.restore()
    }

    private fun layout(t: CharSequence, p: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f).setIncludePad(true).build()

    companion object {
        /** Character ranges of the first [n] whitespace-separated words of [text]. */
        fun wordRanges(text: String, n: Int): List<IntRange> {
            val out = ArrayList<IntRange>()
            Regex("\\S+").findAll(text).forEach { if (out.size < n) out += it.range }
            return out
        }
    }
}
