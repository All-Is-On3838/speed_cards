package com.speedcards.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatorTest {

    @Test fun parsesShortAndLongTokens() {
        assertEquals(
            listOf(Rating.AGAIN, Rating.HARD, Rating.MEDIUM, Rating.EASY, Rating.MEDIUM),
            parseRatings("a, Hard m EASY good x"),
        )
    }

    @Test fun simulationIsCumulative() {
        val steps = simulate(SchedulerConfig(), parseRatings("M M M M"))
        assertEquals(4, steps.size)
        assertEquals(60 * MINUTE_MS, steps[0].delayMs)
        assertEquals(CardState.REVIEW, steps[2].state.state)
        assertEquals(3, steps[3].state.intervalDays)
        assertEquals(steps.sumOf { it.delayMs }, steps.last().dueFromStartMs)
    }

    @Test fun formatsDelays() {
        assertEquals("10m", formatDelay(10 * MINUTE_MS))
        assertEquals("1.5h", formatDelay(90 * MINUTE_MS))
        assertEquals("5d", formatDelay(5 * DAY_MS))
        assertEquals("2mo", formatDelay(60 * DAY_MS))
        assertEquals("1.2y", formatDelay(450 * DAY_MS))
    }
}
