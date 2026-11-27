package com.gever.notepro

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gever.notepro.NoteCanvasView.EraserMode
import com.gever.notepro.NoteCanvasView.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PEN_SIZES = floatArrayOf(2.4f, 4f, 6.5f)
private val HL_SIZES = floatArrayOf(18f, 28f, 40f)
private val ERASER_SIZES = floatArrayOf(6f, 12f, 24f)   // radius, screen dp
private val DOT_RADII = floatArrayOf(3f, 5f, 8f)         // how the 3 width presets look

/**
 * Note editor, Notability-style layout with our own look:
 * - a floating tool pill at the top centre (one row; tap the selected tool again for its style tray),
 * - back + title floating in the top start corner,
 * - a narrow action rail on the left edge (undo, redo, paper, more),
 * - a page counter at the bottom.
 */
class MainActivity : Activity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var diag: TextView
    private lateinit var updateChip: TextView
    private lateinit var pageChip: TextView
    private lateinit var pill: LinearLayout
    private lateinit var tray: LinearLayout
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private val d by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())

    private val toolBtns = mutableMapOf<Tool, ImageButton>()
    private var trayOpen = false
    private var prevTool = Tool.PEN
    private var autoDeselectEraser = true
    private var showDiag = false
    private var pending: Updater.Release? = null

    // Each inking tool keeps 5 quick colour slots; the selected slot is the active colour.
    private val penSlots = intArrayOf(PALETTE[0], PALETTE[3], PALETTE[8], PALETTE[6], PALETTE[11])
    private val hlSlots = intArrayOf(HL_PALETTE[0], HL_PALETTE[10], HL_PALETTE[4], HL_PALETTE[8], HL_PALETTE[2])
    private var penSlot = 0
    private var hlSlot = 0

    private fun dp(v: Float) = (v * d).toInt()
    private fun dp(v: Int) = (v * d).toInt()
    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        canvas = NoteCanvasView(this).apply { sideInset = 76 * d }
        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))

        // Overlays live in their own layer so they can be pushed below the status bar.
        val overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        overlay.addView(titleCluster(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            setMargins(dp(12), dp(10), dp(12), 0) })
        updateChip = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); background = rounded(ACCENT, 16f); elevation = 4 * d
            setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
            setOnClickListener { installUpdate() }
        }
        overlay.addView(updateChip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            setMargins(0, dp(62), dp(16), 0) })
        pill = toolPill()
        overlay.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        overlay.addView(rail(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(dp(12), dp(10), 0, 0) })

        pageChip = TextView(this).apply {
            textSize = 13f; setTextColor(SUB); background = rounded(0xE6FFFFFF.toInt(), 14f)
            setPadding(dp(12), dp(5), dp(12), dp(5)); elevation = 2 * d
        }
        overlay.addView(pageChip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(dp(16), 0, dp(16), dp(16)) })

        diag = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; background = rounded(0xE6101A2E.toInt(), 10f)
            setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
        }
        overlay.addView(diag, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.setPadding(bars.left, bars.top, bars.right, 0)
            canvas.topInset = bars.top + 76 * d
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = true
        }

        canvas.onHistoryChanged = { refreshHistory() }
        canvas.onPageChanged = { cur, total -> pageChip.text = "עמוד $cur מתוך $total" }
        canvas.onEraseEnded = { fromTool -> if (fromTool && autoDeselectEraser && canvas.tool == Tool.ERASER) selectTool(prevTool) }
        applyColor()
        refresh()
        ui.post(object : Runnable { override fun run() { renderDiag(); ui.postDelayed(this, 150) } })
    }

    override fun onResume() { super.onResume(); checkForUpdate(manual = false) }

    // ================= shared widgets =================
    private fun icon(res: Int, desc: String, size: Int = 44, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); contentDescription = desc
        imageTintList = ColorStateList.valueOf(ICON)
        background = rounded(Color.TRANSPARENT, 12f)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun card() = rounded(CARD, 22f).apply { setStroke(1, LINE) }

    private fun vDivider() = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(1, dp(26)).apply { marginStart = dp(8); marginEnd = dp(8) }
    }

    // ================= title (top start corner) =================
    private fun titleCluster(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        row.addView(icon(R.drawable.ic_arrow_forward_ios, "ספרייה") {
            Toast.makeText(this, "הספרייה (נושאים ומחברות) מגיעה בשלב הבא", Toast.LENGTH_SHORT).show()
        })
        val date = SimpleDateFormat("d בMMM yyyy", Locale.forLanguageTag("he")).format(Date())
        row.addView(TextView(this).apply {
            text = "הערה · $date"; textSize = 15f; setTextColor(SUB)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxWidth = dp(170); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(6), 0)
        })
        return row
    }

    // ================= floating tool pill =================
    private fun toolPill(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = card(); elevation = 6 * d
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((t, res, name) in listOf(
            Triple(Tool.PEN, R.drawable.ic_edit, "עט"),
            Triple(Tool.HIGHLIGHTER, R.drawable.ic_ink_highlighter, "מרקר"),
            Triple(Tool.ERASER, R.drawable.ic_ink_eraser, "מחק"))) {
            val b = icon(res, name) { onToolTapped(t) }
            (b.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(2); marginEnd = dp(2) }
            toolBtns[t] = b; tools.addView(b)
        }
        box.addView(tools)
        tray = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
        }
        box.addView(tray, LinearLayout.LayoutParams(-2, dp(48)))
        return box
    }

    /** First tap selects a tool; tapping the selected tool again opens / closes its style tray. */
    private fun onToolTapped(t: Tool) {
        if (t == canvas.tool) { trayOpen = !trayOpen; refresh(); return }
        selectTool(t)
    }

    private fun selectTool(t: Tool) {
        if (t == Tool.ERASER && canvas.tool != Tool.ERASER) prevTool = canvas.tool
        canvas.tool = t
        applyColor(); refresh()
    }

    private fun applyColor() {
        canvas.penColor = penSlots[penSlot]
        canvas.hlColor = (HL_ALPHA shl 24) or (hlSlots[hlSlot] and 0xFFFFFF)
    }

    private fun refresh() {
        for ((t, b) in toolBtns) {
            val on = t == canvas.tool
            b.background = rounded(if (on) ACCENT_SOFT else Color.TRANSPARENT, 12f)
            b.imageTintList = ColorStateList.valueOf(if (on) ACCENT else ICON)
        }
        tray.visibility = if (trayOpen) View.VISIBLE else View.GONE
        if (trayOpen) buildTray()
        refreshHistory()
    }

    private fun buildTray() {
        tray.removeAllViews()
        when (canvas.tool) {
            Tool.ERASER -> {
                ERASER_SIZES.forEachIndexed { i, r ->
                    tray.addView(SizeDot(DOT_RADII[i], r == canvas.eraserRadiusDp, 0xFF9AA3AF.toInt()).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(44))
                        setOnClickListener { canvas.eraserRadiusDp = r; refresh() }
                    })
                }
                tray.addView(vDivider())
                tray.addView(chip("שלם", canvas.eraserMode == EraserMode.WHOLE) { canvas.eraserMode = EraserMode.WHOLE; refresh() })
                tray.addView(chip("חלקי", canvas.eraserMode == EraserMode.PARTIAL) { canvas.eraserMode = EraserMode.PARTIAL; refresh() })
            }
            else -> {
                val hl = canvas.tool == Tool.HIGHLIGHTER
                val slots = if (hl) hlSlots else penSlots
                val sel = if (hl) hlSlot else penSlot
                slots.forEachIndexed { i, c ->
                    tray.addView(Swatch(c, i == sel).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(44))
                        setOnClickListener {
                            if (i == sel) showPalette(hl) else { if (hl) hlSlot = i else penSlot = i; applyColor(); refresh() }
                        }
                    })
                }
                tray.addView(vDivider())
                val sizes = if (hl) HL_SIZES else PEN_SIZES
                val cur = if (hl) canvas.hlSize else canvas.penSize
                sizes.forEachIndexed { i, sz ->
                    tray.addView(SizeDot(DOT_RADII[i], sz == cur, slots[sel]).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(44))
                        setOnClickListener { if (hl) canvas.hlSize = sz else canvas.penSize = sz; refresh() }
                    })
                }
            }
        }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (on) ACCENT else ICON)
        background = rounded(if (on) ACCENT_SOFT else Color.TRANSPARENT, 16f).apply { if (on) setStroke(dp(1), ACCENT) else setStroke(dp(1), LINE) }
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    /** Full colour grid under the pill; the choice replaces the selected quick slot. */
    private fun showPalette(hl: Boolean) {
        val colors = if (hl) HL_PALETTE else PALETTE
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = card(); elevation = 8 * d
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        content.addView(TextView(this).apply { text = "צבעים"; textSize = 14f; setTextColor(SUB) })
        val grid = GridLayout(this).apply { columnCount = 8 }
        val pw = PopupWindow(content, -2, -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 8 * d
        }
        val current = if (hl) hlSlots[hlSlot] else penSlots[penSlot]
        for (c in colors) grid.addView(Swatch(c, c == current).apply {
            layoutParams = GridLayout.LayoutParams().apply { width = dp(40); height = dp(40) }
            setOnClickListener {
                if (hl) hlSlots[hlSlot] = c else penSlots[penSlot] = c
                applyColor(); refresh(); pw.dismiss()
            }
        })
        content.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        pw.showAsDropDown(pill, (pill.width - content.measuredWidth) / 2, dp(8))
    }

    /** A round colour swatch; ring + small chevron when selected (tap again = full palette). */
    private inner class Swatch(val color: Int, val selected: Boolean) : View(this@MainActivity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; this.color = 0x33000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; this.color = ACCENT }
        private val chev = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            val lum = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            this.color = if (lum > 160) INK else Color.WHITE
        }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            c.drawCircle(cx, cy, 11 * d, fill); c.drawCircle(cx, cy, 11 * d, edge)
            if (selected) {
                c.drawCircle(cx, cy, 15.5f * d, ring)
                path.reset(); path.moveTo(cx - 3.5f * d, cy - 1.5f * d); path.lineTo(cx, cy + 2f * d); path.lineTo(cx + 3.5f * d, cy - 1.5f * d)
                c.drawPath(path, chev)
            }
        }
    }

    /** A width preset: dot of increasing size; soft background when selected. */
    private inner class SizeDot(val radiusDp: Float, val selected: Boolean, val color: Int) : View(this@MainActivity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt() }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ACCENT_SOFT }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (selected) c.drawCircle(cx, cy, 17 * d, bg)
            c.drawCircle(cx, cy, radiusDp * d, fill)
        }
    }

    // ================= left action rail =================
    private fun rail(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = card(); elevation = 6 * d
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        undoBtn = icon(R.drawable.ic_undo, "ביטול") { canvas.undo() }
        redoBtn = icon(R.drawable.ic_redo, "חזרה") { canvas.redo() }
        col.addView(undoBtn); col.addView(redoBtn)
        col.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(dp(26), 1).apply { topMargin = dp(6); bottomMargin = dp(6) })
        col.addView(icon(R.drawable.ic_grid_on, "תבנית נייר") { showPaperDialog() })
        val more = icon(R.drawable.ic_more_vert, "עוד") {}
        more.setOnClickListener { showMoreMenu(more) }
        col.addView(more)
        return col
    }

    private fun refreshHistory() {
        undoBtn.isEnabled = canvas.canUndo; undoBtn.alpha = if (canvas.canUndo) 1f else 0.3f
        redoBtn.isEnabled = canvas.canRedo; redoBtn.alpha = if (canvas.canRedo) 1f else 0.3f
    }

    private fun showPaperDialog() {
        PaperDialog(this, canvas.paper, canvas.paperColor) { p, c -> canvas.paper = p; canvas.paperColor = c }.show()
    }

    private fun showMoreMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val menu = pm.menu
        menu.add(0, 7, 0, "תבנית נייר…")
        menu.add(0, 8, 1, "חזרה אוטומטית מהמחק").setCheckable(true).isChecked = autoDeselectEraser
        menu.add(0, 3, 2, "לחץ עט").setCheckable(true).isChecked = canvas.usePressure
        menu.add(0, 4, 3, "חיזוי תנועה").setCheckable(true).isChecked = canvas.usePrediction
        menu.add(0, 5, 4, "אצבע כותבת").setCheckable(true).isChecked = canvas.fingerDraws
        menu.add(0, 2, 5, "נתוני עט").setCheckable(true).isChecked = showDiag
        menu.add(0, 1, 6, "בדוק עדכון")
        menu.add(0, 6, 7, "גרסה " + BuildConfig.VERSION_CODE).isEnabled = false
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> checkForUpdate(manual = true)
                2 -> { showDiag = !showDiag; renderDiag() }
                3 -> canvas.usePressure = !canvas.usePressure
                4 -> canvas.usePrediction = !canvas.usePrediction
                5 -> canvas.fingerDraws = !canvas.fingerDraws
                7 -> showPaperDialog()
                8 -> autoDeselectEraser = !autoDeselectEraser
            }
            true
        }
        pm.show()
    }

    private fun renderDiag() {
        diag.visibility = if (showDiag) View.VISIBLE else View.GONE
        if (!showDiag) return
        val refreshHz = display?.refreshRate?.toInt() ?: 0
        diag.text = buildString {
            appendLine("קלט: " + canvas.diagTool + "   לחץ: " + "%.2f".format(canvas.diagPressure))
            appendLine("דגימה: " + (if (canvas.diagHz > 0) "${canvas.diagHz} Hz" else "—") + "   מסך: $refreshHz Hz")
            append("השהיה מוערכת: " + (if (canvas.diagLatencyMs > 0) "%.1f ms".format(canvas.diagLatencyMs) else "—") +
                "   מגעי יד שנחסמו: " + canvas.diagBlocked)
        }
    }

    // ================= updates =================
    private fun checkForUpdate(manual: Boolean) {
        Updater.check { rel ->
            pending = rel
            if (rel != null) {
                updateChip.text = "עדכון זמין · גרסה " + rel.versionCode
                updateChip.visibility = View.VISIBLE
            } else {
                updateChip.visibility = View.GONE
                if (manual) Toast.makeText(this, "יש לך את הגרסה האחרונה (" + BuildConfig.VERSION_CODE + ")", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installUpdate() {
        val rel = pending ?: return
        Updater.install(this, rel) { msg -> updateChip.text = msg }
    }
}
