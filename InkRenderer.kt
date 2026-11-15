package com.eliosnotes.app.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws strokes in page units onto any android.graphics.Canvas. The same code paints the
 * on-screen page and the exported PDF, so what you see is what you export.
 * Not thread-safe: use one instance per drawing pass/thread.
 */
class InkPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun draw(canvas: Canvas, s: Stroke) {
        val pts = s.points
        if (pts.isEmpty()) return
        val alphaMul = when (s.tool) {
            InkTool.PENCIL -> 0.8f
            InkTool.HIGHLIGHTER -> 0.35f
            else -> 1f
        }
        paint.color = s.colorArgb
        paint.alpha = (Color.alpha(s.colorArgb) * alphaMul).roundToInt().coerceIn(0, 255)
        paint.strokeCap = if (s.tool == InkTool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND

        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0].x, pts[0].y, s.width / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }

        val pressureSensitive = (s.tool == InkTool.PEN || s.tool == InkTool.PENCIL) && pressureVaries(pts)
        if (pressureSensitive) {
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                paint.strokeWidth = s.width * pressureFactor(s.tool, (a.pressure + b.pressure) / 2f)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
        } else {
            paint.strokeWidth = s.width
            path.rewind()
            path.moveTo(pts[0].x, pts[0].y)
            if (pts.size == 2) {
                path.lineTo(pts[1].x, pts[1].y)
            } else {
                for (i in 1 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
                }
                path.lineTo(pts.last().x, pts.last().y)
            }
            canvas.drawPath(path, paint)
        }
    }

    // Devices without pressure report a constant value; treat that as "no pressure".
    private fun pressureVaries(pts: List<InkPoint>): Boolean {
        val first = pts[0].pressure
        for (p in pts) if (abs(p.pressure - first) > 0.02f) return true
        return false
    }

    private fun pressureFactor(tool: InkTool, p: Float): Float =
        if (tool == InkTool.PENCIL) 0.6f + 0.6f * p else 0.45f + 1.0f * p
}

object PageBackgrounds {
    const val SPACING = 32f

    /** Fills the page with its colour and the template pattern. Coordinates are page units. */
    fun draw(canvas: Canvas, template: String, bgArgb: Int, w: Float, h: Float) {
        val fill = Paint().apply { color = bgArgb; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w, h, fill)

        val luminance = (0.299f * Color.red(bgArgb) + 0.587f * Color.green(bgArgb) + 0.114f * Color.blue(bgArgb)) / 255f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (luminance < 0.5f) Color.argb(60, 255, 255, 255) else Color.argb(80, 70, 90, 120)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        when (template) {
            "RULED" -> {
                var y = 88f
                while (y < h - 24f) { canvas.drawLine(0f, y, w, y, line); y += SPACING }
            }
            "GRID" -> {
                var y = SPACING
                while (y < h) { canvas.drawLine(0f, y, w, y, line); y += SPACING }
                var x = SPACING
                while (x < w) { canvas.drawLine(x, 0f, x, h, line); x += SPACING }
            }
            "DOTS" -> {
                line.style = Paint.Style.FILL
                var y = SPACING
                while (y < h) {
                    var x = SPACING
                    while (x < w) { canvas.drawCircle(x, y, 1.6f, line); x += SPACING }
                    y += SPACING
                }
            }
        }
    }
}
