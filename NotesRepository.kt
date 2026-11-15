package com.eliosnotes.app.data

import androidx.room.withTransaction
import com.eliosnotes.app.data.document.NoteDocument
import com.eliosnotes.app.data.document.NoteElementDao
import com.eliosnotes.app.data.document.NoteElementEntity
import com.eliosnotes.app.data.document.NoteElementType
import com.eliosnotes.app.data.document.NotePageDao
import com.eliosnotes.app.data.document.NotePageEntity
import com.eliosnotes.app.data.document.PageContent
import com.eliosnotes.app.data.document.PageOrientation
import com.eliosnotes.app.data.document.PageSize
import com.eliosnotes.app.data.document.PageTemplate
import com.eliosnotes.app.data.document.TextCodec
import com.eliosnotes.app.ink.InkCodec
import com.eliosnotes.app.ink.Stroke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.JsonWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Single entry point for document persistence. Text and ink of a page are written in one
 * transaction, and all operations share one lock so a save can never interleave with a load.
 */
class NotesRepository(private val db: NotesDatabase) {
    val noteDao: NoteDao = db.noteDao()
    val folderDao: FolderDao = db.folderDao()
    private val pageDao: NotePageDao = db.notePageDao()
    private val elementDao: NoteElementDao = db.noteElementDao()
    private val lock = Mutex()

    fun newPage(noteId: String, index: Int, now: Long) = NotePageEntity(
        id = UUID.randomUUID().toString(),
        noteId = noteId,
        pageIndex = index,
        widthDp = PageSize.A4_WIDTH,
        heightDp = PageSize.A4_HEIGHT,
        orientation = PageOrientation.PORTRAIT,
        template = PageTemplate.BLANK,
        createdAt = now,
        updatedAt = now
    )

    suspend fun createNote(folderId: String?): NoteEntity = lock.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val note = NoteEntity(
                id = UUID.randomUUID().toString(),
                title = "", body = "", createdAt = now, updatedAt = now, folderId = folderId
            )
            noteDao.insert(note)
            pageDao.insert(newPage(note.id, 0, now))
            note
        }
    }

    suspend fun loadDocument(noteId: String): NoteDocument? = lock.withLock {
        db.withTransaction {
            val note = noteDao.getById(noteId) ?: return@withTransaction null
            var pages = pageDao.getPages(noteId)
            if (pages.isEmpty()) {
                // Notes created before the page model existed get their first page lazily.
                val first = newPage(noteId, 0, System.currentTimeMillis())
                pageDao.insert(first)
                pages = listOf(first)
            }
            val now = System.currentTimeMillis()
            val noteHasTextElement = pages.any { pg ->
                elementDao.getElements(pg.id).any { it.elementType == NoteElementType.TEXT }
            }
            val content = pages.mapIndexed { index, page ->
                var text = ""
                var hasTextElement = false
                var textLocked = false
                val strokes = ArrayList<Stroke>()
                val lockedInk = HashSet<String>()
                for (e in elementDao.getElements(page.id)) {
                    when (e.elementType) {
                        NoteElementType.TEXT -> {
                            hasTextElement = true
                            val decoded = if (e.payloadVersion > TEXT_PAYLOAD_VERSION) null else TextCodec.decodeOrNull(e.payloadJson)
                            if (decoded == null) textLocked = true else text = decoded
                        }
                        NoteElementType.INK -> {
                            val stroke = if (e.payloadVersion > InkCodec.PAYLOAD_VERSION) null else InkCodec.decode(e.id, e.payloadJson)
                            if (stroke == null) lockedInk.add(e.id) else strokes.add(stroke)
                        }
                    }
                }
                // Legacy v1 note: its text lived in notes.body. Copy it into the first page's TEXT
                // element right now. Without this, saving any other page would rebuild the body from
                // TEXT elements only and silently replace the legacy text.
                if (!hasTextElement && index == 0 && !noteHasTextElement && note.body.isNotEmpty()) {
                    text = note.body
                    elementDao.upsert(
                        NoteElementEntity(
                            id = "text-${page.id}", pageId = page.id, elementType = NoteElementType.TEXT,
                            payloadVersion = TEXT_PAYLOAD_VERSION, payloadJson = TextCodec.encode(text),
                            x = 0f, y = 0f, width = page.widthDp, height = page.heightDp,
                            zIndex = 0, createdAt = now, updatedAt = now
                        )
                    )
                }
                PageContent(page, text, strokes, textLocked, lockedInk)
            }
            NoteDocument(note, content)
        }
    }

    suspend fun saveTitle(noteId: String, title: String) = lock.withLock {
        noteDao.updateTitle(noteId, title, System.currentTimeMillis())
    }

    suspend fun savePage(
        noteId: String,
        pageId: String,
        text: String,
        strokes: List<Stroke>,
        writeText: Boolean = true,
        protectedInkIds: Set<String> = emptySet()
    ) = lock.withLock {
        db.withTransaction {
            val page = pageDao.getById(pageId) ?: return@withTransaction // page was deleted meanwhile
            val now = System.currentTimeMillis()

            if (writeText) {
                elementDao.upsert(
                    NoteElementEntity(
                        id = "text-$pageId", pageId = pageId, elementType = NoteElementType.TEXT,
                        payloadVersion = TEXT_PAYLOAD_VERSION, payloadJson = TextCodec.encode(text),
                        x = 0f, y = 0f, width = page.widthDp, height = page.heightDp,
                        zIndex = 0, createdAt = now, updatedAt = now
                    )
                )
            }

            // Elements we could not decode are neither rewritten nor deleted.
            val existing = elementDao.getIdsByType(pageId, NoteElementType.INK).toSet() - protectedInkIds
            val currentIds = strokes.mapTo(HashSet()) { it.id }
            val stale = existing - currentIds
            stale.toList().chunked(400).forEach { elementDao.deleteByIds(it) }

            val fresh = strokes.filter { it.id !in existing }
            val pureAppend = stale.isEmpty() &&
                strokes.size - fresh.size == existing.size &&
                strokes.take(existing.size).all { it.id in existing }
            // Appending keeps old z-order valid; anything else rewrites the z-order of the page.
            val toWrite = if (pureAppend) fresh else strokes
            val offset = if (pureAppend) existing.size else 0
            elementDao.upsertAll(toWrite.mapIndexed { i, s -> strokeElement(pageId, s, offset + i, now) })

            pageDao.touch(pageId, now)
            val body = elementDao.getTextPayloads(noteId)
                .map { TextCodec.decode(it) }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            noteDao.updateContent(noteId, body, now)
        }
    }

    private companion object {
        const val TEXT_PAYLOAD_VERSION = 1
    }

    private fun strokeElement(pageId: String, s: Stroke, z: Int, now: Long): NoteElementEntity {
        val b = s.bounds
        return NoteElementEntity(
            id = s.id, pageId = pageId, elementType = NoteElementType.INK,
            payloadVersion = InkCodec.PAYLOAD_VERSION, payloadJson = InkCodec.encode(s),
            x = b[0], y = b[1], width = b[2] - b[0], height = b[3] - b[1],
            zIndex = z, createdAt = now, updatedAt = now
        )
    }

    suspend fun insertPage(page: NotePageEntity) = lock.withLock { pageDao.insert(page) }

    suspend fun updatePages(pages: List<NotePageEntity>) = lock.withLock {
        pageDao.updateAll(pages)
    }

    suspend fun deletePage(noteId: String, pageId: String, remaining: List<NotePageEntity>) = lock.withLock {
        db.withTransaction {
            pageDao.deleteById(pageId)
            pageDao.updateAll(remaining)
            val body = elementDao.getTextPayloads(noteId)
                .map { TextCodec.decode(it) }.filter { it.isNotBlank() }.joinToString("\n")
            noteDao.updateContent(noteId, body, System.currentTimeMillis())
        }
    }

    suspend fun setPageMode(noteId: String, mode: String) = lock.withLock { noteDao.setPageMode(noteId, mode) }

    suspend fun deleteFolder(folderId: String) = lock.withLock {
        db.withTransaction {
            folderDao.detachNotes(folderId)
            folderDao.deleteById(folderId)
        }
    }

    suspend fun purgeOldTrash(days: Int) = lock.withLock {
        noteDao.purgeTrashedBefore(System.currentTimeMillis() - days * 86_400_000L)
    }

    // ---- backup / restore ---------------------------------------------------------------

    /** Writes every folder, note (incl. archived and trashed), page and element. Does not close [out]. */
    suspend fun exportBackup(out: OutputStream): BackupStats = lock.withLock {
        val folders = folderDao.getAll()
        val notes = noteDao.getAll()
        val pages = pageDao.getAllPages()
        val w = JsonWriter(OutputStreamWriter(out, Charsets.UTF_8))
        w.beginObject()
        BackupCodec.writeHeader(w, System.currentTimeMillis())
        w.name("folders").beginArray(); folders.forEach { BackupCodec.writeFolder(w, it) }; w.endArray()
        w.name("notes").beginArray(); notes.forEach { BackupCodec.writeNote(w, it) }; w.endArray()
        w.name("pages").beginArray(); pages.forEach { BackupCodec.writePage(w, it) }; w.endArray()
        w.name("elements").beginArray()
        for (p in pages) elementDao.getElements(p.id).forEach { BackupCodec.writeElement(w, it) }
        w.endArray()
        w.endObject()
        w.flush()
        BackupStats(notes.size, 0)
    }

    /** Parses a written backup without touching the database; returns the number of notes it holds. */
    suspend fun verifyBackup(input: InputStream): Int {
        var notes = 0
        BackupCodec.read(input, object : BackupSink {
            override suspend fun folder(r: BackupRow) { r.str("id") }
            override suspend fun note(r: BackupRow) { r.str("id"); notes++ }
            override suspend fun page(r: BackupRow) { r.str("id") }
            override suspend fun element(r: BackupRow) { r.str("id") }
        })
        return notes
    }

    /**
     * Merges a backup into the database inside ONE transaction: anything that fails (bad file,
     * wrong version, ...) rolls everything back. Existing notes and folders are never overwritten;
     * notes whose id already exists are skipped together with their pages and elements.
     */
    suspend fun restoreBackup(input: InputStream): BackupStats = lock.withLock {
        db.withTransaction {
            val folderIds = folderDao.getAll().mapTo(HashSet()) { it.id }
            val existingNotes = noteDao.getAll().mapTo(HashSet()) { it.id }
            val insertedNotes = HashSet<String>()
            val insertedPages = HashSet<String>()
            var skipped = 0

            BackupCodec.read(input, object : BackupSink {
                override suspend fun folder(r: BackupRow) {
                    val id = r.str("id")
                    if (id in folderIds) return
                    folderDao.insert(FolderEntity(id, r.str("name"), r.long("createdAt"), r.long("updatedAt")))
                    folderIds.add(id)
                }

                override suspend fun note(r: BackupRow) {
                    val id = r.str("id")
                    if (id in existingNotes) { skipped++; return }
                    noteDao.insert(
                        NoteEntity(
                            id = id, title = r.str("title"), body = r.str("body"),
                            createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt"),
                            isPinned = r.bool("isPinned"), isArchived = r.bool("isArchived"),
                            isFavorite = r.bool("isFavorite"), pageMode = r.str("pageMode"),
                            folderId = r.strOrNull("folderId")?.takeIf { it in folderIds },
                            deletedAt = r.longOrNull("deletedAt")
                        )
                    )
                    insertedNotes.add(id)
                }

                override suspend fun page(r: BackupRow) {
                    if (r.str("noteId") !in insertedNotes) return
                    pageDao.insert(
                        NotePageEntity(
                            id = r.str("id"), noteId = r.str("noteId"), pageIndex = r.int("pageIndex"),
                            widthDp = r.float("widthDp"), heightDp = r.float("heightDp"),
                            orientation = r.str("orientation"), template = r.str("template"),
                            backgroundColorArgb = r.long("backgroundColorArgb"),
                            createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt")
                        )
                    )
                    insertedPages.add(r.str("id"))
                }

                override suspend fun element(r: BackupRow) {
                    if (r.str("pageId") !in insertedPages) return
                    elementDao.upsert(
                        NoteElementEntity(
                            id = r.str("id"), pageId = r.str("pageId"), elementType = r.str("elementType"),
                            payloadVersion = r.int("payloadVersion"), payloadJson = r.str("payloadJson"),
                            x = r.float("x"), y = r.float("y"), width = r.float("width"), height = r.float("height"),
                            rotationDegrees = r.float("rotationDegrees"), zIndex = r.int("zIndex"),
                            createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt")
                        )
                    )
                }
            })
            BackupStats(insertedNotes.size, skipped)
        }
    }
}
