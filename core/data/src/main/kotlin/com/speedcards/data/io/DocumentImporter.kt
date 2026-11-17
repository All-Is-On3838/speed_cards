package com.speedcards.data.io

import androidx.room.withTransaction
import com.speedcards.data.csv.ImportReport
import com.speedcards.data.csv.RowFailure
import com.speedcards.data.db.Deck
import com.speedcards.data.db.ImportBatch
import com.speedcards.data.db.LinkType
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.template.TextNormalizer
import com.speedcards.scheduler.CardState
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** One card's carried-over schedule (from Anki). */
@Serializable
data class ImportCardSchedule(
    val ord: Int,
    val state: CardState,
    val due: Long,
    val intervalDays: Int,
    val ease: Double,
    val reps: Int,
    val lapses: Int,
    val suspended: Boolean = false,
)

@Serializable
data class ImportLink(val type: String, val target: String)

@Serializable
data class ImportNote(
    /** Stable id; matching notes are updated instead of duplicated. */
    val id: String? = null,
    /** Deck path, levels separated by "::" (Anki style). */
    val deck: String,
    val type: String,
    val fields: Map<String, String>,
    val tags: List<String> = emptyList(),
    val links: List<ImportLink> = emptyList(),
    val cards: List<ImportCardSchedule> = emptyList(),
    /** Source line or row, for error messages. */
    val line: Int = 0,
)

@Serializable
data class DeckSettings(
    val languageFront: String? = null,
    val languageBack: String? = null,
    val builtIn: Boolean = false,
)

/** Format-neutral import: produced by the .sctxt parser, the .apkg reader and the starter decks. */
@Serializable
data class ImportDocument(
    val notes: List<ImportNote>,
    /** Settings for decks by path, applied when the deck is created (and to built-in flags always). */
    val decks: Map<String, DeckSettings> = emptyMap(),
)

data class DocumentImportOptions(
    val sourceName: String,
    /** Apply carried-over schedules to newly created notes. */
    val keepScheduling: Boolean = true,
    val allowDuplicates: Boolean = false,
    /** Notes with ids that already exist are left alone instead of updated (used by starter-deck seeding). */
    val skipExisting: Boolean = false,
    /** Undo batch to record created notes in; null creates a new batch. */
    val batchId: Long? = null,
    /** New notes whose first field has Chinese characters don't read their front aloud (starter decks). */
    val muteChineseFronts: Boolean = false,
)

data class DocumentImportReport(
    val base: ImportReport,
    val linksCreated: Int,
    val decksCreated: Int,
)

private val hanRegex = Regex("\\p{IsHan}")

@Singleton
class DocumentImporter @Inject constructor(
    private val db: SpeedCardsDatabase,
    private val notes: NoteRepository,
    private val links: LinkRepository,
) {
    suspend fun import(doc: ImportDocument, options: DocumentImportOptions): DocumentImportReport = db.withTransaction {
        val batchId = options.batchId ?: db.importBatchDao().insert(ImportBatch(source = options.sourceName))
        val failures = mutableListOf<RowFailure>()
        val duplicates = mutableListOf<Int>()
        var created = 0
        var updated = 0
        val deckResolver = DeckPathResolver(db, doc.decks)
        val types = notes.noteTypes().associateBy { it.name.lowercase() }
        val noteIdsByImportId = mutableMapOf<String, Long>()
        val importedNoteIds = mutableListOf<Pair<ImportNote, Long>>()

        for (n in doc.notes) {
            val type = types[n.type.lowercase()]
            if (type == null) { failures += RowFailure(n.line, "unknown note type \"${n.type}\""); continue }
            val byName = n.fields.mapKeys { it.key.lowercase() }
            val unknown = n.fields.keys.filter { k -> type.fields.none { it.name.equals(k, ignoreCase = true) } }
            if (unknown.isNotEmpty()) failures += RowFailure(n.line, "ignored unknown field(s): ${unknown.joinToString()}")
            val values = type.fields.map { byName[it.name.lowercase()].orEmpty() }
            if (values.first().isBlank()) { failures += RowFailure(n.line, "first field (${type.fields.first().name}) is empty"); continue }
            val deckId = deckResolver.idFor(n.deck)

            val existing = n.id?.let { db.noteDao().findByGuid(it) }
            when {
                existing != null && existing.noteTypeId != type.id ->
                    failures += RowFailure(n.line, "id \"${n.id}\" belongs to a note of a different type")
                existing != null -> {
                    if (!options.skipExisting) {
                        notes.updateNote(existing.id, deckId, values, n.tags)
                        updated++
                    }
                    importedNoteIds += n to existing.id
                }
                !options.allowDuplicates &&
                    db.noteDao().findDuplicates(type.id, TextNormalizer.sortKey(values.first())).isNotEmpty() ->
                    duplicates += n.line
                else -> {
                    val readFront = !(options.muteChineseFronts && hanRegex.containsMatchIn(values.first()))
                    val id = notes.addNote(type, deckId, values, n.tags, guid = n.id, importBatchId = batchId, readFront = readFront)
                    if (options.keepScheduling) applySchedules(id, n.cards)
                    created++
                    importedNoteIds += n to id
                }
            }
        }
        importedNoteIds.forEach { (n, id) -> n.id?.let { noteIdsByImportId[it] = id } }

        // Second pass: links can point at notes later in the file, by id or by front text.
        var linksCreated = 0
        val linkTypes = links.types().associateBy { it.name.lowercase() }.toMutableMap()
        for ((n, noteId) in importedNoteIds) for (l in n.links) {
            val type = linkTypes.getOrPut(l.type.lowercase()) {
                val t = LinkType(name = l.type.trim())
                t.copy(id = links.saveType(t))
            }
            val target = noteIdsByImportId[l.target]
                ?: db.noteDao().findByGuid(l.target)?.id
                ?: db.noteDao().findBySortField(TextNormalizer.sortKey(l.target)).firstOrNull()?.id
            if (target == null) { failures += RowFailure(n.line, "link target \"${l.target}\" not found"); continue }
            if (target == noteId) continue
            links.addLink(noteId, target, type)
            linksCreated++
        }

        db.importBatchDao().get(batchId)?.let {
            db.importBatchDao().update(it.copy(createdCount = it.createdCount + created, updatedCount = it.updatedCount + updated))
        }
        DocumentImportReport(ImportReport(batchId, created, updated, duplicates, failures), linksCreated, deckResolver.created)
    }

    private suspend fun applySchedules(noteId: Long, schedules: List<ImportCardSchedule>) {
        if (schedules.isEmpty()) return
        val byOrd = schedules.associateBy { it.ord }
        db.cardDao().forNote(noteId).forEach { card ->
            val s = byOrd[card.ord] ?: return@forEach
            db.cardDao().update(
                card.copy(
                    state = s.state, due = s.due, intervalDays = s.intervalDays, ease = s.ease,
                    reps = s.reps, lapses = s.lapses, suspended = s.suspended, stepIndex = 0,
                )
            )
        }
    }
}

/** Finds or creates nested decks from "A::B::C" paths. */
class DeckPathResolver(private val db: SpeedCardsDatabase, private val settings: Map<String, DeckSettings>) {
    private val cache = mutableMapOf<String, Long>()
    var created = 0
        private set

    suspend fun idFor(path: String): Long {
        val clean = normalize(path)
        cache[clean]?.let { return it }
        val parts = clean.split("::")
        var parentId: Long? = null
        var soFar = ""
        for (part in parts) {
            soFar = if (soFar.isEmpty()) part else "$soFar::$part"
            val s = settings.entries.firstOrNull { normalize(it.key) == soFar }?.value
            val existing = db.deckDao().findByName(part, parentId)
            parentId = if (existing != null) {
                if (s?.builtIn == true && !existing.builtIn) db.deckDao().update(existing.copy(builtIn = true))
                existing.id
            } else {
                created++
                val presetId = parentId?.let { db.deckDao().get(it)?.presetId } ?: db.presetDao().getAll().first().id
                val parent = parentId?.let { db.deckDao().get(it) }
                db.deckDao().insert(
                    Deck(
                        name = part,
                        parentId = parentId,
                        presetId = presetId,
                        languageFront = s?.languageFront ?: parent?.languageFront,
                        languageBack = s?.languageBack ?: parent?.languageBack,
                        builtIn = s?.builtIn ?: false,
                    )
                )
            }
            cache[soFar] = parentId!!
        }
        return parentId!!
    }

    companion object {
        /** Accepts "A::B", "A › B" and "A/B"-free names; trims each level. */
        fun normalize(path: String): String =
            path.split("::", " › ").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("::").ifEmpty { "Default" }
    }
}
