package com.gever.notepro

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = (2 * PI).toFloat()
private const val HALF_PI = (PI / 2).toFloat()
private fun deg(d: Float) = d * PI.toFloat() / 180f

/** Angle a moved onto the nearest multiple of [step] (+ [offset]) when it is within [tol]. */
private fun snapAngle(a: Float, step: Float, tol: Float, offset: Float = 0f): Float {
    val k = ((a - offset) / step).roundToInt()
    val target = offset + k * step
    return if (abs(a - target) <= tol) target else a
}

/** Points along a polyline / polygon, about [step] apart; closed outlines end on their first point. */
private fun densify(v: FloatArray, closed: Boolean, step: Float): FloatArray {
    val n = v.size / 2
    val out = ArrayList<Float>(n * 8)
    val segs = if (closed) n else n - 1
    for (i in 0 until segs) {
        val ax = v[2 * i]; val ay = v[2 * i + 1]
        val j = (i + 1) % n
        val bx = v[2 * j]; val by = v[2 * j + 1]
        val len = hypot(bx - ax, by - ay)
        val parts = max(1, (len / step).toInt())
        for (k in 0 until parts) { val f = k / parts.toFloat(); out += ax + (bx - ax) * f; out += ay + (by - ay) * f }
    }
    if (closed) { out += v[0]; out += v[1]; if (n > 1) { out += v[0] + (v[2] - v[0]) * 0.02f; out += v[1] + (v[3] - v[1]) * 0.02f } }
    else { out += v[2 * (n - 1)]; out += v[2 * (n - 1) + 1] }
    return out.toFloatArray()
}

private fun rotatePts(p: FloatArray, a: Float, cx: Float, cy: Float) {
    val c = cos(a); val s = sin(a)
    var i = 0
    while (i < p.size) {
        val x = p[i] - cx; val y = p[i + 1] - cy
        p[i] = cx + x * c - y * s; p[i + 1] = cy + x * s + y * c
        i += 2
    }
}

private fun centroid(p: FloatArray): FloatArray {
    var x = 0f; var y = 0f; val n = p.size / 2
    for (i in 0 until n) { x += p[2 * i]; y += p[2 * i + 1] }
    return floatArrayOf(x / n, y / n)
}

/**
 * Geometry of a snapped shape, kept beside its ink stroke so it can still be edited later.
 * All coordinates are page units.
 */
sealed class Shape {
    abstract val name: String
    abstract val closed: Boolean
    /** The special variant (circle, square, regular polygon): guide lines are drawn solid. */
    open val perfect: Boolean get() = false
    open val rotatable: Boolean get() = true
    abstract fun copy(): Shape
    /** Dense outline used to build the ink stroke. */
    abstract fun outline(step: Float): FloatArray
    /** Edit handles, x,y pairs. */
    abstract fun handles(): FloatArray
    abstract fun moveHandle(i: Int, x: Float, y: Float)
    /** Scale by k around (ax, ay), then move by (dx, dy). */
    abstract fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float)
    abstract fun rotate(a: Float, cx: Float, cy: Float)
    abstract fun center(): FloatArray
    abstract fun toJson(): JSONObject
    fun translate(dx: Float, dy: Float) = transform(0f, 0f, 1f, dx, dy)
    /** Points other things snap to. */
    open fun keyPoints(): FloatArray = handles() + center()
    /** Guide lines shown while editing (x1,y1,x2,y2 groups): ellipse axes, rectangle diagonals. */
    open fun indicators(): FloatArray = FloatArray(0)
    /** Straight edges, for "parallel to" guides (x1,y1,x2,y2 groups). */
    open fun edges(): FloatArray = FloatArray(0)

    fun bounds(): RectF {
        val o = outline(10f); val r = RectF(o[0], o[1], o[0], o[1])
        var i = 0
        while (i < o.size) { r.union(o[i], o[i + 1]); i += 2 }
        return r
    }

    companion object {
        fun fromJson(o: JSONObject): Shape? = runCatching {
            val a = o.getJSONArray("p"); val p = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            when (o.getString("t")) {
                "line" -> LineShape(p[0], p[1], p[2], p[3])
                "ellipse" -> EllipseShape(p[0], p[1], p[2], p[3], p[4])
                "rect" -> RectShape(p[0], p[1], p[2], p[3], p[4])
                "regular" -> RegularShape(o.getInt("n"), p[0], p[1], p[2], p[3])
                "poly" -> PolyShape(p, o.getBoolean("c"), o.optString("name", ""))
                else -> null
            }
        }.getOrNull()

        internal fun json(t: String, p: FloatArray) = JSONObject().put("t", t).put("p", JSONArray().apply { for (v in p) put(v.toDouble()) })
    }
}

class LineShape(var x1: Float, var y1: Float, var x2: Float, var y2: Float) : Shape() {
    override val name get() = when { abs(y1 - y2) < 0.01f -> "קו אופקי"; abs(x1 - x2) < 0.01f -> "קו אנכי"; else -> "קו ישר" }
    override val closed get() = false
    override val rotatable get() = false
    override fun copy() = LineShape(x1, y1, x2, y2)
    override fun outline(step: Float) = densify(floatArrayOf(x1, y1, x2, y2), false, step)
    override fun handles() = floatArrayOf(x1, y1, x2, y2)
    override fun moveHandle(i: Int, x: Float, y: Float) { if (i == 0) { x1 = x; y1 = y } else { x2 = x; y2 = y } }
    override fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float) {
        x1 = ax + (x1 - ax) * k + dx; y1 = ay + (y1 - ay) * k + dy; x2 = ax + (x2 - ax) * k + dx; y2 = ay + (y2 - ay) * k + dy
    }
    override fun rotate(a: Float, cx: Float, cy: Float) { val p = floatArrayOf(x1, y1, x2, y2); rotatePts(p, a, cx, cy); x1 = p[0]; y1 = p[1]; x2 = p[2]; y2 = p[3] }
    override fun center() = floatArrayOf((x1 + x2) / 2, (y1 + y2) / 2)
    override fun edges() = floatArrayOf(x1, y1, x2, y2)
    override fun toJson() = json("line", floatArrayOf(x1, y1, x2, y2))
}

class EllipseShape(var cx: Float, var cy: Float, var rx: Float, var ry: Float, var rot: Float) : Shape() {
    override val name get() = if (perfect) "עיגול" else "אליפסה"
    override val closed get() = true
    override val perfect get() = abs(rx - ry) < 0.01f * max(rx, ry) + 0.01f
    override fun copy() = EllipseShape(cx, cy, rx, ry, rot)
    override fun outline(step: Float): FloatArray {
        val per = (PI * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))).toFloat()
        val n = (per / step).toInt().coerceIn(48, 400)
        val c = cos(rot); val s = sin(rot)
        val out = FloatArray((n + 2) * 2)
        for (i in 0..n + 1) {
            val t = TAU * i / n
            val x = cos(t) * rx; val y = sin(t) * ry
            out[2 * i] = cx + x * c - y * s; out[2 * i + 1] = cy + x * s + y * c
        }
        return out
    }
    override fun handles(): FloatArray {
        val c = cos(rot); val s = sin(rot)
        return floatArrayOf(cx + rx * c, cy + rx * s, cx - rx * c, cy - rx * s, cx - ry * s, cy + ry * c, cx + ry * s, cy - ry * c)
    }
    override fun moveHandle(i: Int, x: Float, y: Float) {
        var vx = x - cx; var vy = y - cy
        if (i == 1 || i == 3) { vx = -vx; vy = -vy }
        val len = hypot(vx, vy).coerceAtLeast(3f)
        if (i < 2) { rx = len; rot = atan2(vy, vx) } else { ry = len; rot = atan2(vy, vx) - HALF_PI }
        // Close to a circle: become exactly one (the guide lines turn solid).
        if (abs(rx - ry) < 0.1f * max(rx, ry)) { if (i < 2) rx = ry else ry = rx }
        rot = snapAngle(rot, HALF_PI, deg(6f))
    }
    override fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float) { cx = ax + (cx - ax) * k + dx; cy = ay + (cy - ay) * k + dy; rx *= k; ry *= k }
    override fun rotate(a: Float, cx: Float, cy: Float) { val p = floatArrayOf(this.cx, this.cy); rotatePts(p, a, cx, cy); this.cx = p[0]; this.cy = p[1]; rot += a }
    override fun center() = floatArrayOf(cx, cy)
    override fun indicators(): FloatArray { val h = handles(); return floatArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]) }
    override fun toJson() = json("ellipse", floatArrayOf(cx, cy, rx, ry, rot))
}

class RectShape(var cx: Float, var cy: Float, var w: Float, var h: Float, var rot: Float) : Shape() {
    override val name get() = if (perfect) "ריבוע" else "מלבן"
    override val closed get() = true
    override val perfect get() = abs(w - h) < 0.01f * max(w, h) + 0.01f
    override fun copy() = RectShape(cx, cy, w, h, rot)
    private fun pt(lx: Float, ly: Float, out: FloatArray, i: Int) {
        val c = cos(rot); val s = sin(rot); out[2 * i] = cx + lx * c - ly * s; out[2 * i + 1] = cy + lx * s + ly * c
    }
    fun corners() = FloatArray(8).also { pt(-w / 2, -h / 2, it, 0); pt(w / 2, -h / 2, it, 1); pt(w / 2, h / 2, it, 2); pt(-w / 2, h / 2, it, 3) }
    override fun outline(step: Float) = densify(corners(), true, step)
    override fun handles() = FloatArray(16).also {
        pt(-w / 2, -h / 2, it, 0); pt(w / 2, -h / 2, it, 1); pt(w / 2, h / 2, it, 2); pt(-w / 2, h / 2, it, 3)
        pt(0f, -h / 2, it, 4); pt(w / 2, 0f, it, 5); pt(0f, h / 2, it, 6); pt(-w / 2, 0f, it, 7)
    }
    override fun moveHandle(i: Int, x: Float, y: Float) {
        val ux = cos(rot); val uy = sin(rot)        // local x axis
        val vx = -sin(rot); val vy = cos(rot)       // local y axis
        if (i < 4) {
            val c = corners(); val o = (i + 2) % 4
            val ox = c[2 * o]; val oy = c[2 * o + 1]
            var a = (x - ox) * ux + (y - oy) * uy
            var b = (x - ox) * vx + (y - oy) * vy
            if (abs(abs(a) - abs(b)) < 0.1f * max(abs(a), abs(b))) { val m = (abs(a) + abs(b)) / 2; a = if (a < 0) -m else m; b = if (b < 0) -m else m }
            if (abs(a) < 3f) a = if (a < 0) -3f else 3f
            if (abs(b) < 3f) b = if (b < 0) -3f else 3f
            w = abs(a); h = abs(b)
            cx = ox + ux * a / 2 + vx * b / 2; cy = oy + uy * a / 2 + vy * b / 2
        } else {
            when (i - 4) {
                0, 2 -> {   // top / bottom edge: change the height
                    val sign = if (i == 4) 1f else -1f
                    val ox = cx + vx * h / 2 * sign; val oy = cy + vy * h / 2 * sign
                    var b = (x - ox) * vx + (y - oy) * vy
                    if (abs(abs(b) - w) < 0.1f * max(abs(b), w)) b = if (b < 0) -w else w
                    if (abs(b) < 3f) b = if (b < 0) -3f else 3f
                    h = abs(b); cx = ox + vx * b / 2; cy = oy + vy * b / 2
                }
                else -> {   // right / left edge: change the width
                    val sign = if (i == 5) -1f else 1f
                    val ox = cx + ux * w / 2 * sign; val oy = cy + uy * w / 2 * sign
                    var a = (x - ox) * ux + (y - oy) * uy
                    if (abs(abs(a) - h) < 0.1f * max(abs(a), h)) a = if (a < 0) -h else h
                    if (abs(a) < 3f) a = if (a < 0) -3f else 3f
                    w = abs(a); cx = ox + ux * a / 2; cy = oy + uy * a / 2
                }
            }
        }
    }
    override fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float) { cx = ax + (cx - ax) * k + dx; cy = ay + (cy - ay) * k + dy; w *= k; h *= k }
    override fun rotate(a: Float, cx: Float, cy: Float) { val p = floatArrayOf(this.cx, this.cy); rotatePts(p, a, cx, cy); this.cx = p[0]; this.cy = p[1]; rot += a }
    override fun center() = floatArrayOf(cx, cy)
    override fun indicators(): FloatArray { val c = corners(); return floatArrayOf(c[0], c[1], c[4], c[5], c[2], c[3], c[6], c[7]) }
    override fun edges(): FloatArray { val c = corners(); return floatArrayOf(c[0], c[1], c[2], c[3], c[2], c[3], c[4], c[5], c[4], c[5], c[6], c[7], c[6], c[7], c[0], c[1]) }
    override fun toJson() = json("rect", floatArrayOf(cx, cy, w, h, rot))
}

class RegularShape(val n: Int, var cx: Float, var cy: Float, var r: Float, var rot: Float) : Shape() {
    override val name get() = when (n) { 3 -> "משולש שווה צלעות"; 5 -> "מחומש משוכלל"; 6 -> "משושה משוכלל"; 7 -> "משובע משוכלל"; 8 -> "מתומן משוכלל"; else -> "מצולע משוכלל" }
    override val closed get() = true
    override val perfect get() = true
    override fun copy() = RegularShape(n, cx, cy, r, rot)
    fun vertices() = FloatArray(2 * n) { k -> val i = k / 2; val a = rot + TAU * i / n; if (k % 2 == 0) cx + r * cos(a) else cy + r * sin(a) }
    override fun outline(step: Float) = densify(vertices(), true, step)
    override fun handles() = vertices()
    override fun moveHandle(i: Int, x: Float, y: Float) {
        r = hypot(x - cx, y - cy).coerceAtLeast(3f)
        val a = atan2(y - cy, x - cx) - TAU * i / n
        // Snap to "pointing up" or "flat on top" orientations.
        val step = TAU / n
        val up = snapAngle(a, step, deg(4f), -HALF_PI)
        rot = if (up != a) up else snapAngle(a, step, deg(4f), -HALF_PI + step / 2)
    }
    override fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float) { cx = ax + (cx - ax) * k + dx; cy = ay + (cy - ay) * k + dy; r *= k }
    override fun rotate(a: Float, cx: Float, cy: Float) { val p = floatArrayOf(this.cx, this.cy); rotatePts(p, a, cx, cy); this.cx = p[0]; this.cy = p[1]; rot += a }
    override fun center() = floatArrayOf(cx, cy)
    override fun edges(): FloatArray { val v = vertices(); return FloatArray(4 * n) { k -> val e = k / 4; val j = if (k % 4 < 2) e else (e + 1) % n; v[2 * j + k % 2] } }
    override fun toJson() = json("regular", floatArrayOf(cx, cy, r, rot)).put("n", n)
}

/** Any polygon or polyline; vertices move freely. */
class PolyShape(var pts: FloatArray, override val closed: Boolean, private var label: String) : Shape() {
    override val name get() = label.ifEmpty { generic() }
    private fun generic() = if (!closed) "קו שבור" else when (pts.size / 2) { 3 -> "משולש"; 4 -> "מרובע"; else -> "מצולע" }
    override fun copy() = PolyShape(pts.copyOf(), closed, label)
    override fun outline(step: Float) = densify(pts, closed, step)
    override fun handles() = pts.copyOf()
    override fun moveHandle(i: Int, x: Float, y: Float) { pts[2 * i] = x; pts[2 * i + 1] = y; label = "" }
    override fun transform(ax: Float, ay: Float, k: Float, dx: Float, dy: Float) {
        var i = 0
        while (i < pts.size) { pts[i] = ax + (pts[i] - ax) * k + dx; pts[i + 1] = ay + (pts[i + 1] - ay) * k + dy; i += 2 }
    }
    override fun rotate(a: Float, cx: Float, cy: Float) = rotatePts(pts, a, cx, cy)
    override fun center() = centroid(pts)
    override fun edges(): FloatArray {
        val n = pts.size / 2; val segs = if (closed) n else n - 1
        return FloatArray(4 * segs) { k -> val e = k / 4; val j = if (k % 4 < 2) e else (e + 1) % n; pts[2 * j + k % 2] }
    }
    override fun toJson() = json("poly", pts).put("c", closed).put("name", label)
}

// ======================================================================================
// Recognition: a hand-drawn stroke -> the shape it most likely is, preferring the "perfect"
// variant (circle, square, equilateral / isosceles / right triangle, regular polygon ...) when close.
// ======================================================================================

object ShapeRecognizer {
    /** [raw]: x,y pairs in page units. [u]: page units per dp at the current zoom. */
    fun recognize(raw: FloatArray, u: Float): Shape? {
        if (raw.size < 12) return null
        var minX = raw[0]; var maxX = raw[0]; var minY = raw[1]; var maxY = raw[1]
        var i = 0
        while (i < raw.size) { minX = min(minX, raw[i]); maxX = max(maxX, raw[i]); minY = min(minY, raw[i + 1]); maxY = max(maxY, raw[i + 1]); i += 2 }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 18 * u) return null
        val len = pathLength(raw)
        val p = resample(raw, 96)
        val gap = hypot(p[0] - p[p.size - 2], p[1] - p[p.size - 1])
        return if (gap < max(0.2f * len, 10 * u) && len > 1.8f * diag) closedShape(p, len, diag, u) else openShape(p, len, diag, u)
    }

    // ---------------- open strokes: line or polyline ----------------
    private fun openShape(p: FloatArray, len: Float, diag: Float, u: Float): Shape? {
        val n = p.size / 2
        val x0 = p[0]; val y0 = p[1]; val x1 = p[2 * n - 2]; val y1 = p[2 * n - 1]
        val chord = hypot(x1 - x0, y1 - y0)
        if (chord < 16 * u) return null
        var maxDev = 0f
        for (k in 0 until n) maxDev = max(maxDev, distToLine(p[2 * k], p[2 * k + 1], x0, y0, x1, y1))
        if (maxDev <= max(0.06f * chord, 5 * u)) return snapLine(LineShape(x0, y0, x1, y1))
        val idx = mutableListOf(0); dp(p, 0, n - 1, max(0.045f * len, 4 * u), idx); idx += n - 1
        var v = idx.sorted().flatMap { listOf(p[2 * it], p[2 * it + 1]) }.toFloatArray()
        v = mergeStraight(v, false, 160f)
        val segs = v.size / 2 - 1
        if (segs !in 2..8) return null
        for (k in 0 until segs) if (hypot(v[2 * k + 2] - v[2 * k], v[2 * k + 3] - v[2 * k + 1]) < 12 * u) return null
        if (meanDistToPoly(p, v, false) / diag > 0.025f) return null
        if (!segmentsStraight(p, v, false, 0.06f, u)) return null      // an arc or a curve stays freehand
        return PolyShape(v, false, "קו שבור")
    }

    /**
     * Every edge of the fitted polyline / polygon must really be straight: the drawn points next to it may
     * bulge at most [tol] of that edge's length. This keeps arcs and curves from becoming broken lines.
     */
    private fun segmentsStraight(p: FloatArray, v: FloatArray, closed: Boolean, tol: Float, u: Float): Boolean {
        val n = v.size / 2; val segs = if (closed) n else n - 1
        val worst = FloatArray(segs)
        for (k in 0 until p.size / 2) {
            var best = Float.MAX_VALUE; var bi = 0
            for (e in 0 until segs) {
                val j = (e + 1) % n
                val dd = distToSeg(p[2 * k], p[2 * k + 1], v[2 * e], v[2 * e + 1], v[2 * j], v[2 * j + 1])
                if (dd < best) { best = dd; bi = e }
            }
            worst[bi] = max(worst[bi], best)
        }
        for (e in 0 until segs) {
            val j = (e + 1) % n
            val l = hypot(v[2 * j] - v[2 * e], v[2 * j + 1] - v[2 * e + 1])
            if (worst[e] > max(tol * l, 3 * u)) return false
        }
        return true
    }

    /** Horizontal / vertical within 6°, diagonal within 3°. */
    fun snapLine(l: LineShape): LineShape {
        val a = atan2(l.y2 - l.y1, l.x2 - l.x1); val len = hypot(l.x2 - l.x1, l.y2 - l.y1)
        var s = snapAngle(a, HALF_PI, deg(6f))
        if (s == a) s = snapAngle(a, HALF_PI, deg(3f), HALF_PI / 2)
        if (s != a) { l.x2 = l.x1 + len * cos(s); l.y2 = l.y1 + len * sin(s) }
        return l
    }

    // ---------------- closed strokes: ellipse or polygon ----------------
    private fun closedShape(p0: FloatArray, len: Float, diag: Float, u: Float): Shape? {
        val p = p0.copyOf(p0.size - 2)       // drop the duplicated end point
        val n = p.size / 2
        // Ellipse fit: principal axes, then the extents along them.
        val c = centroid(p)
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (k in 0 until n) { val dx = p[2 * k] - c[0]; val dy = p[2 * k + 1] - c[1]; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val th = 0.5f * atan2(2 * sxy, sxx - syy)
        val ct = cos(th); val st = sin(th)
        var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE; var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE
        for (k in 0 until n) {
            val dx = p[2 * k] - c[0]; val dy = p[2 * k + 1] - c[1]
            val uu = dx * ct + dy * st; val vv = -dx * st + dy * ct
            umin = min(umin, uu); umax = max(umax, uu); vmin = min(vmin, vv); vmax = max(vmax, vv)
        }
        val rx = (umax - umin) / 2; val ry = (vmax - vmin) / 2
        if (min(rx, ry) < 0.12f * max(rx, ry)) return null          // a flat loop: not a shape
        val cu = (umax + umin) / 2; val cv = (vmax + vmin) / 2
        var ellErr = 0f
        for (k in 0 until n) {
            val dx = p[2 * k] - c[0]; val dy = p[2 * k + 1] - c[1]
            val uu = dx * ct + dy * st - cu; val vv = -dx * st + dy * ct - cv
            ellErr += abs(sqrt(uu * uu / (rx * rx) + vv * vv / (ry * ry)) - 1f)
        }
        ellErr = ellErr / n * sqrt(rx * ry) / diag

        val poly = closedPolygon(p, max(0.035f * len, 4 * u))
        val k = poly.size / 2
        val polyErr = if (k >= 3) meanDistToPoly(p, poly, true) / diag else Float.MAX_VALUE
        val polyOk = k in 3..12 && polyErr < 0.03f && segmentsStraight(p, poly, true, 0.09f, u)

        return when {
            polyOk && polyErr <= ellErr -> polygon(poly)
            ellErr < 0.05f -> ellipse(c[0] + cu * ct - cv * st, c[1] + cu * st + cv * ct, rx, ry, th)
            polyOk -> polygon(poly)
            else -> null
        }
    }

    private fun ellipse(cx: Float, cy: Float, rx0: Float, ry0: Float, th: Float): Shape {
        var rx = rx0; var ry = ry0
        // A circle gets level handles (rotation means nothing for it).
        if (min(rx, ry) / max(rx, ry) > 0.86f) { val r = (rx + ry) / 2; return EllipseShape(cx, cy, r, r, 0f) }
        return EllipseShape(cx, cy, rx, ry, snapAngle(th, HALF_PI, deg(10f)))
    }

    private fun polygon(v0: FloatArray): Shape {
        val v = v0.copyOf(); val k = v.size / 2
        val sides = FloatArray(k) { i -> val j = (i + 1) % k; hypot(v[2 * j] - v[2 * i], v[2 * j + 1] - v[2 * i + 1]) }
        val mean = sides.average().toFloat()
        val angles = FloatArray(k) { interiorAngle(v, it, k) }
        val evenSides = sides.all { abs(it - mean) / mean < 0.16f }
        val regularAngle = (k - 2) * 180f / k
        when (k) {
            3 -> {
                if (sides.all { abs(it - mean) / mean < 0.12f }) return regular(v, 3)
                isosceles(v, sides)?.let { return it }
                rightTriangle(v, angles)?.let { return it }
                return PolyShape(v, true, "משולש")
            }
            4 -> {
                if (angles.all { abs(it - 90f) < 14f }) return rectangle(v)
                quad(v, sides)?.let { return it }
                return PolyShape(v, true, "מרובע")
            }
            in 5..8 -> if (evenSides && angles.all { abs(it - regularAngle) < 16f }) return regular(v, k)
        }
        return PolyShape(v, true, "מצולע")
    }

    private fun regular(v: FloatArray, n: Int): Shape {
        val c = centroid(v)
        var r = 0f; for (i in 0 until n) r += hypot(v[2 * i] - c[0], v[2 * i + 1] - c[1]); r /= n
        val a = FloatArray(n) { atan2(v[2 * it + 1] - c[1], v[2 * it] - c[0]) }
        // Drawing direction decides whether the vertex angles go up or down by 2π/n.
        var d = a[1] - a[0]; while (d > PI) d -= TAU; while (d < -PI) d += TAU
        val sgn = if (d >= 0) 1f else -1f
        var sx = 0f; var sy = 0f
        for (i in 0 until n) { val t = a[i] - sgn * TAU * i / n; sx += cos(t * n); sy += sin(t * n) }
        var rot = atan2(sy, sx) / n
        val step = TAU / n
        val up = snapAngle(rot, step, deg(10f), -HALF_PI)
        rot = if (up != rot) up else snapAngle(rot, step, deg(10f), -HALF_PI + step / 2)
        return RegularShape(n, c[0], c[1], r, rot)
    }

    private fun rectangle(v: FloatArray): Shape {
        var sx = 0f; var sy = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; val a = atan2(v[2 * j + 1] - v[2 * i + 1], v[2 * j] - v[2 * i]); sx += cos(4 * a); sy += sin(4 * a) }
        var th = atan2(sy, sx) / 4
        th = snapAngle(th, HALF_PI, deg(8f))
        val ct = cos(th); val st = sin(th)
        var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE; var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val uu = v[2 * i] * ct + v[2 * i + 1] * st; val vv = -v[2 * i] * st + v[2 * i + 1] * ct
            umin = min(umin, uu); umax = max(umax, uu); vmin = min(vmin, vv); vmax = max(vmax, vv)
        }
        var w = umax - umin; var h = vmax - vmin
        if (min(w, h) / max(w, h) > 0.87f) { val m = (w + h) / 2; w = m; h = m }
        val cu = (umax + umin) / 2; val cv = (vmax + vmin) / 2
        return RectShape(cu * ct - cv * st, cu * st + cv * ct, w, h, th)
    }

    /** Two equal sides: exact isosceles, base levelled when nearly horizontal. */
    private fun isosceles(v: FloatArray, sides: FloatArray): Shape? {
        for (b in 0 until 3) {
            val l1 = sides[(b + 1) % 3]; val l2 = sides[(b + 2) % 3]
            if (abs(l1 - l2) / ((l1 + l2) / 2) > 0.1f) continue
            val i0 = b; val i1 = (b + 1) % 3; val apex = (b + 2) % 3        // base = side b (vertex b -> b+1)
            val bx0 = v[2 * i0]; val by0 = v[2 * i0 + 1]; val bx1 = v[2 * i1]; val by1 = v[2 * i1 + 1]
            val mx = (bx0 + bx1) / 2; val my = (by0 + by1) / 2
            val half = hypot(bx1 - bx0, by1 - by0) / 2
            val leg = (l1 + l2) / 2
            if (leg <= half) return null
            var nx = -(by1 - by0); var ny = bx1 - bx0
            val nl = hypot(nx, ny); nx /= nl; ny /= nl
            if ((v[2 * apex] - mx) * nx + (v[2 * apex + 1] - my) * ny < 0) { nx = -nx; ny = -ny }
            val hgt = sqrt(leg * leg - half * half)
            val out = floatArrayOf(bx0, by0, bx1, by1, mx + nx * hgt, my + ny * hgt)
            levelEdge(out, 0, 1)
            return PolyShape(out, true, "משולש שווה שוקיים")
        }
        return null
    }

    private fun rightTriangle(v: FloatArray, angles: FloatArray): Shape? {
        val r = angles.indices.firstOrNull { abs(angles[it] - 90f) < 9f } ?: return null
        val a = (r + 1) % 3; val b = (r + 2) % 3
        val rx = v[2 * r]; val ry = v[2 * r + 1]
        val ax = v[2 * a] - rx; val ay = v[2 * a + 1] - ry
        val lb = hypot(v[2 * b] - rx, v[2 * b + 1] - ry); val la = hypot(ax, ay)
        var px = -ay / la; var py = ax / la
        if (px * (v[2 * b] - rx) + py * (v[2 * b + 1] - ry) < 0) { px = -px; py = -py }
        val out = floatArrayOf(rx, ry, v[2 * a], v[2 * a + 1], rx + px * lb, ry + py * lb)
        levelEdge(out, 0, 1)
        return PolyShape(out, true, "משולש ישר זווית")
    }

    /** Parallelogram (two parallel pairs) or trapezoid (one pair; isosceles when the legs are equal). */
    private fun quad(v: FloatArray, sides: FloatArray): Shape? {
        fun dir(i: Int): Float { val j = (i + 1) % 4; return atan2(v[2 * j + 1] - v[2 * i + 1], v[2 * j] - v[2 * i]) }
        fun parallel(i: Int, j: Int): Boolean { var d = abs(dir(i) - dir(j)) % PI.toFloat(); if (d > PI / 2) d = PI.toFloat() - d; return d < deg(10f) }
        val p02 = parallel(0, 2); val p13 = parallel(1, 3)
        if (p02 && p13) {
            val out = v.copyOf(); out[6] = out[0] + out[4] - out[2]; out[7] = out[1] + out[5] - out[3]
            levelEdge(out, 0, 1)
            val rhombus = sides.all { abs(it - sides.average()) / sides.average() < 0.1f }
            return PolyShape(out, true, if (rhombus) "מעוין" else "מקבילית")
        }
        if (!p02 && !p13) return null
        // Rotate the vertex order so the parallel bases are edges 0 (v0->v1) and 2 (v2->v3).
        val s = if (p02) 0 else 1
        val q = FloatArray(8) { v[(2 * s + it) % 8] }
        var tx = q[2] - q[0]; var ty = q[3] - q[1]
        val ex = q[4] - q[6]; val ey = q[5] - q[7]
        val tl = hypot(tx, ty); tx /= tl; ty /= tl
        val el = hypot(ex, ey)
        val avgx = tx + ex / el; val avgy = ty + ey / el; val al = hypot(avgx, avgy)
        tx = avgx / al; ty = avgy / al
        val ma = floatArrayOf((q[0] + q[2]) / 2, (q[1] + q[3]) / 2); val ha = tl / 2
        val mc = floatArrayOf((q[4] + q[6]) / 2, (q[5] + q[7]) / 2); val hc = el / 2
        val leg1 = hypot(q[4] - q[2], q[5] - q[3]); val leg2 = hypot(q[0] - q[6], q[1] - q[7])
        val iso = abs(leg1 - leg2) / ((leg1 + leg2) / 2) < 0.12f
        if (iso) {   // align the second base's middle with the first base's middle
            val nx = -ty; val ny = tx
            val off = (mc[0] - ma[0]) * nx + (mc[1] - ma[1]) * ny
            mc[0] = ma[0] + nx * off; mc[1] = ma[1] + ny * off
        }
        val out = floatArrayOf(ma[0] - tx * ha, ma[1] - ty * ha, ma[0] + tx * ha, ma[1] + ty * ha,
            mc[0] + tx * hc, mc[1] + ty * hc, mc[0] - tx * hc, mc[1] - ty * hc)
        levelEdge(out, 0, 1)
        return PolyShape(out, true, if (iso) "טרפז שווה שוקיים" else "טרפז")
    }

    /** Rotates the polygon about its centroid so edge (i,j) is exactly horizontal when it is within 8°. */
    private fun levelEdge(v: FloatArray, i: Int, j: Int) {
        val a = atan2(v[2 * j + 1] - v[2 * i + 1], v[2 * j] - v[2 * i])
        val s = snapAngle(a, PI.toFloat(), deg(8f))
        if (s != a) { val c = centroid(v); rotatePts(v, s - a, c[0], c[1]) }
    }

    // ---------------- geometry helpers ----------------
    private fun pathLength(p: FloatArray): Float { var l = 0f; var i = 2; while (i < p.size) { l += hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]); i += 2 }; return l }

    private fun resample(p: FloatArray, n: Int): FloatArray {
        val total = pathLength(p); if (total <= 0f) return p.copyOf()
        val step = total / (n - 1)
        val out = FloatArray(n * 2); out[0] = p[0]; out[1] = p[1]
        var k = 1; var acc = 0f; var px = p[0]; var py = p[1]; var i = 2
        while (i < p.size && k < n) {
            val qx = p[i]; val qy = p[i + 1]
            val dd = hypot(qx - px, qy - py)
            if (acc + dd >= step && dd > 0f) {
                val f = (step - acc) / dd
                px += (qx - px) * f; py += (qy - py) * f
                out[2 * k] = px; out[2 * k + 1] = py; k++; acc = 0f
            } else { acc += dd; px = qx; py = qy; i += 2 }
        }
        while (k < n) { out[2 * k] = p[p.size - 2]; out[2 * k + 1] = p[p.size - 1]; k++ }
        return out
    }

    private fun distToLine(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val l = hypot(x1 - x0, y1 - y0); if (l < 1e-3f) return hypot(x - x0, y - y0)
        return abs((y1 - y0) * x - (x1 - x0) * y + x1 * y0 - y1 * x0) / l
    }

    private fun distToSeg(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (x0 + t * dx), y - (y0 + t * dy))
    }

    private fun meanDistToPoly(p: FloatArray, v: FloatArray, closed: Boolean): Float {
        val n = v.size / 2; val segs = if (closed) n else n - 1
        var sum = 0f
        for (k in 0 until p.size / 2) {
            var best = Float.MAX_VALUE
            for (e in 0 until segs) { val j = (e + 1) % n; best = min(best, distToSeg(p[2 * k], p[2 * k + 1], v[2 * e], v[2 * e + 1], v[2 * j], v[2 * j + 1])) }
            sum += best
        }
        return sum / (p.size / 2)
    }

    /** Douglas–Peucker: adds indices of points between a and b that are farther than eps from the chord. */
    private fun dp(p: FloatArray, a: Int, b: Int, eps: Float, out: MutableList<Int>) {
        if (b - a < 2) return
        var best = -1; var bestD = 0f
        for (k in a + 1 until b) { val dd = distToSeg(p[2 * k], p[2 * k + 1], p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1]); if (dd > bestD) { bestD = dd; best = k } }
        if (bestD > eps) { dp(p, a, best, eps, out); out += best; dp(p, best, b, eps, out) }
    }

    private fun closedPolygon(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        var far = 0; var farD = 0f
        for (k in 1 until n) { val dd = hypot(p[2 * k] - p[0], p[2 * k + 1] - p[1]); if (dd > farD) { farD = dd; far = k } }
        val loop = FloatArray((n + 1) * 2).also { System.arraycopy(p, 0, it, 0, p.size); it[2 * n] = p[0]; it[2 * n + 1] = p[1] }
        val idx = mutableListOf(0); dp(loop, 0, far, eps, idx); idx += far; dp(loop, far, n, eps, idx)
        var v = idx.sorted().flatMap { listOf(loop[2 * it], loop[2 * it + 1]) }.toFloatArray()
        v = mergeStraight(v, true, 155f)
        v = mergeShortEdges(v)
        return mergeStraight(v, true, 155f)
    }

    private fun interiorAngle(v: FloatArray, i: Int, n: Int): Float {
        val a = (i + n - 1) % n; val b = (i + 1) % n
        val ax = v[2 * a] - v[2 * i]; val ay = v[2 * a + 1] - v[2 * i + 1]
        val bx = v[2 * b] - v[2 * i]; val by = v[2 * b + 1] - v[2 * i + 1]
        val cosA = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-6f)
        return (kotlin.math.acos(cosA.coerceIn(-1f, 1f)) * 180 / PI).toFloat()
    }

    /** Removes vertices that sit on a nearly straight run (angle above [maxAngle]). */
    private fun mergeStraight(v0: FloatArray, closed: Boolean, maxAngle: Float): FloatArray {
        var v = v0
        while (true) {
            val n = v.size / 2
            if (n <= (if (closed) 3 else 2)) return v
            var worst = -1; var worstA = maxAngle
            for (i in 0 until n) {
                if (!closed && (i == 0 || i == n - 1)) continue
                val a = interiorAngle(v, i, n)
                if (a > worstA) { worstA = a; worst = i }
            }
            if (worst < 0) return v
            v = v.filterIndexed { k, _ -> k / 2 != worst }.toFloatArray()
        }
    }

    /** A rounded corner often becomes two close vertices; replace them by the corner where their neighbours' edges meet. */
    private fun mergeShortEdges(v0: FloatArray): FloatArray {
        var v = v0
        while (v.size / 2 > 3) {
            val n = v.size / 2
            val lens = FloatArray(n) { i -> val j = (i + 1) % n; hypot(v[2 * j] - v[2 * i], v[2 * j + 1] - v[2 * i + 1]) }
            val mean = lens.average().toFloat()
            val e = lens.indices.minByOrNull { lens[it] } ?: return v
            if (lens[e] > 0.25f * mean) return v
            val a = e; val b = (e + 1) % n; val pa = (a + n - 1) % n; val nb = (b + 1) % n
            val hit = intersect(v[2 * pa], v[2 * pa + 1], v[2 * a], v[2 * a + 1], v[2 * nb], v[2 * nb + 1], v[2 * b], v[2 * b + 1])
            val mx = (v[2 * a] + v[2 * b]) / 2; val my = (v[2 * a + 1] + v[2 * b + 1]) / 2
            val px = if (hit != null && hypot(hit[0] - mx, hit[1] - my) < mean) hit[0] else mx
            val py = if (hit != null && hypot(hit[0] - mx, hit[1] - my) < mean) hit[1] else my
            val out = ArrayList<Float>()
            for (i in 0 until n) {
                if (i == b) continue
                if (i == a) { out += px; out += py } else { out += v[2 * i]; out += v[2 * i + 1] }
            }
            v = out.toFloatArray()
        }
        return v
    }

    /** Intersection of line (p1,p2) with line (p3,p4), or null when (nearly) parallel. */
    private fun intersect(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): FloatArray? {
        val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(den) < 1e-4f) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
        return floatArrayOf(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }
}
