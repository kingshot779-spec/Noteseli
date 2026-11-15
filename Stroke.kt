package com.eliosnotes.app.ink

enum class InkTool { PEN, PENCIL, MARKER, HIGHLIGHTER }

/** One sample of a stroke in page units. pressure is 0..1. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 0.5f)

/**
 * An immutable ink stroke. [width] is the real drawn width in page units (before the
 * pressure factor), so a highlighter simply has a larger width than a pen.
 */
class Stroke(
    val id: String,
    val tool: InkTool,
    val colorArgb: Int,
    val width: Float,
    val points: List<InkPoint>
) {
    /** minX, minY, maxX, maxY of the centre line. */
    val bounds: FloatArray by lazy {
        if (points.isEmpty()) {
            floatArrayOf(0f, 0f, 0f, 0f)
        } else {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            floatArrayOf(minX, minY, maxX, maxY)
        }
    }

    fun derive(newId: String, newPoints: List<InkPoint>) = Stroke(newId, tool, colorArgb, width, newPoints)
}
