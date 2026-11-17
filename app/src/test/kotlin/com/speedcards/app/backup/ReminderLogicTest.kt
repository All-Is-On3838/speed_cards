package com.speedcards.app.backup

import com.speedcards.app.prefs.BackupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderLogicTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun remindsWhenThingsChangedSinceAnOlderBackup() {
        val state = BackupState(lastBackupAt = at(13, 21))
        assertTrue(ReminderLogic.shouldRemind(state, lastChangeAt = at(14, 9), now = at(15, 21), zone = zone))
    }

    @Test fun skipsWhenAlreadyBackedUpToday() {
        val state = BackupState(lastBackupAt = at(15, 8))
        assertFalse(ReminderLogic.shouldRemind(state, lastChangeAt = at(15, 12), now = at(15, 21), zone = zone))
    }

    @Test fun skipsWhenNothingChanged() {
        val state = BackupState(lastBackupAt = at(10, 21))
        assertFalse(ReminderLogic.shouldRemind(state, lastChangeAt = at(10, 20), now = at(15, 21), zone = zone))
    }

    @Test fun skipsWhenTurnedOffOrSkippedToday() {
        val base = BackupState(lastBackupAt = at(13, 21))
        assertFalse(ReminderLogic.shouldRemind(base.copy(reminderEnabled = false), at(14, 9), at(15, 21), zone))
        val today = java.time.LocalDate.of(2026, 10, 15).toEpochDay()
        assertFalse(ReminderLogic.shouldRemind(base.copy(skippedEpochDay = today), at(14, 9), at(15, 21), zone))
    }

    @Test fun firstEverReminderWhenCardsExist() {
        assertTrue(ReminderLogic.shouldRemind(BackupState(), lastChangeAt = at(15, 9), now = at(15, 21), zone = zone))
        assertFalse(ReminderLogic.shouldRemind(BackupState(), lastChangeAt = 0, now = at(15, 21), zone = zone))
    }

    @Test fun delayRollsToTomorrowOncePassed() {
        val now = LocalDateTime.of(2026, 10, 15, 20, 30)
        assertEquals(30 * 60_000L, ReminderLogic.delayUntil(21 * 60, now))
        assertEquals((24 * 60 - 30) * 60_000L, ReminderLogic.delayUntil(20 * 60, now))
    }
}
