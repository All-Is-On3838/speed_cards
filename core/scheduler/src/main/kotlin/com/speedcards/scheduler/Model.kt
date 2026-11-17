package com.speedcards.scheduler

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }

/** The answer buttons. Again is the "forgot" button and can be hidden per preset. */
enum class Rating { AGAIN, HARD, MEDIUM, EASY }

/** Everything the scheduler needs to know about one card. */
data class SchedulingState(
    val state: CardState = CardState.NEW,
    /** Epoch millis when the card is next due. */
    val due: Long = 0L,
    /** Current review interval in days; 0 while the card has never graduated. */
    val intervalDays: Int = 0,
    /** Ease factor, e.g. 2.5 = 250%. */
    val ease: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    /** Index into the learning or relearning steps. */
    val stepIndex: Int = 0,
)

/** A scheduling preset. Defaults mirror Anki's SM-2 defaults with "Medium" in place of "Good". */
data class SchedulerConfig(
    val learningStepsMinutes: List<Double> = listOf(10.0, 60.0, 1440.0),
    val relearningStepsMinutes: List<Double> = listOf(10.0),
    val graduatingIntervalDays: Int = 1,
    val easyIntervalDays: Int = 4,
    val startingEase: Double = 2.5,
    val hardMultiplier: Double = 1.2,
    val easyBonus: Double = 1.3,
    val intervalModifier: Double = 1.0,
    val againEaseDelta: Double = -0.20,
    val hardEaseDelta: Double = -0.15,
    val easyEaseDelta: Double = 0.15,
    val minimumEase: Double = 1.3,
    val maximumIntervalDays: Int = 36_500,
    /** Fraction of the old interval kept after a lapse (0 = start over at the minimum). */
    val lapseIntervalMultiplier: Double = 0.0,
    val minimumLapseIntervalDays: Int = 1,
    val fuzzEnabled: Boolean = true,
    val leechThreshold: Int = 8,
    val showAgain: Boolean = true,
)

data class AnswerResult(
    val newState: SchedulingState,
    /** Delay from "now" until the card is due again. */
    val delayMs: Long,
    /** True when this answer made the card cross a leech threshold. */
    val becameLeech: Boolean,
)
