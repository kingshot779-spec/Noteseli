package com.eliosnotes.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.eliosnotes.app.data.AppScope
import com.eliosnotes.app.data.NotesRepository
import com.eliosnotes.app.data.SaveQueue
import com.eliosnotes.app.data.document.NotePageEntity
import com.eliosnotes.app.data.document.PageContent
import com.eliosnotes.app.data.document.PageMode
import com.eliosnotes.app.data.document.PageOrientation
import com.eliosnotes.app.data.document.PageSize
import com.eliosnotes.app.ink.InkPoint
import com.eliosnotes.app.ink.InkTool
import com.eliosnotes.app.ink.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class EditorTool {
    PEN, PENCIL, MARKER, HIGHLIGHTER, ERASER, TEXT;

    val inkTool: InkTool?
        get() = when (this) {
            PEN -> InkTool.PEN
            PENCIL -> InkTool.PENCIL
            MARKER -> InkTool.MARKER
            HIGHLIGHTER -> InkTool.HIGHLIGHTER
            else -> null
        }
}

enum class LoadStatus { LOADING, READY, MISSING }

/** Live, observable state of one page. [strokes] is replaced (never mutated) on every edit. */
class EditorPage(
    initial: NotePageEntity,
    initialText: String,
    initialStrokes: List<Stroke>,
    /** Stored text is unreadable: show the page but never overwrite that text. */
    val textLocked: Boolean = false,
    /** Ink elements that could not be decoded; autosave leaves them untouched. */
    val lockedInkIds: Set<String> = emptySet()
) {
    val id: String = initial.id
    var entity by mutableStateOf(initial)
    var text by mutableStateOf(initialText)
    var strokes by mutableStateOf(initialStrokes)
    val isEmpty: Boolean get() = strokes.isEmpty() && text.isBlank()
}

/**
 * Holds the open note: pages, tool state, undo/redo and debounced autosave.
 * All persistence goes through [SaveQueue], so closing the editor never loses pending edits.
 */
class EditorSession(
    private val repo: NotesRepository,
    val noteId: String,
    private val scope: CoroutineScope
) {
    var status by mutableStateOf(LoadStatus.LOADING)
        private set
    var title by mutableStateOf("")
        private set
    var isFavorite by mutableStateOf(false)
        private set
    var pageMode by mutableStateOf(PageMode.INDIVIDUAL_PAGES)
        private set
    val pages = mutableStateListOf<EditorPage>()

    // ---- tool state -------------------------------------------------------------------
    var tool by mutableStateOf(EditorTool.PEN)
    var fingerDraws by mutableStateOf(true)
    var eraseWholeStrokes by mutableStateOf(false)
    private val toolColors = mutableStateMapOf(
        EditorTool.PEN to INK_BLACK,
        EditorTool.PENCIL to Color(0xFF455A64).toArgb(),
        EditorTool.MARKER to Color(0xFF1E4FA3).toArgb(),
        EditorTool.HIGHLIGHTER to Color(0xFFFFEB3B).toArgb()
    )
    private val toolWidths = mutableStateMapOf(
        EditorTool.PEN to 3f,
        EditorTool.PENCIL to 2.5f,
        EditorTool.MARKER to 8f,
        EditorTool.HIGHLIGHTER to 18f,
        EditorTool.ERASER to 14f
    )

    fun colorOf(t: EditorTool): Int = toolColors[t] ?: INK_BLACK
    fun setColor(t: EditorTool, argb: Int) { toolColors[t] = argb }
    fun widthOf(t: EditorTool): Float = toolWidths[t] ?: 3f
    fun setWidth(t: EditorTool, w: Float) { toolWidths[t] = w }

    // ---- undo / redo ------------------------------------------------------------------
    private class Edit(val pageId: String, val before: List<Stroke>, val after: List<Stroke>)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun refreshUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun pushEdit(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoFlags()
        markDirty(edit.pageId)
    }

    fun undo() {
        while (undoStack.isNotEmpty()) {
            val edit = undoStack.removeLast()
            val page = pages.firstOrNull { it.id == edit.pageId } ?: continue
            page.strokes = edit.before
            redoStack.addLast(edit)
            markDirty(page.id)
            break
        }
        refreshUndoFlags()
    }

    fun redo() {
        while (redoStack.isNotEmpty()) {
            val edit = redoStack.removeLast()
            val page = pages.firstOrNull { it.id == edit.pageId } ?: continue
            page.strokes = edit.after
            undoStack.addLast(edit)
            markDirty(page.id)
            break
        }
        refreshUndoFlags()
    }

    // ---- ink edits (called by the page canvas) ----------------------------------------
    fun createStroke(points: List<InkPoint>): Stroke? {
        val ink = tool.inkTool ?: return null
        return Stroke(UUID.randomUUID().toString(), ink, colorOf(tool), widthOf(tool), points)
    }

    fun addStroke(page: EditorPage, stroke: Stroke) {
        val before = page.strokes
        val after = before + stroke
        page.strokes = after
        pushEdit(Edit(page.id, before, after))
    }

    /** The canvas edits [EditorPage.strokes] live while erasing; this records the whole gesture as one undo step. */
    fun finishErase(page: EditorPage, before: List<Stroke>) {
        val after = page.strokes
        if (after !== before) pushEdit(Edit(page.id, before, after))
    }

    fun onTextChange(page: EditorPage, text: String) {
        if (page.textLocked) return
        page.text = text
        markDirty(page.id)
    }

    fun onTitleChange(value: String) {
        title = value
        titleDirty = true
        scheduleSave()
    }

    // ---- loading ----------------------------------------------------------------------
    suspend fun load() {
        val doc = SaveQueue.run { repo.loadDocument(noteId) }
        if (doc == null) {
            status = LoadStatus.MISSING
            return
        }
        title = doc.note.title
        isFavorite = doc.note.isFavorite
        pageMode = doc.note.pageMode
        pages.clear()
        doc.pages.forEach { pages.add(EditorPage(it.page, it.text, it.strokes, it.textLocked, it.lockedInkIds)) }
        status = LoadStatus.READY
    }

    // ---- autosave ---------------------------------------------------------------------
    private val dirtyPages = LinkedHashSet<String>()
    private var titleDirty = false
    private var saveJob: Job? = null

    private fun markDirty(pageId: String) {
        dirtyPages.add(pageId)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** True when the latest edits could not be written even after retrying. */
    var saveFailed by mutableStateOf(false)
        private set

    private class PageSnapshot(
        val id: String,
        val seq: Long,
        val text: String,
        val strokes: List<Stroke>,
        val writeText: Boolean,
        val protectedInk: Set<String>
    )

    private val seqCounter = AtomicLong(0)
    private val latestPageSeq = ConcurrentHashMap<String, Long>()
    private val latestTitleSeq = AtomicLong(0)

    /** Snapshots everything unsaved and queues it. Safe to call at any time and from onStop. */
    fun flush() {
        val snapshots = dirtyPages.mapNotNull { id ->
            pages.firstOrNull { it.id == id }?.let { p ->
                val seq = seqCounter.incrementAndGet()
                latestPageSeq[p.id] = seq
                PageSnapshot(p.id, seq, p.text, p.strokes, !p.textLocked, p.lockedInkIds)
            }
        }
        dirtyPages.clear()
        var titleSeq = 0L
        val pendingTitle = if (titleDirty) title else null
        if (pendingTitle != null) {
            titleSeq = seqCounter.incrementAndGet()
            latestTitleSeq.set(titleSeq)
        }
        titleDirty = false
        if (snapshots.isEmpty() && pendingTitle == null) return
        enqueueSave(snapshots, pendingTitle, titleSeq, 0)
    }

    /**
     * Writes the snapshots in order. On failure it retries a few times; a retried snapshot is
     * skipped if a newer one for the same page was queued meanwhile, so old data can never
     * overwrite newer data.
     */
    private fun enqueueSave(snaps: List<PageSnapshot>, title: String?, titleSeq: Long, attempt: Int) {
        SaveQueue.submit(onError = {
            if (attempt < MAX_SAVE_ATTEMPTS) {
                AppScope.launch {
                    delay(1500L * (attempt + 1))
                    enqueueSave(snaps, title, titleSeq, attempt + 1)
                }
            } else {
                saveFailed = true
            }
        }) {
            if (title != null && latestTitleSeq.get() == titleSeq) repo.saveTitle(noteId, title)
            for (s in snaps) {
                if (latestPageSeq[s.id] == s.seq) {
                    repo.savePage(noteId, s.id, s.text, s.strokes, s.writeText, s.protectedInk)
                }
            }
            saveFailed = false
        }
    }

    fun close() {
        saveJob?.cancel()
        flush()
    }

    // ---- page / note operations -------------------------------------------------------
    fun toggleFavorite() {
        isFavorite = !isFavorite
        val value = isFavorite
        SaveQueue.submit { repo.noteDao.setFavorite(noteId, value) }
    }

    fun moveToTrash() {
        flush()
        SaveQueue.submit { repo.noteDao.moveToTrash(noteId, System.currentTimeMillis()) }
    }

    fun addPage() {
        val last = pages.last().entity
        val now = System.currentTimeMillis()
        val page = last.copy(id = UUID.randomUUID().toString(), pageIndex = pages.size, createdAt = now, updatedAt = now)
        pages.add(EditorPage(page, "", emptyList()))
        SaveQueue.submit { repo.insertPage(page) }
    }

    fun deletePage(page: EditorPage) {
        undoStack.removeAll { it.pageId == page.id }
        redoStack.removeAll { it.pageId == page.id }
        refreshUndoFlags()
        dirtyPages.remove(page.id)
        if (pages.size == 1) {
            // A note always keeps one page: deleting the only page clears it instead.
            page.text = ""
            page.strokes = emptyList()
            markDirty(page.id)
            return
        }
        pages.remove(page)
        pages.forEachIndexed { i, p -> if (p.entity.pageIndex != i) p.entity = p.entity.copy(pageIndex = i) }
        val remaining = pages.map { it.entity }
        SaveQueue.submit { repo.deletePage(noteId, page.id, remaining) }
    }

    fun setTemplate(template: String) = updateAllPages { it.copy(template = template) }

    fun setBackground(argb: Long) = updateAllPages { it.copy(backgroundColorArgb = argb) }

    /** Orientation applies to empty pages only, so existing ink is never cut off. */
    fun setOrientation(orientation: String) {
        val landscape = orientation == PageOrientation.LANDSCAPE
        val w = if (landscape) PageSize.A4_HEIGHT else PageSize.A4_WIDTH
        val h = if (landscape) PageSize.A4_WIDTH else PageSize.A4_HEIGHT
        pages.forEach { p ->
            if (p.isEmpty) p.entity = p.entity.copy(orientation = orientation, widthDp = w, heightDp = h, updatedAt = System.currentTimeMillis())
        }
        persistPages()
    }

    fun setPageMode(mode: String) {
        pageMode = mode
        SaveQueue.submit { repo.setPageMode(noteId, mode) }
    }

    private fun updateAllPages(transform: (NotePageEntity) -> NotePageEntity) {
        val now = System.currentTimeMillis()
        pages.forEach { it.entity = transform(it.entity).copy(updatedAt = now) }
        persistPages()
    }

    private fun persistPages() {
        val entities = pages.map { it.entity }
        SaveQueue.submit { repo.updatePages(entities) }
    }

    fun snapshot(): List<PageContent> = pages.map { PageContent(it.entity, it.text, it.strokes) }

    companion object {
        const val SAVE_DEBOUNCE_MS = 700L
        const val MAX_UNDO = 200
        const val MAX_SAVE_ATTEMPTS = 3
        val INK_BLACK: Int = Color(0xFF1B1F23).toArgb()
    }
}
