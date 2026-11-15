package com.eliosnotes.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import com.eliosnotes.app.data.document.PageSize
import com.eliosnotes.app.ink.InkGeometry
import com.eliosnotes.app.ink.InkPainter
import com.eliosnotes.app.ink.InkPoint
import com.eliosnotes.app.ink.PageBackgrounds
import com.eliosnotes.app.ink.Stroke
import kotlin.math.hypot

/**
 * One page drawn at its true proportions. Layers, bottom to top:
 * background template, text, committed ink, live input (only while an ink tool is active).
 */
@Composable
fun PageView(page: EditorPage, session: EditorSession, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val inkPainter = remember { InkPainter() }

    // Bottom edge of the laid-out text (page units). The page grows instead of clipping the text.
    var textBottom by remember(page.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val entity = page.entity
        val scale = with(density) { maxWidth.toPx() } / entity.widthDp
        val heightUnits = maxOf(entity.heightDp, textBottom)
        val pageHeight = with(density) { (heightUnits * scale).toDp() }
        val bgArgb = entity.backgroundColorArgb.toInt()
        val darkPage = luminance(bgArgb) < 0.5f

        Box(Modifier.fillMaxWidth().height(pageHeight).clipToBounds()) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / entity.widthDp
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(s, s)
                    PageBackgrounds.draw(nc, entity.template, bgArgb, entity.widthDp, heightUnits)
                    nc.restore()
                }
            }

            val textColor = if (darkPage) Color(0xFFECEFF1) else Color(0xFF1B1F23)
            BasicTextField(
                value = page.text,
                onValueChange = { session.onTextChange(page, it) },
                enabled = session.tool == EditorTool.TEXT && !page.textLocked,
                onTextLayout = { r ->
                    // Use the real bottom of the last line, not r.size (which can be stretched to the
                    // field's own height and would make the page grow and never shrink back).
                    textBottom = if (page.text.isEmpty() || r.lineCount == 0) 0f
                    else r.getLineBottom(r.lineCount - 1) / scale + 2 * PageSize.TEXT_PADDING
                },
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = with(density) { (PageSize.TEXT_SIZE * scale).toSp() },
                    lineHeight = with(density) { (PageSize.LINE_HEIGHT * scale).toSp() },
                    textDirection = TextDirection.Content
                ),
                cursorBrush = SolidColor(textColor),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(with(density) { (PageSize.TEXT_PADDING * scale).toDp() })
            )

            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / entity.widthDp
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(s, s)
                    for (stroke in page.strokes) inkPainter.draw(nc, stroke)
                    nc.restore()
                }
            }

            if (session.tool != EditorTool.TEXT) InkInputLayer(page, session)
        }
    }
}

@Composable
private fun InkInputLayer(page: EditorPage, session: EditorSession) {
    val tool = session.tool
    val color = session.colorOf(tool)
    val width = session.widthOf(tool)
    val fingerDraws = session.fingerDraws
    val wholeErase = session.eraseWholeStrokes

    val painter = remember { InkPainter() }
    val live = remember { ArrayList<InkPoint>() }
    var liveTick by remember { mutableIntStateOf(0) }
    var eraserX by remember { mutableFloatStateOf(-1000f) }
    var eraserY by remember { mutableFloatStateOf(-1000f) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(tool, color, width, fingerDraws, wholeErase, page.id) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val type = down.type
                        val isStylus = type == PointerType.Stylus || type == PointerType.Eraser
                        val canDraw = isStylus || type == PointerType.Mouse ||
                            (type == PointerType.Touch && fingerDraws)
                        // Finger in "scroll" mode: leave the event unconsumed so the page list scrolls.
                        if (!canDraw) continue

                        val scale = size.width / page.entity.widthDp
                        val erasing = tool == EditorTool.ERASER || type == PointerType.Eraser
                        val before = page.strokes

                        fun pressureOf(c: PointerInputChange): Float =
                            if (c.type == PointerType.Stylus) c.pressure.coerceIn(0f, 1f) else 0.5f

                        fun addPoint(pos: Offset, pressure: Float) {
                            val x = pos.x / scale
                            val y = pos.y / scale
                            val last = live.lastOrNull()
                            if (last != null && hypot(x - last.x, y - last.y) < 0.35f) return
                            live.add(InkPoint(x, y, pressure))
                        }

                        var lastEx = Float.NaN
                        var lastEy = Float.NaN

                        fun eraseCircle(x: Float, y: Float, r: Float) {
                            page.strokes = if (wholeErase) {
                                InkGeometry.eraseWhole(page.strokes, x, y, r)
                            } else {
                                InkGeometry.erasePartialAll(page.strokes, x, y, r)
                            }
                        }

                        fun eraseAt(pos: Offset) {
                            val x = pos.x / scale
                            val y = pos.y / scale
                            eraserX = x
                            eraserY = y
                            val r = session.widthOf(EditorTool.ERASER)
                            if (lastEx.isNaN()) {
                                eraseCircle(x, y, r)
                            } else {
                                // Cover the whole path between two samples, not just the samples.
                                for (p in InkGeometry.sweep(lastEx, lastEy, x, y, r * 0.6f)) eraseCircle(p.x, p.y, r)
                            }
                            lastEx = x
                            lastEy = y
                        }

                        down.consume()
                        live.clear()
                        if (erasing) eraseAt(down.position) else addPoint(down.position, pressureOf(down))
                        liveTick++

                        val tracked = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == tracked } ?: break
                            // While the pen is down, ignore (and swallow) palm touches.
                            if (isStylus) event.changes.forEach { if (it.id != tracked) it.consume() }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (erasing) {
                                change.historical.forEach { eraseAt(it.position) }
                                eraseAt(change.position)
                            } else {
                                val p = pressureOf(change)
                                change.historical.forEach { addPoint(it.position, p) }
                                addPoint(change.position, p)
                            }
                            change.consume()
                            liveTick++
                        }

                        if (erasing) {
                            eraserX = -1000f
                            eraserY = -1000f
                            session.finishErase(page, before)
                        } else if (live.isNotEmpty()) {
                            session.createStroke(ArrayList(live))?.let { session.addStroke(page, it) }
                            live.clear()
                        }
                        liveTick++
                    }
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE") val subscribe = liveTick
        val s = size.width / page.entity.widthDp
        val ink = tool.inkTool
        if (ink != null && live.isNotEmpty()) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.scale(s, s)
                painter.draw(nc, Stroke("live", ink, color, width, live))
                nc.restore()
            }
        }
        if (tool == EditorTool.ERASER && eraserX > -500f) {
            drawCircle(
                color = Color(0x99607D8B),
                radius = session.widthOf(EditorTool.ERASER) * s,
                center = Offset(eraserX * s, eraserY * s),
                style = DrawStroke(width = 2f)
            )
        }
    }
}

private fun luminance(argb: Int): Float {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
}
