package com.gever.notepro

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// Highlighter style popover: tip (slanted / straight / round), slant angle, stabilization.

private fun Context.hpx(v: Float) = v * resources.displayMetrics.density
private fun Context.hdp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Picture of a highlighter stroke with the given tip: the nib stamped along a wave (or a straight line). */
class HlTipPreview(ctx: Context, var tip: HlTip, var angle: Int, var inkColor: Int,
                   private val wave: Boolean = true, private val bandDp: Float = 9f) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val pos = FloatArray(2)
    private val nib = RectF()
    private val tmp = Path()
    private val band = Path()
    private val rot = Matrix()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        path.reset()
        if (wave) {
            path.moveTo(w * 0.14f, h * 0.62f)
            path.cubicTo(w * 0.32f, h * 0.12f, w * 0.46f, h * 0.2f, w * 0.55f, h * 0.5f)
            path.cubicTo(w * 0.64f, h * 0.82f, w * 0.78f, h * 0.84f, w * 0.88f, h * 0.36f)
        } else { path.moveTo(w * 0.3f, h * 0.5f); path.lineTo(w * 0.7f, h * 0.5f) }
        p.color = (inkColor and 0xFFFFFF) or (0x88 shl 24)
        val s = minOf(bandDp * d, h * 0.8f)
        // Union of nib stamps along the path, filled once so the overlap does not darken.
        band.reset()
        val pm = PathMeasure(path, false); val len = pm.length
        var t = 0f
        while (t <= len) {
            pm.getPosTan(t, pos, null)
            tmp.reset()
            when (tip) {
                HlTip.SLANTED -> {
                    nib.set(pos[0] - s * 0.13f, pos[1] - s * 0.5f, pos[0] + s * 0.13f, pos[1] + s * 0.5f)
                    tmp.addRect(nib, Path.Direction.CW)
                    rot.setRotate(angle.toFloat(), pos[0], pos[1]); tmp.transform(rot)   // positive = top leans right
                }
                HlTip.STRAIGHT -> { nib.set(pos[0] - s * 0.35f, pos[1] - s * 0.35f, pos[0] + s * 0.35f, pos[1] + s * 0.35f); tmp.addRect(nib, Path.Direction.CW) }
                HlTip.ROUND -> tmp.addCircle(pos[0], pos[1], s * 0.35f, Path.Direction.CW)
            }
            band.op(tmp, Path.Op.UNION)
            t += 1.5f * d
        }
        c.drawPath(band, p)
    }
}

class HlStylePopover(
    private val act: Activity,
    private var tip: HlTip,
    private var angle: Int,
    private var stab: Int,
    private val inkColor: Int,
    private val onChange: (HlTip, Int, Int) -> Unit,
) {
    fun show(anchor: View) {
        val ctx = act
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun valueView() = TextView(ctx).apply { textSize = 16f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        fun head(text: String, value: TextView?): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                this.text = text; textSize = 16f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) addView(value)
        }
        fun sub(text: String) = TextView(ctx).apply { this.text = text; textSize = 12f; setTextColor(SUB); textAlignment = View.TEXT_ALIGNMENT_VIEW_START }

        // ---- tip cards ----
        col.addView(head("סוג המרקר", null), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.hdp(6), 0, ctx.hdp(6), ctx.hdp(4)) })
        val cards = mutableListOf<Pair<HlTip, View>>()
        val angleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun paint() {
            for ((t, v) in cards) v.background = GradientDrawable().apply {
                cornerRadius = ctx.hpx(16f)
                if (t == tip) { setColor(ACCENT_SOFT); setStroke(ctx.hdp(2), ACCENT) } else setColor(0xFFF4F6F9.toInt())
            }
            angleBox.alpha = if (tip == HlTip.SLANTED) 1f else 0.4f
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var slantedPreview: HlTipPreview? = null
        for ((t, label) in listOf(HlTip.SLANTED to "מוטה", HlTip.STRAIGHT to "ישר", HlTip.ROUND to "מעוגל")) {
            val pv = HlTipPreview(ctx, t, angle, inkColor)
            if (t == HlTip.SLANTED) slantedPreview = pv
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ctx.hdp(8), 0, ctx.hdp(8))
                addView(pv, LinearLayout.LayoutParams(ctx.hdp(70), ctx.hdp(38)))
                addView(TextView(ctx).apply { text = label; textSize = 14f; setTextColor(ICON); gravity = Gravity.CENTER })
                setOnClickListener { tip = t; paint(); onChange(tip, angle, stab) }
            }
            cards += t to card
            row.addView(card, LinearLayout.LayoutParams(ctx.hdp(100), -2).apply { setMargins(ctx.hdp(4), ctx.hdp(4), ctx.hdp(4), ctx.hdp(4)) })
        }
        col.addView(row)

        // ---- slant angle (slanted tip) ----
        val angValue = valueView()
        angleBox.addView(head("זווית ההטייה", angValue), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.hdp(6), ctx.hdp(12), ctx.hdp(6), 0) })
        angleBox.addView(sub("מ־60°- עד 60°+ · ‏0° = עומד ישר"), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.hdp(6), ctx.hdp(2), ctx.hdp(6), 0) })
        val angRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nibPv = HlTipPreview(ctx, HlTip.SLANTED, angle, inkColor, wave = false, bandDp = 30f)
        fun angLabel() { angValue.text = "‎" + (if (angle > 0) "+" else "") + angle + "°" }
        val slider = StepSlider(ctx, 25, (angle + 60) / 5 + 1) { v ->
            angle = (v - 1) * 5 - 60; angLabel()
            nibPv.angle = angle; nibPv.invalidate()
            slantedPreview?.let { it.angle = angle; it.invalidate() }
            if (tip != HlTip.SLANTED) { tip = HlTip.SLANTED; paint() }
            onChange(tip, angle, stab)
        }.apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }   // angles read from − on the left to + on the right
        angRow.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
        angRow.addView(nibPv, LinearLayout.LayoutParams(ctx.hdp(44), ctx.hdp(44)).apply { marginStart = ctx.hdp(10) })
        angleBox.addView(angRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.hdp(4), ctx.hdp(4), ctx.hdp(4), 0) })
        col.addView(angleBox, LinearLayout.LayoutParams(ctx.hdp(324), -2))

        col.addView(View(ctx).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(ctx.hdp(6), ctx.hdp(14), ctx.hdp(6), ctx.hdp(10)) })

        // ---- stabilization ----
        val stValue = valueView()
        col.addView(head("ייצוב", stValue), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.hdp(6), 0, ctx.hdp(6), 0) })
        val stRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val smooth = SmoothPreview(ctx, stab)
        fun stLabel() { stValue.text = String.format(java.util.Locale.US, "%.1f", stab / 10f) }
        stRow.addView(StepSlider(ctx, 11, stab + 1) { v -> stab = v - 1; stLabel(); smooth.level = stab; smooth.invalidate(); onChange(tip, angle, stab) },
            LinearLayout.LayoutParams(0, -2, 1f))
        stRow.addView(smooth, LinearLayout.LayoutParams(ctx.hdp(44), ctx.hdp(44)).apply { marginStart = ctx.hdp(10) })
        col.addView(stRow, LinearLayout.LayoutParams(ctx.hdp(324), -2).apply { setMargins(ctx.hdp(4), ctx.hdp(4), ctx.hdp(4), 0) })

        angLabel(); stLabel(); paint()
        showPopover(anchor, col)
    }
}
