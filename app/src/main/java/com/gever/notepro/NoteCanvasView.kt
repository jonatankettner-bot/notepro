@file:OptIn(androidx.ink.authoring.ExperimentalLatencyDataApi::class)

package com.gever.notepro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.OverScroller
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
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import java.util.IdentityHashMap
import kotlin.math.floor
import kotlin.math.min

/**
 * The note surface: white pages stacked vertically on a grey desk.
 * Pen writes; fingers scroll (one finger) and zoom (pinch). Strokes are stored in "world" units:
 * a page is PAGE_W x PAGE_H world units, independent of screen size and zoom.
 */
class NoteCanvasView(ctx: Context) : FrameLayout(ctx) {

    enum class Tool { PEN, FOUNTAIN, PENCIL, HIGHLIGHTER, ERASER, LASSO }
    enum class LassoMode { FREEFORM, RECT }
    enum class Paper { LINED, GRID, DOTS, BLANK }
    /** WHOLE removes touched strokes; PARTIAL cuts them, the remaining pieces stay normal strokes. */
    enum class EraserMode { WHOLE, PARTIAL }

    companion object {
        const val PAGE_W = 1000f
        const val PAGE_H = 1414f          // A4 proportions
        const val GAP = 36f               // grey space between pages
        const val RULE = 34f              // line / grid spacing
        private const val DESK = 0xFFE6E9EE.toInt()
    }

    // ---- settings driven by the toolbar ----
    var tool = Tool.PEN
    var penColor = 0xFF1F1F24.toInt()
    var penSize = 4f
    var hlColor = 0x66FFE14D
    var hlSize = 28f
    var usePressure = true
    var usePrediction = true
    var fingerDraws = false
    var paper = Paper.LINED
        set(v) { field = v; layer.invalidate() }
    var paperColor = Color.WHITE
        set(v) { field = v; layer.invalidate() }
    var eraserMode = EraserMode.WHOLE
    /** Eraser radius in screen dp (independent of zoom). */
    var eraserRadiusDp = 12f
    /** Screen y where the first page's top edge sits when scrolled to the start (below the toolbar). */
    var topInset = 0f
        set(v) {
            val atStart = width == 0 || ty >= maxTy() - 1f
            field = v
            if (atStart) ty = maxTy()
            if (width > 0) clampAndApply()
        }
    /** Screen pixels kept free on each side at the fit zoom (room for the side rail). */
    var sideInset = 16f
    var onHistoryChanged: (() -> Unit)? = null
    /** (current page, page count), 1-based. */
    var onPageChanged: ((Int, Int) -> Unit)? = null
    /** Called when an eraser stroke ends; true if it came from the eraser tool (not the pen button). */
    var onEraseEnded: ((Boolean) -> Unit)? = null

    // ---- document ----
    private val strokes = mutableListOf<Stroke>()
    private val bounds = IdentityHashMap<Stroke, RectF>()
    var pages = 1; private set
    /** Bookmarked pages (0-based). */
    val bookmarks = sortedSetOf<Int>()

    private sealed class Act {
        class Add(val s: Stroke) : Act()
        /** An eraser pass or page operation: strokes removed (with their old index) and strokes added. */
        class Edit(val removed: List<Pair<Stroke, Int>>, val added: List<Stroke>,
                   val pagesBefore: Int = -1, val pagesAfter: Int = -1,
                   val marksBefore: Set<Int>? = null, val marksAfter: Set<Int>? = null) : Act()
    }
    private val undoS = ArrayDeque<Act>()
    private val redoS = ArrayDeque<Act>()
    val canUndo get() = undoS.isNotEmpty()
    val canRedo get() = redoS.isNotEmpty()

    // ---- view transform: screen = world * s + (tx, ty) ----
    private val d = resources.displayMetrics.density
    private var s = 1f; private var tx = 0f; private var ty = 0f
    private var fitScale = 1f
    private val m = Matrix(); private val inv = Matrix()
    private val identity = Matrix()

    // ---- children ----
    private val layer = PaperLayer(ctx)
    val ink = InProgressStrokesView(ctx)
    private val predictor = MotionEventPredictor.newInstance(ink)

    // ---- input state ----
    private var penPointer = -1
    private var currentId: InProgressStrokeId? = null
    private var erasing = false
    private var eraseFromTool = false
    private val erasedNow = mutableListOf<Pair<Stroke, Int>>()
    private val addedNow = mutableListOf<Stroke>()
    private var gesturing = false

    // ---- diagnostics ----
    var diagPressure = 0f; private set
    var diagTool = "—"; private set
    var diagHz = 0; private set
    @Volatile var diagLatencyMs = 0.0; private set
    var diagBlocked = 0; private set
    private var strokeStart = 0L; private var samples = 0

    init {
        setBackgroundColor(DESK)
        addView(layer, LayoutParams(-1, -1))
        addView(ink, LayoutParams(-1, -1))
        ink.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for (st in strokes.values) commit(st)
                layer.invalidate()
                ink.removeFinishedStrokes(strokes.keys)
            }
        })
        // Latency hook isn't exposed to Kotlin in Ink 1.0.0; set it reflectively, ignore if missing.
        try {
            val cb = object : LatencyDataCallback {
                override fun onLatencyData(data: LatencyData) {
                    val ms = (data.estimatedPixelPresentationTime - data.osDetectsEvent) / 1e6
                    if (ms > 0 && ms < 500) diagLatencyMs = if (diagLatencyMs == 0.0) ms else diagLatencyMs * 0.95 + ms * 0.05
                }
            }
            InProgressStrokesView::class.java.getMethod("setLatencyDataCallback", LatencyDataCallback::class.java).invoke(ink, cb)
        } catch (_: Throwable) {}
        ink.setOnTouchListener { _, e -> onInput(e) }
    }

    // ================= geometry =================
    private fun pageTop(i: Int) = GAP + i * (PAGE_H + GAP)
    /** Largest allowed ty: the first page's top edge exactly at [topInset]. */
    private fun maxTy() = topInset - GAP * s
    private fun docHeight() = GAP + pages * (PAGE_H + GAP)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        val worldTop = if (oldw == 0) 0f else (0f - ty) / s
        val pageScreenW = min(w - 2 * sideInset, 980 * d)
        fitScale = pageScreenW / PAGE_W
        s = fitScale
        tx = (w - PAGE_W * s) / 2f
        ty = if (oldw == 0) maxTy() else -worldTop * s
        clampAndApply()
    }

    private fun clampAndApply() {
        val w = width.toFloat(); val h = height.toFloat()
        val docW = PAGE_W * s; val docH = docHeight() * s; val pad = 16 * d
        tx = if (docW <= w) (w - docW) / 2f else tx.coerceIn(w - docW - pad, pad)
        val top = maxTy()
        ty = if (docH <= h - top) top else ty.coerceIn(h - docH, top)
        m.setScale(s, s); m.postTranslate(tx, ty); m.invert(inv)
        layer.invalidate()
        reportPage()
        if (selection.isNotEmpty() && drag == Drag.NONE) notifySelection()
    }

    private var lastReported = -1L
    private fun reportPage() {
        val centerWorld = (height / 2f - ty) / s
        val cur = (floor((centerWorld - GAP / 2) / (PAGE_H + GAP)).toInt() + 1).coerceIn(1, pages)
        val key = cur.toLong() shl 32 or pages.toLong()
        if (key != lastReported) { lastReported = key; onPageChanged?.invoke(cur, pages) }
    }

    private fun toWorld(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { inv.mapPoints(it) }

    // ================= input =================
    private fun isPen(toolType: Int) = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
        toolType == MotionEvent.TOOL_TYPE_ERASER || toolType == MotionEvent.TOOL_TYPE_MOUSE ||
        (fingerDraws && toolType == MotionEvent.TOOL_TYPE_FINGER)

    private fun brush(): Brush = when (tool) {
        Tool.HIGHLIGHTER -> Brush.createWithColorIntArgb(Families.highlighter, hlColor, hlSize, 0.1f)
        Tool.FOUNTAIN -> Brush.createWithColorIntArgb(Families.of(Families.K_FOUNTAIN), penColor, penSize, 0.05f)
        Tool.PENCIL -> Brush.createWithColorIntArgb(Families.of(Families.K_PENCIL), penColor, penSize, 0.05f)
        else -> {
            val type = if (penType == PenType.PRESSURE && !usePressure) PenType.MARKER else penType
            // Rainbow turns the hue along the stroke, which needs a saturated start colour (black has no hue).
            val color = if (penEffect == PenEffect.RAINBOW) 0xFFE53935.toInt() else penColor
            Brush.createWithColorIntArgb(Families.of(Families.penCode(type, penEffect, stabilization)), color, penSize, 0.05f)
        }
    }

    // Regular pen style (the pen's style popover).
    var penType = PenType.PRESSURE
    var penEffect = PenEffect.NONE
    /** 0..10 = stabilization 0.0..1.0 (input smoothing). */
    var stabilization = 0

    // ---- eyedropper: the next touch on the page reports the colour of the ink under it ----
    /** Called with the colour under the touch, or null when the touch hit no ink. */
    var onColorPicked: ((Int?) -> Unit)? = null
    private var picking = false
    private var pickSwallow = false
    fun startColorPick() { picking = true }

    private fun colorAt(sx: Float, sy: Float): Int? {
        val p = toWorld(sx, sy)
        val r = 8 * d / s
        val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(p[0], p[1]), 2 * r, 2 * r)
        for (k in strokes.indices.reversed()) {
            val st = strokes[k]; val b = boundsOf(st)
            if (p[0] < b.left - r || p[0] > b.right + r || p[1] < b.top - r || p[1] > b.bottom + r) continue
            if (st.shape.computeCoverageIsGreaterThan(box, 0f)) return st.brush.colorIntArgb or 0xFF000000.toInt()
        }
        return null
    }
    /** Draw, hold the pen still ~0.5 s, lift: a nearly straight stroke becomes a straight line. */
    var snapLines = true

    private fun straightened(st: Stroke): Stroke? {
        val inputs = st.inputs; val n = inputs.size
        if (n < 6) return null
        val p = StrokeInput()
        inputs.populate(n - 1, p)
        val ex = p.x; val ey = p.y; val tEnd = p.elapsedTimeMillis
        val r = 8 * d / s
        var j = n - 1
        while (j > 0) { inputs.populate(j - 1, p); if (kotlin.math.hypot(p.x - ex, p.y - ey) > r) break; j-- }
        inputs.populate(j, p)
        if (tEnd - p.elapsedTimeMillis < 450) return null
        val x1 = p.x; val y1 = p.y
        inputs.populate(0, p)
        val x0 = p.x; val y0 = p.y; val t0 = p.elapsedTimeMillis
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        if (len < 30 * d / s) return null
        var maxDev = 0f; val pressures = ArrayList<Float>()
        for (k in 0..j) {
            inputs.populate(k, p)
            val dev = kotlin.math.abs((y1 - y0) * p.x - (x1 - x0) * p.y + x1 * y0 - y1 * x0) / len
            maxDev = maxOf(maxDev, dev); pressures.add(p.pressure)
        }
        if (maxDev > maxOf(0.07f * len, 10 * d / s)) return null
        pressures.sort(); val pr = pressures[pressures.size / 2]
        inputs.populate(0, p)
        val tool = p.toolType; val unit = p.strokeUnitLengthCm; val tilt = p.tiltRadians; val ori = p.orientationRadians
        val batch = MutableStrokeInputBatch(); val steps = 24
        for (k in 0..steps) {
            val f = k / steps.toFloat()
            p.update(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t0 + k * 8L, tool, unit, pr, tilt, ori)
            batch.add(p)
        }
        return Stroke(st.brush, batch.toImmutable())
    }

    private fun onInput(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (picking && action == MotionEvent.ACTION_DOWN) {
            picking = false; pickSwallow = true
            onColorPicked?.invoke(colorAt(e.x, e.y))
            return true
        }
        if (pickSwallow) {   // the rest of the eyedropper touch must not draw or scroll
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pickSwallow = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = e.actionIndex
            val type = e.getToolType(idx)
            if (isPen(type)) {
                if (penPointer != -1) return true
                if (gesturing) cancelGesture(e)
                startPen(e, idx, type)
                return true
            }
            if (penPointer != -1) { diagBlocked++; return true }   // palm while writing
            if (!gesturing) { tapStart = e.eventTime; tapMaxPointers = 0; tapMoved = false; tapX = e.getX(idx); tapY = e.getY(idx) }
            gesturing = true
            scroller.forceFinished(true)
        }

        if (penPointer != -1) { penEvent(e); return true }

        if (gesturing) {
            tapMaxPointers = maxOf(tapMaxPointers, e.pointerCount)
            if (action == MotionEvent.ACTION_MOVE && kotlin.math.hypot(e.getX(0) - tapX, e.getY(0) - tapY) > 18 * d) tapMoved = true
            scaleDetector.onTouchEvent(e)
            gestureDetector.onTouchEvent(e)
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gesturing = false
                if (action == MotionEvent.ACTION_UP && tapGestures && !tapMoved && e.eventTime - tapStart < 350) {
                    if (tapMaxPointers == 2) undo() else if (tapMaxPointers == 3) redo()
                }
            }
        }
        return true
    }

    /** Two-finger tap = undo, three-finger tap = redo. */
    var tapGestures = true
    private var tapStart = 0L; private var tapMaxPointers = 0; private var tapMoved = false
    private var tapX = 0f; private var tapY = 0f

    private fun startPen(e: MotionEvent, idx: Int, type: Int) {
        penPointer = e.getPointerId(idx)
        ink.requestUnbufferedDispatch(e)
        diagTool = toolName(type)
        strokeStart = e.eventTime; samples = 1
        val button = type == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (tool == Tool.LASSO && !button) { lassoActive = true; lassoDown(e.getX(idx), e.getY(idx)); return }
        erasing = tool == Tool.ERASER || button
        eraseFromTool = tool == Tool.ERASER && !button
        if (erasing) { erasedNow.clear(); addedNow.clear(); eraseAt(e.getX(idx), e.getY(idx)) }
        else { predictor.record(e); currentId = ink.startStroke(e, penPointer, brush(), Matrix(inv), identity) }
    }

    private fun penEvent(e: MotionEvent) {
        if (lassoActive) {
            val idx = e.findPointerIndex(penPointer)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (idx >= 0) {
                    for (h in 0 until e.historySize) lassoMove(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    lassoMove(e.getX(idx), e.getY(idx))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == penPointer) {
                        lassoUp(); lassoActive = false; penPointer = -1
                    }
                }
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(penPointer)
                if (idx < 0) return
                diagPressure = e.getPressure(idx)
                samples += e.historySize + 1
                val dt = e.eventTime - strokeStart
                if (dt > 80) diagHz = (samples * 1000L / dt).toInt()
                if (erasing) {
                    for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    eraseAt(e.getX(idx), e.getY(idx))
                } else {
                    val id = currentId ?: return
                    predictor.record(e)
                    val predicted = if (usePrediction) predictor.predict() else null
                    try { ink.addToStroke(e, penPointer, id, predicted) } finally { predicted?.recycle() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == penPointer)
                    endPen(e, canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0)
            }
            MotionEvent.ACTION_CANCEL -> endPen(e, canceled = true)
        }
    }

    private fun endPen(e: MotionEvent, canceled: Boolean) {
        if (erasing) {
            if (erasedNow.isNotEmpty() || addedNow.isNotEmpty()) push(Act.Edit(erasedNow.toList(), addedNow.toList()))
            erasedNow.clear(); addedNow.clear(); erasing = false
            onEraseEnded?.invoke(eraseFromTool)
        } else currentId?.let { id ->
            if (canceled) ink.cancelStroke(id, e) else { predictor.record(e); ink.finishStroke(e, penPointer, id) }
        }
        currentId = null; penPointer = -1
    }

    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_STYLUS -> "עט"
        MotionEvent.TOOL_TYPE_ERASER -> "קצה מחק"
        MotionEvent.TOOL_TYPE_FINGER -> "אצבע"
        MotionEvent.TOOL_TYPE_MOUSE -> "עכבר"
        else -> "לא ידוע"
    }

    // ---- finger gestures: scroll, fling, pinch-zoom ----
    private val scroller = OverScroller(ctx)
    private val flingStep = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat()
                clampAndApply(); postOnAnimation(this)
            }
        }
    }
    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy; clampAndApply(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            val w = width; val h = height
            val docW = (PAGE_W * s).toInt(); val docH = (docHeight() * s).toInt(); val pad = (16 * d).toInt()
            val minX = if (docW <= w) tx.toInt() else w - docW - pad
            val maxX = if (docW <= w) tx.toInt() else pad
            val maxY = maxTy().toInt()
            val minY = if (docH <= h - maxY) maxY else h - docH
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
            postOnAnimation(flingStep)
            return true
        }
    })
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val ns = (s * det.scaleFactor).coerceIn(fitScale * 0.5f, fitScale * 5f)
            val f = ns / s
            tx = det.focusX - (det.focusX - tx) * f
            ty = det.focusY - (det.focusY - ty) * f
            s = ns; clampAndApply(); return true
        }
    })

    private fun cancelGesture(e: MotionEvent) {
        val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
        scaleDetector.onTouchEvent(c); gestureDetector.onTouchEvent(c); c.recycle()
        scroller.forceFinished(true); gesturing = false
    }

    // ================= document edits =================
    private fun boundsOf(st: Stroke): RectF = bounds.getOrPut(st) {
        val b = st.shape.computeBoundingBox()
        if (b == null) RectF() else RectF(b.xMin, b.yMin, b.xMax, b.yMax)
    }

    private fun commit(raw: Stroke) {
        val st = (if (snapLines) runCatching { straightened(raw) }.getOrNull() else null) ?: raw
        strokes.add(st)
        val b = boundsOf(st)
        val lastPageTouched = floor((b.bottom - GAP) / (PAGE_H + GAP)).toInt()
        if (lastPageTouched >= pages - 1) { pages = lastPageTouched + 2; clampAndApply() }
        push(Act.Add(st))
    }

    private fun eraseAt(x: Float, y: Float) {
        val p = toWorld(x, y)
        val r = eraserRadiusDp * d / s
        val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(p[0], p[1]), 2 * r, 2 * r)
        val partial = eraserMode == EraserMode.PARTIAL && eraseFromTool
        var changed = false
        for (k in strokes.indices.reversed()) {
            val st = strokes[k]
            val b = boundsOf(st)
            if (p[0] < b.left - r || p[0] > b.right + r || p[1] < b.top - r || p[1] > b.bottom + r) continue
            if (!st.shape.computeCoverageIsGreaterThan(box, 0f)) continue
            val pieces = if (partial) cut(st, p[0], p[1], r) else emptyList()
            if (partial && pieces.size == 1 && pieces[0] === st) continue   // no input point inside: nothing to cut
            strokes.removeAt(k)
            // A piece created earlier in this same pass just disappears; otherwise remember the original.
            if (!addedNow.remove(st)) erasedNow.add(st to k)
            for ((j, piece) in pieces.withIndex()) { strokes.add(k + j, piece); addedNow.add(piece) }
            changed = true
        }
        if (changed) layer.invalidate()
    }

    /** Splits a stroke into the runs of input points that lie outside the eraser circle. */
    private fun cut(st: Stroke, cx: Float, cy: Float, r: Float): List<Stroke> {
        val inputs = st.inputs
        val n = inputs.size
        val pt = StrokeInput()
        val runs = mutableListOf<MutableStrokeInputBatch>()
        var cur: MutableStrokeInputBatch? = null
        var removedAny = false
        val r2 = r * r
        for (i in 0 until n) {
            inputs.populate(i, pt)
            val dx = pt.x - cx; val dy = pt.y - cy
            if (dx * dx + dy * dy <= r2) { removedAny = true; cur = null; continue }
            val batch = cur ?: MutableStrokeInputBatch().also { runs.add(it); cur = it }
            batch.add(pt)
        }
        if (!removedAny) return listOf(st)
        return runs.filter { it.size >= 2 }.map { Stroke(st.brush, it.toImmutable()) }
    }

    private fun push(a: Act) { undoS.addLast(a); redoS.clear(); dirty = true; onHistoryChanged?.invoke() }

    // ================= persistence =================
    /** True when strokes changed since the last load / markSaved(). */
    var dirty = false; private set
    fun markSaved() { dirty = false }
    fun strokesSnapshot(): List<Stroke> = strokes.toList()

    fun load(list: List<Stroke>) {
        strokes.clear(); bounds.clear(); undoS.clear(); redoS.clear()
        strokes.addAll(list)
        var lastPage = 0
        for (st in list) lastPage = maxOf(lastPage, floor((boundsOf(st).bottom - GAP) / (PAGE_H + GAP)).toInt())
        pages = lastPage + 2
        dirty = false
        if (width > 0) clampAndApply()
        layer.invalidate(); onHistoryChanged?.invoke()
    }

    /** Renders one page (paper + ink) into a small bitmap (library card, pages panel). */
    fun renderThumbnail(widthPx: Int, pageIndex: Int = 0): android.graphics.Bitmap {
        val sc = widthPx / PAGE_W
        val top = pageTop(pageIndex)
        val bmp = android.graphics.Bitmap.createBitmap(widthPx, (PAGE_H * sc).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tm = Matrix().apply { setScale(sc, sc); postTranslate(0f, -top * sc) }
        c.concat(tm)
        val page = RectF(0f, top, PAGE_W, top + PAGE_H)
        c.drawRect(page, Paint().apply { color = paperColor })
        layer.drawPattern(c, page)
        for (st in strokes) { val b = boundsOf(st); if (b.bottom > page.top && b.top < page.bottom) layer.renderer.draw(c, st, tm) }
        return bmp
    }

    // ================= pages =================
    fun currentPage(): Int = (floor(((height / 2f - ty) / s - GAP / 2) / (PAGE_H + GAP)).toInt()).coerceIn(0, pages - 1)

    fun scrollToPage(i: Int) {
        scroller.forceFinished(true)
        ty = topInset - pageTop(i) * s
        clampAndApply()
    }

    private fun pageOf(st: Stroke): Int { val b = boundsOf(st); return floor(((b.top + b.bottom) / 2 - GAP / 2) / (PAGE_H + GAP)).toInt() }

    fun pageHasInk(i: Int) = strokes.any { pageOf(it) == i }

    private fun moved(st: Stroke, dy: Float): Stroke {
        val inputs = st.inputs; val p = StrokeInput(); val batch = MutableStrokeInputBatch()
        for (k in 0 until inputs.size) {
            inputs.populate(k, p)
            p.update(p.x, p.y + dy, p.elapsedTimeMillis, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
            batch.add(p)
        }
        return Stroke(st.brush, batch.toImmutable())
    }

    /**
     * Generic page edit, undoable as one step. [shiftFrom]/[shift]: pages >= shiftFrom move by [shift] pages.
     * [dropPage]: strokes on this page are removed. [copyPage]: strokes on it are copied onto copyPage + 1.
     */
    private fun pageEdit(newPages: Int, shiftFrom: Int, shift: Int, dropPage: Int = -1, copyPage: Int = -1) {
        clearSelection()
        val step = PAGE_H + GAP
        val removed = mutableListOf<Pair<Stroke, Int>>(); val added = mutableListOf<Stroke>()
        val marksBefore = bookmarks.toSet()
        for ((k, st) in strokes.withIndex()) {
            val pg = pageOf(st)
            when {
                pg == dropPage -> removed += st to k
                pg >= shiftFrom && shift != 0 -> { removed += st to k; added += moved(st, shift * step) }
            }
            if (pg == copyPage) added += moved(st, step)
        }
        for (r in removed.asReversed()) strokes.removeAt(r.second)
        strokes.addAll(added)
        val newMarks = bookmarks.mapNotNull { b -> when { b == dropPage -> null; b >= shiftFrom -> b + shift; else -> b } }.toSet()
        bookmarks.clear(); bookmarks.addAll(newMarks)
        val before = pages
        pages = maxOf(1, newPages)
        push(Act.Edit(removed, added, before, pages, marksBefore, bookmarks.toSet()))
        clampAndApply(); layer.invalidate()
    }

    fun insertPageAfter(i: Int) = pageEdit(pages + 1, i + 1, 1)
    fun duplicatePage(i: Int) = pageEdit(pages + 1, i + 1, 1, copyPage = i)
    fun clearPage(i: Int) = pageEdit(pages, Int.MAX_VALUE, 0, dropPage = i)
    fun deletePage(i: Int) = pageEdit(pages - 1, i + 1, -1, dropPage = i)
    fun toggleBookmark(i: Int) { if (!bookmarks.remove(i)) bookmarks.add(i); dirty = true }

    /** Restores pages / bookmarks saved with the note (after load()). */
    fun restorePages(count: Int, marks: Collection<Int>) {
        pages = maxOf(pages, count); bookmarks.clear(); bookmarks.addAll(marks.filter { it < pages })
        if (width > 0) clampAndApply()
    }

    fun undo() {
        clearSelection()
        val a = undoS.removeLastOrNull() ?: return
        when (a) {
            is Act.Add -> strokes.remove(a.s)
            is Act.Edit -> {
                for (s in a.added) strokes.remove(s)
                for (p in a.removed.asReversed()) strokes.add(p.second.coerceAtMost(strokes.size), p.first)
                if (a.pagesBefore > 0) pages = a.pagesBefore
                a.marksBefore?.let { bookmarks.clear(); bookmarks.addAll(it) }
                if (a.pagesBefore > 0) clampAndApply()
            }
        }
        redoS.addLast(a); dirty = true; layer.invalidate(); onHistoryChanged?.invoke()
    }

    fun redo() {
        clearSelection()
        val a = redoS.removeLastOrNull() ?: return
        when (a) {
            is Act.Add -> strokes.add(a.s)
            is Act.Edit -> {
                for (p in a.removed) strokes.remove(p.first)
                strokes.addAll(a.added)
                if (a.pagesAfter > 0) pages = a.pagesAfter
                a.marksAfter?.let { bookmarks.clear(); bookmarks.addAll(it) }
                if (a.pagesAfter > 0) clampAndApply()
            }
        }
        undoS.addLast(a); dirty = true; layer.invalidate(); onHistoryChanged?.invoke()
    }

    // ================= lasso selection =================
    var lassoMode = LassoMode.FREEFORM
    /** Selection bounds in SCREEN pixels, or null when nothing is selected. */
    var onSelectionChanged: ((RectF?) -> Unit)? = null
    private var lassoActive = false
    private enum class Drag { NONE, DRAW, MOVE, RESIZE }
    private var drag = Drag.NONE
    private val lassoPts = ArrayList<Float>()          // world x,y pairs
    private val selection = mutableListOf<Stroke>()
    private val selBounds = RectF()
    private var dragX0 = 0f; private var dragY0 = 0f
    private var selDx = 0f; private var selDy = 0f; private var selK = 1f
    val hasSelection get() = selection.isNotEmpty()

    private fun lassoDown(x: Float, y: Float) {
        val w = toWorld(x, y)
        dragX0 = w[0]; dragY0 = w[1]; selDx = 0f; selDy = 0f; selK = 1f
        if (selection.isNotEmpty()) {
            val hr = 24 * d / s
            if (kotlin.math.hypot(w[0] - selBounds.right, w[1] - selBounds.bottom) < hr) { drag = Drag.RESIZE; return }
            val pad = 10 * d / s
            if (w[0] in selBounds.left - pad..selBounds.right + pad && w[1] in selBounds.top - pad..selBounds.bottom + pad) { drag = Drag.MOVE; notifySelection(true); return }
        }
        clearSelection()
        drag = Drag.DRAW; lassoPts.clear(); lassoPts.add(w[0]); lassoPts.add(w[1])
    }

    private fun lassoMove(x: Float, y: Float) {
        val w = toWorld(x, y)
        when (drag) {
            Drag.DRAW -> {
                if (lassoMode == LassoMode.RECT) { while (lassoPts.size > 2) lassoPts.removeAt(lassoPts.size - 1) }
                lassoPts.add(w[0]); lassoPts.add(w[1])
            }
            Drag.MOVE -> { selDx = w[0] - dragX0; selDy = w[1] - dragY0 }
            Drag.RESIZE -> {
                val bw = selBounds.width().coerceAtLeast(1f); val bh = selBounds.height().coerceAtLeast(1f)
                val kx = (w[0] - selBounds.left) / bw; val ky = (w[1] - selBounds.top) / bh
                selK = ((kx + ky) / 2).coerceIn(0.2f, 6f)
            }
            Drag.NONE -> {}
        }
        layer.invalidate()
    }

    private fun lassoUp() {
        when (drag) {
            Drag.DRAW -> {
                val poly = if (lassoMode == LassoMode.RECT && lassoPts.size >= 4) {
                    val x0 = lassoPts[0]; val y0 = lassoPts[1]; val x1 = lassoPts[lassoPts.size - 2]; val y1 = lassoPts[lassoPts.size - 1]
                    floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
                } else lassoPts.toFloatArray()
                lassoPts.clear()
                if (poly.size >= 6) {
                    val p = StrokeInput()
                    for (st in strokes) {
                        val inputs = st.inputs; var inside = 0
                        for (k in 0 until inputs.size) { inputs.populate(k, p); if (pointInPoly(p.x, p.y, poly)) inside++ }
                        if (inputs.size > 0 && inside * 2 >= inputs.size) selection.add(st)
                    }
                }
                updateSelBounds()
            }
            Drag.MOVE, Drag.RESIZE -> if (selDx != 0f || selDy != 0f || selK != 1f) {
                replaceSelection { st -> transformed(st, selBounds.left, selBounds.top, selK, selDx, selDy) }
            }
            Drag.NONE -> {}
        }
        drag = Drag.NONE; selDx = 0f; selDy = 0f; selK = 1f
        layer.invalidate(); notifySelection()
    }

    private fun pointInPoly(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false; var j = poly.size - 2; var i = 0
        while (i < poly.size) {
            val xi = poly[i]; val yi = poly[i + 1]; val xj = poly[j]; val yj = poly[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i; i += 2
        }
        return inside
    }

    private fun updateSelBounds() {
        selBounds.setEmpty()
        for (st in selection) { val b = boundsOf(st); if (selBounds.isEmpty) selBounds.set(b) else selBounds.union(b) }
    }

    private fun transformed(st: Stroke, ax: Float, ay: Float, k: Float, dx: Float, dy: Float, color: Int? = null): Stroke {
        val inputs = st.inputs; val p = StrokeInput(); val batch = MutableStrokeInputBatch()
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            p.update(ax + (p.x - ax) * k + dx, ay + (p.y - ay) * k + dy, p.elapsedTimeMillis, p.toolType,
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
            batch.add(p)
        }
        val b = st.brush
        val brush = Brush.createWithColorIntArgb(b.family, color ?: b.colorIntArgb, b.size * k, b.epsilon)
        return Stroke(brush, batch.toImmutable())
    }

    /** Replaces every selected stroke by f(stroke), as one undoable step; the new strokes stay selected. */
    private fun replaceSelection(f: (Stroke) -> Stroke) {
        val removed = mutableListOf<Pair<Stroke, Int>>(); val added = mutableListOf<Stroke>()
        for (st in selection) { val k = strokes.indexOf(st); if (k >= 0) removed += st to k }
        removed.sortBy { it.second }
        for (r in removed.asReversed()) strokes.removeAt(r.second)
        for (r in removed) added += f(r.first)
        strokes.addAll(added)
        selection.clear(); selection.addAll(added); updateSelBounds()
        growPagesFor(added)
        push(Act.Edit(removed, added))
        layer.invalidate()
    }

    private fun growPagesFor(list: List<Stroke>) {
        var last = pages - 2
        for (st in list) last = maxOf(last, floor((boundsOf(st).bottom - GAP) / (PAGE_H + GAP)).toInt())
        if (last + 2 > pages) { pages = last + 2; clampAndApply() }
    }

    fun clearSelection() {
        if (selection.isEmpty() && lassoPts.isEmpty()) return
        selection.clear(); lassoPts.clear(); selBounds.setEmpty(); layer.invalidate(); notifySelection()
    }

    fun deleteSelection() {
        val removed = selection.mapNotNull { st -> strokes.indexOf(st).takeIf { it >= 0 }?.let { st to it } }.sortedBy { it.second }
        for (r in removed.asReversed()) strokes.removeAt(r.second)
        push(Act.Edit(removed, emptyList()))
        selection.clear(); selBounds.setEmpty(); layer.invalidate(); notifySelection()
    }

    fun duplicateSelection() {
        val off = 40f
        val copies = selection.map { transformed(it, 0f, 0f, 1f, off, off) }
        strokes.addAll(copies); growPagesFor(copies)
        push(Act.Edit(emptyList(), copies))
        selection.clear(); selection.addAll(copies); updateSelBounds(); layer.invalidate(); notifySelection()
    }

    /** Recolours the selection; highlighter strokes keep their transparency. */
    fun recolorSelection(color: Int) = replaceSelection { st ->
        val c = if (st.brush.family == Families.highlighter) (HL_ALPHA shl 24) or (color and 0xFFFFFF) else color
        transformed(st, 0f, 0f, 1f, 0f, 0f, c)
    }

    private fun notifySelection(hideMenu: Boolean = false) {
        if (selection.isEmpty() || hideMenu) { onSelectionChanged?.invoke(null); return }
        val r = RectF(selBounds); m.mapRect(r); onSelectionChanged?.invoke(r)
    }

    // ================= drawing =================
    private inner class PaperLayer(ctx: Context) : View(ctx) {
        val renderer = CanvasStrokeRenderer.create()
        private val pagePaint = Paint()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000 }
        private val rulePaint = Paint().apply { color = 0xFFD5E1F2.toInt(); strokeWidth = 1.4f }
        private val gridPaint = Paint().apply { color = 0xFFE1E6EE.toInt(); strokeWidth = 1.2f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C2D0.toInt() }
        private val page = RectF()
        private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val lassoPath = android.graphics.Path()

        override fun onDraw(c: Canvas) {
            val visTop = (0f - ty) / s; val visBottom = (height - ty) / s
            val visLeft = (0f - tx) / s; val visRight = (width - tx) / s
            c.save(); c.concat(m)
            pagePaint.color = paperColor
            for (i in 0 until pages) {
                val top = pageTop(i)
                if (top > visBottom || top + PAGE_H < visTop) continue
                page.set(0f, top, PAGE_W, top + PAGE_H)
                c.drawRect(page.left - 2f, page.top - 1f, page.right + 2f, page.bottom + 4f, shadowPaint)
                c.drawRect(page, pagePaint)
                drawPattern(c, page)
            }
            val moving = selection.isNotEmpty() && (selDx != 0f || selDy != 0f || selK != 1f)
            for (st in strokes) {
                val b = boundsOf(st)
                if (b.bottom < visTop || b.top > visBottom || b.right < visLeft || b.left > visRight) continue
                if (moving && st in selection) continue
                renderer.draw(c, st, m)
            }
            if (selection.isNotEmpty()) {
                val t = Matrix().apply { setScale(selK, selK, selBounds.left, selBounds.top); postTranslate(selDx, selDy) }
                if (moving) {
                    c.save(); c.concat(t)
                    val full = Matrix(m).apply { preConcat(t) }
                    for (st in selection) renderer.draw(c, st, full)
                    c.restore()
                }
                val r = RectF(selBounds); t.mapRect(r)
                val pad = 8 * d / s
                r.inset(-pad, -pad)
                selPaint.strokeWidth = 1.5f * d / s
                selPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d / s, 6 * d / s), 0f)
                c.drawRect(r, selPaint)
                c.drawCircle(r.right, r.bottom, 7 * d / s, handlePaint)
                c.drawCircle(r.right, r.bottom, 7 * d / s, selPaint.apply { pathEffect = null })
            }
            if (lassoPts.size >= 4) {
                lassoPath.reset(); lassoPath.moveTo(lassoPts[0], lassoPts[1])
                if (lassoMode == LassoMode.RECT) {
                    val x1 = lassoPts[lassoPts.size - 2]; val y1 = lassoPts[lassoPts.size - 1]
                    lassoPath.lineTo(x1, lassoPts[1]); lassoPath.lineTo(x1, y1); lassoPath.lineTo(lassoPts[0], y1); lassoPath.close()
                } else { var i = 2; while (i < lassoPts.size) { lassoPath.lineTo(lassoPts[i], lassoPts[i + 1]); i += 2 } }
                selPaint.strokeWidth = 1.5f * d / s
                selPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d / s, 6 * d / s), 0f)
                c.drawPath(lassoPath, selPaint)
            }
            c.restore()
        }

        fun drawPattern(c: Canvas, p: RectF) {
            when (paper) {
                Paper.BLANK -> Unit
                Paper.LINED -> {
                    var y = p.top + 3 * RULE
                    while (y < p.bottom - RULE) { c.drawLine(p.left, y, p.right, y, rulePaint); y += RULE }
                }
                Paper.GRID -> {
                    var y = p.top + RULE
                    while (y < p.bottom) { c.drawLine(p.left, y, p.right, y, gridPaint); y += RULE }
                    var x = p.left + RULE
                    while (x < p.right) { c.drawLine(x, p.top, x, p.bottom, gridPaint); x += RULE }
                }
                Paper.DOTS -> {
                    var y = p.top + RULE
                    while (y < p.bottom) {
                        var x = p.left + RULE
                        while (x < p.right) { c.drawCircle(x, y, 1.8f, dotPaint); x += RULE }
                        y += RULE
                    }
                }
            }
        }
    }
}
