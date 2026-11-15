package com.eliosnotes.app.ink

import java.util.UUID
import kotlin.math.hypot

/** Hit-testing and eraser maths, in page units. Pure Kotlin so it is unit-testable on the JVM. */
object InkGeometry {
    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * Points along (x0,y0) -> (x1,y1) spaced at most [step] apart, excluding the start and
     * including the end. Used so a fast eraser stroke cannot skip over ink between two samples.
     */
    fun sweep(x0: Float, y0: Float, x1: Float, y1: Float, step: Float): List<InkPoint> {
        val dist = hypot(x1 - x0, y1 - y0)
        // Do not cap the sample count: a cap can leave gaps on a long pointer event and let
        // the eraser jump over ink, contradicting the spacing guarantee above.
        val n = if (step <= 0f || !dist.isFinite()) 1
            else kotlin.math.ceil(dist / step).toInt().coerceAtLeast(1)
        return (1..n).map { i ->
            val t = i / n.toFloat()
            InkPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    /** True when an eraser circle (cx, cy, r) touches the stroke's ink. */
    fun hits(s: Stroke, cx: Float, cy: Float, r: Float): Boolean {
        val reach = r + s.width * 0.75f
        val b = s.bounds
        if (cx < b[0] - reach || cx > b[2] + reach || cy < b[1] - reach || cy > b[3] + reach) return false
        val p = s.points
        if (p.isEmpty()) return false
        if (p.size == 1) return hypot(cx - p[0].x, cy - p[0].y) <= reach
        for (i in 0 until p.size - 1) {
            if (distanceToSegment(cx, cy, p[i].x, p[i].y, p[i + 1].x, p[i + 1].y) <= reach) return true
        }
        return false
    }

    /** Removes every stroke touched by the eraser. Returns the same list instance when nothing changed. */
    fun eraseWhole(strokes: List<Stroke>, cx: Float, cy: Float, r: Float): List<Stroke> {
        if (strokes.none { hits(it, cx, cy, r) }) return strokes
        return strokes.filterNot { hits(it, cx, cy, r) }
    }

    /**
     * Cuts the touched part out of one stroke. Returns null if the stroke is untouched,
     * otherwise the remaining pieces (possibly empty), each with a fresh id.
     */
    fun erasePartial(s: Stroke, cx: Float, cy: Float, r: Float): List<Stroke>? {
        if (!hits(s, cx, cy, r)) return null
        val p = s.points
        if (p.size == 1) return emptyList()
        val reach = r + s.width * 0.5f
        val pieces = ArrayList<Stroke>()
        var run = ArrayList<InkPoint>()
        fun closeRun() {
            if (run.size >= 2) pieces.add(s.derive(UUID.randomUUID().toString(), run))
            run = ArrayList()
        }
        for (i in p.indices) {
            val pt = p[i]
            if (hypot(cx - pt.x, cy - pt.y) <= reach) {
                closeRun()
                continue
            }
            if (run.isNotEmpty()) {
                val prev = p[i - 1]
                if (distanceToSegment(cx, cy, prev.x, prev.y, pt.x, pt.y) <= reach) closeRun()
            }
            run.add(pt)
        }
        closeRun()
        // Touched only by the wider hit zone but nothing was actually cut.
        if (pieces.size == 1 && pieces[0].points.size == p.size) return null
        return pieces
    }

    /** Partial erase over a whole page. Returns the same list instance when nothing changed. */
    fun erasePartialAll(strokes: List<Stroke>, cx: Float, cy: Float, r: Float): List<Stroke> {
        var out: ArrayList<Stroke>? = null
        for (i in strokes.indices) {
            val pieces = erasePartial(strokes[i], cx, cy, r)
            if (pieces != null && out == null) out = ArrayList(strokes.subList(0, i))
            if (out != null) {
                if (pieces == null) out.add(strokes[i]) else out.addAll(pieces)
            }
        }
        return out ?: strokes
    }
}
