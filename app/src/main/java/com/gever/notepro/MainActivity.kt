@file:OptIn(androidx.ink.authoring.ExperimentalLatencyDataApi::class)

package com.gever.notepro

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyData
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

private const val BAR = 0xFF1D3461.toInt()
private const val BTN = 0xFF2B4A80.toInt()
private const val ON = 0xFFFFD24A.toInt()

/** The lined notebook page plus every finished stroke. */
class PaperView(ctx: Context) : View(ctx) {
    val strokes = mutableListOf<Stroke>()
    private val renderer = CanvasStrokeRenderer.create()
    private val identity = Matrix()
    private val d = resources.displayMetrics.density
    private val linePaint = Paint().apply { color = 0xFFC9D8EF.toInt(); strokeWidth = d }
    private val marginPaint = Paint().apply { color = 0xFFEFA0A0.toInt(); strokeWidth = 1.2f * d }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA6B8.toInt(); textSize = 14f * d; textAlign = Paint.Align.RIGHT
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFFDFDFB.toInt())
        val top = 72f * d
        var y = top
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, linePaint); y += 34f * d }
        val mx = width - 64f * d
        c.drawLine(mx, 0f, mx, height.toFloat(), marginPaint)
        c.drawText("NotePro · דף טיוטה", mx - 12f * d, top - 16f * d, textPaint)
        for (s in strokes) renderer.draw(c, s, identity)
    }
}

class MainActivity : Activity() {
    private enum class Mode { PEN, ERASER }
    private sealed class Act {
        class Add(val s: Stroke) : Act()
        class Erase(val list: List<Pair<Stroke, Int>>) : Act()
        class Clear(val list: List<Stroke>) : Act()
    }

    private lateinit var ips: InProgressStrokesView
    private lateinit var paper: PaperView
    private lateinit var diag: TextView
    private lateinit var predictor: MotionEventPredictor
    private val d by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())

    // settings
    private var mode = Mode.PEN
    private var color = 0xFF1B1C20.toInt()
    private var sizePx = 7f
    private var usePressure = true
    private var usePrediction = true
    private var fingerDraws = false
    private var showDiag = false

    // stroke state
    private var activePointer = -1
    private var currentId: InProgressStrokeId? = null
    private var erasing = false
    private val erasedNow = mutableListOf<Pair<Stroke, Int>>()
    private val undoS = ArrayDeque<Act>()
    private val redoS = ArrayDeque<Act>()

    // diagnostics
    private var dType = "—"; private var dPressure = "—"; private var dTilt = "—"; private var dButtons = "—"
    private var strokeStart = 0L; private var samples = 0; private var hz = 0
    @Volatile private var latMs = 0.0
    private var latencySupported = false
    private var blocked = 0

    // toolbar views that change state
    private lateinit var penBtn: Button; private lateinit var eraserBtn: Button
    private lateinit var pressureBtn: Button; private lateinit var predictBtn: Button
    private lateinit var fingerBtn: Button; private lateinit var diagBtn: Button
    private lateinit var undoBtn: Button; private lateinit var redoBtn: Button; private lateinit var clearBtn: Button
    private val swatches = mutableListOf<Pair<View, Int>>()
    private lateinit var updateBtn: Button
    private var pending: Updater.Release? = null

    private fun dp(v: Int) = (v * d).toInt()
    private fun round(c: Int, r: Float = 10f) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFDFE5EE.toInt()) }
        root.addView(buildToolbar(), LinearLayout.LayoutParams(-1, dp(60)))

        val host = FrameLayout(this)
        paper = PaperView(this)
        ips = InProgressStrokesView(this)
        diag = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; background = round(0xE6101A2E.toInt(), 12f)
            setPadding(dp(14), dp(10), dp(14), dp(10)); textDirection = View.TEXT_DIRECTION_RTL
        }
        host.addView(paper, FrameLayout.LayoutParams(-1, -1))
        host.addView(ips, FrameLayout.LayoutParams(-1, -1))
        host.addView(diag, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        setContentView(root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        predictor = MotionEventPredictor.newInstance(ips)
        ips.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for (s in strokes.values) { paper.strokes.add(s); push(Act.Add(s)) }
                paper.invalidate()
                ips.removeFinishedStrokes(strokes.keys)
            }
        })
        val latencyCb = object : LatencyDataCallback {
            override fun onLatencyData(data: LatencyData) {
                val ms = (data.estimatedPixelPresentationTime - data.osDetectsEvent) / 1e6
                if (ms > 0 && ms < 500) latMs = if (latMs == 0.0) ms else latMs * 0.95 + ms * 0.05
            }
        }
        // The latency hook is not exposed to Kotlin in Ink 1.0.0, so it is set through reflection; if that fails the app still works.
        latencySupported = try {
            InProgressStrokesView::class.java.getMethod("setLatencyDataCallback", LatencyDataCallback::class.java).invoke(ips, latencyCb); true
        } catch (t: Throwable) { false }
        ips.setOnTouchListener { v, e -> onTouch(v, e) }
        ips.setOnGenericMotionListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) note(e, 0, hover = true)
            false
        }
        refreshUi()
        ui.post(object : Runnable { override fun run() { renderDiag(); ui.postDelayed(this, 120) } })
    }

    // ---------- input ----------
    private fun allowed(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER ||
        tool == MotionEvent.TOOL_TYPE_MOUSE || (tool == MotionEvent.TOOL_TYPE_FINGER && fingerDraws)

    private fun brush(): Brush = Brush.createWithColorIntArgb(
        if (usePressure) StockBrushes.pressurePen() else StockBrushes.marker(), color, sizePx, 0.1f)

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val tool = e.getToolType(idx)
                note(e, idx, hover = false)
                if (!allowed(tool)) { if (tool == MotionEvent.TOOL_TYPE_FINGER) blocked++; return true }
                if (activePointer != -1) return true
                activePointer = e.getPointerId(idx)
                v.requestUnbufferedDispatch(e)
                strokeStart = e.eventTime; samples = 1
                erasing = mode == Mode.ERASER || tool == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (erasing) { erasedNow.clear(); eraseAt(e.getX(idx), e.getY(idx)) }
                else { predictor.record(e); currentId = ips.startStroke(e, activePointer, brush()) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointer == -1) return true
                val idx = e.findPointerIndex(activePointer)
                if (idx < 0) return true
                note(e, idx, hover = false)
                samples += e.historySize + 1
                val dt = e.eventTime - strokeStart
                if (dt > 80) hz = (samples * 1000L / dt).toInt()
                if (erasing) {
                    for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    eraseAt(e.getX(idx), e.getY(idx))
                } else {
                    val id = currentId ?: return true
                    predictor.record(e)
                    val predicted = if (usePrediction) predictor.predict() else null
                    try { ips.addToStroke(e, activePointer, id, predicted) } finally { predicted?.recycle() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) != activePointer) return true
                endStroke(e, canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0)
            }
            MotionEvent.ACTION_CANCEL -> if (activePointer != -1) endStroke(e, canceled = true)
        }
        return true
    }

    private fun endStroke(e: MotionEvent, canceled: Boolean) {
        if (erasing) {
            if (erasedNow.isNotEmpty()) push(Act.Erase(erasedNow.toList()))
            erasedNow.clear(); erasing = false
        } else currentId?.let { id ->
            if (canceled) ips.cancelStroke(id, e) else { predictor.record(e); ips.finishStroke(e, activePointer, id) }
        }
        currentId = null; activePointer = -1
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = 12f * d
        val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(x, y), 2 * r, 2 * r)
        var changed = false
        for (k in paper.strokes.indices.reversed()) {
            val s = paper.strokes[k]
            if (s.shape.computeCoverageIsGreaterThan(box, 0f)) { erasedNow.add(s to k); paper.strokes.removeAt(k); changed = true }
        }
        if (changed) paper.invalidate()
    }

    // ---------- undo / redo ----------
    private fun push(a: Act) { undoS.addLast(a); redoS.clear(); refreshUi() }
    private fun undo() {
        val a = undoS.removeLastOrNull() ?: return
        when (a) {
            is Act.Add -> paper.strokes.remove(a.s)
            is Act.Erase -> for (p in a.list.asReversed()) paper.strokes.add(p.second.coerceAtMost(paper.strokes.size), p.first)
            is Act.Clear -> { paper.strokes.clear(); paper.strokes.addAll(a.list) }
        }
        redoS.addLast(a); paper.invalidate(); refreshUi()
    }
    private fun redo() {
        val a = redoS.removeLastOrNull() ?: return
        when (a) {
            is Act.Add -> paper.strokes.add(a.s)
            is Act.Erase -> for (p in a.list) paper.strokes.remove(p.first)
            is Act.Clear -> paper.strokes.clear()
        }
        undoS.addLast(a); paper.invalidate(); refreshUi()
    }
    private fun clearAll() {
        if (paper.strokes.isEmpty()) return
        undoS.addLast(Act.Clear(paper.strokes.toList())); redoS.clear()
        paper.strokes.clear(); paper.invalidate(); refreshUi()
    }

    // ---------- diagnostics ----------
    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_STYLUS -> "עט"
        MotionEvent.TOOL_TYPE_ERASER -> "קצה מחק"
        MotionEvent.TOOL_TYPE_FINGER -> "אצבע"
        MotionEvent.TOOL_TYPE_MOUSE -> "עכבר"
        else -> "לא ידוע"
    }
    private fun note(e: MotionEvent, idx: Int, hover: Boolean) {
        dType = toolName(e.getToolType(idx)) + if (hover) " (ריחוף)" else ""
        dPressure = "%.3f".format(e.getPressure(idx))
        dTilt = "%.0f°".format(Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT, idx).toDouble()))
        val side = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        dButtons = (if (side) "לחוץ" else "לא") + "  (buttonState=" + e.buttonState + ")"
    }
    private fun renderDiag() {
        diag.visibility = if (showDiag) View.VISIBLE else View.GONE
        if (!showDiag) return
        val refresh = display?.refreshRate?.toInt() ?: 0
        diag.text = buildString {
            appendLine("סוג קלט: $dType")
            appendLine("לחץ: $dPressure")
            appendLine("הטיה: $dTilt")
            appendLine("כפתור צד: $dButtons")
            appendLine("קצב דגימה: " + if (hz > 0) "$hz Hz" else "—")
            appendLine("השהיה מוערכת (מגע עד פיקסל): " + when { latMs > 0 -> "%.1f ms".format(latMs); latencySupported -> "—"; else -> "לא זמין" })
            appendLine("רענון מסך: $refresh Hz")
            append("מגעי יד שנחסמו: $blocked")
        }
    }

    // ---------- updates ----------
    private fun checkForUpdate(manual: Boolean) {
        updateBtn.text = "בודק…"
        Updater.check { rel ->
            pending = rel
            if (rel != null) {
                updateBtn.text = "עדכון זמין: גרסה " + rel.versionCode
                style(updateBtn, true)
            } else {
                updateBtn.text = "בדוק עדכון"
                style(updateBtn, false)
                if (manual) Toast.makeText(this, "יש לך את הגרסה האחרונה", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun onUpdateClick() {
        val rel = pending
        if (rel == null) { checkForUpdate(manual = true); return }
        Updater.install(this, rel) { msg -> updateBtn.text = msg }
    }

    override fun onResume() {
        super.onResume()
        if (::updateBtn.isInitialized) checkForUpdate(manual = false)
    }

    // ---------- toolbar ----------
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        background = round(BTN); stateListAnimator = null
        minWidth = dp(48); minimumWidth = dp(48); minHeight = 0; minimumHeight = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(5) }
    }
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 1) }
    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
    }

    private fun buildToolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(10), 0, dp(10), 0)
        }
        penBtn = btn("עט") { mode = Mode.PEN; refreshUi() }
        eraserBtn = btn("מחק") { mode = Mode.ERASER; refreshUi() }
        row.addView(penBtn); row.addView(eraserBtn); row.addView(gap())

        for (c in listOf(0xFF1B1C20.toInt(), 0xFF1F5FD1.toInt(), 0xFFD33A2C.toInt(), 0xFF198754.toInt())) {
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(7) }
                setOnClickListener { color = c; mode = Mode.PEN; refreshUi() }
            }
            swatches.add(sw to c); row.addView(sw)
        }
        row.addView(gap())

        row.addView(label("עובי"))
        val sizeLabel = label("%.0f".format(sizePx))
        row.addView(SeekBar(this).apply {
            max = 22; progress = (sizePx - 2).toInt()
            layoutParams = LinearLayout.LayoutParams(dp(150), -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sizePx = p + 2f; sizeLabel.text = "%.0f".format(sizePx) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        row.addView(sizeLabel); row.addView(gap())

        pressureBtn = btn("לחץ") { usePressure = !usePressure; refreshUi() }
        predictBtn = btn("חיזוי") { usePrediction = !usePrediction; refreshUi() }
        fingerBtn = btn("אצבע כותבת") { fingerDraws = !fingerDraws; refreshUi() }
        row.addView(pressureBtn); row.addView(predictBtn); row.addView(fingerBtn); row.addView(gap())

        undoBtn = btn("↶") { undo() }; redoBtn = btn("↷") { redo() }; clearBtn = btn("נקה") { clearAll() }
        row.addView(undoBtn); row.addView(redoBtn); row.addView(clearBtn); row.addView(gap())

        diagBtn = btn("נתונים") { showDiag = !showDiag; refreshUi(); renderDiag() }
        row.addView(diagBtn); row.addView(gap())

        updateBtn = btn("בדוק עדכון") { onUpdateClick() }
        row.addView(updateBtn)
        row.addView(label("גרסה " + BuildConfig.VERSION_CODE))

        return HorizontalScrollView(this).apply {
            setBackgroundColor(BAR); isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(row, FrameLayout.LayoutParams(-2, -1))
        }
    }

    private fun style(b: Button, on: Boolean) {
        b.background = round(if (on) ON else BTN)
        b.setTextColor(if (on) 0xFF1B1B1B.toInt() else Color.WHITE)
    }
    private fun refreshUi() {
        style(penBtn, mode == Mode.PEN); style(eraserBtn, mode == Mode.ERASER)
        style(pressureBtn, usePressure); style(predictBtn, usePrediction)
        style(fingerBtn, fingerDraws); style(diagBtn, showDiag)
        for ((v, c) in swatches) v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(c)
            if (c == this@MainActivity.color) setStroke(dp(3), ON) else setStroke(dp(2), 0x40FFFFFF)
        }
        undoBtn.isEnabled = undoS.isNotEmpty(); undoBtn.alpha = if (undoS.isEmpty()) 0.35f else 1f
        redoBtn.isEnabled = redoS.isNotEmpty(); redoBtn.alpha = if (redoS.isEmpty()) 0.35f else 1f
        val any = paper.strokes.isNotEmpty()
        clearBtn.isEnabled = any; clearBtn.alpha = if (any) 1f else 0.35f
    }
}
