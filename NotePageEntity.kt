package com.eliosnotes.app.data.document

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eliosnotes.app.data.NoteEntity

/**
 * A persisted page/canvas belonging to a note.
 *
 * PAGE mode: each row is a separately navigable page.
 * INFINITE mode: rows are ordered vertical segments of one continuous canvas;
 * the renderer may present them as a seamless scrolling surface.
 */
@Entity(
    tableName = "note_pages",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index(value = ["noteId", "pageIndex"])]
)
data class NotePageEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val pageIndex: Int,
    val widthDp: Float,
    val heightDp: Float,
    val orientation: String = PageOrientation.PORTRAIT,
    val template: String = PageTemplate.BLANK,
    val backgroundColorArgb: Long = 0xFFFFFFFFL,
    val createdAt: Long,
    val updatedAt: Long
)

object PageMode {
    const val INDIVIDUAL_PAGES = "PAGED"
    const val INFINITE_SCROLLING = "INFINITE"
}

object PageOrientation {
    const val PORTRAIT = "PORTRAIT"
    const val LANDSCAPE = "LANDSCAPE"
}

object PageTemplate {
    const val BLANK = "BLANK"
    const val RULED = "RULED"
    const val GRID = "GRID"
    const val DOTS = "DOTS"
}
