package com.eliosnotes.app.data.document

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A renderer-independent document element.
 * payloadJson is versioned per element type and stores type-specific data
 * (for example Ink stroke samples, rich text spans, or media references).
 */
@Entity(
    tableName = "note_elements",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId"), Index(value = ["pageId", "zIndex"])]
)
data class NoteElementEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val elementType: String,
    val payloadVersion: Int = 1,
    val payloadJson: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val rotationDegrees: Float = 0f,
    val zIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

object NoteElementType {
    const val TEXT = "TEXT"
    const val INK = "INK"
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
    const val PDF_CONTENT = "PDF_CONTENT"
}
