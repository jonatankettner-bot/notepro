package com.gever.notepro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/**
 * Docked pages panel (opens from the rail): page thumbnails with filter chips
 * All / Bookmarked / Written, a bookmark toggle per page and a per-page menu.
 */
class PagesPanel(private val act: Activity, private val canvas: NoteCanvasView, private val onClose: () -> Unit) {
    private enum class Filter { ALL, MARKED, WRITTEN }

    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    private var filter = Filter.ALL
    private val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = rounded(CARD, 20f).apply { setStroke(1, LINE) }; elevation = 8 * d
        setPadding(dp(14), dp(12), dp(14), dp(8))
        isClickable = true // keep touches from reaching the page underneath
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(act).apply {
            text = "עמודים"; textSize = 18f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(act).apply {
            text = "✕"; textSize = 18f; setTextColor(SUB); gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(header)
        addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
        addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(TextView(act).apply {
            text = "+ עמוד בסוף"; textSize = 15f; setTextColor(ACCENT); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { canvas.insertPageAfter(canvas.pages - 1); refresh(); canvas.scrollToPage(canvas.pages - 1) }
        })
    }

    fun refresh() {
        chips.removeAllViews()
        for ((f, label) in listOf(Filter.ALL to "הכול", Filter.MARKED to "מסומנים", Filter.WRITTEN to "כתובים")) {
            val on = f == filter
            chips.addView(TextView(act).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else ICON)
                background = rounded(if (on) ACCENT else 0xFFF1F3F6.toInt(), 14f)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                setOnClickListener { filter = f; refresh() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }

        list.removeAllViews()
        val cur = canvas.currentPage()
        val thumbW = dp(150)
        for (i in 0 until canvas.pages) {
            if (filter == Filter.MARKED && i !in canvas.bookmarks) continue
            if (filter == Filter.WRITTEN && !canvas.pageHasInk(i)) continue
            val frame = FrameLayout(act)
            val img = ImageView(act).apply {
                setImageBitmap(canvas.renderThumbnail(thumbW, i))
                background = rounded(Color.WHITE, 6f).apply { setStroke(dp(if (i == cur) 3 else 1), if (i == cur) ACCENT else LINE) }
                setPadding(dp(3), dp(3), dp(3), dp(3)); adjustViewBounds = true
                setOnClickListener { canvas.scrollToPage(i); refresh() }
            }
            frame.addView(img, FrameLayout.LayoutParams(thumbW + dp(6), -2))
            frame.addView(TextView(act).apply {
                val marked = i in canvas.bookmarks
                text = if (marked) "🔖" else "☆"; textSize = if (marked) 16f else 18f
                setTextColor(if (marked) ACCENT else 0xFFB0B6C0.toInt()); gravity = Gravity.CENTER
                setOnClickListener { canvas.toggleBookmark(i); refresh() }
            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.RIGHT))
            val more = TextView(act).apply {
                text = "⋯"; textSize = 20f; setTextColor(ICON); gravity = Gravity.CENTER
                background = rounded(0xE6FFFFFF.toInt(), 14f)
            }
            more.setOnClickListener { pageMenu(more, i) }
            frame.addView(more, FrameLayout.LayoutParams(dp(34), dp(28), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(6), 0, 0, dp(6)) })
            list.addView(frame, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            list.addView(TextView(act).apply { text = "${i + 1}"; textSize = 12f; setTextColor(if (i == cur) ACCENT else SUB); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2))
        }
        if (list.childCount == 0) list.addView(TextView(act).apply {
            text = if (filter == Filter.MARKED) "אין עמודים מסומנים.\nלחץ על ☆ כדי לסמן עמוד." else "אין עמודים כתובים עדיין."
            textSize = 14f; setTextColor(SUB); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
        })
    }

    private fun pageMenu(anchor: View, i: Int) {
        val pm = PopupMenu(act, anchor)
        pm.menu.add(0, 1, 0, "עמוד חדש אחרי")
        pm.menu.add(0, 2, 1, "שכפול עמוד")
        pm.menu.add(0, 3, 2, "ניקוי עמוד")
        if (canvas.pages > 1) pm.menu.add(0, 4, 3, "מחיקת עמוד")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> canvas.insertPageAfter(i)
                2 -> canvas.duplicatePage(i)
                3 -> canvas.clearPage(i)
                4 -> canvas.deletePage(i)
            }
            refresh(); true
        }
        pm.show()
    }
}
