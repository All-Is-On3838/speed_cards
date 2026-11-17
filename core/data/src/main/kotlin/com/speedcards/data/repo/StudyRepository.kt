package com.speedcards.data.repo

import androidx.room.withTransaction
import com.speedcards.data.db.Card
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.FieldLanguage
import com.speedcards.data.db.NoteType
import com.speedcards.data.db.ReviewLog
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.template.RenderSettings
import com.speedcards.data.util.DayClock
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.Rating
import com.speedcards.scheduler.Scheduler
import com.speedcards.scheduler.SchedulingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class DueCounts(val new: Int, val learning: Int, val review: Int) {
    val total get() = new + learning + review
    operator fun plus(o: DueCounts) = DueCounts(new + o.new, learning + o.learning, review + o.review)

    companion object { val ZERO = DueCounts(0, 0, 0) }
}

data class StudyCard(
    val card: Card,
    val type: NoteType,
    val fields: List<String>,
    val deck: Deck,
    val question: String,
    val answer: String,
    /** Delay each available button would give, for labelling the buttons. */
    val buttonDelays: Map<Rating, Long>,
    val questionAudio: PlayableAudio = PlayableAudio.NONE,
    val answerAudio: PlayableAudio = PlayableAudio.NONE,
)

/** Media file names to play, or (when there are none) text-to-speech lines with BCP-47 language tags. */
data class PlayableAudio(val sounds: List<String>, val speech: List<SpokenLine>) {
    val isEmpty get() = sounds.isEmpty() && speech.isEmpty()
    companion object { val NONE = PlayableAudio(emptyList(), emptyList()) }
}

data class SpokenLine(val text: String, val languageTag: String)

sealed interface QueueResult {
    data class Next(val studyCard: StudyCard, val counts: DueCounts) : QueueResult
    /** Nothing left now; learning cards may come back at [nextDueAt]. */
    data class Finished(val nextDueAt: Long?) : QueueResult
}

/** What's needed to take back the last answer. */
data class UndoEntry(val cardBefore: Card, val logId: Long)

data class AnswerOutcome(val undo: UndoEntry, val becameLeech: Boolean)

@Singleton
class StudyRepository @Inject constructor(
    private val db: SpeedCardsDatabase,
    private val dayClock: DayClock,
    private val renderSettings: RenderSettings = RenderSettings(),
) {
    companion object {
        /** Pseudo deck id meaning "every deck"; Room ids start at 1. */
        const val ALL_DECKS = 0L

        /** Pseudo deck id for "every top-level deck that isn't in a folder". */
        const val UNFILED = Long.MIN_VALUE

        /**
         * Pseudo deck id for "every top-level deck in this folder" ([UNFILED] for null).
         * Negative, so it can't clash with a real deck id.
         */
        fun folderScope(folderId: Long?): Long = if (folderId == null) UNFILED else -folderId

        /** True for [ALL_DECKS] and folder scopes: ids that stand for a group of top-level decks. */
        fun isGroup(deckId: Long): Boolean = deckId <= 0

        /** The folder id for a [folderScope] value: null for [UNFILED]. Only meaningful when [deckId] < 0. */
        fun folderOf(deckId: Long): Long? = if (deckId == UNFILED) null else -deckId
    }

    /** Cards that may be shown early at the end of a session, as in Anki. */
    private val learnAheadMs = 20 * 60_000L

    /** Due counts per deck (each deck's own cards, limits applied using that deck's preset). */
    fun observeCounts(): Flow<Map<Long, DueCounts>> {
        val now = dayClock.now()
        return combine(
            db.cardDao().observeCounts(now, dayClock.endOfDay(now)),
            db.reviewLogDao().observeAnswersSince(dayClock.startOfDay(now)),
            db.deckDao().observeAll(),
        ) { raw, _, _ -> raw.associate { it.deckId to DueCounts(it.newCount, it.learningDue, it.reviewDue) } }
    }

    /**
     * Counts for studying [deckId] including sub-decks, with the deck's daily limits applied.
     * New cards count as 0 until the deck's due reviews (and learning cards due now) are done.
     * For [ALL_DECKS] or a [folderScope], the sum over its top-level decks, each with its own limits.
     */
    suspend fun countsFor(deckId: Long): DueCounts {
        val decks = db.deckDao().getAll()
        if (isGroup(deckId)) return rootsFor(decks, deckId).fold(DueCounts.ZERO) { acc, d -> acc + countsFor(d.id) }
        val deck = decks.firstOrNull { it.id == deckId } ?: return DueCounts.ZERO
        val ids = studyableIds(decks, deckId)
        if (ids.isEmpty()) return DueCounts.ZERO
        val preset = presetFor(deck)
        val now = dayClock.now()
        val raw = db.cardDao().counts(ids, now, dayClock.endOfDay(now))
            .fold(DueCounts.ZERO) { acc, c -> acc + DueCounts(c.newCount, c.learningDue, c.reviewDue) }
        val start = dayClock.startOfDay(now)
        val newLeft = max(0, preset.newPerDay - db.reviewLogDao().newIntroducedSince(ids, start))
        val reviewsLeft = max(0, preset.reviewsPerDay - db.reviewLogDao().reviewsSince(ids, start))
        val reviews = min(raw.review, reviewsLeft)
        // Reviews first: no new cards while the deck has reviews left today or learning cards due now.
        // (Learning cards due later today, e.g. a new card's 10-minute step, don't hold new cards back.)
        val backlog = reviews > 0 || db.cardDao().countLearningDueNow(ids, now) > 0
        return DueCounts(if (backlog) 0 else min(raw.new, newLeft), raw.learning, reviews)
    }

    /**
     * Next card to show. Cards whose note is in [avoidNoteIds] (the note just shown and notes linked
     * to it) are skipped when anything else is available, so one card doesn't give away the next.
     */
    suspend fun next(deckId: Long, avoidNoteIds: Set<Long> = emptySet(), aheadDays: Int = 0): QueueResult {
        if (aheadDays > 0) return nextAhead(deckId, avoidNoteIds, aheadDays)
        val decks = db.deckDao().getAll()
        val roots = rootsFor(decks, deckId)
        // Each root keeps its own daily limits: its reviews/new cards are offered only while it has some left.
        val perRoot = roots.map { studyableIds(decks, it.id) to countsFor(it.id) }
        val ids = perRoot.flatMap { it.first }
        if (ids.isEmpty()) return QueueResult.Finished(null)
        val reviewIds = perRoot.filter { it.second.review > 0 }.flatMap { it.first }
        val newIds = perRoot.filter { it.second.new > 0 }.flatMap { it.first }
        val counts = perRoot.fold(DueCounts.ZERO) { acc, r -> acc + r.second }
        val now = dayClock.now()
        val cardDao = db.cardDao()
        val n = CANDIDATES

        // Priority order: learning cards due now, reviews, new cards, then learning cards due soon.
        val groups = listOf(
            cardDao.nextLearning(ids, now, now, n),
            if (reviewIds.isNotEmpty()) cardDao.nextReview(reviewIds, dayClock.endOfDay(now), now, n) else emptyList(),
            if (newIds.isNotEmpty()) cardDao.nextNew(newIds, now, n) else emptyList(),
            cardDao.nextLearning(ids, now + learnAheadMs, now, n),
        )
        val card = pickSpaced(groups, avoidNoteIds)
            ?: return QueueResult.Finished(cardDao.nextLearningDueTime(ids, now))
        return QueueResult.Next(load(card, decks), counts)
    }

    suspend fun load(card: Card, decks: List<Deck>? = null): StudyCard {
        val note = db.noteDao().get(card.noteId) ?: error("note ${card.noteId} missing")
        val type = db.noteTypeDao().get(note.noteTypeId) ?: error("note type missing")
        val deck = (decks ?: db.deckDao().getAll()).first { it.id == card.deckId }
        val rendered = CardGenerator.render(type, note.fields, card.ord, renderSettings.options.value)
        val audio = CardGenerator.audio(type, note.fields, card.ord, note.readFront)
        val scheduler = Scheduler(presetFor(deck).toConfig())
        return StudyCard(
            card = card,
            type = type,
            fields = note.fields,
            deck = deck,
            question = rendered.question,
            answer = rendered.answer,
            buttonDelays = scheduler.preview(card.toState(), dayClock.now()),
            questionAudio = audio.question.resolve(deck),
            answerAudio = audio.answer.resolve(deck),
        )
    }

    /**
     * Studying ahead: review cards due within [aheadDays] days, answered with real scheduling
     * (the scheduler credits only the time that actually passed). Daily limits don't apply; new cards aren't shown.
     */
    private suspend fun nextAhead(deckId: Long, avoidNoteIds: Set<Long>, aheadDays: Int): QueueResult {
        val decks = db.deckDao().getAll()
        val ids = studyableIds(decks, deckId)
        val now = dayClock.now()
        val until = dayClock.endOfDay(now) + aheadDays * com.speedcards.scheduler.DAY_MS
        val learning = db.cardDao().nextLearning(ids, now, now, CANDIDATES)
        val reviews = db.cardDao().reviewsDueBy(ids, until, now, CANDIDATES)
        val card = pickSpaced(listOf(learning, reviews, db.cardDao().nextLearning(ids, now + learnAheadMs, now, CANDIDATES)), avoidNoteIds)
            ?: return QueueResult.Finished(db.cardDao().nextLearningDueTime(ids, now))
        val counts = DueCounts(0, db.cardDao().counts(ids, now, dayClock.endOfDay(now)).sumOf { it.learningDue }, db.cardDao().countReviewsDueBy(ids, until, now))
        return QueueResult.Next(load(card, decks), counts)
    }

    /** Tags used by notes in a deck and its sub-decks (for "cram a tag"). */
    suspend fun tagsIn(deckId: Long): List<String> = db.tagDao().namesInDecks(studyableIds(db.deckDao().getAll(), deckId))

    /** Note ids for cramming a deck, optionally only notes with [tag]. */
    suspend fun cramNoteIds(deckId: Long, tag: String?): List<Long> {
        val ids = studyableIds(db.deckDao().getAll(), deckId)
        return if (tag == null) db.noteDao().idsInDecks(ids) else db.tagDao().noteIdsWithTag(tag, ids)
    }

    /** Cards answered "Again" in the last [days] days. */
    suspend fun forgottenCards(deckId: Long, days: Int): List<StudyCard> {
        val decks = db.deckDao().getAll()
        val since = dayClock.startOfDay() - (days - 1) * com.speedcards.scheduler.DAY_MS
        val cardIds = db.reviewLogDao().forgottenCardIds(studyableIds(decks, deckId), since)
        return db.cardDao().getMany(cardIds).map { load(it, decks) }
    }

    /** Puts a card back to new, keeping its review history (and clears the leech flag). */
    suspend fun resetCard(card: Card) = db.cardDao().update(
        card.copy(state = CardState.NEW, due = 0, intervalDays = 0, ease = 0.0, stepIndex = 0, leech = false, buriedUntil = 0)
    )

    /**
     * Starts [deckId] over from scratch: every card in it and its sub-decks (every deck in the group for
     * [ALL_DECKS] or a [folderScope]) becomes new again, in its original order. With [clearHistory] the
     * cards' review history is deleted too, so stats and today's new-card count start fresh as well.
     * Returns how many cards were reset.
     */
    suspend fun resetDeck(deckId: Long, clearHistory: Boolean): Int = db.withTransaction {
        val ids = studyableIds(db.deckDao().getAll(), deckId)
        if (ids.isEmpty()) return@withTransaction 0
        if (clearHistory) db.reviewLogDao().deleteForDecks(ids)
        db.cardDao().resetDecks(ids)
    }

    suspend fun clearLeech(card: Card) = db.cardDao().update(card.copy(leech = false))

    /** Every unsuspended card of these notes, ready to show (used for cluster practice). */
    suspend fun cardsForNotes(noteIds: List<Long>): List<StudyCard> {
        if (noteIds.isEmpty()) return emptyList()
        val decks = db.deckDao().getAll()
        return db.cardDao().forNotes(noteIds).map { load(it, decks) }
    }

    suspend fun answer(card: Card, rating: Rating, durationMs: Long): AnswerOutcome = db.withTransaction {
        val deck = db.deckDao().get(card.deckId) ?: error("deck missing")
        val scheduler = Scheduler(presetFor(deck).toConfig())
        val now = dayClock.now()
        val result = scheduler.answer(card.toState(), rating, now)
        val s = result.newState
        val updated = card.copy(
            state = s.state, due = s.due, intervalDays = s.intervalDays, ease = s.ease,
            reps = s.reps, lapses = s.lapses, stepIndex = s.stepIndex,
            leech = card.leech || result.becameLeech,
        )
        db.cardDao().update(updated)
        val logId = db.reviewLogDao().insert(
            ReviewLog(
                cardId = card.id, timestamp = now, rating = rating,
                prevState = card.state, prevIntervalDays = card.intervalDays, newIntervalDays = s.intervalDays,
                prevEase = card.ease, newEase = s.ease, durationMs = durationMs,
            )
        )
        AnswerOutcome(UndoEntry(card, logId), result.becameLeech)
    }

    suspend fun undo(entry: UndoEntry) = db.withTransaction {
        db.cardDao().update(entry.cardBefore)
        db.reviewLogDao().delete(entry.logId)
    }

    /** Hides the card until the next study day. */
    suspend fun bury(card: Card) = db.cardDao().update(card.copy(buriedUntil = dayClock.endOfDay()))

    suspend fun setSuspended(card: Card, suspended: Boolean) = db.cardDao().update(card.copy(suspended = suspended))

    private suspend fun presetFor(deck: Deck): SchedulingPreset =
        db.presetDao().get(deck.presetId) ?: db.presetDao().getAll().first()

    /** Top-level decks other than the Inbox. */
    private fun rootDecks(decks: List<Deck>): List<Deck> = decks.filter { it.parentId == null && it.kind == DeckKind.NORMAL }

    /** The top-level decks [deckId] stands for: all of them, a folder's, or just the deck itself. */
    private fun rootsFor(decks: List<Deck>, deckId: Long): List<Deck> = when {
        deckId == ALL_DECKS -> rootDecks(decks)
        deckId < 0 -> folderOf(deckId).let { folder -> rootDecks(decks).filter { it.folderId == folder } }
        else -> decks.filter { it.id == deckId }
    }

    /** The deck and its descendants (every deck in the group for [isGroup] ids), minus Inbox decks (Inbox cards are never reviewed). */
    private fun studyableIds(decks: List<Deck>, deckId: Long): List<Long> {
        if (isGroup(deckId)) return rootsFor(decks, deckId).flatMap { studyableIds(decks, it.id) }
        val inbox = decks.filter { it.kind == DeckKind.INBOX }.map { it.id }.toSet()
        return DeckRepository.subtree(decks, deckId).filter { it !in inbox }
    }
}

/** Resolves FRONT/BACK field languages against the deck; lines with no known language are dropped. */
fun CardGenerator.SideAudio.resolve(deck: Deck): PlayableAudio = PlayableAudio(
    sounds = sounds,
    speech = speech.mapNotNull { line ->
        val tag = when (line.language) {
            FieldLanguage.FRONT -> deck.languageFront
            FieldLanguage.BACK -> deck.languageBack
            FieldLanguage.NONE -> null
        }
        tag?.let { SpokenLine(line.text, it) }
    },
)

private const val CANDIDATES = 10

/** First card (by priority group) whose note isn't avoided; falls back to the first card at all. */
internal fun pickSpaced(groups: List<List<Card>>, avoidNoteIds: Set<Long>): Card? =
    groups.firstNotNullOfOrNull { g -> g.firstOrNull { it.noteId !in avoidNoteIds } }
        ?: groups.firstNotNullOfOrNull { it.firstOrNull() }

fun Card.toState() = SchedulingState(
    state = state, due = due, intervalDays = intervalDays, ease = ease,
    reps = reps, lapses = lapses, stepIndex = stepIndex,
)

val Card.isNew get() = state == CardState.NEW
