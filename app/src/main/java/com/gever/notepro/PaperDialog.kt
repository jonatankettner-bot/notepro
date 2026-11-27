package com.gever.notepro

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gever.notepro.NoteCanvasView.Paper

/** Paper colours offered in the template dialog. */
val PAPER_COLORS = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFFFFFCF2.toInt(), 0xFFFBF3DF.toInt(), 0xFFF2F4F7.toInt(), 0xFFEAF2FC.toInt())

/**
 * Centred "template" dialog: four paper thumbnails, paper colour dots, Cancel / Save.
 * Nothing changes on the note until Save.
 */
class PaperDialog(
    private val act: Activity,
    startPaper: Paper,
    startColor: Int,
    private val onSave: (Paper, Int) -> Unit,
) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private var paper = startPaper
    private var color = startColor
    private val thumbs = mutableListOf<Thumb>()
    private val dots = mutableListOf<ColorDot>()

    fun show() {
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 20 * d }
            setPadding(dp(24f), dp(20f), dp(24f), dp(18f))
        }
        card.addView(TextView(act).apply {
            text = "תבנית"; textSize = 20f; setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.addView(label("רקע בסיסי"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) })

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((p, name) in listOf(Paper.BLANK to "ריק", Paper.LINED to "שורות", Paper.GRID to "משבצות", Paper.DOTS to "נקודות")) {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val t = Thumb(p).apply { setOnClickListener { paper = p; refresh() } }
            thumbs += t
            col.addView(t, LinearLayout.LayoutParams(dp(96f), dp(132f)))
            col.addView(TextView(act).apply { text = name; textSize = 14f; setTextColor(SUB); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
            row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14f) })
        }
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })

        card.addView(label("צבע נייר"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18f) })
        val colors = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in PAPER_COLORS) {
            val dot = ColorDot(c).apply { setOnClickListener { color = c; refresh() } }
            dots += dot
            colors.addView(dot, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginEnd = dp(4f) })
        }
        card.addView(colors, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })

        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(TextView(act).apply {
            text = "ביטול"; textSize = 15f; setTextColor(ACCENT); setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { dlg.dismiss() }
        })
        buttons.addView(TextView(act).apply {
            text = "שמירה"; textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = 12 * d }
            setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            setOnClickListener { onSave(paper, color); dlg.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22f) })

        refresh()
        dlg.setContentView(FrameLayout(act).apply { addView(card) })
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun label(s: String) = TextView(act).apply { text = s; textSize = 13f; setTextColor(SUB) }

    private fun refresh() { thumbs.forEach { it.invalidate() }; dots.forEach { it.invalidate() } }

    /** A tall mini page showing the pattern in the chosen paper colour. */
    private inner class Thumb(val p: Paper) : View(act) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9D6EA.toInt(); strokeWidth = 1f * d }
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10 * d; textAlign = Paint.Align.CENTER }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val sel = p == paper
            val inset = 3 * d
            r.set(inset, inset, width - inset, height - inset)
            fill.color = color
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            val step = 11 * d
            c.save(); c.clipRect(r)
            when (p) {
                Paper.BLANK -> Unit
                Paper.LINED -> { var y = r.top + 2 * step; while (y < r.bottom) { c.drawLine(r.left, y, r.right, y, line); y += step } }
                Paper.GRID -> {
                    var y = r.top + step; while (y < r.bottom) { c.drawLine(r.left, y, r.right, y, line); y += step }
                    var x = r.left + step; while (x < r.right) { c.drawLine(x, r.top, x, r.bottom, line); x += step }
                }
                Paper.DOTS -> {
                    var y = r.top + step
                    while (y < r.bottom) { var x = r.left + step; while (x < r.right) { c.drawCircle(x, y, 1.2f * d, line); x += step }; y += step }
                }
            }
            c.restore()
            border.color = if (sel) ACCENT else 0xFFDDE1E7.toInt()
            border.strokeWidth = (if (sel) 2.5f else 1f) * d
            c.drawRoundRect(r, 10 * d, 10 * d, border)
            if (sel) {
                val bw = 40 * d; val bh = 18 * d; val cx = width / 2f; val top = r.top + 8 * d
                c.drawRoundRect(cx - bw / 2, top, cx + bw / 2, top + bh, bh / 2, bh / 2, badgeBg)
                c.drawText("נוכחי", cx, top + bh / 2 + 3.5f * d, badgeText)
            }
        }
    }

    private inner class ColorDot(val c0: Int) : View(act) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c0 }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0xFFCDD3DC.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = ACCENT }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            c.drawCircle(cx, cy, 13 * d, fill); c.drawCircle(cx, cy, 13 * d, edge)
            if (c0 == color) c.drawCircle(cx, cy, 17.5f * d, ring)
        }
    }
}
