package com.eliosnotes.app.data.document

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteElementDao {
    @Query("SELECT * FROM note_elements WHERE pageId = :pageId ORDER BY zIndex ASC, createdAt ASC")
    fun observeElements(pageId: String): Flow<List<NoteElementEntity>>

    @Query("SELECT * FROM note_elements WHERE pageId = :pageId ORDER BY zIndex ASC, createdAt ASC")
    suspend fun getElements(pageId: String): List<NoteElementEntity>

    @Query("SELECT id FROM note_elements WHERE pageId = :pageId AND elementType = :type")
    suspend fun getIdsByType(pageId: String, type: String): List<String>

    /** Text payloads of every page of a note, in page order (used to rebuild the search mirror). */
    @Query(
        """SELECT e.payloadJson FROM note_elements e
           INNER JOIN note_pages p ON e.pageId = p.id
           WHERE p.noteId = :noteId AND e.elementType = 'TEXT'
           ORDER BY p.pageIndex ASC"""
    )
    suspend fun getTextPayloads(noteId: String): List<String>

    @Upsert
    suspend fun upsert(element: NoteElementEntity)

    @Upsert
    suspend fun upsertAll(elements: List<NoteElementEntity>)

    @Query("DELETE FROM note_elements WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM note_elements WHERE pageId = :pageId")
    suspend fun deleteAllForPage(pageId: String)
}
