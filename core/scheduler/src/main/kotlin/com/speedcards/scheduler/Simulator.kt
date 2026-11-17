package com.speedcards.scheduler

data class SimulationStep(
    val rating: Rating,
    val state: SchedulingState,
    val delayMs: Long,
    /** Time since the first answer, when this card next comes due. */
    val dueFromStartMs: Long,
)

/**
 * Previews how a preset behaves for an answer pattern, assuming each answer is given
 * exactly when the card comes due. Fuzz is disabled so the result is deterministic.
 */
fun simulate(config: SchedulerConfig, ratings: List<Rating>): List<SimulationStep> {
    val scheduler = Scheduler(config.copy(fuzzEnabled = false))
    var state = SchedulingState()
    var now = 0L
    return ratings.map { rating ->
        val result = scheduler.answer(state, rating, now)
        state = result.newState
        now = state.due
        SimulationStep(rating, state, result.delayMs, state.due)
    }
}

/** Parses "A H M E" (also "again hard medium easy", case-insensitive, any separator). */
fun parseRatings(pattern: String): List<Rating> =
    pattern.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { token ->
        when (token.lowercase()) {
            "a", "again" -> Rating.AGAIN
            "h", "hard" -> Rating.HARD
            "m", "medium", "g", "good" -> Rating.MEDIUM
            "e", "easy" -> Rating.EASY
            else -> null
        }
    }

/** Short human-readable duration: "10m", "3h", "5d", "2.3mo", "1.2y". */
fun formatDelay(ms: Long): String {
    val minutes = ms / MINUTE_MS.toDouble()
    val days = ms / DAY_MS.toDouble()
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes.toInt()}m"
        ms < DAY_MS -> "${(ms / HOUR_MS.toDouble()).roundOne()}h"
        days < 30 -> "${days.toInt()}d"
        days < 365 -> "${(days / 30.0).roundOne()}mo"
        else -> "${(days / 365.0).roundOne()}y"
    }
}

private fun Double.roundOne(): String {
    val r = Math.round(this * 10) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
