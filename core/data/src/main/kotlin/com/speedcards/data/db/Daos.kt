package com.speedcards.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Folder>>

    @Query("SELECT * FROM folder ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Folder>

    @Query("SELECT * FROM folder WHERE id = :id")
    suspend fun get(id: Long): Folder?

    @Query("SELECT * FROM folder WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Folder?

    @Insert suspend fun insert(folder: Folder): Long
    @Update suspend fun update(folder: Folder)
    @Delete suspend fun delete(folder: Folder)

    @Query("SELECT COUNT(*) FROM deck WHERE folderId = :folderId")
    suspend fun deckCount(folderId: Long): Int
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM deck ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Deck>>

    @Query("SELECT * FROM deck ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Deck>

    @Query("SELECT * FROM deck WHERE id = :id")
    suspend fun get(id: Long): Deck?

    @Query("SELECT * FROM deck WHERE kind = 'INBOX' LIMIT 1")
    suspend fun getInbox(): Deck?

    @Query("SELECT * FROM deck WHERE name = :name AND parentId IS :parentId LIMIT 1")
    suspend fun findByName(name: String, parentId: Long?): Deck?

    @Insert suspend fun insert(deck: Deck): Long
    @Update suspend fun update(deck: Deck)
    @Delete suspend fun delete(deck: Deck)

    @Query("SELECT COUNT(*) FROM deck")
    suspend fun count(): Int
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM scheduling_preset ORDER BY id")
    fun observeAll(): Flow<List<SchedulingPreset>>

    @Query("SELECT * FROM scheduling_preset ORDER BY id")
    suspend fun getAll(): List<SchedulingPreset>

    @Query("SELECT * FROM scheduling_preset WHERE id = :id")
    suspend fun get(id: Long): SchedulingPreset?

    @Insert suspend fun insert(preset: SchedulingPreset): Long
    @Update suspend fun update(preset: SchedulingPreset)
    @Delete suspend fun delete(preset: SchedulingPreset)

    @Query("SELECT COUNT(*) FROM deck WHERE presetId = :presetId")
    suspend fun deckCount(presetId: Long): Int
}

@Dao
interface NoteTypeDao {
    @Query("SELECT * FROM note_type ORDER BY id")
    fun observeAll(): Flow<List<NoteType>>

    @Query("SELECT * FROM note_type ORDER BY id")
    suspend fun getAll(): List<NoteType>

    @Query("SELECT * FROM note_type WHERE id = :id")
    suspend fun get(id: Long): NoteType?

    @Query("SELECT * FROM note_type WHERE name = :name")
    suspend fun findByName(name: String): NoteType?

    @Insert suspend fun insert(type: NoteType): Long
    @Update suspend fun update(type: NoteType)
}

data class NoteRow(
    val id: Long,
    val noteTypeId: Long,
    val deckId: Long,
    val fields: List<String>,
    val deckName: String,
    val modified: Long,
)

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: Note): Long
    @Update suspend fun update(note: Note)

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM note WHERE guid = :guid")
    suspend fun findByGuid(guid: String): Note?

    @Query("SELECT * FROM note WHERE noteTypeId = :noteTypeId AND sortField = :sortField")
    suspend fun findDuplicates(noteTypeId: Long, sortField: String): List<Note>

    @Query("SELECT * FROM note WHERE sortField = :sortField")
    suspend fun findBySortField(sortField: String): List<Note>

    @Query(
        """
        SELECT n.id, n.noteTypeId, n.deckId, n.fields, d.name AS deckName, n.modified
        FROM note n JOIN deck d ON d.id = n.deckId
        WHERE (:deckId IS NULL OR n.deckId = :deckId)
          AND (:query = '' OR n.fields LIKE '%' || :query || '%')
        ORDER BY n.modified DESC
        LIMIT 500
        """
    )
    fun search(query: String, deckId: Long?): Flow<List<NoteRow>>

    @Query("SELECT id FROM note WHERE importBatchId = :batchId")
    suspend fun idsForBatch(batchId: Long): List<Long>

    @Query("DELETE FROM note WHERE importBatchId = :batchId")
    suspend fun deleteBatch(batchId: Long): Int

    @Query("SELECT COUNT(*) FROM note WHERE deckId = :deckId")
    fun observeCountInDeck(deckId: Long): Flow<Int>

    @Query("SELECT * FROM note WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<Note>

    @Query(
        """
        SELECT n.id, n.noteTypeId, n.deckId, n.fields, d.name AS deckName, n.modified
        FROM note n JOIN deck d ON d.id = n.deckId
        WHERE n.fields LIKE '%' || :query || '%'
        ORDER BY n.sortField LIMIT :limit
        """
    )
    suspend fun searchOnce(query: String, limit: Int): List<NoteRow>

    @Query("SELECT COUNT(*) FROM note WHERE deckId IN (:deckIds)")
    suspend fun countInDecks(deckIds: List<Long>): Int

    @Query("SELECT id FROM note WHERE deckId IN (:deckIds)")
    suspend fun idsInDecks(deckIds: List<Long>): List<Long>
}

/** Raw per-deck card counts; daily limits are applied in the repository. */
data class DeckCardCounts(
    val deckId: Long,
    val newCount: Int,
    val learningDue: Int,
    val reviewDue: Int,
)

@Dao
interface CardDao {
    @Insert suspend fun insert(card: Card): Long
    @Insert suspend fun insertAll(cards: List<Card>): List<Long>
    @Update suspend fun update(card: Card)

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun get(id: Long): Card?

    @Query("SELECT * FROM card WHERE noteId = :noteId ORDER BY ord")
    suspend fun forNote(noteId: Long): List<Card>

    @Query("SELECT * FROM card WHERE noteId IN (:noteIds) AND suspended = 0 ORDER BY noteId, ord")
    suspend fun forNotes(noteIds: List<Long>): List<Card>

    @Query("SELECT * FROM card WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<Card>

    @Query("SELECT * FROM card WHERE deckId IN (:deckIds)")
    suspend fun inDecks(deckIds: List<Long>): List<Card>

    @Query(
        """
        SELECT * FROM card
        WHERE deckId IN (:deckIds) AND state = 2 AND due <= :until
          AND suspended = 0 AND buriedUntil <= :now
        ORDER BY due, id LIMIT :limit
        """
    )
    suspend fun reviewsDueBy(deckIds: List<Long>, until: Long, now: Long, limit: Int): List<Card>

    @Query(
        """
        SELECT COUNT(*) FROM card
        WHERE deckId IN (:deckIds) AND state = 2 AND due <= :until AND suspended = 0 AND buriedUntil <= :now
        """
    )
    suspend fun countReviewsDueBy(deckIds: List<Long>, until: Long, now: Long): Int

    @Query("DELETE FROM card WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE card SET deckId = :deckId WHERE noteId = :noteId")
    suspend fun moveNoteCards(noteId: Long, deckId: Long)

    @Query(
        """
        SELECT * FROM card
        WHERE deckId IN (:deckIds) AND state IN (1, 3) AND due <= :cutoff
          AND suspended = 0 AND buriedUntil <= :now
        ORDER BY due LIMIT :limit
        """
    )
    suspend fun nextLearning(deckIds: List<Long>, cutoff: Long, now: Long, limit: Int): List<Card>

    @Query(
        """
        SELECT * FROM card
        WHERE deckId IN (:deckIds) AND state = 2 AND due <= :endOfDay
          AND suspended = 0 AND buriedUntil <= :now
        ORDER BY due, id LIMIT :limit
        """
    )
    suspend fun nextReview(deckIds: List<Long>, endOfDay: Long, now: Long, limit: Int): List<Card>

    @Query(
        """
        SELECT * FROM card
        WHERE deckId IN (:deckIds) AND state = 0 AND suspended = 0 AND buriedUntil <= :now
        ORDER BY noteId, ord LIMIT :limit
        """
    )
    suspend fun nextNew(deckIds: List<Long>, now: Long, limit: Int): List<Card>

    @Query(
        """
        SELECT MIN(due) FROM card
        WHERE deckId IN (:deckIds) AND state IN (1, 3) AND suspended = 0 AND buriedUntil <= :now
        """
    )
    suspend fun nextLearningDueTime(deckIds: List<Long>, now: Long): Long?

    @Query(
        """
        SELECT deckId,
          SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END) AS newCount,
          SUM(CASE WHEN state IN (1, 3) AND due <= :endOfDay THEN 1 ELSE 0 END) AS learningDue,
          SUM(CASE WHEN state = 2 AND due <= :endOfDay THEN 1 ELSE 0 END) AS reviewDue
        FROM card
        WHERE suspended = 0 AND buriedUntil <= :now
        GROUP BY deckId
        """
    )
    fun observeCounts(now: Long, endOfDay: Long): Flow<List<DeckCardCounts>>

    @Query(
        """
        SELECT deckId,
          SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END) AS newCount,
          SUM(CASE WHEN state IN (1, 3) AND due <= :endOfDay THEN 1 ELSE 0 END) AS learningDue,
          SUM(CASE WHEN state = 2 AND due <= :endOfDay THEN 1 ELSE 0 END) AS reviewDue
        FROM card
        WHERE suspended = 0 AND buriedUntil <= :now AND deckId IN (:deckIds)
        GROUP BY deckId
        """
    )
    suspend fun counts(deckIds: List<Long>, now: Long, endOfDay: Long): List<DeckCardCounts>

    /**
     * Puts every card in these decks back to new: no interval, ease, reps, lapses, leech flag or burying.
     * Suspended cards stay suspended. Returns how many cards were reset.
     */
    @Query(
        """
        UPDATE card SET state = 0, due = 0, intervalDays = 0, ease = 0, reps = 0, lapses = 0,
          stepIndex = 0, leech = 0, buriedUntil = 0
        WHERE deckId IN (:deckIds)
        """
    )
    suspend fun resetDecks(deckIds: List<Long>): Int

    /** Learning/relearning cards that are due right now (not later today). */
    @Query(
        """
        SELECT COUNT(*) FROM card
        WHERE deckId IN (:deckIds) AND state IN (1, 3) AND due <= :now AND suspended = 0 AND buriedUntil <= :now
        """
    )
    suspend fun countLearningDueNow(deckIds: List<Long>, now: Long): Int
}

@Dao
interface ReviewLogDao {
    @Insert suspend fun insert(log: ReviewLog): Long

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun delete(id: Long)

    /** Deletes the review history of every card in these decks. */
    @Query("DELETE FROM review_log WHERE cardId IN (SELECT id FROM card WHERE deckId IN (:deckIds))")
    suspend fun deleteForDecks(deckIds: List<Long>): Int

    /** New cards introduced since [since] in the given decks. */
    @Query(
        """
        SELECT COUNT(*) FROM review_log r JOIN card c ON c.id = r.cardId
        WHERE r.timestamp >= :since AND r.prevState = 0 AND c.deckId IN (:deckIds)
        """
    )
    suspend fun newIntroducedSince(deckIds: List<Long>, since: Long): Int

    /** Review-state answers since [since] in the given decks. */
    @Query(
        """
        SELECT COUNT(*) FROM review_log r JOIN card c ON c.id = r.cardId
        WHERE r.timestamp >= :since AND r.prevState = 2 AND c.deckId IN (:deckIds)
        """
    )
    suspend fun reviewsSince(deckIds: List<Long>, since: Long): Int

    @Query("SELECT COUNT(*) FROM review_log WHERE timestamp >= :since")
    fun observeAnswersSince(since: Long): Flow<Int>

    @Query(
        """
        SELECT r.* FROM review_log r JOIN card c ON c.id = r.cardId
        WHERE r.timestamp >= :since AND c.deckId IN (:deckIds) ORDER BY r.timestamp
        """
    )
    suspend fun since(deckIds: List<Long>, since: Long): List<ReviewLog>

    @Query("SELECT timestamp FROM review_log WHERE timestamp >= :since ORDER BY timestamp")
    suspend fun timestampsSince(since: Long): List<Long>

    /** Cards answered "Again" since [since] (for practising what you forgot). */
    @Query(
        """
        SELECT DISTINCT r.cardId FROM review_log r JOIN card c ON c.id = r.cardId
        WHERE r.rating = 0 AND r.timestamp >= :since AND c.deckId IN (:deckIds) AND c.suspended = 0
        """
    )
    suspend fun forgottenCardIds(deckIds: List<Long>, since: Long): List<Long>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(tag: Tag): Long
    @Query("SELECT * FROM tag WHERE name = :name") suspend fun find(name: String): Tag?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(noteTag: NoteTag)
    @Query("DELETE FROM note_tag WHERE noteId = :noteId") suspend fun clearNote(noteId: Long)

    @Query("SELECT t.name FROM tag t JOIN note_tag nt ON nt.tagId = t.id WHERE nt.noteId = :noteId ORDER BY t.name")
    suspend fun namesForNote(noteId: Long): List<String>

    @Query(
        """
        SELECT DISTINCT t.name FROM tag t JOIN note_tag nt ON nt.tagId = t.id JOIN note n ON n.id = nt.noteId
        WHERE n.deckId IN (:deckIds) ORDER BY t.name COLLATE NOCASE
        """
    )
    suspend fun namesInDecks(deckIds: List<Long>): List<String>

    @Query(
        """
        SELECT n.id FROM note n JOIN note_tag nt ON nt.noteId = n.id JOIN tag t ON t.id = nt.tagId
        WHERE t.name = :tag AND n.deckId IN (:deckIds)
        """
    )
    suspend fun noteIdsWithTag(tag: String, deckIds: List<Long>): List<Long>
}

@Dao
interface LinkDao {
    @Query("SELECT * FROM link_type ORDER BY id") suspend fun allTypes(): List<LinkType>
    @Query("SELECT * FROM link_type ORDER BY id") fun observeTypes(): Flow<List<LinkType>>
    @Insert suspend fun insertType(type: LinkType): Long
    @Update suspend fun updateType(type: LinkType)
    @Delete suspend fun deleteType(type: LinkType)
    @Query("SELECT COUNT(*) FROM link_type") suspend fun typeCount(): Int
    @Query("SELECT COUNT(*) FROM note_link WHERE linkTypeId = :typeId") suspend fun usageCount(typeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(link: NoteLink)

    @Query("DELETE FROM note_link WHERE fromNoteId = :from AND toNoteId = :to AND linkTypeId = :typeId")
    suspend fun delete(from: Long, to: Long, typeId: Long)

    @Query("SELECT * FROM note_link WHERE fromNoteId IN (:noteIds) OR toNoteId IN (:noteIds)")
    suspend fun touching(noteIds: List<Long>): List<NoteLink>

    @Query("SELECT * FROM note_link WHERE fromNoteId = :noteId OR toNoteId = :noteId ORDER BY created")
    fun observeTouching(noteId: Long): Flow<List<NoteLink>>
}

@Dao
interface ImportBatchDao {
    @Insert suspend fun insert(batch: ImportBatch): Long
    @Update suspend fun update(batch: ImportBatch)
    @Query("SELECT * FROM import_batch WHERE id = :id") suspend fun get(id: Long): ImportBatch?
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(media: Media): Long
    @Query("SELECT * FROM media WHERE hash = :hash") suspend fun findByHash(hash: String): Media?
    @Query("SELECT * FROM media WHERE fileName = :name") suspend fun findByName(name: String): Media?
    @Query("SELECT * FROM media") suspend fun all(): List<Media>
    @Query("DELETE FROM media WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)

    /** Raw JSON of every note's fields, scanned for media references. */
    @Query("SELECT fields FROM note") suspend fun allNoteFieldsJson(): List<String>

    @Insert suspend fun insertRecording(recording: UserRecording): Long
    @Query("DELETE FROM user_recording WHERE id = :id") suspend fun deleteRecording(id: Long)

    @Query(
        """
        SELECT r.id, r.cardId, r.timestamp, m.fileName, m.id AS mediaId FROM user_recording r
        JOIN media m ON m.id = r.mediaId WHERE r.cardId = :cardId ORDER BY r.timestamp DESC
        """
    )
    suspend fun recordingsForCard(cardId: Long): List<RecordingRow>

    @Query("SELECT mediaId FROM user_recording") suspend fun recordingMediaIds(): List<Long>
}

data class RecordingRow(val id: Long, val cardId: Long, val timestamp: Long, val fileName: String, val mediaId: Long)
