package com.eliosnotes.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eliosnotes.app.data.document.NoteElementDao
import com.eliosnotes.app.data.document.NoteElementEntity
import com.eliosnotes.app.data.document.NotePageDao
import com.eliosnotes.app.data.document.NotePageEntity

@Database(
    entities = [NoteEntity::class, NotePageEntity::class, NoteElementEntity::class, FolderEntity::class],
    version = 4,
    exportSchema = true
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun notePageDao(): NotePageDao
    abstract fun noteElementDao(): NoteElementDao

    companion object {
        /** Adds a column only when an old install does not already have it. */
        private fun ensureColumn(db: SupportSQLiteDatabase, table: String, column: String, ddl: String) {
            var exists = false
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) if (c.getString(nameIdx) == column) exists = true
            }
            if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $ddl")
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumn(db, "notes", "isPinned", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, "notes", "isArchived", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, "notes", "isFavorite", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, "notes", "pageMode", "TEXT NOT NULL DEFAULT 'PAGED'")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `note_pages` (
                        `id` TEXT NOT NULL,
                        `noteId` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `widthDp` REAL NOT NULL,
                        `heightDp` REAL NOT NULL,
                        `orientation` TEXT NOT NULL,
                        `template` TEXT NOT NULL,
                        `backgroundColorArgb` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_pages_noteId` ON `note_pages` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_pages_noteId_pageIndex` ON `note_pages` (`noteId`, `pageIndex`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `note_elements` (
                        `id` TEXT NOT NULL,
                        `pageId` TEXT NOT NULL,
                        `elementType` TEXT NOT NULL,
                        `payloadVersion` INTEGER NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `x` REAL NOT NULL,
                        `y` REAL NOT NULL,
                        `width` REAL NOT NULL,
                        `height` REAL NOT NULL,
                        `rotationDegrees` REAL NOT NULL,
                        `zIndex` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`pageId`) REFERENCES `note_pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_elements_pageId` ON `note_elements` (`pageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_elements_pageId_zIndex` ON `note_elements` (`pageId`, `zIndex`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumn(db, "notes", "folderId", "TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_folderId ON notes(folderId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )""".trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumn(db, "notes", "deletedAt", "INTEGER")
            }
        }

        @Volatile private var INSTANCE: NotesDatabase? = null

        fun getInstance(context: Context): NotesDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "elios_notes.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
