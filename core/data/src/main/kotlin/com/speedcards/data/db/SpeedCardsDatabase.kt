package com.speedcards.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Deck::class, SchedulingPreset::class, NoteType::class, Note::class, Card::class,
        ReviewLog::class, LinkType::class, NoteLink::class, Tag::class, NoteTag::class,
        Media::class, UserRecording::class, ImportBatch::class, Folder::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpeedCardsDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao
    abstract fun folderDao(): FolderDao
    abstract fun presetDao(): PresetDao
    abstract fun noteTypeDao(): NoteTypeDao
    abstract fun noteDao(): NoteDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun tagDao(): TagDao
    abstract fun linkDao(): LinkDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun mediaDao(): MediaDao

    companion object {
        const val NAME = "speedcards.db"

        /** Adds Note.readFront; starter notes whose front is Chinese characters start muted. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE note ADD COLUMN readFront INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE note SET readFront = 0 WHERE guid LIKE 'seed-%' AND sortField GLOB '*[一-鿿]*'")
            }
        }

        /**
         * Adds folders. Starter decks are filed into theirs by StarterDecks at the next startup.
         * Each starter topic becomes its own top-level deck with its own daily limits, so the Vocabulary
         * preset's new cards per day drops from 20 to 5 (only if it was never changed).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `folder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `created` INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE deck ADD COLUMN folderId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deck_folderId` ON `deck` (`folderId`)")
                db.execSQL("UPDATE scheduling_preset SET newPerDay = 5 WHERE name = 'Vocabulary' AND newPerDay = 20")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
