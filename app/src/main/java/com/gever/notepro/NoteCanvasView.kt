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

    enum class Tool { PEN, HIGHLIGHTER, ERASER }
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
    /** Screen pixels kept free at the top for the floating toolbar when scrolled to the start. */
    var topInset = 0f
        set(v) { field = v; if (width > 0) clampAndApply() }
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
    private fun docHeight() = GAP + pages * (PAGE_H + GAP)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        val worldTop = if (oldw == 0) 0f else (0f - ty) / s
        val pageScreenW = min(w - 2 * sideInset, 980 * d)
        fitScale = pageScreenW / PAGE_W
        s = fitScale
        tx = (w - PAGE_W * s) / 2f
        ty = -worldTop * s
        clampAndApply()
    }

    private fun clampAndApply() {
        val w = width.toFloat(); val h = height.toFloat()
        val docW = PAGE_W * s; val docH = docHeight() * s; val pad = 16 * d
        tx = if (docW <= w) (w - docW) / 2f else tx.coerceIn(w - docW - pad, pad)
        val avail = h - topInset
        ty = if (docH <= avail) topInset else ty.coerceIn(h - docH, topInset)
        m.setScale(s, s); m.postTranslate(tx, ty); m.invert(inv)
        layer.invalidate()
        reportPage()
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
        else -> Brush.createWithColorIntArgb(
            if (usePressure) Families.pen else Families.marker, penColor, penSize, 0.05f)
    }

    private fun onInput(e: MotionEvent): Boolean {
        val action = e.actionMasked
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
            gesturing = true
            scroller.forceFinished(true)
        }

        if (penPointer != -1) { penEvent(e); return true }

        if (gesturing) {
            scaleDetector.onTouchEvent(e)
            gestureDetector.onTouchEvent(e)
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesturing = false
        }
        return true
    }

    private fun startPen(e: MotionEvent, idx: Int, type: Int) {
        penPointer = e.getPointerId(idx)
        ink.requestUnbufferedDispatch(e)
        diagTool = toolName(type)
        strokeStart = e.eventTime; samples = 1
        val button = type == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        erasing = tool == Tool.ERASER || button
        eraseFromTool = tool == Tool.ERASER && !button
        if (erasing) { erasedNow.clear(); addedNow.clear(); eraseAt(e.getX(idx), e.getY(idx)) }
        else { predictor.record(e); currentId = ink.startStroke(e, penPointer, brush(), Matrix(inv), identity) }
    }

    private fun penEvent(e: MotionEvent) {
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
            val minY = if (docH <= h) 0 else h - docH
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, 0)
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

    private fun commit(st: Stroke) {
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
        ty = topInset - (pageTop(i) - GAP / 2) * s
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

    // ================= drawing =================
    private inner class PaperLayer(ctx: Context) : View(ctx) {
        val renderer = CanvasStrokeRenderer.create()
        private val pagePaint = Paint()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000 }
        private val rulePaint = Paint().apply { color = 0xFFD5E1F2.toInt(); strokeWidth = 1.4f }
        private val gridPaint = Paint().apply { color = 0xFFE1E6EE.toInt(); strokeWidth = 1.2f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C2D0.toInt() }
        private val page = RectF()

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
            for (st in strokes) {
                val b = boundsOf(st)
                if (b.bottom < visTop || b.top > visBottom || b.right < visLeft || b.left > visRight) continue
                renderer.draw(c, st, m)
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
