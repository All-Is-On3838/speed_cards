package com.speedcards.scheduler

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Adjustable SM-2 scheduler (Anki-style).
 *
 * Pure function of (config, state, rating, now) plus an injectable [random] for interval fuzz,
 * so it can be unit-tested and used for previews and the preset simulator.
 */
class Scheduler(
    private val config: SchedulerConfig,
    private val random: Random = Random.Default,
) {

    fun answer(current: SchedulingState, rating: Rating, now: Long): AnswerResult =
        answerInternal(current, rating, now, applyFuzz = config.fuzzEnabled)

    /** Delay each button would give, without fuzz, for showing on the buttons. */
    fun preview(current: SchedulingState, now: Long): Map<Rating, Long> =
        availableRatings().associateWith { answerInternal(current, it, now, applyFuzz = false).delayMs }

    fun availableRatings(): List<Rating> =
        if (config.showAgain) Rating.entries else Rating.entries - Rating.AGAIN

    private fun answerInternal(
        current: SchedulingState,
        rating: Rating,
        now: Long,
        applyFuzz: Boolean,
    ): AnswerResult {
        val state = if (current.state == CardState.NEW) {
            current.copy(state = CardState.LEARNING, stepIndex = 0, ease = config.startingEase)
        } else current
        val result = when (state.state) {
            CardState.LEARNING -> answerLearning(state, rating, now, config.learningStepsMinutes, relearning = false, applyFuzz)
            CardState.RELEARNING -> answerLearning(state, rating, now, config.relearningStepsMinutes, relearning = true, applyFuzz)
            CardState.REVIEW -> answerReview(state, rating, now, applyFuzz)
            CardState.NEW -> error("unreachable")
        }
        return result.copy(newState = result.newState.copy(reps = current.reps + 1))
    }

    private fun answerLearning(
        s: SchedulingState,
        rating: Rating,
        now: Long,
        steps: List<Double>,
        relearning: Boolean,
        applyFuzz: Boolean,
    ): AnswerResult {
        if (steps.isEmpty()) return graduate(s, now, relearning, easy = rating == Rating.EASY, applyFuzz)
        val step = s.stepIndex.coerceIn(0, steps.lastIndex)
        return when (rating) {
            Rating.AGAIN -> stayInLearning(s.copy(stepIndex = 0), minutesToMs(steps[0]), now)
            Rating.HARD -> {
                val delay = if (step == 0) hardFirstStepDelay(steps) else minutesToMs(steps[step])
                stayInLearning(s.copy(stepIndex = step), delay, now)
            }
            Rating.MEDIUM -> {
                val next = step + 1
                if (next > steps.lastIndex) graduate(s, now, relearning, easy = false, applyFuzz)
                else stayInLearning(s.copy(stepIndex = next), minutesToMs(steps[next]), now)
            }
            Rating.EASY -> graduate(s, now, relearning, easy = true, applyFuzz)
        }
    }

    /** Hard on the first step: halfway to the second step, or 1.5x a single step (capped at +1 day). */
    private fun hardFirstStepDelay(steps: List<Double>): Long =
        if (steps.size >= 2) minutesToMs((steps[0] + steps[1]) / 2)
        else min(minutesToMs(steps[0] * 1.5), minutesToMs(steps[0]) + DAY_MS)

    private fun stayInLearning(s: SchedulingState, delay: Long, now: Long) =
        AnswerResult(s.copy(due = now + delay), delay, becameLeech = false)

    private fun graduate(
        s: SchedulingState,
        now: Long,
        relearning: Boolean,
        easy: Boolean,
        applyFuzz: Boolean,
    ): AnswerResult {
        val baseDays = when {
            relearning && easy -> s.intervalDays + 1
            relearning -> s.intervalDays
            easy -> config.easyIntervalDays
            else -> config.graduatingIntervalDays
        }
        val days = clampInterval(baseDays.toDouble(), minimum = 1, applyFuzz)
        return toReview(s, days, now)
    }

    private fun answerReview(s: SchedulingState, rating: Rating, now: Long, applyFuzz: Boolean): AnswerResult {
        if (rating == Rating.AGAIN) return lapse(s, now)

        val ivl = max(s.intervalDays, 1).toDouble()
        val mod = config.intervalModifier
        val daysEarly = (s.due - now) / DAY_MS
        val hard: Int
        val medium: Int
        val easy: Int
        if (daysEarly >= 1) {
            // Studying ahead: grow from the time that actually passed, and never shorten on a success.
            val elapsed = max(1.0, ivl - daysEarly)
            hard = clampInterval(elapsed * config.hardMultiplier * mod, max(1, ivl.toInt() / 2), applyFuzz = false)
            medium = clampInterval(elapsed * s.ease * mod, max(ivl.toInt(), hard + 1), applyFuzz = false)
            easy = clampInterval(elapsed * s.ease * config.easyBonus * mod, medium + 1, applyFuzz = false)
        } else {
            val daysLate = max(0L, (now - s.due) / DAY_MS).toDouble()
            val hardMin = if (config.hardMultiplier > 1.0) ivl.toInt() + 1 else ivl.toInt()
            hard = clampInterval(ivl * config.hardMultiplier * mod, hardMin, applyFuzz = false)
            medium = clampInterval((ivl + daysLate / 2) * s.ease * mod, hard + 1, applyFuzz = false)
            easy = clampInterval((ivl + daysLate) * s.ease * config.easyBonus * mod, medium + 1, applyFuzz = false)
        }

        val (days, easeDelta) = when (rating) {
            Rating.HARD -> hard to config.hardEaseDelta
            Rating.MEDIUM -> medium to 0.0
            Rating.EASY -> easy to config.easyEaseDelta
            Rating.AGAIN -> error("handled above")
        }
        val finalDays = if (applyFuzz) fuzz(days) else days
        val newEase = max(config.minimumEase, s.ease + easeDelta)
        return toReview(s.copy(ease = newEase), finalDays, now)
    }

    private fun lapse(s: SchedulingState, now: Long): AnswerResult {
        val lapses = s.lapses + 1
        val newIvl = max(config.minimumLapseIntervalDays, (s.intervalDays * config.lapseIntervalMultiplier).toInt())
        val lapsed = s.copy(
            lapses = lapses,
            ease = max(config.minimumEase, s.ease + config.againEaseDelta),
            intervalDays = newIvl,
        )
        val leech = isLeechThreshold(lapses)
        val steps = config.relearningStepsMinutes
        return if (steps.isEmpty()) {
            toReview(lapsed, newIvl, now).copy(becameLeech = leech)
        } else {
            val delay = minutesToMs(steps[0])
            AnswerResult(lapsed.copy(state = CardState.RELEARNING, stepIndex = 0, due = now + delay), delay, leech)
        }
    }

    /** Leech at the threshold, then again every half-threshold lapses (Anki behaviour). */
    private fun isLeechThreshold(lapses: Int): Boolean {
        val t = config.leechThreshold
        if (t <= 0 || lapses < t) return false
        val every = max(1, t / 2)
        return (lapses - t) % every == 0
    }

    private fun toReview(s: SchedulingState, days: Int, now: Long): AnswerResult {
        val delay = days * DAY_MS
        return AnswerResult(
            s.copy(state = CardState.REVIEW, intervalDays = days, stepIndex = 0, due = now + delay),
            delay,
            becameLeech = false,
        )
    }

    private fun clampInterval(days: Double, minimum: Int, applyFuzz: Boolean): Int {
        val rounded = max(minimum, days.roundToInt())
        val capped = min(rounded, config.maximumIntervalDays)
        return if (applyFuzz) fuzz(capped) else capped
    }

    /** Spread due dates so cards added together don't stay clumped. */
    internal fun fuzz(days: Int): Int {
        if (days < 3) return days
        val d = days.toDouble()
        var delta = 1.0
        delta += 0.15 * (min(d, 7.0) - 2.5).coerceAtLeast(0.0)
        delta += 0.10 * (min(d, 20.0) - 7.0).coerceAtLeast(0.0)
        delta += 0.05 * (d - 20.0).coerceAtLeast(0.0)
        val lo = max(2, (d - delta).roundToInt())
        val hi = min(config.maximumIntervalDays, (d + delta).roundToInt())
        if (hi <= lo) return min(days, config.maximumIntervalDays)
        return random.nextInt(lo, hi + 1)
    }

    private fun minutesToMs(minutes: Double): Long = (minutes * MINUTE_MS).toLong()
}
