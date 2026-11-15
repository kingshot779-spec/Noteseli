package com.eliosnotes.app.data.document

import com.eliosnotes.app.data.NoteEntity
import com.eliosnotes.app.ink.Stroke
import org.json.JSONObject

/** Everything one page holds, decoded and ready for rendering or export. */
data class PageContent(
    val page: NotePageEntity,
    val text: String,
    val strokes: List<Stroke>,
    /** The stored text could not be read safely: it must never be overwritten. */
    val textLocked: Boolean = false,
    /** Ink elements that could not be decoded (corrupt / newer version): never deleted by autosave. */
    val lockedInkIds: Set<String> = emptySet()
)

data class NoteDocument(val note: NoteEntity, val pages: List<PageContent>)

object TextCodec {
    fun encode(text: String): String = JSONObject().put("text", text).toString()

    fun decode(payload: String): String = decodeOrNull(payload) ?: ""

    /** Null when the payload is not valid text JSON (so callers can protect it instead of clobbering it). */
    fun decodeOrNull(payload: String): String? = try {
        JSONObject(payload).getString("text")
    } catch (e: Exception) {
        null
    }
}

/** Shared page geometry in "page units" (A4 at 96 dpi). */
object PageSize {
    const val A4_WIDTH = 794f
    const val A4_HEIGHT = 1123f

    /** Page text metrics, shared by the on-screen text layer and the PDF exporter. */
    const val TEXT_SIZE = 20f
    const val TEXT_PADDING = 56f

    /** Text lines sit on the 32-unit template grid (ruled lines start at 88 = 56 + 32). */
    const val LINE_HEIGHT = 32f
}
