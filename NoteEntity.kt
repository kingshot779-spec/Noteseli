package com.eliosnotes.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes", indices = [Index(value = ["folderId"])])
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Plain-text mirror of all page texts; used only for search and list previews. */
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val pageMode: String = "PAGED",
    val folderId: String? = null,
    /** Non-null = the note is in the trash (soft delete). */
    val deletedAt: Long? = null
)
