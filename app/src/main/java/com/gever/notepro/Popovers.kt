package com.gever.notepro

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private fun Context.px(v: Float) = v * resources.displayMetrics.density
private fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()
private fun roundRect(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r }

// ======================================================================================
// Bubble popover: a white card with a small arrow that points at the button it came from.
// ======================================================================================

class Bubble(ctx: Context) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    val shadow = 12 * d
    val arrowH = 9 * d
    var arrowX = 0f
    var arrowUp = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD; setShadowLayer(12 * d, 0f, 3 * d, 0x2E000000) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = LINE }
    private val path = Path()
    private val tri = Path()

    init { setWillNotDraw(false); layoutDirection = View.LAYOUT_DIRECTION_RTL }

    fun pad(inner: Int) {
        val s = shadow.toInt(); val a = arrowH.toInt()
        setPadding(s + inner, s + inner + (if (arrowUp) a else 0), s + inner, s + inner + (if (arrowUp) 0 else a))
    }

    override fun onDraw(c: Canvas) {
        val l = shadow; val r = width - shadow
        val t = shadow + if (arrowUp) arrowH else 0f
        val b = height - shadow - if (arrowUp) 0f else arrowH
        val rad = 18 * d
        val ax = arrowX.coerceIn(l + rad + arrowH, r - rad - arrowH)
        path.reset(); tri.reset()
        path.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW)
        val w = arrowH * 1.3f
        if (arrowUp) { tri.moveTo(ax - w, t + 1); tri.lineTo(ax, t - arrowH); tri.lineTo(ax + w, t + 1) }
        else { tri.moveTo(ax - w, b - 1); tri.lineTo(ax, b + arrowH); tri.lineTo(ax + w, b - 1) }
        tri.close()
        path.op(tri, Path.Op.UNION)
        c.drawPath(path, fill); c.drawPath(path, edge)
    }
}

/** Shows [content] in a [Bubble] under (or, if there is no room, over) [anchor]. */
fun showPopover(anchor: View, content: View, innerPadDp: Int = 14): PopupWindow {
    val ctx = anchor.context
    val bubble = Bubble(ctx)
    bubble.addView(content)
    val root = anchor.rootView
    val loc = IntArray(2); anchor.getLocationInWindow(loc)
    val cx = loc[0] + anchor.width / 2
    val spec = View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST)
    bubble.arrowUp = true; bubble.pad(ctx.dpi(innerPadDp))
    bubble.measure(spec, View.MeasureSpec.UNSPECIFIED)
    val sh = bubble.shadow.toInt()
    val below = loc[1] + anchor.height - sh + ctx.dpi(4)
    val fitsBelow = below + bubble.measuredHeight < root.height
    if (!fitsBelow) { bubble.arrowUp = false; bubble.pad(ctx.dpi(innerPadDp)); bubble.measure(spec, View.MeasureSpec.UNSPECIFIED) }
    val w = bubble.measuredWidth; val h = bubble.measuredHeight
    val x = (cx - w / 2).coerceIn(-sh + ctx.dpi(6), maxOf(-sh + ctx.dpi(6), root.width - w + sh - ctx.dpi(6)))
    val y = if (fitsBelow) below else loc[1] - h + sh - ctx.dpi(4)
    bubble.arrowX = (cx - x).toFloat()
    val pw = PopupWindow(bubble, w, h, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true; isClippingEnabled = false
    }
    pw.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
    return pw
}

// ======================================================================================
// Small views shared by the toolbar and the popovers
// ======================================================================================

/** Round colour swatch; ring (and a small chevron) when selected. */
class SwatchView(ctx: Context, swatch: Int, private val selected: Boolean, private val chevron: Boolean = selected,
                 private val radiusDp: Float = 11f) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val opaque = swatch or 0xFF000000.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x33000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = ACCENT }
    private val chev = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = if (Color.luminance(opaque) > 0.6f) INK else Color.WHITE
    }
    private val p = Path()
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = radiusDp * d
        c.drawCircle(cx, cy, r, fill); c.drawCircle(cx, cy, r, edge)
        if (selected) c.drawCircle(cx, cy, r + 4.5f * d, ring)
        if (chevron) {
            p.reset(); p.moveTo(cx - 3.5f * d, cy - 1.5f * d); p.lineTo(cx, cy + 2f * d); p.lineTo(cx + 3.5f * d, cy - 1.5f * d)
            c.drawPath(p, chev)
        }
    }
}

/** A width preset: grey circle with a dot of the preset's size; blue ring when selected. */
class WidthDot(ctx: Context, var dotRadiusDp: Float, var chosen: Boolean, var dotColor: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9ECF1.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = ACCENT }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, 15 * d, bg)
        if (chosen) c.drawCircle(cx, cy, 15 * d, ring)
        fill.color = dotColor or 0xFF000000.toInt()
        c.drawCircle(cx, cy, dotRadiusDp * d, fill)
    }
}

/** Colourful wheel used as the "custom colour" button. */
class HueWheel(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        p.shader = SweepGradient(cx, cy, intArrayOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
            0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF3B30.toInt()), null)
        c.drawCircle(cx, cy, 12 * d, p)
    }
}

/** Discrete slider: a rounded track with one dot per step and a vertical thumb. Mirrors in RTL. */
class StepSlider(ctx: Context, private val steps: Int, value: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var value = value.coerceIn(1, steps); private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EEFB.toInt() }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize((300 * d).toInt(), w), (46 * d).toInt())

    private val rtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
    private fun xOf(i: Int): Float {
        val pad = 14 * d; val f = (i - 1) / (steps - 1f).coerceAtLeast(1f)
        return if (rtl) width - pad - f * (width - 2 * pad) else pad + f * (width - 2 * pad)
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f; val th = 11 * d
        r.set(0f, cy - th, width.toFloat(), cy + th); c.drawRoundRect(r, th, th, track)
        val tx = xOf(value)
        if (rtl) r.set(tx, cy - th, width.toFloat(), cy + th) else r.set(0f, cy - th, tx, cy + th)
        c.drawRoundRect(r, th, th, filled)
        for (i in 1..steps) {
            val on = if (rtl) xOf(i) >= tx else xOf(i) <= tx
            dot.color = if (on) Color.WHITE else 0xFF2B59C3.toInt()
            if (i != value) c.drawCircle(xOf(i), cy, 2.4f * d, dot)
        }
        c.drawRect(tx - 5 * d, cy - th - 1, tx + 5 * d, cy + th + 1, gap)
        r.set(tx - 2.2f * d, cy - 19 * d, tx + 2.2f * d, cy + 19 * d); c.drawRoundRect(r, 2.2f * d, 2.2f * d, filled)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = 1; var bestD = Float.MAX_VALUE
                for (i in 1..steps) { val dd = abs(xOf(i) - e.x); if (dd < bestD) { bestD = dd; best = i } }
                if (best != value) { value = best; invalidate(); onChange(best) }
            }
        }
        return true
    }
}

/** Frame that turns a horizontal swipe into a page change (+1 = next, which in RTL is a swipe to the right). */
class PagerFrame(ctx: Context, private val onSwipe: (Int) -> Unit) : FrameLayout(ctx) {
    private val slop = 28 * ctx.resources.displayMetrics.density
    private var x0 = 0f; private var y0 = 0f; private var done = false
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; done = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - x0
                if (!done && abs(dx) > slop && abs(dx) > 1.5f * abs(e.y - y0)) {
                    done = true
                    val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
                    onSwipe(if ((dx > 0) == rtl) 1 else -1)
                    return true
                }
            }
        }
        return false
    }
    override fun onTouchEvent(e: MotionEvent) = true
}

// ======================================================================================
// Pen style previews
// ======================================================================================

private val RAINBOW = intArrayOf(0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
    0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt())

/** A sample line drawn in a pen style: a handwriting-like scribble, or a short wave for the toolbar button. */
class StylePreview(ctx: Context, var type: PenType, var effect: PenEffect, var inkColor: Int,
                   private val wave: Boolean = false, private val strokeDp: Float = 2.6f) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val sparkle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pos = FloatArray(2)

    private fun buildPath(w: Float, h: Float) {
        path.reset()
        if (wave) {
            path.moveTo(w * 0.08f, h * 0.6f)
            path.cubicTo(w * 0.28f, h * 0.05f, w * 0.45f, h * 0.15f, w * 0.55f, h * 0.5f)
            path.cubicTo(w * 0.65f, h * 0.85f, w * 0.8f, h * 0.9f, w * 0.92f, h * 0.35f)
        } else {
            path.moveTo(w * 0.1f, h * 0.78f)
            path.cubicTo(w * 0.2f, h * 0.05f, w * 0.34f, h * 0.12f, w * 0.3f, h * 0.78f)
            path.cubicTo(w * 0.36f, h * 0.3f, w * 0.5f, h * 0.18f, w * 0.52f, h * 0.72f)
            path.cubicTo(w * 0.58f, h * 0.35f, w * 0.72f, h * 0.25f, w * 0.7f, h * 0.7f)
            path.cubicTo(w * 0.74f, h * 0.9f, w * 0.86f, h * 0.82f, w * 0.92f, h * 0.55f)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        buildPath(w, h)
        paint.shader = if (effect == PenEffect.RAINBOW) LinearGradient(0f, 0f, w, 0f, RAINBOW, null, Shader.TileMode.CLAMP) else null
        paint.color = inkColor or 0xFF000000.toInt()
        val sw = strokeDp * d
        val pm = PathMeasure(path, false); val len = pm.length
        when (type) {
            PenType.PRESSURE -> {   // thin at the ends, thicker in the middle
                paint.style = Paint.Style.FILL
                var t = 0f
                while (t <= len) { pm.getPosTan(t, pos, null); c.drawCircle(pos[0], pos[1], sw * (0.25f + 0.45f * sin(PI * t / len).toFloat()), paint); t += 0.8f * d }
            }
            PenType.MARKER -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.pathEffect = null; c.drawPath(path, paint) }
            PenType.DASHED -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.pathEffect = DashPathEffect(floatArrayOf(4.5f * d, 4f * d), 0f); c.drawPath(path, paint); paint.pathEffect = null }
            PenType.DOTTED -> {
                paint.style = Paint.Style.FILL
                var t = 0f
                while (t <= len) { pm.getPosTan(t, pos, null); c.drawCircle(pos[0], pos[1], sw * 0.55f, paint); t += sw * 2.2f }
            }
        }
        if (effect == PenEffect.GLITTER) {
            val rnd = java.util.Random(5)
            var t = 0f
            while (t <= len) {
                pm.getPosTan(t, pos, null)
                sparkle.color = if (rnd.nextBoolean()) 0xEEFFFFFF.toInt() else 0xCCFFF4B8.toInt()
                c.drawCircle(pos[0] + (rnd.nextFloat() - 0.5f) * sw * 1.6f, pos[1] + (rnd.nextFloat() - 0.5f) * sw * 1.6f, (0.5f + rnd.nextFloat()) * d, sparkle)
                t += 1.6f * d
            }
        }
    }
}

/** Effect card picture: a rainbow band or a glittery band. */
class EffectSwatch(ctx: Context, private val effect: PenEffect) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val rad = 14 * d
        if (effect == PenEffect.RAINBOW) {
            p.shader = LinearGradient(0f, height.toFloat(), width.toFloat(), 0f, RAINBOW, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p); p.shader = null
        } else {
            p.color = 0xFFE7E4F2.toInt(); c.drawRoundRect(r, rad, rad, p)
            c.save(); c.clipRect(r)
            val rnd = java.util.Random(9)
            val tints = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFF1B8.toInt(), 0xFFD7E8FF.toInt(), 0xFFF5D7FF.toInt(), 0xFFC9C3DD.toInt())
            repeat(260) {
                p.color = tints[rnd.nextInt(tints.size)]
                c.drawCircle(rnd.nextFloat() * width, rnd.nextFloat() * height, (0.6f + rnd.nextFloat() * 1.4f) * d, p)
            }
            c.restore()
        }
    }
}

/** Shows what stabilization does: a shaky line that gets smoother as the value rises. */
class SmoothPreview(ctx: Context, var level: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F6.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF3A4A8A.toInt() }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = minOf(width, height) / 2f
        c.drawCircle(cx, cy, rad, bg)
        val n = 36; val rnd = java.util.Random(2)
        val raw = FloatArray(n) { i -> (sin(i / (n - 1f) * 2.6 * PI).toFloat() * 0.38f + (rnd.nextFloat() - 0.5f) * 0.42f) }
        val k = (level * 0.7f).roundToInt()
        val ys = FloatArray(n) { i -> var s = 0f; var m = 0; for (j in (i - k)..(i + k)) if (j in 0 until n) { s += raw[j]; m++ }; s / m }
        path.reset()
        for (i in 0 until n) {
            val x = cx - rad * 0.62f + (i / (n - 1f)) * rad * 1.24f
            val y = cy - ys[i] * rad * 0.6f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, ink)
    }
}

// ======================================================================================
// Colour popover: recent colours, swipeable palette pages, eyedropper and custom colour.
// ======================================================================================

class ColorPopover(
    private val act: Activity,
    private val pages: List<IntArray>,
    private val current: Int,
    private val onPick: (Int) -> Unit,
    private val onEyedropper: () -> Unit,
    private val onCustom: () -> Unit,
) {
    private var page = 0
    private var pw: PopupWindow? = null

    fun show(anchor: View) {
        val ctx = act
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply {
            text = "צבעים"; textSize = 17f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPaddingRelative(ctx.dpi(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_eyedropper); imageTintList = ColorStateList.valueOf(ICON)
            background = pressable(ctx, 12f); contentDescription = "דגימת צבע מהדף"
            setOnClickListener { pw?.dismiss(); onEyedropper() }
        }, LinearLayout.LayoutParams(ctx.dpi(42), ctx.dpi(42)))
        head.addView(FrameLayout(ctx).apply {
            background = pressable(ctx, 12f); contentDescription = "צבע מותאם אישית"
            addView(HueWheel(ctx), FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { pw?.dismiss(); onCustom() }
        }, LinearLayout.LayoutParams(ctx.dpi(42), ctx.dpi(42)))
        col.addView(head)

        // Recent colours, newest first.
        val recents = Prefs(ctx).recentColors
        val recentRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        recentRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_recent); imageTintList = ColorStateList.valueOf(SUB); scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))
        if (recents.isEmpty()) recentRow.addView(TextView(ctx).apply {
            text = "כאן יופיעו הצבעים שבחרת לאחרונה"; textSize = 13f; setTextColor(SUB)
        })
        for (c in recents) recentRow.addView(SwatchView(ctx, c, c == current, chevron = false).apply {
            setOnClickListener { pw?.dismiss(); onPick(c) }
        }, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))
        col.addView(recentRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dpi(4) })
        col.addView(View(ctx).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, ctx.dpi(8), 0, ctx.dpi(8)) })

        val grid = GridLayout(ctx).apply { columnCount = 8 }
        val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun render() {
            grid.removeAllViews()
            for (c in pages[page]) grid.addView(SwatchView(ctx, c, c == current, chevron = false).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = ctx.dpi(40); height = ctx.dpi(40) }
                setOnClickListener { pw?.dismiss(); onPick(c) }
            })
            dots.removeAllViews()
            for (i in pages.indices) dots.addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == page) ICON else 0xFFCBD2DC.toInt()) }
                setOnClickListener { page = i; render() }
            }, LinearLayout.LayoutParams(ctx.dpi(8), ctx.dpi(8)).apply { setMargins(ctx.dpi(5), ctx.dpi(10), ctx.dpi(5), ctx.dpi(2)) })
        }
        lateinit var pager: PagerFrame
        pager = PagerFrame(ctx) { dir -> page = (page + dir).coerceIn(0, pages.lastIndex); render() }
        pager.addView(grid, FrameLayout.LayoutParams(-2, -2))
        col.addView(pager, LinearLayout.LayoutParams(-2, -2))
        col.addView(dots, LinearLayout.LayoutParams(-1, ctx.dpi(24)))
        render()
        pw = showPopover(anchor, col)
    }
}

/** Custom colour: saturation/brightness square, hue bar, hex code. */
class ColorPickerDialog(private val act: Activity, initial: Int, private val onPick: (Int) -> Unit) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial or 0xFF000000.toInt(), it) }

    fun show() {
        val ctx = act; val d = ctx.resources.displayMetrics.density
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundRect(Color.WHITE, 20 * d); setPadding(ctx.dpi(22), ctx.dpi(18), ctx.dpi(22), ctx.dpi(16))
        }
        card.addView(TextView(ctx).apply {
            text = "צבע מותאם אישית"; textSize = 19f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val preview = View(ctx)
        val hex = EditText(ctx).apply {
            textSize = 15f; isSingleLine = true; textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = roundRect(0xFFF4F6F9.toInt(), 10 * d); setPadding(ctx.dpi(12), ctx.dpi(8), ctx.dpi(12), ctx.dpi(8))
        }
        var editing = false
        lateinit var sv: SatValView; lateinit var hue: HueBar
        fun sync(fromHex: Boolean = false) {
            val c = Color.HSVToColor(hsv)
            preview.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(1, 0x33000000) }
            if (!fromHex) { editing = true; hex.setText(String.format("#%06X", c and 0xFFFFFF)); editing = false }
            sv.invalidate(); hue.invalidate()
        }
        sv = SatValView(ctx, hsv) { sync() }
        hue = HueBar(ctx, hsv) { sync() }
        card.addView(sv, LinearLayout.LayoutParams(ctx.dpi(300), ctx.dpi(180)).apply { topMargin = ctx.dpi(14) })
        card.addView(hue, LinearLayout.LayoutParams(ctx.dpi(300), ctx.dpi(30)).apply { topMargin = ctx.dpi(12) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(preview, LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(36)))
        row.addView(hex, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.dpi(12) })
        card.addView(row, LinearLayout.LayoutParams(ctx.dpi(300), -2).apply { topMargin = ctx.dpi(14) })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                val t = s.toString().trim().removePrefix("#")
                if (t.length == 6) t.toLongOrNull(16)?.let { Color.colorToHSV(0xFF000000.toInt() or it.toInt(), hsv); sync(fromHex = true) }
            }
        })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(TextView(ctx).apply {
            text = "בחירה"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = pressable(ctx, 12f, ACCENT); setPadding(ctx.dpi(22), ctx.dpi(10), ctx.dpi(22), ctx.dpi(10))
            setOnClickListener { dlg.dismiss(); onPick(Color.HSVToColor(hsv)) }
        })
        buttons.addView(TextView(ctx).apply {
            text = "ביטול"; textSize = 15f; setTextColor(ACCENT); gravity = Gravity.CENTER
            background = pressable(ctx, 12f); setPadding(ctx.dpi(18), ctx.dpi(10), ctx.dpi(18), ctx.dpi(10))
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dpi(8) })
        card.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dpi(18) })
        sync()
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    private class SatValView(ctx: Context, private val hsv: FloatArray, private val changed: () -> Unit) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
        private val ringOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x66000000 }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val rad = 12 * d
            r.set(0f, 0f, w, h)
            p.shader = null; p.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)); c.drawRoundRect(r, rad, rad, p)
            p.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawRoundRect(r, rad, rad, p)
            p.shader = LinearGradient(0f, 0f, 0f, h, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP); c.drawRoundRect(r, rad, rad, p)
            val x = hsv[1] * w; val y = (1 - hsv[2]) * h
            c.drawCircle(x, y, 9 * d, ringOut); c.drawCircle(x, y, 8 * d, ring)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f); hsv[2] = 1 - (e.y / height).coerceIn(0f, 1f)
            changed(); return true
        }
    }

    private class HueBar(ctx: Context, private val hsv: FloatArray, private val changed: () -> Unit) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, w, 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
            r.set(0f, h * 0.2f, w, h * 0.8f); c.drawRoundRect(r, h * 0.3f, h * 0.3f, p)
            val x = hsv[0] / 360f * w
            c.drawCircle(x.coerceIn(h * 0.4f, w - h * 0.4f), h / 2, h * 0.4f, ring)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[0] = (e.x / width).coerceIn(0f, 0.9999f) * 360f
            changed(); return true
        }
    }
}

/** "עובי: N" with a step slider; [onChange] gets the new step. */
fun showWidthPopover(anchor: View, steps: Int, value: Int, onChange: (Int) -> Unit) {
    val ctx = anchor.context
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val title = TextView(ctx).apply {
        text = "עובי: $value"; textSize = 17f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    col.addView(title)
    col.addView(StepSlider(ctx, steps, value) { v -> title.text = "עובי: $v"; onChange(v) },
        LinearLayout.LayoutParams(ctx.dpi(320), -2).apply { topMargin = ctx.dpi(10) })
    showPopover(anchor, col, innerPadDp = 18)
}

/** The regular pen's style: line type, effects, stabilization. */
class PenStylePopover(
    private val act: Activity,
    private var type: PenType,
    private var effect: PenEffect,
    private var stab: Int,
    private val onType: (PenType) -> Unit,
    private val onEffect: (PenEffect) -> Unit,
    private val onStab: (Int) -> Unit,
) {
    fun show(anchor: View) {
        val ctx = act
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val typeCards = mutableListOf<Pair<PenType, View>>()
        val effectCards = mutableListOf<Pair<PenEffect, View>>()

        fun paintCards() {
            for ((t, v) in typeCards) v.background = GradientDrawable().apply {
                cornerRadius = ctx.px(16f)
                if (t == type) { setColor(ACCENT_SOFT); setStroke(ctx.dpi(2), ACCENT) } else setColor(0xFFF4F6F9.toInt())
            }
            for ((e, v) in effectCards) v.background = GradientDrawable().apply {
                cornerRadius = ctx.px(18f); setColor(Color.TRANSPARENT)
                if (e == effect) setStroke(ctx.dpi(3), ACCENT)
            }
        }

        val names = listOf(PenType.PRESSURE to "משתנה", PenType.MARKER to "קבוע", PenType.DASHED to "מקווקו", PenType.DOTTED to "מנוקד")
        for (rowIdx in 0..1) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((t, label) in names.subList(rowIdx * 2, rowIdx * 2 + 2)) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ctx.dpi(10), 0, ctx.dpi(8))
                    addView(StylePreview(ctx, t, PenEffect.NONE, 0xFF3A4060.toInt()), LinearLayout.LayoutParams(ctx.dpi(54), ctx.dpi(34)))
                    addView(TextView(ctx).apply { text = label; textSize = 14f; setTextColor(ICON); gravity = Gravity.CENTER })
                    setOnClickListener { type = t; paintCards(); onType(t) }
                }
                typeCards += t to card
                row.addView(card, LinearLayout.LayoutParams(ctx.dpi(150), -2).apply { setMargins(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4)) })
            }
            col.addView(row)
        }

        val fxHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fxHead.addView(TextView(ctx).apply {
            text = "אפקטים"; textSize = 16f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fxHead.addView(TextView(ctx).apply {
            text = "ניסיוני"; textSize = 12f; setTextColor(SUB); background = roundRect(0xFFEDEFF3.toInt(), ctx.px(10f))
            setPadding(ctx.dpi(10), ctx.dpi(3), ctx.dpi(10), ctx.dpi(3))
        })
        col.addView(fxHead, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dpi(6), ctx.dpi(14), ctx.dpi(6), ctx.dpi(6)) })
        val fxRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((e, label) in listOf(PenEffect.RAINBOW to "קשת", PenEffect.GLITTER to "נצנצים")) {
            val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val ring = FrameLayout(ctx).apply {
                setPadding(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4))
                addView(EffectSwatch(ctx, e), FrameLayout.LayoutParams(-1, -1))
            }
            effectCards += e to ring
            holder.addView(ring, LinearLayout.LayoutParams(-1, ctx.dpi(64)))
            holder.addView(TextView(ctx).apply { text = label; textSize = 14f; setTextColor(ICON); gravity = Gravity.CENTER; setPadding(0, ctx.dpi(4), 0, 0) })
            holder.setOnClickListener {
                effect = if (effect == e) PenEffect.NONE else e   // tap the chosen effect again to turn it off
                paintCards(); onEffect(effect)
            }
            fxRow.addView(holder, LinearLayout.LayoutParams(ctx.dpi(150), -2).apply { setMargins(ctx.dpi(4), 0, ctx.dpi(4), 0) })
        }
        col.addView(fxRow)
        col.addView(View(ctx).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(ctx.dpi(6), ctx.dpi(14), ctx.dpi(6), ctx.dpi(10)) })

        val stHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stHead.addView(TextView(ctx).apply {
            text = "ייצוב"; textSize = 16f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val stValue = TextView(ctx).apply { textSize = 16f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        stHead.addView(stValue)
        col.addView(stHead, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dpi(6), 0, ctx.dpi(6), 0) })
        col.addView(TextView(ctx).apply {
            text = "מחליק רעידות של היד. ערך גבוה = קו חלק יותר, עם השהיה קטנה"; textSize = 12f; setTextColor(SUB)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dpi(6), ctx.dpi(2), ctx.dpi(6), 0) })
        val stRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val smooth = SmoothPreview(ctx, stab)
        fun label() { stValue.text = String.format(java.util.Locale.US, "%.1f", stab / 10f) }
        stRow.addView(StepSlider(ctx, 11, stab + 1) { v -> stab = v - 1; label(); smooth.level = stab; smooth.invalidate(); onStab(stab) },
            LinearLayout.LayoutParams(0, -2, 1f))
        stRow.addView(smooth, LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(44)).apply { marginStart = ctx.dpi(10) })
        col.addView(stRow, LinearLayout.LayoutParams(ctx.dpi(316), -2).apply { setMargins(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), 0) })
        label(); paintCards()
        showPopover(anchor, col)
    }
}
