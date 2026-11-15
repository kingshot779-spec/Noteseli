package com.eliosnotes.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isArchived = 0 AND deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isArchived = 0 AND deletedAt IS NULL AND isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteEntity?

    /** Fails if the id exists. Never use REPLACE on notes: it would cascade-delete pages and elements. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    /** INSERT or in-place UPDATE (no delete), so child rows survive. */
    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE notes SET body = :body, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: String, body: String, updatedAt: Long)

    @Query("UPDATE notes SET pageMode = :mode WHERE id = :id")
    suspend fun setPageMode(id: String, mode: String)

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun moveToFolder(noteId: String, folderId: String?, updatedAt: Long)

    @Query("UPDATE notes SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE notes SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE notes SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: String)

    @Query("UPDATE notes SET deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: String, now: Long)

    @Query("UPDATE notes SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteForever(id: String)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTrashedBefore(cutoff: Long)
}
