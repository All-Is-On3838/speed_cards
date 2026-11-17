package com.speedcards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.repo.QueueResult
import com.speedcards.data.repo.StatsRepository
import com.speedcards.data.repo.StudyRepository
import com.speedcards.data.util.DayClock
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.DAY_MS
import com.speedcards.scheduler.Rating
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsAndCustomStudyTest {
    private val zone = ZoneId.of("UTC")
    /** Noon on Oct 1 2026, UTC. */
    private var now = LocalDateTime.of(2026, 10, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val clock = DayClock(zone = { zone }, clock = { now })
    private lateinit var db: SpeedCardsDatabase
    private lateinit var notes: NoteRepository
    private lateinit var study: StudyRepository
    private lateinit var stats: StatsRepository
    private var deckId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SpeedCardsDatabase::class.java)
            .allowMainThreadQueries().build()
        CollectionInitializer(db).ensureDefaults()
        notes = NoteRepository(db)
        study = StudyRepository(db, clock)
        stats = StatsRepository(db, clock)
        deckId = DeckRepository(db).all().first { it.name == Defaults.DEFAULT_DECK }.id
        // No random interval spread, so due dates are exact.
        val deck = db.deckDao().get(deckId)!!
        db.presetDao().update(db.presetDao().get(deck.presetId)!!.copy(fuzzEnabled = false))
    }

    @After fun tearDown() = db.close()

    private suspend fun add(front: String, tags: List<String> = emptyList()) =
        notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, deckId, listOf(front, "x"), tags)

    private suspend fun answerNext(rating: Rating) {
        val c = (study.next(deckId) as QueueResult.Next).studyCard.card
        study.answer(c, rating, durationMs = 10_000)
    }

    @Test fun resetDeckStartsOverFromScratchInOriginalOrder() = runTest {
        val sub = db.deckDao().insert(com.speedcards.data.db.Deck(name = "Sub", parentId = deckId, presetId = db.deckDao().get(deckId)!!.presetId))
        val first = add("uno"); add("dos")
        val inSub = notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, sub, listOf("tres", "x"))
        repeat(3) { answerNext(Rating.EASY) }
        val suspended = db.cardDao().forNote(inSub).single()
        db.cardDao().update(db.cardDao().get(suspended.id)!!.copy(suspended = true, leech = true, lapses = 8))
        assertTrue(db.cardDao().forNote(first).single().state != CardState.NEW)

        // Keeping history: cards are new, history (and today's new-card count) stays.
        assertEquals(3, study.resetDeck(deckId, clearHistory = false))
        val cards = listOf(first, inSub).map { db.cardDao().forNote(it).single() }
        assertTrue(cards.all { it.state == CardState.NEW && it.reps == 0 && it.lapses == 0 && it.intervalDays == 0 && !it.leech })
        assertTrue(cards.last().suspended) // suspension is the user's choice; it stays
        assertEquals(3, stats.stats(null).reviewsToday)
        // The first card added comes up first again.
        assertEquals(first, (study.next(deckId) as QueueResult.Next).studyCard.card.noteId)

        // Clearing history as well.
        answerNext(Rating.EASY)
        assertEquals(3, study.resetDeck(deckId, clearHistory = true))
        assertEquals(0, stats.stats(null).reviewsToday)
        assertEquals(first, (study.next(deckId) as QueueResult.Next).studyCard.card.noteId)
    }

    @Test fun heatmapStreakTimeAndForecast() = runTest {
        repeat(5) { add("w$it") }
        // Day 1: two cards; day 2: one card; skip day 3; day 4 (today): one card.
        answerNext(Rating.EASY); answerNext(Rating.EASY)
        now += DAY_MS; answerNext(Rating.EASY)
        now += 2 * DAY_MS; answerNext(Rating.EASY)
        val s = stats.stats(null)
        assertEquals(3, s.heatmap.size)
        assertEquals(1, s.reviewsToday)
        assertEquals(1, s.streakDays) // day 3 was missed
        assertEquals(10_000L, s.timeTodayMs)
        assertEquals(40_000L, s.time7Ms)
        assertEquals(4, s.forecast.sum()) // the 4 graduated cards are due within 30 days
        assertEquals(1, s.newCards)
        assertEquals(1, stats.streak())
    }

    @Test fun retentionMaturityAndLeeches() = runTest {
        val id = add("gato")
        val card = db.cardDao().forNote(id).single()
        // A mature review card that gets one pass and one fail.
        db.cardDao().update(card.copy(state = CardState.REVIEW, intervalDays = 30, ease = 2.5, due = now - 1))
        answerNext(Rating.MEDIUM)
        db.cardDao().update(db.cardDao().get(card.id)!!.copy(due = now - 1))
        answerNext(Rating.AGAIN)
        val s = stats.stats(deckId)
        assertEquals(2, s.retention30.single().reviews)
        assertEquals(0.5, s.overallRetention!!, 1e-9)

        db.cardDao().update(db.cardDao().get(card.id)!!.copy(state = CardState.REVIEW, intervalDays = 40, leech = true, lapses = 8))
        val s2 = stats.stats(deckId)
        assertEquals(1, s2.matureCards)
        assertEquals(1, s2.wordsLearned)
        assertEquals("gato", s2.leeches.single().front)
        assertEquals(1, s2.intervals.first { it.label == "1–3mo" }.count)
    }

    @Test fun studyAheadShowsUpcomingReviewsOnly() = runTest {
        val id = add("hola")
        add("nuevo") // a new card must not appear when studying ahead
        val card = db.cardDao().forNote(id).single()
        db.cardDao().update(card.copy(state = CardState.REVIEW, intervalDays = 10, ease = 2.5, due = now + 5 * DAY_MS))
        assertTrue(study.next(deckId, aheadDays = 3) is QueueResult.Finished)
        val next = study.next(deckId, aheadDays = 7) as QueueResult.Next
        assertEquals("hola", next.studyCard.question)
        study.answer(next.studyCard.card, Rating.MEDIUM, 0)
        // Answered ~5 days early: credit for 5 elapsed days → max(10, 5×2.5=12.5→13).
        assertEquals(13, db.cardDao().get(card.id)!!.intervalDays)
    }

    @Test fun cramByTagAndForgottenCards() = runTest {
        add("a", listOf("verbs")); add("b", listOf("verbs")); add("c")
        assertEquals(listOf("verbs"), study.tagsIn(deckId))
        assertEquals(2, study.cramNoteIds(deckId, "verbs").size)
        assertEquals(3, study.cramNoteIds(deckId, null).size)

        answerNext(Rating.AGAIN)
        assertEquals(1, study.forgottenCards(deckId, days = 1).size)
        now += 3 * DAY_MS
        assertEquals(0, study.forgottenCards(deckId, days = 1).size)
        assertEquals(1, study.forgottenCards(deckId, days = 7).size)
    }

    @Test fun resetAndClearLeech() = runTest {
        val id = add("x")
        val card = db.cardDao().forNote(id).single()
        db.cardDao().update(card.copy(state = CardState.REVIEW, intervalDays = 50, leech = true))
        study.clearLeech(db.cardDao().get(card.id)!!)
        assertEquals(false, db.cardDao().get(card.id)!!.leech)
        study.resetCard(db.cardDao().get(card.id)!!)
        assertEquals(CardState.NEW, db.cardDao().get(card.id)!!.state)
    }
}
