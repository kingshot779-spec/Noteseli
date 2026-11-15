package com.eliosnotes.app.ink

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Versioned, editable stroke payload stored in note_elements.payloadJson.
 * Points are quantised (0.1 page unit, 0.001 pressure) and delta-encoded as integers,
 * which keeps long strokes small while staying fully reversible to within that precision.
 */
object InkCodec {
    const val PAYLOAD_VERSION = 1
    private const val POS_SCALE = 10f
    private const val PRESSURE_SCALE = 1000f

    fun encode(stroke: Stroke): String {
        val sb = StringBuilder(stroke.points.size * 8)
        var px = 0
        var py = 0
        var pp = 0
        stroke.points.forEachIndexed { i, p ->
            val x = (p.x * POS_SCALE).roundToInt()
            val y = (p.y * POS_SCALE).roundToInt()
            val pr = (p.pressure.coerceIn(0f, 1f) * PRESSURE_SCALE).roundToInt()
            if (i > 0) sb.append(' ')
            sb.append(x - px).append(' ').append(y - py).append(' ').append(pr - pp)
            px = x; py = y; pp = pr
        }
        return JSONObject()
            .put("tool", stroke.tool.name)
            .put("color", stroke.colorArgb)
            .put("width", stroke.width.toDouble())
            .put("pts", sb.toString())
            .toString()
    }

    /** Returns null for a corrupt or unknown payload instead of crashing the whole page. */
    fun decode(id: String, json: String): Stroke? = try {
        val o = JSONObject(json)
        val tool = runCatching { InkTool.valueOf(o.getString("tool")) }.getOrDefault(InkTool.PEN)
        val raw = o.getString("pts")
        val tokens = if (raw.isEmpty()) emptyList() else raw.split(' ')
        if (tokens.size % 3 != 0) {
            null
        } else {
            val pts = ArrayList<InkPoint>(tokens.size / 3)
            var x = 0
            var y = 0
            var pr = 0
            var i = 0
            while (i < tokens.size) {
                x += tokens[i].toInt()
                y += tokens[i + 1].toInt()
                pr += tokens[i + 2].toInt()
                pts.add(InkPoint(x / POS_SCALE, y / POS_SCALE, pr / PRESSURE_SCALE))
                i += 3
            }
            Stroke(id, tool, o.getInt("color"), o.getDouble("width").toFloat(), pts)
        }
    } catch (e: Exception) {
        null
    }
}
