package com.speedcards.data.repo

import androidx.room.withTransaction
import com.speedcards.data.db.Card
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Folder
import com.speedcards.data.db.Note
import com.speedcards.data.db.NoteType
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.db.Tag
import com.speedcards.data.db.NoteTag
import com.speedcards.data.template.TextNormalizer
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Creates the built-in presets, note types, link types, Inbox and a starting deck. Idempotent. */
@Singleton
class CollectionInitializer @Inject constructor(private val db: SpeedCardsDatabase) {
    suspend fun ensureDefaults() = db.withTransaction {
        val presetDao = db.presetDao()
        if (presetDao.getAll().isEmpty()) Defaults.presets.forEach { presetDao.insert(it) }
        val defaultPresetId = presetDao.getAll().first().id

        val typeDao = db.noteTypeDao()
        (Defaults.noteTypes + Defaults.starterNoteTypes).forEach { if (typeDao.findByName(it.name) == null) typeDao.insert(it) }

        // Add any built-in link types this install doesn't have yet (new ones arrive with app updates).
        val linkDao = db.linkDao()
        val existingLinkTypes = linkDao.allTypes().map { it.name }.toSet()
        Defaults.linkTypes.filter { it.name !in existingLinkTypes }.forEach { linkDao.insertType(it) }

        val deckDao = db.deckDao()
        if (deckDao.getInbox() == null) {
            deckDao.insert(Deck(name = Defaults.INBOX_DECK, presetId = defaultPresetId, builtIn = true, kind = DeckKind.INBOX))
        }
        if (deckDao.getAll().none { it.kind == DeckKind.NORMAL }) {
            deckDao.insert(Deck(name = Defaults.DEFAULT_DECK, presetId = defaultPresetId))
        }
    }
}

class DeckDeletionException(message: String) : Exception(message)

@Singleton
class DeckRepository @Inject constructor(private val db: SpeedCardsDatabase) {
    private val dao get() = db.deckDao()

    fun observeDecks(): Flow<List<Deck>> = dao.observeAll()
    suspend fun all(): List<Deck> = dao.getAll()
    suspend fun get(id: Long): Deck? = dao.get(id)
    suspend fun inbox(): Deck = dao.getInbox() ?: error("Inbox missing; call ensureDefaults()")
    suspend fun noteCount(deckId: Long): Int = db.noteDao().countInDecks(listOf(deckId))

    suspend fun create(
        name: String,
        parentId: Long?,
        presetId: Long,
        languageFront: String?,
        languageBack: String?,
        folderId: Long? = null,
    ): Long = dao.insert(
        Deck(
            name = name.trim(), parentId = parentId, presetId = presetId, languageFront = languageFront, languageBack = languageBack,
            // Only top-level decks are filed; sub-decks live wherever their top-level deck is.
            folderId = folderId.takeIf { parentId == null },
        )
    )

    suspend fun update(deck: Deck) = dao.update(deck.copy(name = deck.name.trim()))

    /** Deck plus all of its descendants. */
    suspend fun subtreeIds(deckId: Long): List<Long> = subtree(dao.getAll(), deckId)

    /** Deletes a deck, its sub-decks and their notes. Built-in decks (and decks containing them) are protected. */
    suspend fun delete(deckId: Long) = db.withTransaction {
        val decks = dao.getAll()
        val ids = subtree(decks, deckId)
        val protected = decks.filter { it.id in ids && it.builtIn }
        if (protected.isNotEmpty()) {
            throw DeckDeletionException("\"${protected.first().name}\" is built in and can't be deleted.")
        }
        // Children first so the parent reference never dangles.
        ids.reversed().forEach { id -> decks.first { it.id == id }.let { dao.delete(it) } }
    }

    companion object {
        fun subtree(decks: List<Deck>, rootId: Long): List<Long> {
            val children = decks.groupBy { it.parentId }
            val out = mutableListOf<Long>()
            fun walk(id: Long) { out += id; children[id].orEmpty().forEach { walk(it.id) } }
            walk(rootId)
            return out
        }

        /** "Parent › Child" display name. */
        fun fullName(decks: List<Deck>, deck: Deck): String {
            val byId = decks.associateBy { it.id }
            val parts = generateSequence(deck) { d -> d.parentId?.let { byId[it] } }.map { it.name }.toList()
            return parts.reversed().joinToString(" › ")
        }
    }
}

class FolderDeletionException(message: String) : Exception(message)

@Singleton
class FolderRepository @Inject constructor(private val db: SpeedCardsDatabase) {
    private val dao get() = db.folderDao()

    fun observeAll(): Flow<List<Folder>> = dao.observeAll()
    suspend fun all(): List<Folder> = dao.getAll()
    suspend fun get(id: Long): Folder? = dao.get(id)

    suspend fun create(name: String): Long = dao.insert(Folder(name = name.trim()))

    suspend fun rename(folder: Folder, name: String) {
        if (name.isNotBlank()) dao.update(folder.copy(name = name.trim()))
    }

    /** The folder with this name (ignoring case), created if there isn't one. */
    suspend fun findOrCreate(name: String): Long = dao.findByName(name.trim())?.id ?: create(name)

    /** Only empty folders can be deleted, so no deck is ever lost or silently moved. */
    suspend fun delete(folder: Folder) = db.withTransaction {
        if (dao.deckCount(folder.id) > 0) throw FolderDeletionException("Move or delete the decks in \"${folder.name}\" first.")
        dao.delete(folder)
    }
}

@Singleton
class PresetRepository @Inject constructor(private val db: SpeedCardsDatabase) {
    fun observeAll(): Flow<List<SchedulingPreset>> = db.presetDao().observeAll()
    suspend fun all() = db.presetDao().getAll()
    suspend fun get(id: Long) = db.presetDao().get(id)
    suspend fun save(preset: SchedulingPreset): Long =
        if (preset.id == 0L) db.presetDao().insert(preset) else { db.presetDao().update(preset); preset.id }

    /** Returns false when the preset is still used by a deck or is the last preset. */
    suspend fun delete(preset: SchedulingPreset): Boolean {
        if (db.presetDao().deckCount(preset.id) > 0 || db.presetDao().getAll().size <= 1) return false
        db.presetDao().delete(preset)
        return true
    }
}

data class NoteWithCards(val note: Note, val type: NoteType, val cards: List<Card>, val tags: List<String>)

@Singleton
class NoteRepository @Inject constructor(private val db: SpeedCardsDatabase) {

    fun observeNoteTypes(): Flow<List<NoteType>> = db.noteTypeDao().observeAll()
    suspend fun noteTypes(): List<NoteType> = db.noteTypeDao().getAll()
    suspend fun noteType(id: Long): NoteType? = db.noteTypeDao().get(id)
    suspend fun noteTypeByName(name: String): NoteType? = db.noteTypeDao().findByName(name)

    fun search(query: String, deckId: Long?) = db.noteDao().search(query.trim(), deckId)

    suspend fun get(noteId: Long): NoteWithCards? {
        val note = db.noteDao().get(noteId) ?: return null
        val type = db.noteTypeDao().get(note.noteTypeId) ?: return null
        return NoteWithCards(note, type, db.cardDao().forNote(noteId), db.tagDao().namesForNote(noteId))
    }

    /** Notes with the same first field (any deck), for duplicate warnings. */
    suspend fun findDuplicates(firstField: String, excludeNoteId: Long? = null): List<Note> {
        val key = TextNormalizer.sortKey(firstField)
        if (key.isEmpty()) return emptyList()
        return db.noteDao().findBySortField(key).filter { it.id != excludeNoteId }
    }

    suspend fun addNote(
        type: NoteType,
        deckId: Long,
        fields: List<String>,
        tags: List<String> = emptyList(),
        guid: String? = null,
        importBatchId: Long? = null,
        readFront: Boolean = true,
    ): Long = db.withTransaction {
        val normalized = normalizeFields(type, fields)
        val noteId = db.noteDao().insert(
            Note(
                guid = guid ?: UUID.randomUUID().toString(),
                noteTypeId = type.id,
                deckId = deckId,
                fields = normalized,
                sortField = TextNormalizer.sortKey(normalized.firstOrNull().orEmpty()),
                importBatchId = importBatchId,
                readFront = readFront,
            )
        )
        syncCards(noteId, deckId, type, normalized)
        setTags(noteId, tags)
        noteId
    }

    /** Updates fields/deck/tags and adds or removes cards to match. Existing cards keep their history. */
    suspend fun updateNote(
        noteId: Long,
        deckId: Long,
        fields: List<String>,
        tags: List<String>? = null,
        readFront: Boolean? = null,
    ) = db.withTransaction {
        val note = db.noteDao().get(noteId) ?: return@withTransaction
        val type = db.noteTypeDao().get(note.noteTypeId) ?: return@withTransaction
        val normalized = normalizeFields(type, fields)
        db.noteDao().update(
            note.copy(
                deckId = deckId,
                fields = normalized,
                sortField = TextNormalizer.sortKey(normalized.firstOrNull().orEmpty()),
                modified = System.currentTimeMillis(),
                readFront = readFront ?: note.readFront,
            )
        )
        if (deckId != note.deckId) db.cardDao().moveNoteCards(noteId, deckId)
        syncCards(noteId, deckId, type, normalized)
        if (tags != null) setTags(noteId, tags)
    }

    suspend fun deleteNote(noteId: Long) = db.noteDao().delete(noteId)

    private suspend fun syncCards(noteId: Long, deckId: Long, type: NoteType, fields: List<String>) {
        val wanted = CardGenerator.ordsFor(type, fields)
        val existing = db.cardDao().forNote(noteId)
        val existingOrds = existing.map { it.ord }.toSet()
        val toAdd = (wanted - existingOrds).sorted().map { Card(noteId = noteId, deckId = deckId, ord = it) }
        if (toAdd.isNotEmpty()) db.cardDao().insertAll(toAdd)
        // Only delete cards that were never studied; studied cards are kept so history isn't lost.
        val toDelete = existing.filter { it.ord !in wanted && it.reps == 0 }.map { it.id }
        if (toDelete.isNotEmpty()) db.cardDao().deleteIds(toDelete)
    }

    private suspend fun setTags(noteId: Long, tags: List<String>) {
        val tagDao = db.tagDao()
        tagDao.clearNote(noteId)
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            val tagId = tagDao.find(name)?.id ?: tagDao.insert(Tag(name = name))
            tagDao.link(NoteTag(noteId, tagId))
        }
    }

    private fun normalizeFields(type: NoteType, fields: List<String>): List<String> =
        type.fields.indices.map { fields.getOrElse(it) { "" }.trim() }
}
