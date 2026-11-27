package com.gever.notepro

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val ACCENT = 0xFF2B59C3.toInt()       // selection / highlights
private const val ACCENT_SOFT = 0xFFE6EDFB.toInt()  // selected-tool background
private const val ICON = 0xFF3A3F4B.toInt()
private const val BAR = 0xFFFFFFFF.toInt()
private const val TOOLBAR = 0xFFFAFBFD.toInt()
private const val LINE = 0xFFE3E6EB.toInt()

private val PEN_COLORS = intArrayOf(
    0xFF1F1F24.toInt(), 0xFF2155D6.toInt(), 0xFFD62F2F.toInt(), 0xFF178A43.toInt(), 0xFF7B3FE4.toInt(), 0xFFE07A10.toInt())
private val HL_COLORS = intArrayOf(0x66FFE14D, 0x668BE38B, 0x66FF8FC2, 0x667FC8FF, 0x66FFB36B)
private val PEN_SIZES = floatArrayOf(2.4f, 4f, 6.5f)
private val HL_SIZES = floatArrayOf(18f, 28f, 40f)

/** Note editor: top bar (title, actions) + tool bar + the page canvas. */
class MainActivity : Activity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var diag: TextView
    private lateinit var updateChip: TextView
    private val d by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())

    private val toolBtns = mutableMapOf<NoteCanvasView.Tool, ImageButton>()
    private lateinit var colorRow: LinearLayout
    private lateinit var sizeRow: LinearLayout
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private var showDiag = false
    private var pending: Updater.Release? = null

    private fun dp(v: Int) = (v * d).toInt()
    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = NoteCanvasView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BAR)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(toolBar(), LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))

        val host = FrameLayout(this)
        host.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        diag = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; background = rounded(0xE6101A2E.toInt(), 10f)
            setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
        }
        host.addView(diag, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // Android 15+ draws apps edge-to-edge: keep the bars below the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0); insets
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = true
        }

        canvas.onHistoryChanged = { refreshHistory() }
        refresh()
        ui.post(object : Runnable { override fun run() { renderDiag(); ui.postDelayed(this, 150) } })
    }

    override fun onResume() { super.onResume(); checkForUpdate(manual = false) }

    // ================= top bar =================
    private fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); contentDescription = desc
        imageTintList = ColorStateList.valueOf(ICON)
        background = rounded(Color.TRANSPARENT, 10f)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
    }

    private fun topBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0); setBackgroundColor(BAR)
        }
        row.addView(icon(R.drawable.ic_arrow_forward_ios, "ספרייה") {
            Toast.makeText(this, "הספרייה (נושאים ומחברות) מגיעה בשלב הבא", Toast.LENGTH_SHORT).show()
        })
        row.addView(TextView(this).apply {
            text = "הערה חדשה"; textSize = 18f; setTextColor(0xFF1C1F26.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(6), 0, dp(6), 0)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        updateChip = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); background = rounded(ACCENT, 18f)
            setPadding(dp(14), dp(7), dp(14), dp(7)); visibility = View.GONE
            setOnClickListener { installUpdate() }
        }
        row.addView(updateChip)
        val more = icon(R.drawable.ic_more_horiz, "עוד") {}
        more.setOnClickListener { showMoreMenu(more) }
        row.addView(more)
        return row
    }

    private fun showMoreMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val menu = pm.menu
        menu.add(0, 1, 0, "בדוק עדכון")
        menu.add(0, 2, 1, "נתוני עט").setCheckable(true).isChecked = showDiag
        menu.add(0, 3, 2, "לחץ עט").setCheckable(true).isChecked = canvas.usePressure
        menu.add(0, 4, 3, "חיזוי תנועה").setCheckable(true).isChecked = canvas.usePrediction
        menu.add(0, 5, 4, "אצבע כותבת").setCheckable(true).isChecked = canvas.fingerDraws
        menu.add(0, 6, 5, "גרסה " + BuildConfig.VERSION_CODE).isEnabled = false
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> checkForUpdate(manual = true)
                2 -> { showDiag = !showDiag; renderDiag() }
                3 -> canvas.usePressure = !canvas.usePressure
                4 -> canvas.usePrediction = !canvas.usePrediction
                5 -> canvas.fingerDraws = !canvas.fingerDraws
            }
            true
        }
        pm.show()
    }

    // ================= tool bar =================
    private fun divider() = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(1, dp(28)).apply { marginStart = dp(12); marginEnd = dp(8) }
    }

    private fun toolBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        for ((t, res, name) in listOf(
            Triple(NoteCanvasView.Tool.PEN, R.drawable.ic_edit, "עט"),
            Triple(NoteCanvasView.Tool.HIGHLIGHTER, R.drawable.ic_ink_highlighter, "מרקר"),
            Triple(NoteCanvasView.Tool.ERASER, R.drawable.ic_ink_eraser, "מחק"))) {
            val b = icon(res, name) { canvas.tool = t; refresh() }
            toolBtns[t] = b; row.addView(b)
        }
        row.addView(divider())
        colorRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(colorRow)
        row.addView(divider())
        sizeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(sizeRow)
        row.addView(divider())
        undoBtn = icon(R.drawable.ic_undo, "ביטול") { canvas.undo() }
        redoBtn = icon(R.drawable.ic_redo, "חזרה") { canvas.redo() }
        row.addView(undoBtn); row.addView(redoBtn)
        row.addView(divider())
        val paperBtn = icon(R.drawable.ic_grid_on, "סוג נייר") {}
        paperBtn.setOnClickListener { showPaperMenu(paperBtn) }
        row.addView(paperBtn)

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true; setBackgroundColor(TOOLBAR)
            addView(row, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showPaperMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val names = listOf("שורות" to NoteCanvasView.Paper.LINED, "משבצות" to NoteCanvasView.Paper.GRID,
            "נקודות" to NoteCanvasView.Paper.DOTS, "ריק" to NoteCanvasView.Paper.BLANK)
        names.forEachIndexed { i, (label, _) -> pm.menu.add(1, i, i, label) }
        pm.menu.setGroupCheckable(1, true, true)
        names.forEachIndexed { i, (_, p) -> pm.menu.getItem(i).isChecked = canvas.paper == p }
        pm.setOnMenuItemClickListener { canvas.paper = names[it.itemId].second; true }
        pm.show()
    }

    /** A round colour swatch; ring when selected. */
    private inner class Swatch(val color: Int, val selected: Boolean) : View(this@MainActivity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; this.color = ACCENT }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            c.drawCircle(cx, cy, 11 * d, fill)
            if (selected) c.drawCircle(cx, cy, 15.5f * d, ring)
        }
    }

    /** A width preset: dot of increasing size; soft background when selected. */
    private inner class SizeDot(val radiusDp: Float, val selected: Boolean, val color: Int) : View(this@MainActivity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt() }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ACCENT_SOFT }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (selected) c.drawCircle(cx, cy, 18 * d, bg)
            c.drawCircle(cx, cy, radiusDp * d, fill)
        }
    }

    private fun rebuildPalette() {
        colorRow.removeAllViews(); sizeRow.removeAllViews()
        val tool = canvas.tool
        if (tool == NoteCanvasView.Tool.ERASER) {
            colorRow.addView(TextView(this).apply { text = "מחק קווים שלמים · גם בכפתור הצד של העט"; textSize = 13f; setTextColor(0xFF6B7280.toInt()) })
            return
        }
        val hl = tool == NoteCanvasView.Tool.HIGHLIGHTER
        val colors = if (hl) HL_COLORS else PEN_COLORS
        val current = if (hl) canvas.hlColor else canvas.penColor
        for (c in colors) colorRow.addView(Swatch(c, c == current).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
            setOnClickListener { if (hl) canvas.hlColor = c else canvas.penColor = c; refresh() }
        })
        val sizes = if (hl) HL_SIZES else PEN_SIZES
        val curSize = if (hl) canvas.hlSize else canvas.penSize
        sizes.forEachIndexed { i, sz ->
            sizeRow.addView(SizeDot(floatArrayOf(3f, 5f, 8f)[i], sz == curSize, current).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(44))
                setOnClickListener { if (hl) canvas.hlSize = sz else canvas.penSize = sz; refresh() }
            })
        }
    }

    private fun refresh() {
        for ((t, b) in toolBtns) {
            val on = t == canvas.tool
            b.background = rounded(if (on) ACCENT_SOFT else Color.TRANSPARENT, 10f)
            b.imageTintList = ColorStateList.valueOf(if (on) ACCENT else ICON)
        }
        refreshHistory()
        rebuildPalette()
    }

    private fun refreshHistory() {
        undoBtn.isEnabled = canvas.canUndo; undoBtn.alpha = if (canvas.canUndo) 1f else 0.3f
        redoBtn.isEnabled = canvas.canRedo; redoBtn.alpha = if (canvas.canRedo) 1f else 0.3f
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
