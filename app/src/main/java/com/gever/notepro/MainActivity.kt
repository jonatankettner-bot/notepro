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

const val EXTRA_NOTE = "note_id"
private const val REQ_GALLERY = 41
private const val REQ_CAMERA = 42

/** "הערה · 27 בנוב׳ 2026" */
fun defaultTitle(): String = "הערה · " + SimpleDateFormat("d בMMM yyyy", Locale.forLanguageTag("he")).format(Date())

private val ERASER_SIZES = floatArrayOf(6f, 12f, 24f)   // radius, screen dp
private val DOT_RADII = floatArrayOf(3f, 5f, 8f)         // how the 3 width presets look

/**
 * Note editor, Notability-style layout with our own look:
 * - a floating two-row toolbar at the top centre (tools; options of the selected tool),
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
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private val d by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())

    private val toolBtns = mutableMapOf<Tool, ToolButton>()
    private var prevTool = Tool.PEN
    private var autoDeselectEraser = true
    private var showDiag = false
    private var pending: Updater.Release? = null

    private val store by lazy { NoteStore(this) }
    private lateinit var meta: NoteMeta
    private lateinit var titleView: TextView
    private var metaDirty = false
    private lateinit var pagesPanel: PagesPanel
    private lateinit var dockBar: View
    private lateinit var railView: View
    private lateinit var titleBox: View
    private var statusTop = 0

    private fun togglePages() {
        val v = pagesPanel.view
        if (v.visibility == View.VISIBLE) v.visibility = View.GONE
        else { pagesPanel.refresh(); v.visibility = View.VISIBLE }
    }


    private fun dp(v: Float) = (v * d).toInt()
    private fun dp(v: Int) = (v * d).toInt()
    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        meta = intent.getStringExtra(EXTRA_NOTE)?.let { store.note(it) }
            ?: store.create(defaultTitle(), null)

        canvas = NoteCanvasView(this).apply { sideInset = 76 * d }
        loadToolPrefs()
        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        dockBar = FrameLayout(this).apply {
            // No elevation: it would lift the strip above the (flat) pill and title drawn after it.
            setBackgroundColor(CARD); visibility = View.GONE
            addView(View(this@MainActivity).apply { setBackgroundColor(LINE) }, FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM))
        }
        root.addView(dockBar, FrameLayout.LayoutParams(-1, dp(74), Gravity.TOP))

        // Overlays live in their own layer so they can be pushed below the status bar.
        val overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        titleBox = titleCluster()
        overlay.addView(titleBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
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
        // When the toolbar changes height (row 2 shown / hidden), move the page start and the docked strip with it.
        pill.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) pill.post { applyDock(docked) }
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> avoidTitle() }
        railView = rail()
        overlay.addView(railView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(dp(12), dp(10), 0, 0) })
        pagesPanel = PagesPanel(this, canvas) { togglePages() }
        overlay.addView(pagesPanel.view, FrameLayout.LayoutParams(dp(228), -1, Gravity.RIGHT).apply {
            setMargins(0, dp(64), dp(12), dp(56)) })
        pagesPanel.view.visibility = View.GONE

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

        // Floating menu above a lasso selection.
        val selMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = card(); elevation = 8 * d; setPadding(dp(6), dp(2), dp(6), dp(2)); visibility = View.GONE
        }
        fun selBtn(label: String, onClick: (View) -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(INK); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick(it) }
        }
        selMenu.addView(selBtn("צבע") { showRecolor(it) })
        selMenu.addView(selBtn("שכפול") { canvas.duplicateSelection() })
        selMenu.addView(selBtn("מחיקה") { canvas.deleteSelection() })
        root.addView(selMenu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        canvas.onSelectionChanged = { r ->
            if (r == null) selMenu.visibility = View.GONE
            else {
                selMenu.visibility = View.VISIBLE
                selMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val w = selMenu.measuredWidth; val h = selMenu.measuredHeight
                selMenu.translationX = (r.centerX() - w / 2f).coerceIn(dp(8).toFloat(), (root.width - w - dp(8)).toFloat().coerceAtLeast(0f))
                val above = r.top - h - dp(16)
                selMenu.translationY = if (above > dp(90)) above else (r.bottom + dp(16)).coerceAtMost((root.height - h - dp(8)).toFloat())
            }
        }

        // Floating menu above a selected picture / sticker / sticky note.
        val itemMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = card(); elevation = 8 * d; setPadding(dp(6), dp(2), dp(6), dp(2)); visibility = View.GONE
        }
        root.addView(itemMenu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        canvas.onItemSelected = { item, r ->
            if (item == null || r == null) itemMenu.visibility = View.GONE
            else {
                itemMenu.removeAllViews()
                if (item.type == ItemType.NOTE) {
                    itemMenu.addView(selBtn("עריכה") { editNote(item) })
                    itemMenu.addView(selBtn("צבע") { noteColors(it) })
                }
                itemMenu.addView(selBtn("שכפול") { canvas.duplicateSelectedItem() })
                itemMenu.addView(selBtn("מחיקה") { canvas.deleteSelectedItem() })
                itemMenu.visibility = View.VISIBLE
                itemMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val w = itemMenu.measuredWidth; val h = itemMenu.measuredHeight
                itemMenu.translationX = (r.centerX() - w / 2f).coerceIn(dp(8).toFloat(), (root.width - w - dp(8)).toFloat().coerceAtLeast(0f))
                val above = r.top - h - dp(16)
                itemMenu.translationY = if (above > dp(140)) above else (r.bottom + dp(60)).coerceAtMost((root.height - h - dp(8)).toFloat())
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.setPadding(bars.left, bars.top, bars.right, 0)
            statusTop = bars.top
            applyDock(Prefs(this).dockedToolbar)
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = true
        }

        runCatching { canvas.paper = NoteCanvasView.Paper.valueOf(meta.paper) }
        if (meta.paperColor != -1) canvas.paperColor = meta.paperColor
        canvas.load(store.loadStrokes(meta.id))
        canvas.restoreShapes(store.loadShapes(meta.id))
        canvas.mediaDir = store.mediaDir(meta.id)
        canvas.restoreItems(store.loadItems(meta.id))
        canvas.restorePages(meta.pages, meta.bookmarks)
        meta.opened = System.currentTimeMillis(); store.saveMeta(meta)

        canvas.onHistoryChanged = { refreshHistory(); scheduleAutosave() }
        pageChip.text = "עמוד 1 מתוך ${canvas.pages}"
        // Posted: this can fire during a layout pass, where a text change would not re-measure the chip.
        canvas.onPageChanged = { cur, total ->
            pageChip.post {
                pageChip.text = "עמוד $cur מתוך $total"
                if (pagesPanel.view.visibility == View.VISIBLE) pagesPanel.refresh()
            }
        }
        pageChip.setOnClickListener { togglePages() }
        canvas.onEraseEnded = { fromTool -> if (fromTool && autoDeselectEraser && canvas.tool == Tool.ERASER) selectTool(prevTool) }
        applyColor()
        refresh()
        ui.post(object : Runnable { override fun run() { renderDiag(); ui.postDelayed(this, 150) } })
    }

    override fun onResume() {
        super.onResume()
        applyPrefs()
        checkForUpdate(manual = false)
    }

    /** Applies the saved settings (called on resume and after a toggle in the ⋮ menu). */
    private fun applyPrefs() {
        val p = Prefs(this)
        canvas.usePressure = p.usePressure; canvas.usePrediction = p.usePrediction; canvas.fingerDraws = p.fingerDraws
        canvas.snapLines = p.snapLines; canvas.tapGestures = p.tapGestures; autoDeselectEraser = p.autoDeselectEraser
        if (p.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyDock(p.dockedToolbar)
    }

    /**
     * Floating (default): the pill floats over the desk. Docked: a solid strip across the top holds the
     * pill and title, and the page always starts below it, so the toolbar never covers writing.
     */
    private fun applyDock(docked: Boolean) {
        this.docked = docked
        // The toolbar is one or two rows tall; use its real height once it has been laid out.
        val pillH = if (pill.height > 0) pill.height else dp(if (collapsed) 54 else 112)
        val below = dp(10) + pillH + dp(10)          // overlay coordinates (the overlay starts under the status bar)
        dockBar.visibility = if (docked) View.VISIBLE else View.GONE
        dockBar.layoutParams.height = statusTop + below
        pill.elevation = if (docked) 0f else 6 * d
        pill.background = if (docked) rounded(0xFFF4F6F9.toInt(), 22f) else card()
        (updateChip.layoutParams as FrameLayout.LayoutParams).topMargin = if (docked) below + dp(8) else dp(62)
        (railView.layoutParams as FrameLayout.LayoutParams).topMargin = if (docked) below + dp(10) else dp(10)
        (pagesPanel.view.layoutParams as FrameLayout.LayoutParams).topMargin = if (docked) below + dp(10) else dp(64)
        updateChip.requestLayout(); dockBar.requestLayout(); railView.requestLayout(); pagesPanel.view.requestLayout()
        canvas.topInset = (statusTop + below + if (docked) dp(14) else dp(2)).toFloat()
    }

    private var docked = false

    /** On a narrow (portrait) screen the centred toolbar can run into the title; slide it left, not past the rail. */
    private fun avoidTitle() {
        if (pill.width == 0 || titleBox.width == 0) return
        val overlap = pill.right + dp(8) - titleBox.left
        val room = (pill.left - (railView.right + dp(8))).coerceAtLeast(0)
        val tx = if (overlap > 0) -minOf(overlap, room).toFloat() else 0f
        if (pill.translationX != tx) pill.translationX = tx
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(autosave); save() }

    // Autosave a few seconds after the last change, so a crash or a forced close (e.g. installing an
    // update while a note is open) loses at most a few seconds of writing, not the whole session.
    private val autosave = Runnable { if (!isFinishing) save() }
    private fun scheduleAutosave() { ui.removeCallbacks(autosave); ui.postDelayed(autosave, 3000) }

    /** Writes ink, metadata and the library thumbnail if anything changed. */
    private fun save() {
        if (!canvas.dirty && !metaDirty) return
        meta.modified = System.currentTimeMillis()
        meta.paper = canvas.paper.name; meta.paperColor = canvas.paperColor
        meta.pages = canvas.pages; meta.bookmarks = canvas.bookmarks.toList()
        if (canvas.dirty) {
            store.saveStrokes(meta.id, canvas.strokesSnapshot()); store.saveShapes(meta.id, canvas.shapesJson())
            store.saveItems(meta.id, canvas.itemsJson())
        }
        store.saveMeta(meta)
        store.saveThumbnail(meta.id, canvas.renderThumbnail(360))
        canvas.markSaved(); metaDirty = false
    }

    private fun renameDialog() {
        val input = android.widget.EditText(this).apply { setText(meta.title); setSelection(text.length); textDirection = View.TEXT_DIRECTION_RTL }
        android.app.AlertDialog.Builder(this).setTitle("שם ההערה").setView(input)
            .setPositiveButton("שמירה") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { meta.title = t; titleView.text = t; metaDirty = true; scheduleAutosave() }
            }
            .setNegativeButton("ביטול", null).show()
    }

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
        row.addView(icon(R.drawable.ic_arrow_forward_ios, "ספרייה") { finish() })
        titleView = TextView(this).apply {
            text = meta.title; textSize = 15f; setTextColor(SUB)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxWidth = dp(170); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(8), dp(6), dp(8))
            setOnClickListener { renameDialog() }
        }
        row.addView(titleView)
        return row
    }

    // ================= floating toolbar (two rows) =================
    // Row 1: the tools. Row 2: the selected tool's options (colours | widths | pen style).
    // The chevron at the end of row 1 hides / shows row 2.
    private enum class Btn(val res: Int, val label: String, val tool: Tool?) {
        PEN(R.drawable.ic_edit, "עט", Tool.PEN),
        FOUNTAIN(R.drawable.ic_pen_fountain, "עט נובע", Tool.FOUNTAIN),
        PENCIL(R.drawable.ic_pencil, "עיפרון", Tool.PENCIL),
        HIGHLIGHTER(R.drawable.ic_ink_highlighter, "מרקר", Tool.HIGHLIGHTER),
        ERASER(R.drawable.ic_ink_eraser, "מחק", Tool.ERASER),
        LASSO(R.drawable.ic_lasso, "בחירה", Tool.LASSO),
        INSERT(R.drawable.ic_add_circle, "הוספה", null),
        SHAPES(R.drawable.ic_shapes, "צורות", null),
        STICKY(R.drawable.ic_sticky, "פתק", null),
        TEXT(R.drawable.ic_text, "טקסט", null),
    }

    private val prefs by lazy { Prefs(this) }
    private val penInk = InkSettings("pen", intArrayOf(PALETTE[0], PALETTE[3], PALETTE[8]), intArrayOf(4, 6, 8), PEN_WIDTHS)
    private val fountainInk = InkSettings("fountain", intArrayOf(PALETTE[0], 0xFF1E3A8A.toInt(), 0xFF9F1239.toInt()), intArrayOf(6, 8, 10), PEN_WIDTHS)
    private val pencilInk = InkSettings("pencil", intArrayOf(0xFF4B5563.toInt(), PALETTE[0], PALETTE[3]), intArrayOf(3, 5, 7), PEN_WIDTHS)
    private val hlInk = InkSettings("hl", intArrayOf(HL_PALETTE[0], HL_PALETTE[10], HL_PALETTE[4]), intArrayOf(4, 7, 9), HL_WIDTHS)
    private fun inkFor(t: Tool) = when (t) {
        Tool.PEN -> penInk; Tool.FOUNTAIN -> fountainInk; Tool.PENCIL -> pencilInk; Tool.HIGHLIGHTER -> hlInk; else -> null
    }

    private lateinit var optionsRow: LinearLayout
    private lateinit var optionsDivider: View
    private lateinit var collapseBtn: ImageButton
    private var collapsed = false
    private val slotViews = mutableListOf<View>()
    private var styleBtnPreview: StylePreview? = null

    /** Restores the toolbar's saved state: per-tool colours and widths, pen style, last tool, collapsed row. */
    private fun loadToolPrefs() {
        for (ink in listOf(penInk, fountainInk, pencilInk, hlInk)) ink.load(prefs)
        canvas.penType = runCatching { PenType.valueOf(prefs.str("pen.type", "PRESSURE")) }.getOrDefault(PenType.PRESSURE)
        canvas.penEffect = runCatching { PenEffect.valueOf(prefs.str("pen.effect", "NONE")) }.getOrDefault(PenEffect.NONE)
        canvas.stabilization = prefs.int("pen.stab", 0).coerceIn(0, 10)
        canvas.tool = runCatching { Tool.valueOf(prefs.str("tool", "PEN")) }.getOrDefault(Tool.PEN)
        collapsed = prefs.toolbarCollapsed
    }

    private fun toolPill(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = card(); elevation = 6 * d
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (b in Btn.values()) {
            if (b == Btn.TEXT) tools.addView(vDivider())
            val v = ToolButton().apply {
                setImageResource(b.res); contentDescription = b.label
                scaleType = android.widget.ImageView.ScaleType.CENTER
                background = pressable(this@MainActivity, 12f)
                imageTintList = ColorStateList.valueOf(ICON)
                setOnClickListener { onToolButton(b, this) }
            }
            b.tool?.let { toolBtns[it] = v }
            tools.addView(v, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(1); marginEnd = dp(1) })
        }
        collapseBtn = icon(R.drawable.ic_expand_less, "הסתרת אפשרויות", size = 40) {
            collapsed = !collapsed; prefs.toolbarCollapsed = collapsed; refresh()
        }
        tools.addView(collapseBtn)
        // Explicit wrap_content: with the default match_parent a narrow options row squeezed the tool row.
        box.addView(tools, LinearLayout.LayoutParams(-2, -2))
        optionsDivider = View(this).apply { setBackgroundColor(LINE) }
        box.addView(optionsDivider, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(2) })
        optionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        box.addView(optionsRow, LinearLayout.LayoutParams(-2, dp(54)))
        return box
    }

    private fun onToolButton(b: Btn, v: View) {
        val t = b.tool
        if (t == null) {
            when (b) {
                Btn.INSERT -> insertMenu(v)
                Btn.SHAPES -> shapesPopover(v)
                Btn.STICKY -> addStickyNote()
                else -> Toast.makeText(this, "${b.label}: הכלי הזה עוד לא מוכן, הוא יגיע באחד השלבים הבאים", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (t == canvas.tool) { if (collapsed) { collapsed = false; prefs.toolbarCollapsed = false; refresh() }; return }
        selectTool(t)
    }

    private fun selectTool(t: Tool) {
        if (t == Tool.ERASER && canvas.tool != Tool.ERASER) prevTool = canvas.tool
        if (t != Tool.LASSO) canvas.clearSelection()
        canvas.tool = t
        if (inkFor(t) != null) prefs.putStr("tool", t.name)
        applyColor(); refresh()
    }

    private fun applyColor() {
        inkFor(canvas.tool)?.takeIf { it !== hlInk }?.let { canvas.penColor = it.color; canvas.penSize = it.size }
        canvas.hlColor = (HL_ALPHA shl 24) or (hlInk.color and 0xFFFFFF)
        canvas.hlSize = hlInk.size
    }

    private fun refresh() {
        for ((t, b) in toolBtns) {
            val on = t == canvas.tool
            b.background = pressable(this, 12f, if (on) ACCENT_SOFT else Color.TRANSPARENT)
            b.imageTintList = ColorStateList.valueOf(if (on) ACCENT else ICON)
            b.mark = inkFor(t)?.color ?: 0
            b.invalidate()
        }
        val vis = if (collapsed) View.GONE else View.VISIBLE
        optionsRow.visibility = vis; optionsDivider.visibility = vis
        collapseBtn.setImageResource(if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        collapseBtn.contentDescription = if (collapsed) "הצגת אפשרויות" else "הסתרת אפשרויות"
        if (!collapsed) buildOptions()
        refreshHistory()
    }

    private fun dotRadius(ink: InkSettings, step: Int) = 2f + 7.5f * (step - 1) / (ink.steps - 1f)

    private fun buildOptions() {
        optionsRow.removeAllViews(); slotViews.clear(); styleBtnPreview = null
        when (canvas.tool) {
            Tool.LASSO -> {
                optionsRow.addView(chip("חופשי", canvas.lassoMode == NoteCanvasView.LassoMode.FREEFORM) { canvas.lassoMode = NoteCanvasView.LassoMode.FREEFORM; refresh() })
                optionsRow.addView(chip("מלבן", canvas.lassoMode == NoteCanvasView.LassoMode.RECT) { canvas.lassoMode = NoteCanvasView.LassoMode.RECT; refresh() })
            }
            Tool.ERASER -> {
                ERASER_SIZES.forEachIndexed { i, r ->
                    optionsRow.addView(WidthDot(this, DOT_RADII[i], r == canvas.eraserRadiusDp, 0xFF9AA3AF.toInt()).apply {
                        setOnClickListener { canvas.eraserRadiusDp = r; refresh() }
                    }, LinearLayout.LayoutParams(dp(42), dp(48)))
                }
                optionsRow.addView(vDivider())
                optionsRow.addView(chip("מחיקת קו שלם", canvas.eraserMode == EraserMode.WHOLE) { canvas.eraserMode = EraserMode.WHOLE; refresh() })
                optionsRow.addView(chip("מחיקה חלקית", canvas.eraserMode == EraserMode.PARTIAL) { canvas.eraserMode = EraserMode.PARTIAL; refresh() })
            }
            else -> {
                val ink = inkFor(canvas.tool) ?: return
                // Colour slots: tap = use, tap the selected one = colour popover, long-press = change / remove.
                ink.slots.forEachIndexed { i, c ->
                    val sel = i == ink.slot
                    val sw = SwatchView(this, c, sel)
                    sw.setOnClickListener { if (sel) openColors(ink, sw) else { ink.slot = i; ink.save(prefs); applyColor(); refresh() } }
                    sw.setOnLongClickListener { slotMenu(ink, i, sw); true }
                    slotViews += sw
                    optionsRow.addView(sw, LinearLayout.LayoutParams(dp(42), dp(48)))
                }
                if (ink.slots.size < MAX_SLOTS) {
                    val add = ImageButton(this).apply {
                        setImageResource(R.drawable.ic_add); imageTintList = ColorStateList.valueOf(ICON)
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE9ECF1.toInt()) }
                        contentDescription = "הוספת צבע"
                    }
                    add.setOnClickListener {
                        ink.slots.add(ink.color); ink.slot = ink.slots.lastIndex; ink.save(prefs); applyColor(); refresh()
                        optionsRow.post { slotViews.getOrNull(ink.slot)?.let { openColors(ink, it) } }
                    }
                    optionsRow.addView(add, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6); marginEnd = dp(6) })
                }
                optionsRow.addView(vDivider())
                // Width presets: tap = use, tap the selected one = width slider.
                for (k in 0..2) {
                    val dot = WidthDot(this, dotRadius(ink, ink.widths[k]), k == ink.widthIdx, ink.color)
                    dot.contentDescription = "עובי ${ink.widths[k]}"
                    dot.setOnClickListener {
                        if (k == ink.widthIdx) showWidthPopover(dot, ink.steps, ink.widths[k]) { v ->
                            ink.widths[k] = v; ink.save(prefs); applyColor()
                            dot.dotRadiusDp = dotRadius(ink, v); dot.invalidate()
                        } else { ink.widthIdx = k; ink.save(prefs); applyColor(); refresh() }
                    }
                    optionsRow.addView(dot, LinearLayout.LayoutParams(dp(42), dp(48)))
                }
                if (canvas.tool == Tool.PEN) { optionsRow.addView(vDivider()); optionsRow.addView(styleButton()) }
            }
        }
    }

    private fun setInkColor(ink: InkSettings, c: Int) {
        ink.slots[ink.slot] = c; ink.save(prefs); prefs.addRecentColor(c); applyColor(); refresh()
    }

    private fun openColors(ink: InkSettings, anchor: View) {
        val pages = if (ink === hlInk) listOf(HL_PALETTE) + COLOR_PAGES.drop(1) else COLOR_PAGES
        ColorPopover(this, pages, ink.color,
            onPick = { c -> setInkColor(ink, c) },
            onEyedropper = {
                Toast.makeText(this, "גע בקו בדף כדי לקחת את הצבע שלו", Toast.LENGTH_SHORT).show()
                canvas.onColorPicked = { c ->
                    if (c != null) setInkColor(ink, c)
                    else Toast.makeText(this, "לא נמצא קו במקום הזה", Toast.LENGTH_SHORT).show()
                }
                canvas.startColorPick()
            },
            onCustom = { ColorPickerDialog(this, ink.color) { c -> setInkColor(ink, c) }.show() },
        ).show(anchor)
    }

    private fun slotMenu(ink: InkSettings, i: Int, anchor: View) {
        showMenu(anchor, listOf(
            MenuItem("שינוי הצבע…") { ink.slot = i; applyColor(); refresh(); optionsRow.post { slotViews.getOrNull(i)?.let { openColors(ink, it) } } },
            MenuItem("הסרת הצבע", danger = true, enabled = ink.slots.size > 1) {
                ink.slots.removeAt(i)
                if (ink.slot >= i && ink.slot > 0) ink.slot--
                ink.slot = ink.slot.coerceIn(0, ink.slots.lastIndex)
                ink.save(prefs); applyColor(); refresh()
            },
        ), minWidthDp = 180)
    }

    /** The "~ ⌄" button that opens the pen style popover; shows the current line type and effect. */
    private fun styleButton(): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(this@MainActivity, 18f, 0xFFF1F3F6.toInt()); setPadding(dp(10), 0, dp(6), 0)
            contentDescription = "סגנון העט"
        }
        val prev = StylePreview(this, canvas.penType, canvas.penEffect, penInk.color, wave = true, strokeDp = 2.4f)
        styleBtnPreview = prev
        b.addView(prev, LinearLayout.LayoutParams(dp(46), dp(26)))
        b.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_expand_more); imageTintList = ColorStateList.valueOf(SUB) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(2) })
        b.setOnClickListener {
            PenStylePopover(this, canvas.penType, canvas.penEffect, canvas.stabilization,
                onType = { t -> canvas.penType = t; prefs.putStr("pen.type", t.name); updateStylePreview() },
                onEffect = { e -> canvas.penEffect = e; prefs.putStr("pen.effect", e.name); updateStylePreview() },
                onStab = { s -> canvas.stabilization = s; prefs.putInt("pen.stab", s) },
            ).show(b)
        }
        b.layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(6); marginEnd = dp(4) }
        return b
    }

    private fun updateStylePreview() {
        styleBtnPreview?.apply { type = canvas.penType; effect = canvas.penEffect; inkColor = penInk.color; invalidate() }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (on) ACCENT else ICON)
        background = rounded(if (on) ACCENT_SOFT else Color.TRANSPARENT, 16f).apply { if (on) setStroke(dp(1), ACCENT) else setStroke(dp(1), LINE) }
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    /** Colour grid for recolouring the lasso selection. */
    private fun showRecolor(anchor: View) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { text = "צבע לבחירה"; textSize = 15f; setTextColor(INK); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        val grid = GridLayout(this).apply { columnCount = 8 }
        var pw: PopupWindow? = null
        for (c in COLOR_PAGES[0] + COLOR_PAGES[2]) grid.addView(SwatchView(this, c, false, chevron = false).apply {
            layoutParams = GridLayout.LayoutParams().apply { width = dp(40); height = dp(40) }
            setOnClickListener { canvas.recolorSelection(c); prefs.addRecentColor(c); pw?.dismiss() }
        })
        content.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        pw = showPopover(anchor, content)
    }

    /** Tool icon with a small bar under it in the tool's current colour (pens / highlighter). */
    private inner class ToolButton : ImageButton(this@MainActivity) {
        var mark = 0
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x33000000 }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (mark == 0) return
            bar.color = mark or 0xFF000000.toInt()
            val w = 14 * d; val h = 3.5f * d; val cx = width / 2f; val y = height - 6.5f * d
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, bar)
            if (Color.luminance(bar.color) > 0.8f) c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, barEdge)
        }
    }

    // ================= insert: pictures, stickers, sticky notes, ready shapes =================
    private var cameraFile: java.io.File? = null

    private fun insertMenu(anchor: View) {
        showMenu(anchor, listOf(
            MenuItem("תמונה מהגלריה") { pickImage() },
            MenuItem("צילום תמונה") { takePhoto() },
            MenuItem("מדבקה…") { stickerPicker(anchor) },
            MenuItem("פתק דביק") { addStickyNote() },
        ), minWidthDp = 200)
    }

    private fun pickImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(i, REQ_GALLERY) }.onFailure { Toast.makeText(this, "לא נמצאה גלריה", Toast.LENGTH_SHORT).show() }
    }

    private fun takePhoto() {
        val f = java.io.File(cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs(); delete() }
        cameraFile = f
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivityForResult(i, REQ_CAMERA) }.onFailure { Toast.makeText(this, "לא נמצאה אפליקציית מצלמה", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Activity result API is enough for two simple requests")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_GALLERY -> data?.data?.let { uri -> importImage { contentResolver.openInputStream(uri) } }
            REQ_CAMERA -> cameraFile?.takeIf { it.exists() }?.let { f -> importImage { f.inputStream() } }
        }
    }

    /** Copies a picture into the note (scaled down to at most 2048 px, turned upright) and places it. */
    private fun importImage(open: () -> java.io.InputStream?) {
        val bmp = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > 2048) sample *= 2
            var b = open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching null
            val turn = open()?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
            if (turn != 0f) b = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(turn) }, true)
            b
        }.getOrNull()
        if (bmp == null) { Toast.makeText(this, "לא הצלחתי לפתוח את התמונה", Toast.LENGTH_SHORT).show(); return }
        val png = bmp.hasAlpha()
        val name = java.util.UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        java.io.File(store.mediaDir(meta.id), name).outputStream().use {
            bmp.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        val k = minOf(600f / bmp.width, 520f / bmp.height)
        val c = canvas.freeSpot(bmp.width * k, bmp.height * k)
        canvas.addItem(PageItem(java.util.UUID.randomUUID().toString(), ItemType.IMAGE, c[0], c[1], bmp.width * k, bmp.height * k, file = name))
    }

    /** Sticker picker: topic chips and a grid; a tap places the sticker in the middle of the screen. */
    private fun stickerPicker(anchor: View) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "מדבקות"; textSize = 17f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = GridLayout(this).apply { columnCount = 6 }
        var pw: PopupWindow? = null
        fun show(cat: Int) {
            chips.removeAllViews()
            STICKERS.forEachIndexed { i, (name, _) -> chips.addView(chip(name, i == cat) { show(i) }) }
            grid.removeAllViews()
            for (e in STICKERS[cat].second) grid.addView(TextView(this).apply {
                text = e; textSize = 28f; gravity = Gravity.CENTER; background = pressable(this@MainActivity, 12f)
                layoutParams = GridLayout.LayoutParams().apply { width = dp(52); height = dp(52) }
                setOnClickListener {
                    pw?.dismiss()
                    val c = canvas.freeSpot(110f, 110f)
                    canvas.addItem(PageItem(java.util.UUID.randomUUID().toString(), ItemType.STICKER, c[0], c[1], 110f, 110f, sticker = e))
                }
            })
        }
        show(0)
        col.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
        col.addView(grid, LinearLayout.LayoutParams(dp(52 * 6), -2))
        pw = showPopover(anchor, col)
    }

    private fun addStickyNote() {
        val c = canvas.freeSpot(260f, 230f)
        val note = PageItem(java.util.UUID.randomUUID().toString(), ItemType.NOTE, c[0], c[1], 260f, 230f, color = NOTE_COLORS[0])
        canvas.addItem(note)
        editNote(note)
    }

    /** Text of a sticky note. */
    private fun editNote(item: PageItem) {
        val input = android.widget.EditText(this).apply {
            setText(item.text); setSelection(text.length); textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            minLines = 4; gravity = Gravity.TOP or Gravity.START; hint = "כתוב כאן…"
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        android.app.AlertDialog.Builder(this).setTitle("פתק").setView(box)
            .setPositiveButton("שמירה") { _, _ -> canvas.changeSelectedItem { it.text = input.text.toString() } }
            .setNegativeButton("ביטול", null).show()
    }

    private fun noteColors(anchor: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var pw: PopupWindow? = null
        val cur = canvas.selItem?.color
        for (c in NOTE_COLORS) row.addView(SwatchView(this, c, c == cur, chevron = false, radiusDp = 13f).apply {
            setOnClickListener { pw?.dismiss(); canvas.changeSelectedItem { it.color = c } }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        pw = showPopover(anchor, row)
    }

    /** The shapes tool: a grid of ready perfect shapes; a tap places one with the pen's colour and width. */
    private fun shapesPopover(anchor: View) {
        val list: List<Pair<String, (Float, Float) -> Shape>> = listOf(
            "קו" to { x, y -> LineShape(x - 160, y, x + 160, y) },
            "ריבוע" to { x, y -> RectShape(x, y, 220f, 220f, 0f) },
            "מלבן" to { x, y -> RectShape(x, y, 320f, 200f, 0f) },
            "עיגול" to { x, y -> EllipseShape(x, y, 120f, 120f, 0f) },
            "אליפסה" to { x, y -> EllipseShape(x, y, 170f, 105f, 0f) },
            "משולש" to { x, y -> RegularShape(3, x, y + 20, 140f, (-Math.PI / 2).toFloat()) },
            "ישר זווית" to { x, y -> PolyShape(floatArrayOf(x - 130, y + 100, x + 130, y + 100, x - 130, y - 100), true, "משולש ישר זווית") },
            "מעוין" to { x, y -> PolyShape(floatArrayOf(x, y - 140, x + 110, y, x, y + 140, x - 110, y), true, "מעוין") },
            "טרפז" to { x, y -> PolyShape(floatArrayOf(x - 170, y + 90, x + 170, y + 90, x + 100, y - 90, x - 100, y - 90), true, "טרפז שווה שוקיים") },
            "מקבילית" to { x, y -> PolyShape(floatArrayOf(x - 170, y + 90, x + 110, y + 90, x + 170, y - 90, x - 110, y - 90), true, "מקבילית") },
            "מחומש" to { x, y -> RegularShape(5, x, y, 140f, (-Math.PI / 2).toFloat()) },
            "משושה" to { x, y -> RegularShape(6, x, y, 140f, 0f) },
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "צורות"; textSize = 17f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        col.addView(TextView(this).apply {
            text = "או: מציירים ועוצרים רגע עם העט"; textSize = 12f; setTextColor(SUB); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        val grid = GridLayout(this).apply { columnCount = 4 }
        var pw: PopupWindow? = null
        for ((name, make) in list) grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = pressable(this@MainActivity, 14f)
            setPadding(dp(4), dp(8), dp(4), dp(6))
            addView(ShapeIcon(make(0f, 0f)), LinearLayout.LayoutParams(dp(56), dp(44)))
            addView(TextView(this@MainActivity).apply { text = name; textSize = 12f; setTextColor(ICON); gravity = Gravity.CENTER })
            layoutParams = GridLayout.LayoutParams().apply { width = dp(78) }
            setOnClickListener {
                pw?.dismiss()
                if (canvas.tool != Tool.PEN && canvas.tool != Tool.HIGHLIGHTER) selectTool(Tool.PEN)
                val b = make(0f, 0f).bounds(); val c = canvas.freeSpot(b.width(), b.height()); canvas.addShape(make(c[0], c[1]))
            }
        })
        col.addView(grid, LinearLayout.LayoutParams(dp(78 * 4), -2).apply { topMargin = dp(8) })
        pw = showPopover(anchor, col)
    }

    /** Small outline picture of a shape (fitted into the view). */
    private inner class ShapeIcon(private val shape: Shape) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = ICON; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val o = shape.outline(4f); val b = shape.bounds()
            val pad = 6 * d
            val k = minOf((width - 2 * pad) / b.width().coerceAtLeast(1f), (height - 2 * pad) / b.height().coerceAtLeast(1f))
            val ox = width / 2f - b.centerX() * k; val oy = height / 2f - b.centerY() * k
            path.reset(); path.moveTo(o[0] * k + ox, o[1] * k + oy)
            var i = 2; while (i < o.size) { path.lineTo(o[i] * k + ox, o[i + 1] * k + oy); i += 2 }
            c.drawPath(path, p)
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
        col.addView(icon(R.drawable.ic_pages, "עמודים") { togglePages() })
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
        PaperDialog(this, canvas.paper, canvas.paperColor) { p, c -> canvas.paper = p; canvas.paperColor = c; metaDirty = true; scheduleAutosave() }.show()
    }

    /** The ⋮ menu. Toggles are saved, so they match the settings screen and survive a restart. */
    private fun showMoreMenu(anchor: View) {
        val p = Prefs(this)
        showMenu(anchor, listOf(
            MenuItem("שינוי שם…") { renameDialog() },
            MenuItem("תבנית נייר…") { showPaperDialog() },
            MenuItem("צורות בהחזקת העט", checked = p.snapLines, separatorBefore = true) { p.snapLines = !p.snapLines; applyPrefs() },
            MenuItem("חזרה אוטומטית מהמחק", checked = p.autoDeselectEraser) { p.autoDeselectEraser = !p.autoDeselectEraser; applyPrefs() },
            MenuItem("אצבע כותבת", checked = p.fingerDraws) { p.fingerDraws = !p.fingerDraws; applyPrefs() },
            MenuItem("סרגל כלים קבוע", checked = p.dockedToolbar) { p.dockedToolbar = !p.dockedToolbar; applyPrefs() },
            MenuItem("נתוני עט", checked = showDiag) { showDiag = !showDiag; renderDiag() },
            MenuItem("הגדרות…", separatorBefore = true) { startActivity(android.content.Intent(this, SettingsActivity::class.java)) },
        ), minWidthDp = 240)
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
        save()   // installing replaces the running app
        Updater.install(this, rel) { msg -> updateChip.text = msg }
    }
}
