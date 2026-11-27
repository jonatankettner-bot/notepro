package com.gever.notepro

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row of [showMenu]. `checked` != null makes it a toggle row with a check mark. */
class MenuItem(
    val label: String,
    val checked: Boolean? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val action: () -> Unit = {},
)

/**
 * Our own popup menu: a rounded white card, right-to-left rows, check marks on the left,
 * red text for destructive rows. Replaces the system PopupMenu, which ignores RTL on English devices.
 */
fun showMenu(anchor: View, items: List<MenuItem>, minWidthDp: Int = 220) {
    val ctx = anchor.context
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = 16 * d; setStroke(1, LINE) }
        setPadding(dp(6), dp(6), dp(6), dp(6)); minimumWidth = dp(minWidthDp)
    }
    val pw = PopupWindow(box, -2, -2, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 10 * d
    }
    for (it in items) {
        if (it.separatorBefore) box.addView(View(ctx).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0); minimumHeight = dp(46)
            background = pressable(ctx, 10f)
            isEnabled = it.enabled; alpha = if (it.enabled) 1f else 0.4f
        }
        row.addView(TextView(ctx).apply {
            text = it.label; textSize = 15f; setTextColor(if (it.danger) DANGER else INK)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (it.checked != null) row.addView(TextView(ctx).apply {
            text = if (it.checked) "✓" else ""; textSize = 17f; setTextColor(ACCENT); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(dp(26), -2).apply { marginStart = dp(12) })
        val item = it
        row.setOnClickListener { pw.dismiss(); item.action() }
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    // Fix the width to the rows' natural width; with wrap_content the weighted labels stretched the menu
    // across half the screen.
    box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val w = maxOf(box.measuredWidth, dp(minWidthDp)); val h = box.measuredHeight
    pw.width = w
    // Open towards the middle of the screen: anchors on the left grow to the right, anchors low on the
    // screen (the new-note button) open upwards instead of off the bottom edge.
    val loc = IntArray(2); anchor.getLocationInWindow(loc)
    val root = anchor.rootView
    val onLeft = loc[0] + anchor.width / 2 < root.width / 2
    val x = (if (onLeft) loc[0] else loc[0] + anchor.width - w).coerceIn(dp(8), maxOf(dp(8), root.width - w - dp(8)))
    val below = loc[1] + anchor.height + dp(4)
    val y = if (below + h > root.height - dp(8)) maxOf(dp(8), loc[1] - h - dp(4)) else below
    pw.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
}

/** Rounded background with a soft grey pressed state. */
fun pressable(ctx: Context, radiusDp: Float, base: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable {
    val d = ctx.resources.displayMetrics.density
    val content = GradientDrawable().apply { setColor(base); cornerRadius = radiusDp * d }
    val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusDp * d }
    return android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1F1C1F26), content, mask)
}

/** Switch in the app accent instead of the system teal. */
fun Switch.accent() {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFFFFFFFF.toInt()))
    trackTintList = ColorStateList(states, intArrayOf(0x802B59C3.toInt(), 0xFFC5CBD5.toInt()))
}

/** ImageView whose height follows its width (used for A4 note thumbnails). */
class AspectImageView(ctx: Context, private val ratio: Float) : ImageView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = View.MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * ratio).toInt())
    }
}

/** "היום, 16:42" / "אתמול, 09:10" / "27 בנוב׳" / "27 בנוב׳ 2025". */
fun relativeDate(t: Long): String {
    val he = Locale.forLanguageTag("he")
    val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { timeInMillis = t }
    val time = SimpleDateFormat("HH:mm", he).format(Date(t))
    val dayDiff = run {
        val a = Calendar.getInstance().apply { timeInMillis = now.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val b = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        ((a.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
    }
    return when {
        dayDiff == 0 -> "היום, $time"
        dayDiff == 1 -> "אתמול, $time"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> SimpleDateFormat("d בMMM", he).format(Date(t))
        else -> SimpleDateFormat("d בMMM yyyy", he).format(Date(t))
    }
}
