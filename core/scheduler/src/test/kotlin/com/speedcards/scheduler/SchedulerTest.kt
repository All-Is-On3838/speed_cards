package com.speedcards.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SchedulerTest {

    private val config = SchedulerConfig(fuzzEnabled = false)
    private val scheduler = Scheduler(config)

    private fun review(ivl: Int, ease: Double = 2.5, due: Long = 0L) =
        SchedulingState(state = CardState.REVIEW, intervalDays = ivl, ease = ease, due = due, reps = 5)

    // --- Learning -------------------------------------------------------------------------

    @Test fun newCardMediumMovesToSecondStep() {
        val r = scheduler.answer(SchedulingState(), Rating.MEDIUM, now = 0)
        assertEquals(CardState.LEARNING, r.newState.state)
        assertEquals(1, r.newState.stepIndex)
        assertEquals(60 * MINUTE_MS, r.delayMs)
        assertEquals(2.5, r.newState.ease, 1e-9)
        assertEquals(1, r.newState.reps)
    }

    @Test fun newCardHardOnFirstStepIsHalfwayToSecondStep() {
        val r = scheduler.answer(SchedulingState(), Rating.HARD, now = 0)
        assertEquals(35 * MINUTE_MS, r.delayMs)
        assertEquals(0, r.newState.stepIndex)
    }

    @Test fun hardWithSingleStepIsOneAndAHalfTimes() {
        val s = Scheduler(config.copy(learningStepsMinutes = listOf(10.0)))
        assertEquals(15 * MINUTE_MS, s.answer(SchedulingState(), Rating.HARD, 0).delayMs)
    }

    @Test fun againResetsToFirstStep() {
        val learning = SchedulingState(state = CardState.LEARNING, stepIndex = 2, ease = 2.5)
        val r = scheduler.answer(learning, Rating.AGAIN, now = 0)
        assertEquals(0, r.newState.stepIndex)
        assertEquals(10 * MINUTE_MS, r.delayMs)
    }

    @Test fun mediumThroughAllStepsGraduates() {
        var s = SchedulingState()
        var now = 0L
        repeat(3) {
            val r = scheduler.answer(s, Rating.MEDIUM, now)
            s = r.newState; now = s.due
        }
        assertEquals(CardState.REVIEW, s.state)
        assertEquals(1, s.intervalDays)
    }

    @Test fun easyOnNewCardGraduatesWithEasyInterval() {
        val r = scheduler.answer(SchedulingState(), Rating.EASY, now = 0)
        assertEquals(CardState.REVIEW, r.newState.state)
        assertEquals(4, r.newState.intervalDays)
        assertEquals(4 * DAY_MS, r.delayMs)
    }

    @Test fun noLearningStepsGraduatesImmediately() {
        val s = Scheduler(config.copy(learningStepsMinutes = emptyList()))
        val r = s.answer(SchedulingState(), Rating.MEDIUM, 0)
        assertEquals(CardState.REVIEW, r.newState.state)
        assertEquals(1, r.newState.intervalDays)
    }

    // --- Review ----------------------------------------------------------------------------

    @Test fun reviewMultipliersOnTime() {
        val s = review(10)
        assertEquals(12, scheduler.answer(s, Rating.HARD, 0).newState.intervalDays)
        assertEquals(25, scheduler.answer(s, Rating.MEDIUM, 0).newState.intervalDays)
        assertEquals(33, scheduler.answer(s, Rating.EASY, 0).newState.intervalDays) // 32.5 rounds up
    }

    @Test fun customMultipliersAreUsed() {
        val s = Scheduler(config.copy(hardMultiplier = 1.5, easyBonus = 2.0))
        assertEquals(15, s.answer(review(10), Rating.HARD, 0).newState.intervalDays)
        assertEquals(50, s.answer(review(10), Rating.EASY, 0).newState.intervalDays)
    }

    @Test fun lateReviewGetsCreditForDelay() {
        val now = 4 * DAY_MS
        val s = review(10, due = 0)
        assertEquals(30, scheduler.answer(s, Rating.MEDIUM, now).newState.intervalDays) // (10+2)*2.5
        assertEquals(46, scheduler.answer(s, Rating.EASY, now).newState.intervalDays)   // 14*2.5*1.3=45.5
    }

    @Test fun buttonsAreStrictlyIncreasingForShortIntervals() {
        val s = review(1)
        val hard = scheduler.answer(s, Rating.HARD, 0).newState.intervalDays
        val medium = scheduler.answer(s, Rating.MEDIUM, 0).newState.intervalDays
        val easy = scheduler.answer(s, Rating.EASY, 0).newState.intervalDays
        assertEquals(listOf(2, 3, 4), listOf(hard, medium, easy))
    }

    @Test fun easeChangesPerRating() {
        val s = review(10)
        assertEquals(2.35, scheduler.answer(s, Rating.HARD, 0).newState.ease, 1e-9)
        assertEquals(2.5, scheduler.answer(s, Rating.MEDIUM, 0).newState.ease, 1e-9)
        assertEquals(2.65, scheduler.answer(s, Rating.EASY, 0).newState.ease, 1e-9)
        assertEquals(2.3, scheduler.answer(s, Rating.AGAIN, 0).newState.ease, 1e-9)
    }

    @Test fun easeNeverDropsBelowMinimum() {
        val r = scheduler.answer(review(10, ease = 1.3), Rating.HARD, 0)
        assertEquals(1.3, r.newState.ease, 1e-9)
    }

    @Test fun intervalCappedAtMaximum() {
        val s = Scheduler(config.copy(maximumIntervalDays = 30))
        assertEquals(30, s.answer(review(20), Rating.EASY, 0).newState.intervalDays)
    }

    @Test fun intervalModifierScalesIntervals() {
        val s = Scheduler(config.copy(intervalModifier = 0.8))
        assertEquals(20, s.answer(review(10), Rating.MEDIUM, 0).newState.intervalDays)
    }

    // --- Lapses ----------------------------------------------------------------------------

    @Test fun lapseGoesToRelearningThenBackToReview() {
        val r = scheduler.answer(review(10), Rating.AGAIN, 0)
        assertEquals(CardState.RELEARNING, r.newState.state)
        assertEquals(1, r.newState.lapses)
        assertEquals(1, r.newState.intervalDays)
        assertEquals(10 * MINUTE_MS, r.delayMs)

        val back = scheduler.answer(r.newState, Rating.MEDIUM, r.newState.due)
        assertEquals(CardState.REVIEW, back.newState.state)
        assertEquals(1, back.newState.intervalDays)
    }

    @Test fun lapseMultiplierKeepsPartOfInterval() {
        val s = Scheduler(config.copy(lapseIntervalMultiplier = 0.5))
        assertEquals(5, s.answer(review(10), Rating.AGAIN, 0).newState.intervalDays)
    }

    @Test fun lapseWithoutRelearningStepsStaysInReview() {
        val s = Scheduler(config.copy(relearningStepsMinutes = emptyList()))
        val r = s.answer(review(10), Rating.AGAIN, 0)
        assertEquals(CardState.REVIEW, r.newState.state)
        assertEquals(DAY_MS, r.delayMs)
    }

    @Test fun leechFlaggedAtThresholdAndEveryHalfThresholdAfter() {
        fun lapseAt(lapses: Int) =
            scheduler.answer(review(10).copy(lapses = lapses - 1), Rating.AGAIN, 0).becameLeech
        assertFalse(lapseAt(7))
        assertTrue(lapseAt(8))
        assertFalse(lapseAt(9))
        assertTrue(lapseAt(12))
        assertTrue(lapseAt(16))
    }

    // --- Fuzz, preview, options ------------------------------------------------------------

    @Test fun fuzzStaysWithinBounds() {
        val s = Scheduler(SchedulerConfig(fuzzEnabled = true), Random(42))
        val seen = (1..1000).map { s.fuzz(10) }.toSet()
        assertTrue(seen.all { it in 8..12 })
        assertTrue("fuzz should vary", seen.size > 1)
        assertEquals(2, s.fuzz(2)) // short intervals are not fuzzed
    }

    @Test fun fuzzNeverExceedsMaximumInterval() {
        val s = Scheduler(SchedulerConfig(fuzzEnabled = true, maximumIntervalDays = 100), Random(1))
        assertTrue((1..500).all { s.fuzz(100) <= 100 })
    }

    @Test fun previewMatchesAnswersWithoutFuzz() {
        val s = Scheduler(SchedulerConfig(fuzzEnabled = true), Random(7))
        val p = s.preview(review(10), 0)
        assertEquals(12 * DAY_MS, p[Rating.HARD])
        assertEquals(25 * DAY_MS, p[Rating.MEDIUM])
        assertEquals(33 * DAY_MS, p[Rating.EASY])
        assertEquals(10 * MINUTE_MS, p[Rating.AGAIN])
    }

    @Test fun hidingAgainLeavesThreeButtons() {
        val s = Scheduler(config.copy(showAgain = false))
        assertEquals(listOf(Rating.HARD, Rating.MEDIUM, Rating.EASY), s.availableRatings())
        assertFalse(Rating.AGAIN in s.preview(review(10), 0))
    }

    // --- Golden sequence -------------------------------------------------------------------

    @Test fun goldenSm2SequenceAnsweringMedium() {
        val steps = simulate(config, List(9) { Rating.MEDIUM })
        val intervals = steps.filter { it.state.state == CardState.REVIEW }.map { it.state.intervalDays }
        assertEquals(listOf(1, 3, 8, 20, 50, 125, 313), intervals)
    }

    // --- Studying ahead ----------------------------------------------------------------------

    @Test fun earlyReviewGrowsFromElapsedTimeOnly() {
        // Interval 10, due in 6 days: only 4 days have passed.
        val s = review(10, due = 6 * DAY_MS)
        assertEquals(10, scheduler.answer(s, Rating.MEDIUM, 0).newState.intervalDays) // 4*2.5=10, never below 10
        assertEquals(13, scheduler.answer(s, Rating.EASY, 0).newState.intervalDays)   // 4*2.5*1.3=13
        assertEquals(5, scheduler.answer(s, Rating.HARD, 0).newState.intervalDays)    // max(10/2, 4*1.2)
    }

    @Test fun slightlyEarlySameDayReviewIsNormal() {
        // Due later today (less than a day away) behaves like an on-time review.
        val s = review(10, due = 6 * 3600_000L)
        assertEquals(25, scheduler.answer(s, Rating.MEDIUM, 0).newState.intervalDays)
    }
}
