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
import androidx.ink.brush.InputToolType
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
        /** How long the pen must rest before a stroke snaps to a shape. */
        private const val HOLD_MS = 420L
        private const val NO_DRAG = -1
        private const val BODY = -2
        private const val ROTATE = -3
    }

    // ---- settings driven by the toolbar ----
    var tool = Tool.PEN
        set(v) { field = v; exitEdit(); deselectItem() }
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
        /** A change to the page items (pictures, stickers, notes): the whole list before and after. */
        class Items(val before: List<PageItem>, val after: List<PageItem>) : Act()
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
        if (selItem != null) notifyItem()
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
    /** Draw, rest the pen ~0.4 s: the stroke turns into a straight line or a perfect shape while the pen is still down. */
    var snapLines = true

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
        // A shape showing handles: touches on a handle / its outline edit it; anything else hides them.
        // Pictures, stickers and notes: drag the selected one; the selection tool picks them up.
        if (penPointer == -1 && !gesturing && handleItemTouch(e)) return true
        if ((editShape != null || dragStroke != null) && penPointer == -1 && handleEditTouch(e)) return true
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
                if (action == MotionEvent.ACTION_UP && !tapMoved && e.eventTime - tapStart < 350) {
                    if (tapMaxPointers == 1) { if (!selectShapeAt(tapX, tapY)) selectItemAt(tapX, tapY) }          // tap a shape = show its handles
                    else if (tapGestures && tapMaxPointers == 2) undo() else if (tapGestures && tapMaxPointers == 3) redo()
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
        else {
            predictor.record(e)
            val b = brush(); strokeBrush = b
            currentId = ink.startStroke(e, penPointer, b, Matrix(inv), identity)
            livePts.clear(); livePress.clear(); addLive(e.getX(idx), e.getY(idx), e.getPressure(idx))
            armHold(e.getX(idx), e.getY(idx))
        }
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
                } else if (live != null) {
                    liveMove(e.getX(idx), e.getY(idx))          // snapped: the pen now shapes the shape
                } else {
                    val id = currentId ?: return
                    for (h in 0 until e.historySize) addLive(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h))
                    addLive(e.getX(idx), e.getY(idx), e.getPressure(idx))
                    // Restart the "pen is resting" timer whenever the pen really moves.
                    if (kotlin.math.hypot(e.getX(idx) - holdX, e.getY(idx) - holdY) > 6 * d) armHold(e.getX(idx), e.getY(idx))
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
        removeCallbacks(holdCheck)
        if (live != null) {
            if (canceled) cancelLive() else commitLive()
            currentId = null; penPointer = -1
            return
        }
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
        val st = raw
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
        exitEdit(); strokes.clear(); bounds.clear(); undoS.clear(); redoS.clear(); shapes.clear()
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
        for (it in items) { val b = it.bounds(); if (b.bottom > page.top && b.top < page.bottom) itemPainter.draw(c, it) }
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
        // A moved shape stays an editable shape.
        return Stroke(st.brush, batch.toImmutable()).also { n -> shapes[st]?.let { shapes[n] = it.copy().apply { translate(0f, dy) } } }
    }

    /**
     * Generic page edit, undoable as one step. [shiftFrom]/[shift]: pages >= shiftFrom move by [shift] pages.
     * [dropPage]: strokes on this page are removed. [copyPage]: strokes on it are copied onto copyPage + 1.
     */
    private fun pageEdit(newPages: Int, shiftFrom: Int, shift: Int, dropPage: Int = -1, copyPage: Int = -1) {
        exitEdit()
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
        exitEdit(); deselectItem()
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
            is Act.Items -> { items.clear(); items.addAll(a.before.map { it.copy() }) }
        }
        redoS.addLast(a); dirty = true; layer.invalidate(); onHistoryChanged?.invoke()
    }

    fun redo() {
        exitEdit(); deselectItem()
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
            is Act.Items -> { items.clear(); items.addAll(a.after.map { it.copy() }) }
        }
        undoS.addLast(a); dirty = true; layer.invalidate(); onHistoryChanged?.invoke()
    }

    // ================= shapes: draw + hold = a perfect shape, then handles to edit it =================
    /** Geometry of strokes that are snapped shapes (kept so they stay editable). */
    private val shapes = IdentityHashMap<Stroke, Shape>()
    private val livePts = ArrayList<Float>()      // page x,y of the stroke being drawn
    private val livePress = ArrayList<Float>()
    private var holdX = 0f; private var holdY = 0f
    private var strokeBrush: Brush? = null
    private val holdCheck = Runnable { trySnap() }
    /** Page units per dp at the current zoom. */
    private val u get() = d / s

    /** The shape being shaped while the pen is still down (after the hold). */
    private var live: Shape? = null
    private var livePressure = 0.5f
    /** The shape whose handles are shown after the pen lifted. */
    private var editStroke: Stroke? = null
    private var editShape: Shape? = null
    /** Active handle index, or BODY (move) / ROTATE, during a live or edit drag. */
    private var dragH = NO_DRAG
    private var dragOffX = 0f; private var dragOffY = 0f
    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragBase: Shape? = null
    private var dragStroke: Stroke? = null           // non-null while an edit drag runs
    private var dragIdx = -1
    private var dragChanged = false
    private var editPointer = -1
    private var preview: Stroke? = null
    /** Smart guides while dragging: page segments x1,y1,x2,y2, and a magnet ring. */
    private val guides = ArrayList<Float>()
    private var magnet: FloatArray? = null
    private var targets: SnapTargets? = null

    private class SnapTargets(
        val xs: FloatArray,      // (x, y0, y1) triples: vertical alignment lines
        val ys: FloatArray,      // (y, x0, x1) triples: horizontal alignment lines
        val pts: FloatArray,     // magnet points
        val lines: FloatArray,   // straight edges for "parallel" guides
    )

    private fun isInkTool() = tool == Tool.PEN || tool == Tool.FOUNTAIN || tool == Tool.PENCIL || tool == Tool.HIGHLIGHTER

    private fun addLive(sx: Float, sy: Float, pr: Float) {
        val w = toWorld(sx, sy); livePts.add(w[0]); livePts.add(w[1]); livePress.add(pr)
    }

    private fun armHold(sx: Float, sy: Float) {
        holdX = sx; holdY = sy
        removeCallbacks(holdCheck)
        if (snapLines && isInkTool()) postDelayed(holdCheck, HOLD_MS)
    }

    /** The pen has rested: if the stroke so far looks like a shape, replace it by the perfect one. */
    private fun trySnap() {
        val id = currentId ?: return
        if (penPointer == -1 || live != null || erasing || livePts.size < 12) return
        val sh = ShapeRecognizer.recognize(livePts.toFloatArray(), u) ?: return
        ink.cancelStroke(id); currentId = null
        live = sh
        livePressure = livePress.sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }.coerceIn(0.05f, 1f)
        // The pen now holds the nearest handle: moving it changes that dimension until the pen lifts.
        val px = livePts[livePts.size - 2]; val py = livePts[livePts.size - 1]
        val hs = sh.handles(); var best = 0; var bd = Float.MAX_VALUE
        for (k in 0 until hs.size / 2) { val dd = kotlin.math.hypot(hs[2 * k] - px, hs[2 * k + 1] - py); if (dd < bd) { bd = dd; best = k } }
        dragH = best; dragOffX = hs[2 * best] - px; dragOffY = hs[2 * best + 1] - py
        targets = collectTargets(null)
        preview = strokeOf(sh, strokeBrush ?: brush(), livePressure)
        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        layer.invalidate()
    }

    private fun liveMove(sx: Float, sy: Float) {
        val sh = live ?: return
        val w = toWorld(sx, sy)
        dragHandle(sh, dragH, w[0] + dragOffX, w[1] + dragOffY)
        preview = strokeOf(sh, strokeBrush ?: brush(), livePressure)
        layer.invalidate()
    }

    /** Pen lifted after a snap: the shape becomes a stroke and keeps its handles for editing. */
    private fun commitLive() {
        val sh = live ?: return
        live = null; preview = null; guides.clear(); magnet = null; dragH = NO_DRAG
        val st = strokeOf(sh, strokeBrush ?: brush(), livePressure)
        if (st != null) {
            strokes.add(st); shapes[st] = sh.copy()
            growPagesFor(listOf(st)); push(Act.Add(st))
            editStroke = st; editShape = sh
        }
        layer.invalidate()
    }

    private fun cancelLive() { live = null; preview = null; guides.clear(); magnet = null; dragH = NO_DRAG; layer.invalidate() }

    /** Builds the ink stroke of a shape: its outline as inputs with one constant pressure. */
    private fun strokeOf(sh: Shape, brush0: Brush, pressure: Float): Stroke? = runCatching {
        // Same look as the pen, but without input smoothing: it bent the start of perfect circles.
        val brush = Brush.createWithColorIntArgb(Families.exact(brush0.family), brush0.colorIntArgb, brush0.size, brush0.epsilon)
        val o = sh.outline(4f)
        val batch = MutableStrokeInputBatch(); val p = StrokeInput()
        val unitCm = s / (resources.displayMetrics.xdpi / 2.54f)
        for (k in 0 until o.size / 2) {
            p.update(o[2 * k], o[2 * k + 1], k * 4L, InputToolType.STYLUS, unitCm, pressure)
            batch.add(p)
        }
        Stroke(brush, batch.toImmutable())
    }.getOrNull()

    private fun pressureOf(st: Stroke): Float {
        if (st.inputs.size == 0) return 0.5f
        val p = StrokeInput(); st.inputs.populate(0, p)
        return if (p.pressure.isNaN() || p.pressure < 0f) 0.5f else p.pressure
    }

    // ---------------- edit mode ----------------
    /** Touches while a shape shows its handles. Returns true when the touch was used for editing. */
    private fun handleEditTouch(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (dragStroke != null) {
            val idx = e.findPointerIndex(editPointer)
            when (action) {
                MotionEvent.ACTION_MOVE -> if (idx >= 0) editMove(e.getX(idx), e.getY(idx))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == editPointer) editEnd()
                MotionEvent.ACTION_CANCEL -> editEnd()
            }
            return true
        }
        if (action == MotionEvent.ACTION_DOWN && editShape != null) {
            val h = hitTest(e.x, e.y)
            if (h != NO_DRAG) { editPointer = e.getPointerId(0); editBegin(h, e.x, e.y); return true }
            exitEdit()
        }
        return false
    }

    private fun screen(p: FloatArray) = p.copyOf().also { m.mapPoints(it) }

    private fun rotHandle(sh: Shape): FloatArray {
        val r = RectF(sh.bounds()); m.mapRect(r)
        return floatArrayOf(r.centerX(), r.bottom + 36 * d)
    }

    private fun hitTest(sx: Float, sy: Float): Int {
        val sh = editShape ?: return NO_DRAG
        val hs = screen(sh.handles())
        var best = NO_DRAG; var bd = 26 * d
        for (k in 0 until hs.size / 2) { val dd = kotlin.math.hypot(hs[2 * k] - sx, hs[2 * k + 1] - sy); if (dd < bd) { bd = dd; best = k } }
        if (best != NO_DRAG) return best
        if (sh.rotatable) { val r = rotHandle(sh); if (kotlin.math.hypot(r[0] - sx, r[1] - sy) < 26 * d) return ROTATE }
        val o = screen(sh.outline(6f))
        var i = 0
        while (i + 3 < o.size) { if (segDist(sx, sy, o[i], o[i + 1], o[i + 2], o[i + 3]) < 18 * d) return BODY; i += 2 }
        return NO_DRAG
    }

    private fun segDist(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (x0 + t * dx), y - (y0 + t * dy))
    }

    private fun editBegin(h: Int, sx: Float, sy: Float) {
        val st = editStroke ?: return; val sh = editShape ?: return
        dragIdx = strokes.indexOf(st)
        if (dragIdx < 0) { exitEdit(); return }
        dragStroke = st; dragBase = sh.copy(); dragH = h; dragChanged = false
        val w = toWorld(sx, sy); dragStartX = w[0]; dragStartY = w[1]
        if (h >= 0) { val hs = sh.handles(); dragOffX = hs[2 * h] - w[0]; dragOffY = hs[2 * h + 1] - w[1] }
        targets = collectTargets(st)
        layer.invalidate()
    }

    private fun editMove(sx: Float, sy: Float) {
        val base = dragBase ?: return; val from = dragStroke ?: return
        val w = toWorld(sx, sy)
        val sh = base.copy()
        guides.clear(); magnet = null
        when (dragH) {
            BODY -> { val t = snapTranslate(sh, w[0] - dragStartX, w[1] - dragStartY); sh.translate(t[0], t[1]) }
            ROTATE -> {
                val c = base.center()
                var a = kotlin.math.atan2(w[1] - c[1], w[0] - c[0]) - kotlin.math.atan2(dragStartY - c[1], dragStartX - c[0])
                val step = (Math.PI / 12).toFloat()                      // 15° steps snap within 2.5°
                val k = kotlin.math.round(a / step); if (kotlin.math.abs(a - k * step) < 0.044f) a = k * step
                sh.rotate(a, c[0], c[1])
            }
            else -> dragHandle(sh, dragH, w[0] + dragOffX, w[1] + dragOffY)
        }
        val st = strokeOf(sh, from.brush, pressureOf(from)) ?: return
        if (dragIdx in strokes.indices) strokes[dragIdx] = st
        editStroke = st; editShape = sh; dragChanged = true
        layer.invalidate()
    }

    private fun editEnd() {
        val from = dragStroke
        if (from != null && dragChanged) {
            val st = editStroke!!
            shapes[st] = editShape!!.copy()
            push(Act.Edit(listOf(from to dragIdx), listOf(st)))
            growPagesFor(listOf(st))
        }
        dragStroke = null; dragBase = null; dragH = NO_DRAG; dragChanged = false; editPointer = -1
        guides.clear(); magnet = null
        layer.invalidate()
    }

    /** A finger tap on a shape's outline brings its handles back, so older shapes can be edited too. */
    private fun selectShapeAt(sx: Float, sy: Float): Boolean {
        for (k in strokes.indices.reversed()) {
            val st = strokes[k]; val sh = shapes[st] ?: continue
            val o = screen(sh.outline(6f))
            var i = 0
            while (i + 3 < o.size) {
                if (segDist(sx, sy, o[i], o[i + 1], o[i + 2], o[i + 3]) < 16 * d) {
                    editStroke = st; editShape = sh.copy(); layer.invalidate(); return true
                }
                i += 2
            }
        }
        return false
    }

    /** Hides the shape handles (another touch, tool change, undo ...). */
    fun exitEdit() {
        if (dragStroke != null) editEnd()
        if (editShape == null && editStroke == null) return
        editStroke = null; editShape = null; layer.invalidate()
    }

    // ---------------- smart guides ----------------
    /** Moves one handle, snapping to nearby points, alignments and (for lines) angles. */
    private fun dragHandle(sh: Shape, i: Int, x0: Float, y0: Float) {
        guides.clear(); magnet = null
        // 1) a magnet point wins, 2) for lines an angle (level, upright, 45°, parallel), 3) else alignment.
        magnetPoint(x0, y0)?.let { magnet = it; sh.moveHandle(i, it[0], it[1]); return }
        if (sh is LineShape) angleSnap(sh, i, x0, y0)?.let { sh.moveHandle(i, it[0], it[1]); return }
        val p = snapPoint(x0, y0)
        sh.moveHandle(i, p[0], p[1])
    }

    /** Line end rotated onto a level / upright / diagonal / parallel direction when close; null if none. */
    private fun angleSnap(sh: LineShape, i: Int, x0: Float, y0: Float): FloatArray? {
        var x = x0; var y = y0
        run {
            val fx = if (i == 0) sh.x2 else sh.x1; val fy = if (i == 0) sh.y2 else sh.y1
            val a = kotlin.math.atan2(y - fy, x - fx); val len = kotlin.math.hypot(x - fx, y - fy)
            var bestA = Float.NaN; var bestD = 0.06f                 // ~3.5°
            val q = (Math.PI / 4).toFloat()
            val k = kotlin.math.round(a / q); if (kotlin.math.abs(a - k * q) < bestD) { bestD = kotlin.math.abs(a - k * q); bestA = k * q }
            var parallel: Int = -1
            val ls = targets?.lines ?: FloatArray(0)
            var j = 0
            while (j < ls.size) {
                var la = kotlin.math.atan2(ls[j + 3] - ls[j + 1], ls[j + 2] - ls[j])
                var dd = a - la
                while (dd > Math.PI / 2) { dd -= Math.PI.toFloat(); la += Math.PI.toFloat() }
                while (dd < -Math.PI / 2) { dd += Math.PI.toFloat(); la -= Math.PI.toFloat() }
                if (kotlin.math.abs(dd) < 0.05f && kotlin.math.abs(dd) < bestD) { bestD = kotlin.math.abs(dd); bestA = la; parallel = j }
                j += 4
            }
            if (bestA.isNaN()) return null
            x = fx + len * kotlin.math.cos(bestA); y = fy + len * kotlin.math.sin(bestA)
            if (parallel >= 0) { for (t in 0 until 4) guides.add(ls[parallel + t]); guides.add(fx); guides.add(fy); guides.add(x); guides.add(y) }
            else {   // level / upright / diagonal: a guide through the line, across the page
                val ext = 3000f
                clipToPage(fx - ext * kotlin.math.cos(bestA), fy - ext * kotlin.math.sin(bestA),
                    fx + ext * kotlin.math.cos(bestA), fy + ext * kotlin.math.sin(bestA))?.let { for (v in it) guides.add(v) }
            }
        }
        return floatArrayOf(x, y)
    }

    /** The part of a segment inside the current page (Liang–Barsky), or null. */
    private fun clipToPage(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray? {
        val top = pageTop(currentPage())
        var t0 = 0f; var t1 = 1f
        val dx = x2 - x1; val dy = y2 - y1
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - 0f, PAGE_W - x1, y1 - top, top + PAGE_H - y1)
        for (k in 0 until 4) {
            if (p[k] == 0f) { if (q[k] < 0) return null; continue }
            val r = q[k] / p[k]
            if (p[k] < 0) { if (r > t1) return null; if (r > t0) t0 = r } else { if (r < t0) return null; if (r < t1) t1 = r }
        }
        return floatArrayOf(x1 + t0 * dx, y1 + t0 * dy, x1 + t1 * dx, y1 + t1 * dy)
    }

    /** The nearest magnet point (another shape's corner, end, centre ...) within reach, or null. */
    private fun magnetPoint(x: Float, y: Float): FloatArray? {
        val t = targets ?: return null
        var best = -1; var bd = 12 * u
        for (k in 0 until t.pts.size / 2) { val dd = kotlin.math.hypot(t.pts[2 * k] - x, t.pts[2 * k + 1] - y); if (dd < bd) { bd = dd; best = k } }
        return if (best >= 0) floatArrayOf(t.pts[2 * best], t.pts[2 * best + 1]) else null
    }

    /** Lines a point up with other things' edges and centres (vertical / horizontal guides). */
    private fun snapPoint(x: Float, y: Float): FloatArray {
        val t = targets ?: return floatArrayOf(x, y)
        var rx = x; var ry = y
        val tol = 7 * u
        var bx = -1; var bdx = tol
        for (k in 0 until t.xs.size / 3) { val dd = kotlin.math.abs(t.xs[3 * k] - x); if (dd < bdx) { bdx = dd; bx = k } }
        if (bx >= 0) { rx = t.xs[3 * bx]; guides.add(rx); guides.add(min(y, t.xs[3 * bx + 1])); guides.add(rx); guides.add(kotlin.math.max(y, t.xs[3 * bx + 2])) }
        var by = -1; var bdy = tol
        for (k in 0 until t.ys.size / 3) { val dd = kotlin.math.abs(t.ys[3 * k] - y); if (dd < bdy) { bdy = dd; by = k } }
        if (by >= 0) { ry = t.ys[3 * by]; guides.add(min(x, t.ys[3 * by + 1])); guides.add(ry); guides.add(kotlin.math.max(x, t.ys[3 * by + 2])); guides.add(ry) }
        return floatArrayOf(rx, ry)
    }

    /** Snaps a whole-shape move: its points to other points, else its edges / centre to other things'. */
    private fun snapTranslate(sh: Shape, dx0: Float, dy0: Float): FloatArray {
        val t = targets ?: return floatArrayOf(dx0, dy0)
        val kp = sh.keyPoints()
        var bd = 10 * u; var mdx = 0f; var mdy = 0f; var hit = false
        for (k in 0 until kp.size / 2) {
            val px = kp[2 * k] + dx0; val py = kp[2 * k + 1] + dy0
            for (j in 0 until t.pts.size / 2) {
                val dd = kotlin.math.hypot(t.pts[2 * j] - px, t.pts[2 * j + 1] - py)
                if (dd < bd) { bd = dd; mdx = t.pts[2 * j] - px; mdy = t.pts[2 * j + 1] - py; hit = true; magnet = floatArrayOf(t.pts[2 * j], t.pts[2 * j + 1]) }
            }
        }
        if (hit) return floatArrayOf(dx0 + mdx, dy0 + mdy)
        val b = sh.bounds(); b.offset(dx0, dy0)
        var dx = dx0; var dy = dy0
        val tol = 7 * u
        var bestX = tol; var gx = -1; var gxv = 0f
        for (v in floatArrayOf(b.left, b.centerX(), b.right)) for (k in 0 until t.xs.size / 3) {
            val dd = t.xs[3 * k] - v; if (kotlin.math.abs(dd) < bestX) { bestX = kotlin.math.abs(dd); gx = k; gxv = dd }
        }
        if (gx >= 0) { dx += gxv; val x = t.xs[3 * gx]; guides.add(x); guides.add(min(b.top, t.xs[3 * gx + 1])); guides.add(x); guides.add(kotlin.math.max(b.bottom, t.xs[3 * gx + 2])) }
        var bestY = tol; var gy = -1; var gyv = 0f
        for (v in floatArrayOf(b.top, b.centerY(), b.bottom)) for (k in 0 until t.ys.size / 3) {
            val dd = t.ys[3 * k] - v; if (kotlin.math.abs(dd) < bestY) { bestY = kotlin.math.abs(dd); gy = k; gyv = dd }
        }
        if (gy >= 0) { dy += gyv; val y = t.ys[3 * gy]; guides.add(min(b.left, t.ys[3 * gy + 1])); guides.add(y); guides.add(kotlin.math.max(b.right, t.ys[3 * gy + 2])); guides.add(y) }
        return floatArrayOf(dx, dy)
    }

    /** What the moving shape can line up with: other strokes on screen, other shapes' points and edges, the page centre. */
    private fun collectTargets(exclude: Stroke?): SnapTargets {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val pts = ArrayList<Float>(); val lines = ArrayList<Float>()
        val visTop = (0f - ty) / s; val visBottom = (height - ty) / s
        fun box(r: RectF) {
            for (x in floatArrayOf(r.left, r.centerX(), r.right)) { xs += x; xs += r.top; xs += r.bottom }
            for (y in floatArrayOf(r.top, r.centerY(), r.bottom)) { ys += y; ys += r.left; ys += r.right }
        }
        for (st in strokes) {
            if (st === exclude) continue
            val b = boundsOf(st)
            if (b.bottom < visTop || b.top > visBottom) continue
            val sh = shapes[st]
            if (sh != null) { pts.addAll(sh.keyPoints().toList()); lines.addAll(sh.edges().toList()); box(sh.bounds()) } else box(b)
        }
        val top = pageTop(currentPage())
        xs += PAGE_W / 2; xs += top; xs += top + PAGE_H
        return SnapTargets(xs.toFloatArray(), ys.toFloatArray(), pts.toFloatArray(), lines.toFloatArray())
    }

    // ---------------- persistence ----------------
    /** Shapes as JSON keyed by stroke index (stored beside ink.bin). */
    fun shapesJson(): String {
        val a = org.json.JSONArray()
        for ((i, st) in strokes.withIndex()) shapes[st]?.let { a.put(it.toJson().put("i", i)) }
        return a.toString()
    }

    fun restoreShapes(json: String?) {
        shapes.clear()
        if (json.isNullOrEmpty()) return
        runCatching {
            val a = org.json.JSONArray(json)
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k); val i = o.getInt("i")
                if (i in strokes.indices) Shape.fromJson(o)?.let { shapes[strokes[i]] = it }
            }
        }
    }

    // ================= page items: pictures, stickers, sticky notes =================
    val items = mutableListOf<PageItem>()
    /** Where this note's pictures are stored (set by the editor). */
    var mediaDir: java.io.File? = null
    private val bitmaps = HashMap<String, android.graphics.Bitmap?>()
    private val itemPainter = ItemPainter { name -> bitmaps.getOrPut(name) { decodeMedia(name) } }
    /** Selected item and its screen bounds (for the floating menu), or nulls. */
    var onItemSelected: ((PageItem?, RectF?) -> Unit)? = null
    var selItem: PageItem? = null; private set
    private enum class IDrag { NONE, MOVE, CORNER, ROTATE }
    private var iDrag = IDrag.NONE
    private var iPointer = -1
    private var iBefore: List<PageItem>? = null
    private var iStart: PageItem? = null
    private var iStartX = 0f; private var iStartY = 0f
    private var iCorner = 0
    private var iChanged = false

    private fun decodeMedia(name: String): android.graphics.Bitmap? {
        val f = java.io.File(mediaDir ?: return null, name)
        if (!f.exists()) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun snapshotItems() = items.map { it.copy() }

    /** Records an items change as one undo step. */
    private fun pushItems(before: List<PageItem>) { push(Act.Items(before, snapshotItems())); notifyItem(); layer.invalidate() }

    /** Centre of what is on screen now (clamped to the pages), for placing new things. */
    fun visibleCenter(): FloatArray {
        val p = toWorld(width / 2f, (topInset + height) / 2f)
        p[0] = p[0].coerceIn(120f, PAGE_W - 120f)
        return p
    }

    /**
     * A free spot near the middle of the screen for something w x h big: tries the centre, then around it,
     * so a new sticker / note / shape does not land on top of what is already there.
     */
    fun freeSpot(w: Float, h: Float): FloatArray {
        val c = visibleCenter()
        val sx = w + 40f; val sy = h + 40f
        val offs = listOf(0f to 0f, 1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f, 2f to 0f, -2f to 0f, 0f to 2f)
        val taken = items.map { it.bounds() } + strokes.map { boundsOf(it) }
        for ((ox, oy) in offs) {
            val x = c[0] + ox * sx; val y = c[1] + oy * sy
            if (x - w / 2 < 10f || x + w / 2 > PAGE_W - 10f) continue
            val r = RectF(x - w / 2 - 10, y - h / 2 - 10, x + w / 2 + 10, y + h / 2 + 10)
            if (taken.none { RectF.intersects(it, r) }) return floatArrayOf(x, y)
        }
        return c
    }

    fun addItem(it: PageItem) {
        exitEdit(); clearSelection()
        val before = snapshotItems()
        items.add(it)
        growPagesForRect(it.bounds())
        selItem = it
        pushItems(before)
    }

    /** Changes the selected item (text, colour ...) as one undo step. */
    fun changeSelectedItem(f: (PageItem) -> Unit) {
        val it = selItem ?: return
        val before = snapshotItems(); f(it); pushItems(before)
    }

    fun deleteSelectedItem() {
        val it = selItem ?: return
        val before = snapshotItems(); items.remove(it); selItem = null; pushItems(before)
    }

    fun duplicateSelectedItem() {
        val it = selItem ?: return
        val before = snapshotItems()
        val c = it.copy(java.util.UUID.randomUUID().toString()).apply { cx += 30f; cy += 30f }
        items.add(c); selItem = c; pushItems(before)
    }

    fun deselectItem() { if (selItem == null) return; endItemDrag(); selItem = null; notifyItem(); layer.invalidate() }

    private fun notifyItem() {
        val it = selItem
        if (it == null || iDrag != IDrag.NONE) { onItemSelected?.invoke(null, null); return }
        val r = it.bounds(); m.mapRect(r); onItemSelected?.invoke(it, r)
    }

    private fun growPagesForRect(r: RectF) {
        val last = floor((r.bottom - GAP) / (PAGE_H + GAP)).toInt()
        if (last + 2 > pages) { pages = last + 2; clampAndApply() }
    }

    private fun itemAt(sx: Float, sy: Float): PageItem? {
        val w = toWorld(sx, sy)
        return items.lastOrNull { it.contains(w[0], w[1]) }
    }

    private fun rotHandleOf(it: PageItem): FloatArray {
        val r = it.bounds(); m.mapRect(r); return floatArrayOf(r.centerX(), r.bottom + 34 * d)
    }

    /** What a touch at (sx, sy) on the selected item does. */
    private fun itemHit(sx: Float, sy: Float): IDrag {
        val it = selItem ?: return IDrag.NONE
        val k = it.corners(); m.mapPoints(k)
        for (i in 0 until 4) if (kotlin.math.hypot(k[2 * i] - sx, k[2 * i + 1] - sy) < 26 * d) { iCorner = i; return IDrag.CORNER }
        val r = rotHandleOf(it)
        if (kotlin.math.hypot(r[0] - sx, r[1] - sy) < 26 * d) return IDrag.ROTATE
        val w = toWorld(sx, sy)
        return if (it.contains(w[0], w[1], 10 * u)) IDrag.MOVE else IDrag.NONE
    }

    /** Touches for items. Returns true when used. */
    private fun handleItemTouch(e: MotionEvent): Boolean {
        if (iDrag != IDrag.NONE) {
            val idx = e.findPointerIndex(iPointer)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (idx >= 0) itemMove(e.getX(idx), e.getY(idx))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == iPointer) endItemDrag()
                MotionEvent.ACTION_CANCEL -> endItemDrag()
            }
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return false
        val pen = isPen(e.getToolType(0))
        // On the selected item: drag a corner, the rotate handle or the item itself (pen or finger).
        if (selItem != null) {
            val h = itemHit(e.x, e.y)
            if (h != IDrag.NONE) { beginItemDrag(h, e); return true }
            deselectItem()
        }
        // With the selection tool the pen picks items up directly.
        if (pen && tool == Tool.LASSO) {
            val it = itemAt(e.x, e.y) ?: return false
            selItem = it; beginItemDrag(IDrag.MOVE, e); return true
        }
        return false
    }

    private fun beginItemDrag(h: IDrag, e: MotionEvent) {
        val it = selItem ?: return
        iDrag = h; iPointer = e.getPointerId(0); iBefore = snapshotItems(); iStart = it.copy(); iChanged = false
        val w = toWorld(e.x, e.y); iStartX = w[0]; iStartY = w[1]
        notifyItem(); layer.invalidate()
    }

    private fun itemMove(sx: Float, sy: Float) {
        val it = selItem ?: return; val st = iStart ?: return
        val w = toWorld(sx, sy)
        when (iDrag) {
            IDrag.MOVE -> { it.cx = st.cx + w[0] - iStartX; it.cy = st.cy + w[1] - iStartY }
            IDrag.ROTATE -> {
                var a = kotlin.math.atan2(w[1] - st.cy, w[0] - st.cx) - kotlin.math.atan2(iStartY - st.cy, iStartX - st.cx)
                var r = st.rot + a
                val step = (Math.PI / 12).toFloat(); val k = kotlin.math.round(r / step)
                if (kotlin.math.abs(r - k * step) < 0.05f) r = k * step                 // snap to 15°, so 0° is easy to hit
                it.rot = r
            }
            IDrag.CORNER -> {
                val l = st.local(w[0], w[1])
                if (it.type == ItemType.NOTE) {   // notes change width and height freely
                    it.w = (2 * kotlin.math.abs(l[0])).coerceAtLeast(60f); it.h = (2 * kotlin.math.abs(l[1])).coerceAtLeast(60f)
                } else {                          // pictures and stickers keep their shape
                    val k = (kotlin.math.hypot(l[0], l[1]) / kotlin.math.hypot(st.w / 2, st.h / 2)).coerceAtLeast(0.1f)
                    it.w = st.w * k; it.h = st.h * k
                }
            }
            IDrag.NONE -> return
        }
        iChanged = true
        layer.invalidate()
    }

    private fun endItemDrag() {
        if (iDrag == IDrag.NONE) return
        iDrag = IDrag.NONE
        val before = iBefore
        if (iChanged && before != null) { selItem?.let { growPagesForRect(it.bounds()) }; pushItems(before) } else notifyItem()
        iBefore = null; iStart = null; iPointer = -1
        layer.invalidate()
    }

    /** A finger tap on an item selects it. */
    private fun selectItemAt(sx: Float, sy: Float): Boolean {
        val it = itemAt(sx, sy) ?: return false
        exitEdit(); clearSelection(); selItem = it; notifyItem(); layer.invalidate(); return true
    }

    fun itemsJson(): String = org.json.JSONArray().apply { for (it in items) put(it.toJson()) }.toString()

    fun restoreItems(json: String?) {
        items.clear(); selItem = null
        if (json.isNullOrEmpty()) return
        runCatching { val a = org.json.JSONArray(json); for (k in 0 until a.length()) PageItem.fromJson(a.getJSONObject(k))?.let { items.add(it) } }
        for (it in items) growPagesForRect(it.bounds())
        layer.invalidate()
    }

    // ---------------- shapes tool: place a perfect shape directly ----------------
    /** Brush of the regular pen with its current settings (used for shapes placed from the shapes tool). */
    private fun penBrush(): Brush {
        val type = if (penType == PenType.PRESSURE && !usePressure) PenType.MARKER else penType
        val color = if (penEffect == PenEffect.RAINBOW) 0xFFE53935.toInt() else penColor
        return Brush.createWithColorIntArgb(Families.of(Families.penCode(type, penEffect, 0)), color, penSize, 0.05f)
    }

    /** Adds a ready-made shape (from the shapes tool) and shows its handles. */
    fun addShape(sh: Shape) {
        exitEdit(); clearSelection(); deselectItem()
        val st = strokeOf(sh, if (tool == Tool.HIGHLIGHTER) brush() else penBrush(), 0.6f) ?: return
        strokes.add(st); shapes[st] = sh.copy(); growPagesFor(listOf(st)); push(Act.Add(st))
        editStroke = st; editShape = sh; layer.invalidate()
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
                val ax = selBounds.left; val ay = selBounds.top; val k = selK; val dx = selDx; val dy = selDy
                replaceSelection({ st -> transformed(st, ax, ay, k, dx, dy) }) { sh -> sh.transform(ax, ay, k, dx, dy) }
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
    private fun replaceSelection(f: (Stroke) -> Stroke, g: (Shape) -> Unit = {}) {
        val removed = mutableListOf<Pair<Stroke, Int>>(); val added = mutableListOf<Stroke>()
        for (st in selection) { val k = strokes.indexOf(st); if (k >= 0) removed += st to k }
        removed.sortBy { it.second }
        for (r in removed.asReversed()) strokes.removeAt(r.second)
        for (r in removed) {
            val n = f(r.first); added += n
            shapes[r.first]?.let { sh -> shapes[n] = sh.copy().also(g) }   // shapes stay editable after a move / recolour
        }
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
        val copies = selection.map { st ->
            transformed(st, 0f, 0f, 1f, off, off).also { n -> shapes[st]?.let { shapes[n] = it.copy().apply { translate(off, off) } } }
        }
        strokes.addAll(copies); growPagesFor(copies)
        push(Act.Edit(emptyList(), copies))
        selection.clear(); selection.addAll(copies); updateSelBounds(); layer.invalidate(); notifySelection()
    }

    /** Recolours the selection; highlighter strokes keep their transparency. */
    fun recolorSelection(color: Int) = replaceSelection({ st ->
        val c = if (st.brush.family == Families.highlighter) (HL_ALPHA shl 24) or (color and 0xFFFFFF) else color
        transformed(st, 0f, 0f, 1f, 0f, 0f, c)
    })

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

        // ---- shape editing overlay (drawn in screen pixels so handles keep their size at any zoom) ----
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFE0338C.toInt(); strokeWidth = 1.3f * d
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = 1.3f * d }
        private val axisDash = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        private val hFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val hEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = 2f * d }
        private val hActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val rotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFCBD2DC.toInt(); strokeWidth = 1.2f * d }
        private val rotIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = ICON; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1F26.toInt() }
        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; textAlign = Paint.Align.CENTER }
        private val tmpPath = android.graphics.Path()

        private fun drawShapeUi(c: Canvas) {
            // Smart guides and the magnet ring.
            var i = 0
            val g = FloatArray(4)
            while (i + 3 < guides.size) {
                g[0] = guides[i]; g[1] = guides[i + 1]; g[2] = guides[i + 2]; g[3] = guides[i + 3]; m.mapPoints(g)
                c.drawLine(g[0], g[1], g[2], g[3], guidePaint); i += 4
            }
            magnet?.let { val p = it.copyOf(); m.mapPoints(p); c.drawCircle(p[0], p[1], 10 * d, hEdge) }
            // Selected picture / sticker / note: frame, corner handles, rotate handle.
            selItem?.let { item ->
                val k = item.corners(); m.mapPoints(k)
                tmpPath.reset(); tmpPath.moveTo(k[0], k[1]); tmpPath.lineTo(k[2], k[3]); tmpPath.lineTo(k[4], k[5]); tmpPath.lineTo(k[6], k[7]); tmpPath.close()
                axisPaint.pathEffect = axisDash; c.drawPath(tmpPath, axisPaint)
                for (q in 0 until 4) { c.drawCircle(k[2 * q], k[2 * q + 1], 7 * d, hFill); c.drawCircle(k[2 * q], k[2 * q + 1], 7 * d, hEdge) }
                val r = rotHandleOf(item)
                c.drawCircle(r[0], r[1], 14 * d, hFill); c.drawCircle(r[0], r[1], 14 * d, rotEdge)
                val rr = 6 * d
                tmpPath.reset(); tmpPath.addArc(r[0] - rr, r[1] - rr, r[0] + rr, r[1] + rr, -60f, 290f); c.drawPath(tmpPath, rotIcon)
            }
            val sh = live ?: editShape ?: return
            // Axes / diagonals: dashed, solid once the shape is perfect (circle, square).
            val ind = screen(sh.indicators())
            axisPaint.pathEffect = if (sh.perfect) null else axisDash
            i = 0
            while (i + 3 < ind.size) { c.drawLine(ind[i], ind[i + 1], ind[i + 2], ind[i + 3], axisPaint); i += 4 }
            val hs = screen(sh.handles())
            for (k in 0 until hs.size / 2) {
                val r = if (sh is RectShape && k >= 4) 5.5f * d else 7f * d
                if (k == dragH) c.drawCircle(hs[2 * k], hs[2 * k + 1], r + 1.5f * d, hActive)
                else { c.drawCircle(hs[2 * k], hs[2 * k + 1], r, hFill); c.drawCircle(hs[2 * k], hs[2 * k + 1], r, hEdge) }
            }
            if (live == null && sh.rotatable) {   // rotate handle under the shape
                val r = rotHandle(sh)
                c.drawCircle(r[0], r[1], 14 * d, hFill); c.drawCircle(r[0], r[1], 14 * d, rotEdge)
                val rr = 6 * d
                tmpPath.reset(); tmpPath.addArc(r[0] - rr, r[1] - rr, r[0] + rr, r[1] + rr, -60f, 290f)
                c.drawPath(tmpPath, rotIcon)
                val ax = r[0] + rr * kotlin.math.cos(Math.toRadians(-60.0)).toFloat(); val ay = r[1] + rr * kotlin.math.sin(Math.toRadians(-60.0)).toFloat()
                c.drawLine(ax, ay, ax - 3.5f * d, ay - 0.5f * d, rotIcon); c.drawLine(ax, ay, ax + 0.5f * d, ay - 3.8f * d, rotIcon)
            }
            // Name of the recognised shape, above it.
            val b = RectF(sh.bounds()); m.mapRect(b)
            val label = sh.name
            val tw = pillText.measureText(label) + 20 * d; val th = 26 * d
            var top = b.top - th - 14 * d
            if (top < topInset) top = b.bottom + 14 * d + if (live == null && sh.rotatable) 44 * d else 0f
            val lx = b.centerX()
            c.drawRoundRect(lx - tw / 2, top, lx + tw / 2, top + th, th / 2, th / 2, pillBg)
            c.drawText(label, lx, top + th / 2 + 4.5f * d, pillText)
        }

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
            for (it in items) { val b = it.bounds(); if (b.bottom >= visTop && b.top <= visBottom) itemPainter.draw(c, it) }   // under the ink, so it can be written on
            val moving = selection.isNotEmpty() && (selDx != 0f || selDy != 0f || selK != 1f)
            for (st in strokes) {
                val b = boundsOf(st)
                if (b.bottom < visTop || b.top > visBottom || b.right < visLeft || b.left > visRight) continue
                if (moving && st in selection) continue
                renderer.draw(c, st, m)
            }
            preview?.let { renderer.draw(c, it, m) }   // the snapped shape while the pen is still down
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
            drawShapeUi(c)
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
