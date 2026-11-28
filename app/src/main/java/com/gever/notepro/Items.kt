package com.gever.notepro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Things placed on a page that are not ink: a picture, a sticker or a sticky note. */
enum class ItemType { IMAGE, STICKER, NOTE }

/** Sticky-note colours (own pastel set). */
val NOTE_COLORS = intArrayOf(0xFFFFE97A.toInt(), 0xFFFFC9D6.toInt(), 0xFFBFE6FF.toInt(), 0xFFC9F0C2.toInt(),
    0xFFFFD8A8.toInt(), 0xFFE0D4FF.toInt(), 0xFFF1F3F6.toInt())

/** Built-in stickers: emoji grouped by topic (rendered as pictures). */
val STICKERS: List<Pair<String, List<String>>> = listOf(
    "מדע" to listOf("🧪", "⚗️", "🔬", "🧬", "⚛️", "🧫", "💊", "🌡️", "🔥", "💧", "⚡", "🧲", "🌍", "🔭", "🧯", "☢️"),
    "סימונים" to listOf("⭐", "✅", "❌", "❗", "❓", "📌", "🎯", "💡", "🔑", "⚠️", "💯", "🏆", "➡️", "⬅️", "🔁", "🆕"),
    "לימודים" to listOf("📚", "📝", "📖", "🗓️", "⏰", "🧠", "✏️", "📐", "📏", "🖊️", "🎓", "💻", "🧮", "📊", "📎", "🗂️"),
    "רגש" to listOf("😀", "😎", "🤓", "🥳", "😴", "🤯", "😅", "🙃", "👍", "👏", "🙌", "💪", "❤️", "🎉", "☕", "🍀"),
)

/** One placed object, in page units: centre, size, rotation (radians). */
class PageItem(
    val id: String, val type: ItemType,
    var cx: Float, var cy: Float, var w: Float, var h: Float, var rot: Float = 0f,
    var file: String = "", var sticker: String = "", var text: String = "", var color: Int = NOTE_COLORS[0],
) {
    fun copy(newId: String = id) = PageItem(newId, type, cx, cy, w, h, rot, file, sticker, text, color)

    /** Corners x,y: top-left, top-right, bottom-right, bottom-left (before rotation, in that order). */
    fun corners(): FloatArray {
        val c = cos(rot); val s = sin(rot)
        val l = floatArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2)
        return FloatArray(8) { i -> val x = l[i - i % 2]; val y = l[i - i % 2 + 1]; if (i % 2 == 0) cx + x * c - y * s else cy + x * s + y * c }
    }

    fun bounds(): RectF { val k = corners(); return RectF(minOf(k[0], k[2], k[4], k[6]), minOf(k[1], k[3], k[5], k[7]), maxOf(k[0], k[2], k[4], k[6]), maxOf(k[1], k[3], k[5], k[7])) }

    /** Point in the item's own (unrotated, centred) frame. */
    fun local(x: Float, y: Float): FloatArray { val c = cos(-rot); val s = sin(-rot); val dx = x - cx; val dy = y - cy; return floatArrayOf(dx * c - dy * s, dx * s + dy * c) }

    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean { val l = local(x, y); return abs(l[0]) <= w / 2 + pad && abs(l[1]) <= h / 2 + pad }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("cx", cx.toDouble()).put("cy", cy.toDouble())
        .put("w", w.toDouble()).put("h", h.toDouble()).put("rot", rot.toDouble()).put("file", file).put("sticker", sticker)
        .put("text", text).put("color", color)

    companion object {
        fun fromJson(o: JSONObject): PageItem? = runCatching {
            PageItem(o.getString("id"), ItemType.valueOf(o.getString("type")), o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), o.optDouble("rot", 0.0).toFloat(), o.optString("file"),
                o.optString("sticker"), o.optString("text"), o.optInt("color", NOTE_COLORS[0]))
        }.getOrNull()
    }
}

/** Draws items (in page units; the canvas already has the page transform). */
class ItemPainter(private val loadImage: (String) -> Bitmap?) {
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
    private val missing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3E6EB.toInt() }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 20f; typeface = Typeface.DEFAULT }
    private val stickerCache = HashMap<String, Bitmap>()
    private val r = RectF()

    /** Emoji rendered once into a bitmap (very large glyphs do not draw reliably on a hardware canvas). */
    private fun stickerBitmap(e: String): Bitmap = stickerCache.getOrPut(e) {
        val size = 256
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.8f; textAlign = Paint.Align.CENTER }
        val fm = p.fontMetrics
        Canvas(b).drawText(e, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p)
        b
    }

    fun draw(c: Canvas, it: PageItem) {
        c.save()
        c.translate(it.cx, it.cy); c.rotate(Math.toDegrees(it.rot.toDouble()).toFloat())
        r.set(-it.w / 2, -it.h / 2, it.w / 2, it.h / 2)
        when (it.type) {
            ItemType.IMAGE -> {
                val b = loadImage(it.file)
                if (b != null) c.drawBitmap(b, null, r, bmpPaint) else c.drawRect(r, missing)
            }
            ItemType.STICKER -> c.drawBitmap(stickerBitmap(it.sticker), null, r, bmpPaint)
            ItemType.NOTE -> {
                val rad = 6f
                c.drawRoundRect(r.left + 2f, r.top + 4f, r.right + 2f, r.bottom + 5f, rad, rad, shadow)
                notePaint.color = it.color
                c.drawRoundRect(r, rad, rad, notePaint)
                c.drawRect(r.left, r.top, r.right, r.top + 10f, fold)
                if (it.text.isNotEmpty()) {
                    val pad = 14f
                    val width = (it.w - 2 * pad).toInt().coerceAtLeast(10)
                    val layout = StaticLayout.Builder.obtain(it.text, 0, it.text.length, textPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL)
                        .setLineSpacing(2f, 1f).build()
                    c.save(); c.clipRect(r); c.translate(r.left + pad, r.top + pad + 6f); layout.draw(c); c.restore()
                }
            }
        }
        c.restore()
    }
}
