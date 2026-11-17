package com.speedcards.data.repo

import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.util.DayClock
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.DAY_MS
import com.speedcards.scheduler.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class Bucket(val label: String, val count: Int)
data class DeckRetention(val deckName: String, val reviews: Int, val passed: Int) {
    val rate get() = if (reviews == 0) null else passed.toDouble() / reviews
}
data class LeechRow(val cardId: Long, val noteId: Long, val front: String, val lapses: Int, val deckName: String, val suspended: Boolean)

data class Stats(
    val today: LocalDate,
    /** Reviews per study day for the heatmap (only days with reviews). */
    val heatmap: Map<LocalDate, Int>,
    val streakDays: Int,
    val reviewsToday: Int,
    val timeTodayMs: Long,
    val time7Ms: Long,
    val time30Ms: Long,
    /** Review + learning cards due per day; index 0 is today (including overdue). */
    val forecast: List<Int>,
    val retention30: List<DeckRetention>,
    val intervals: List<Bucket>,
    val eases: List<Bucket>,
    val newCards: Int,
    val learningCards: Int,
    val youngCards: Int,
    val matureCards: Int,
    val suspendedCards: Int,
    /** Notes with at least one mature card (interval ≥ 21 days). */
    val wordsLearned: Int,
    val leeches: List<LeechRow>,
) {
    val overallRetention: Double?
        get() = retention30.sumOf { it.reviews }.takeIf { it > 0 }?.let { total -> retention30.sumOf { it.passed }.toDouble() / total }
}

@Singleton
class StatsRepository @Inject constructor(
    private val db: SpeedCardsDatabase,
    private val dayClock: DayClock,
) {
    /** Study day a timestamp belongs to (days roll over at 4am, like the study queue). */
    fun studyDay(ts: Long): LocalDate = Instant.ofEpochMilli(dayClock.startOfDay(ts)).atZone(dayClock.zone()).toLocalDate()

    /** Stats for [deckId] and its sub-decks, or every deck when null. The Inbox is never included. */
    suspend fun stats(deckId: Long?, heatmapDays: Int = 182): Stats = withContext(Dispatchers.IO) {
        val decks = db.deckDao().getAll()
        val inbox = decks.filter { it.kind == DeckKind.INBOX }.map { it.id }.toSet()
        val ids = (deckId?.let { DeckRepository.subtree(decks, it) } ?: decks.map { it.id }).filter { it !in inbox }
        val byId = decks.associateBy { it.id }
        val now = dayClock.now()
        val today = studyDay(now)
        val startToday = dayClock.startOfDay(now)

        val logs = db.reviewLogDao().since(ids, startToday - heatmapDays * DAY_MS)
        val heatmap = logs.groupingBy { studyDay(it.timestamp) }.eachCount()
        fun timeSince(days: Int) = logs.filter { it.timestamp >= startToday - (days - 1) * DAY_MS }.sumOf { it.durationMs }

        var streak = 0
        var day = if ((heatmap[today] ?: 0) > 0) today else today.minusDays(1)
        while ((heatmap[day] ?: 0) > 0) { streak++; day = day.minusDays(1) }

        val cards = db.cardDao().inDecks(ids)
        val active = cards.filter { !it.suspended }
        val endToday = dayClock.endOfDay(now)
        val forecast = IntArray(30)
        active.filter { it.state != CardState.NEW }.forEach { c ->
            val index = if (c.due <= endToday) 0 else ((c.due - endToday) / DAY_MS + 1).toInt()
            if (index < 30) forecast[index]++
        }

        // Retention: share of review-state answers that weren't "Again", last 30 days, per top-level deck.
        val cardDeck = cards.associate { it.id to it.deckId }
        fun topName(id: Long): String {
            var d: Deck? = byId[id]
            while (d?.parentId != null) d = byId[d.parentId]
            return d?.name.orEmpty()
        }
        val recentReviews = logs.filter { it.prevState == CardState.REVIEW && it.timestamp >= startToday - 29 * DAY_MS }
        val retention = recentReviews.groupBy { topName(cardDeck[it.cardId] ?: 0) }
            .map { (name, rs) -> DeckRetention(name, rs.size, rs.count { it.rating != Rating.AGAIN }) }
            .sortedByDescending { it.reviews }

        val reviewCards = active.filter { it.state == CardState.REVIEW }
        val intervals = listOf(
            "1d" to 1..1, "2–3d" to 2..3, "4–7d" to 4..7, "1–2w" to 8..14,
            "2w–1mo" to 15..30, "1–3mo" to 31..90, "3–6mo" to 91..180, "6mo+" to 181..Int.MAX_VALUE,
        ).map { (label, range) -> Bucket(label, reviewCards.count { it.intervalDays in range }) }
        val eases = listOf(
            "<150%" to (0.0 to 1.5), "150–200" to (1.5 to 2.0), "200–250" to (2.0 to 2.5),
            "250–300" to (2.5 to 3.0), "300%+" to (3.0 to 100.0),
        ).map { (label, r) -> Bucket(label, reviewCards.count { it.ease >= r.first && it.ease < r.second }) }

        val noteFront = db.noteDao().getMany(cards.filter { it.leech }.map { it.noteId }).associate { it.id to LinkRepository.frontText(it) }
        val leeches = cards.filter { it.leech }.sortedByDescending { it.lapses }.map {
            LeechRow(it.id, it.noteId, noteFront[it.noteId].orEmpty(), it.lapses, byId[it.deckId]?.name.orEmpty(), it.suspended)
        }

        Stats(
            today = today,
            heatmap = heatmap,
            streakDays = streak,
            reviewsToday = heatmap[today] ?: 0,
            timeTodayMs = timeSince(1),
            time7Ms = timeSince(7),
            time30Ms = timeSince(30),
            forecast = forecast.toList(),
            retention30 = retention,
            intervals = intervals,
            eases = eases,
            newCards = active.count { it.state == CardState.NEW },
            learningCards = active.count { it.state == CardState.LEARNING || it.state == CardState.RELEARNING },
            youngCards = reviewCards.count { it.intervalDays < MATURE_DAYS },
            matureCards = reviewCards.count { it.intervalDays >= MATURE_DAYS },
            suspendedCards = cards.count { it.suspended },
            wordsLearned = reviewCards.filter { it.intervalDays >= MATURE_DAYS }.map { it.noteId }.distinct().size,
            leeches = leeches,
        )
    }

    /** Consecutive study days up to today (or yesterday, if you haven't studied yet today). */
    suspend fun streak(): Int = withContext(Dispatchers.IO) {
        val now = dayClock.now()
        val days = db.reviewLogDao().timestampsSince(dayClock.startOfDay(now) - 400 * DAY_MS).map { studyDay(it) }.toSet()
        var day = studyDay(now).let { if (it in days) it else it.minusDays(1) }
        var n = 0
        while (day in days) { n++; day = day.minusDays(1) }
        n
    }

    companion object {
        const val MATURE_DAYS = 21
    }
}
