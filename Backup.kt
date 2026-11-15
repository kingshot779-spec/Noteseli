package com.eliosnotes.app.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.eliosnotes.app.data.document.NoteElementEntity
import com.eliosnotes.app.data.document.NotePageEntity
import java.io.InputStream
import java.io.InputStreamReader

/** One decoded JSON object of a backup file. Missing or malformed fields throw, which aborts a restore. */
class BackupRow(private val m: Map<String, Any?>) {
    fun str(k: String): String = m[k] as String
    fun strOrNull(k: String): String? = m[k] as String?
    fun long(k: String): Long = (m[k] as String).toLong()
    fun longOrNull(k: String): Long? = (m[k] as String?)?.toLong()
    fun int(k: String): Int = (m[k] as String).toInt()
    fun float(k: String): Float = (m[k] as String).toFloat()
    fun bool(k: String): Boolean = m[k] as Boolean
}

interface BackupSink {
    suspend fun folder(r: BackupRow)
    suspend fun note(r: BackupRow)
    suspend fun page(r: BackupRow)
    suspend fun element(r: BackupRow)
}

data class BackupStats(val notes: Int, val skipped: Int)

/**
 * Streaming JSON backup format (version 1): folders, notes, pages and elements exactly as stored,
 * with element payloads kept as opaque strings. Streaming keeps memory low for ink-heavy notes.
 * The file is NOT encrypted.
 */
object BackupCodec {
    const val FORMAT = "ELIOS_BACKUP"
    const val VERSION = 1

    fun writeHeader(w: JsonWriter, createdAt: Long) {
        w.name("format").value(FORMAT)
        w.name("version").value(VERSION.toLong())
        w.name("createdAt").value(createdAt)
    }

    fun writeFolder(w: JsonWriter, f: FolderEntity) {
        w.beginObject()
        w.name("id").value(f.id)
        w.name("name").value(f.name)
        w.name("createdAt").value(f.createdAt)
        w.name("updatedAt").value(f.updatedAt)
        w.endObject()
    }

    fun writeNote(w: JsonWriter, n: NoteEntity) {
        w.beginObject()
        w.name("id").value(n.id)
        w.name("title").value(n.title)
        w.name("body").value(n.body)
        w.name("createdAt").value(n.createdAt)
        w.name("updatedAt").value(n.updatedAt)
        w.name("isPinned").value(n.isPinned)
        w.name("isArchived").value(n.isArchived)
        w.name("isFavorite").value(n.isFavorite)
        w.name("pageMode").value(n.pageMode)
        if (n.folderId == null) w.name("folderId").nullValue() else w.name("folderId").value(n.folderId)
        if (n.deletedAt == null) w.name("deletedAt").nullValue() else w.name("deletedAt").value(n.deletedAt)
        w.endObject()
    }

    fun writePage(w: JsonWriter, p: NotePageEntity) {
        w.beginObject()
        w.name("id").value(p.id)
        w.name("noteId").value(p.noteId)
        w.name("pageIndex").value(p.pageIndex.toLong())
        w.name("widthDp").value(p.widthDp.toDouble())
        w.name("heightDp").value(p.heightDp.toDouble())
        w.name("orientation").value(p.orientation)
        w.name("template").value(p.template)
        w.name("backgroundColorArgb").value(p.backgroundColorArgb)
        w.name("createdAt").value(p.createdAt)
        w.name("updatedAt").value(p.updatedAt)
        w.endObject()
    }

    fun writeElement(w: JsonWriter, e: NoteElementEntity) {
        w.beginObject()
        w.name("id").value(e.id)
        w.name("pageId").value(e.pageId)
        w.name("elementType").value(e.elementType)
        w.name("payloadVersion").value(e.payloadVersion.toLong())
        w.name("payloadJson").value(e.payloadJson)
        w.name("x").value(e.x.toDouble())
        w.name("y").value(e.y.toDouble())
        w.name("width").value(e.width.toDouble())
        w.name("height").value(e.height.toDouble())
        w.name("rotationDegrees").value(e.rotationDegrees.toDouble())
        w.name("zIndex").value(e.zIndex.toLong())
        w.name("createdAt").value(e.createdAt)
        w.name("updatedAt").value(e.updatedAt)
        w.endObject()
    }

    /** Streams [input] into [sink]. Throws if the file is not a version-1 ELIOS backup or is malformed. */
    suspend fun read(input: InputStream, sink: BackupSink) {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        var format: String? = null
        var version = -1
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextString()
                "version" -> version = r.nextInt()
                "folders" -> { requireHeader(format, version); readArray(r) { sink.folder(BackupRow(it)) } }
                "notes" -> { requireHeader(format, version); readArray(r) { sink.note(BackupRow(it)) } }
                "pages" -> { requireHeader(format, version); readArray(r) { sink.page(BackupRow(it)) } }
                "elements" -> { requireHeader(format, version); readArray(r) { sink.element(BackupRow(it)) } }
                else -> r.skipValue()
            }
        }
        r.endObject()
        requireHeader(format, version)
    }

    private fun requireHeader(format: String?, version: Int) {
        require(format == FORMAT) { "Not an ELIOS backup" }
        require(version in 1..VERSION) { "Unsupported backup version" }
    }

    private suspend fun readArray(r: JsonReader, each: suspend (Map<String, Any?>) -> Unit) {
        r.beginArray()
        while (r.hasNext()) each(readObject(r))
        r.endArray()
    }

    private fun readObject(r: JsonReader): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[key] = r.nextString()
                JsonToken.BOOLEAN -> m[key] = r.nextBoolean()
                JsonToken.NULL -> { r.nextNull(); m[key] = null }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }
}
