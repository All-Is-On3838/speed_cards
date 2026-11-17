package com.speedcards.data.csv

import androidx.room.withTransaction
import com.speedcards.data.db.ImportBatch
import com.speedcards.data.db.NoteType
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.io.MediaResolver
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.template.TextNormalizer
import javax.inject.Inject
import javax.inject.Singleton

data class CsvImportOptions(
    val noteType: NoteType,
    val deckId: Long,
    val hasHeader: Boolean,
    /** For each note-type field index, the CSV column that fills it (null = leave empty). */
    val fieldColumns: List<Int?>,
    /** Column of space-separated tags, if any. */
    val tagsColumn: Int? = null,
    /** Column holding a stable id; matching rows update existing notes instead of creating new ones. */
    val idColumn: Int? = null,
    /** Extra tags added to every imported note. */
    val extraTags: List<String> = emptyList(),
    /** When false, rows whose first field already exists (and have no id match) are skipped. */
    val allowDuplicates: Boolean = false,
)

data class RowFailure(val rowNumber: Int, val reason: String)

data class ImportReport(
    val batchId: Long,
    val created: Int,
    val updated: Int,
    val duplicates: List<Int>,
    val failures: List<RowFailure>,
)

@Singleton
class CsvImporter @Inject constructor(
    private val db: SpeedCardsDatabase,
    private val notes: NoteRepository,
) {
    /** Guesses which column fills each field by matching header names (case-insensitive), else by position. */
    fun guessMapping(type: NoteType, header: List<String>?, columnCount: Int): List<Int?> {
        val normalized = header?.map { it.trim().lowercase() }
        return type.fields.mapIndexed { i, f ->
            normalized?.indexOf(f.name.lowercase())?.takeIf { it >= 0 }
                ?: if (normalized == null && i < columnCount) i else null
        }.let { guessed ->
            // With a header but no name matches, fall back to positional mapping.
            if (header != null && guessed.all { it == null }) type.fields.indices.map { if (it < columnCount) it else null }
            else guessed
        }
    }

    suspend fun import(
        rows: List<List<String>>,
        options: CsvImportOptions,
        sourceName: String,
        media: MediaResolver? = null,
    ): ImportReport =
        db.withTransaction {
            val batchId = db.importBatchDao().insert(ImportBatch(source = sourceName))
            var created = 0
            var updated = 0
            val duplicates = mutableListOf<Int>()
            val failures = mutableListOf<RowFailure>()
            val dataRows = if (options.hasHeader) rows.drop(1) else rows
            val firstRowNumber = if (options.hasHeader) 2 else 1

            dataRows.forEachIndexed { i, row ->
                val rowNumber = firstRowNumber + i
                val fields = options.fieldColumns.map { col ->
                    col?.let { row.getOrNull(it) }.orEmpty().trim().let { v -> media?.resolve(v) ?: v }
                }
                if (fields.all { it.isBlank() }) {
                    failures += RowFailure(rowNumber, "empty row")
                    return@forEachIndexed
                }
                if (fields.first().isBlank()) {
                    failures += RowFailure(rowNumber, "first field (${options.noteType.fields.first().name}) is empty")
                    return@forEachIndexed
                }
                val tags = options.extraTags +
                    (options.tagsColumn?.let { row.getOrNull(it) }?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList())
                val id = options.idColumn?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

                val existing = id?.let { db.noteDao().findByGuid(it) }
                when {
                    existing != null && existing.noteTypeId != options.noteType.id ->
                        failures += RowFailure(rowNumber, "id \"$id\" belongs to a note of a different type")
                    existing != null -> {
                        notes.updateNote(existing.id, options.deckId, fields, tags)
                        updated++
                    }
                    !options.allowDuplicates &&
                        db.noteDao().findDuplicates(options.noteType.id, TextNormalizer.sortKey(fields.first())).isNotEmpty() ->
                        duplicates += rowNumber
                    else -> {
                        notes.addNote(options.noteType, options.deckId, fields, tags, guid = id, importBatchId = batchId)
                        created++
                    }
                }
            }
            db.importBatchDao().update(ImportBatch(batchId, sourceName, createdCount = created, updatedCount = updated))
            ImportReport(batchId, created, updated, duplicates, failures)
        }

    /** Deletes the notes the import created. Notes it updated are not reverted. */
    suspend fun undo(batchId: Long): Int = db.withTransaction {
        val deleted = db.noteDao().deleteBatch(batchId)
        db.importBatchDao().get(batchId)?.let { db.importBatchDao().update(it.copy(undone = true)) }
        deleted
    }
}
