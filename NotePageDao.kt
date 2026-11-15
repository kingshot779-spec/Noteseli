package com.eliosnotes.app.data.document

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotePageDao {
    @Query("SELECT * FROM note_pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    fun observePages(noteId: String): Flow<List<NotePageEntity>>

    @Query("SELECT * FROM note_pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    suspend fun getPages(noteId: String): List<NotePageEntity>

    @Query("SELECT * FROM note_pages ORDER BY noteId, pageIndex")
    suspend fun getAllPages(): List<NotePageEntity>

    @Query("SELECT * FROM note_pages WHERE id = :pageId LIMIT 1")
    suspend fun getById(pageId: String): NotePageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(page: NotePageEntity)

    @Update
    suspend fun updateAll(pages: List<NotePageEntity>)

    @Query("UPDATE note_pages SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    /** Cascades to the page's elements through the foreign key. */
    @Query("DELETE FROM note_pages WHERE id = :pageId")
    suspend fun deleteById(pageId: String)
}
