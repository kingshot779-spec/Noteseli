package com.eliosnotes.app.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Log
import com.eliosnotes.app.data.document.PageContent
import com.eliosnotes.app.data.document.PageSize
import com.eliosnotes.app.ink.InkPainter
import com.eliosnotes.app.ink.PageBackgrounds
import kotlin.math.roundToInt

/**
 * Exports pages as a real vector PDF (text and ink are drawn, not rasterised) and then
 * re-opens the written file with PdfRenderer to verify the page count.
 */
object PdfExporter {
    data class Result(val ok: Boolean, val pageCount: Int)

    // A4 is 595 pt wide; page units are 794 wide (96 dpi).
    private const val POINTS_PER_UNIT = 595f / 794f

    fun export(context: Context, uri: Uri, pages: List<PageContent>): Result {
        if (pages.isEmpty()) return Result(false, 0)
        val doc = PdfDocument()
        try {
            val painter = InkPainter()
            pages.forEachIndexed { index, content ->
                val w = content.page.widthDp
                val layout = textLayout(content.text, w, content.page.backgroundColorArgb.toInt())
                // Same rule as the screen: a page grows when its text is taller, never clips it.
                val h = maxOf(
                    content.page.heightDp,
                    if (layout == null) 0f else layout.height + 2 * PageSize.TEXT_PADDING
                )
                val widthPt = (w * POINTS_PER_UNIT).roundToInt().coerceAtLeast(1)
                val heightPt = (h * POINTS_PER_UNIT).roundToInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                val page = doc.startPage(info)
                val canvas = page.canvas
                canvas.scale(widthPt / w, heightPt / h)

                val bg = content.page.backgroundColorArgb.toInt()
                PageBackgrounds.draw(canvas, content.page.template, bg, w, h)
                drawText(canvas, layout)
                for (stroke in content.strokes) painter.draw(canvas, stroke)
                doc.finishPage(page)
            }
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return Result(false, 0)
            out.use { doc.writeTo(it) }
        } catch (e: Exception) {
            Log.e("PdfExporter", "export failed: ${e.javaClass.simpleName}")
            discard(context, uri)
            return Result(false, 0)
        } finally {
            doc.close()
        }
        val result = verify(context, uri, pages.size)
        if (!result.ok) discard(context, uri)
        return result
    }

    /** Best effort removal of a broken or half-written destination file. */
    private fun discard(context: Context, uri: Uri) {
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (ignored: Exception) {}
    }

    private fun textLayout(text: String, w: Float, bg: Int): StaticLayout? {
        if (text.isBlank()) return null
        val dark = (0.299f * ((bg shr 16) and 0xFF) + 0.587f * ((bg shr 8) and 0xFF) + 0.114f * (bg and 0xFF)) / 255f < 0.5f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFECEFF1.toInt() else 0xFF1B1F23.toInt()
            textSize = PageSize.TEXT_SIZE
        }
        val natural = paint.fontMetrics.descent - paint.fontMetrics.ascent
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, (w - 2 * PageSize.TEXT_PADDING).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            // Lines sit on the same 32-unit grid as the on-screen text and the ruled template.
            .setLineSpacing(maxOf(0f, PageSize.LINE_HEIGHT - natural), 1f)
            .setIncludePad(false)
            .build()
    }

    private fun drawText(canvas: android.graphics.Canvas, layout: StaticLayout?) {
        if (layout == null) return
        canvas.save()
        canvas.translate(PageSize.TEXT_PADDING, PageSize.TEXT_PADDING)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun verify(context: Context, uri: Uri, expected: Int): Result {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                val renderer = PdfRenderer(fd)
                try {
                    Result(renderer.pageCount == expected, renderer.pageCount)
                } finally {
                    renderer.close()
                }
            } ?: Result(false, 0)
        } catch (e: Exception) {
            Log.e("PdfExporter", "verification failed", e)
            Result(false, 0)
        }
    }
}
