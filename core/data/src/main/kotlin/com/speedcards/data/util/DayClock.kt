package com.speedcards.data.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The study day starts at [rolloverHour] local time (default 4am, like Anki),
 * so late-night reviews count toward the same day.
 */
class DayClock(
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val rolloverHour: Int = 4,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun now(): Long = clock()

    fun startOfDay(at: Long = now()): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone())
        var start = t.toLocalDate().atStartOfDay(zone()).plusHours(rolloverHour.toLong())
        if (t.isBefore(start)) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }

    fun endOfDay(at: Long = now()): Long {
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startOfDay(at)), zone())
        return start.plusDays(1).toInstant().toEpochMilli()
    }
}
