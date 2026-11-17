package com.speedcards.app.backup

import com.speedcards.app.prefs.BackupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoBackupLogicTest {
    private val on = BackupState(folderUri = "content://nas/tree/backups", lastBackupAt = 1_000, autoEnabled = true)

    @Test fun runsWhenOnWithAFolderAndSomethingChanged() {
        assertTrue(AutoBackupLogic.shouldRun(on, lastChangeAt = 2_000))
    }

    @Test fun skipsWhenNothingChangedSinceTheLastBackup() {
        assertFalse(AutoBackupLogic.shouldRun(on, lastChangeAt = 1_000))
        assertFalse(AutoBackupLogic.shouldRun(on, lastChangeAt = 500))
    }

    @Test fun skipsWhenTurnedOff() {
        assertFalse(AutoBackupLogic.shouldRun(on.copy(autoEnabled = false), lastChangeAt = 2_000))
    }

    @Test fun skipsWithoutAFolder() {
        assertFalse(AutoBackupLogic.shouldRun(on.copy(folderUri = null), lastChangeAt = 2_000))
    }

    @Test fun offByDefaultAt3am() {
        val defaults = BackupState()
        assertFalse(defaults.autoEnabled)
        assertEquals(3 * 60, defaults.autoMinuteOfDay)
    }

    @Test fun firstRunIsTheNextOccurrenceOfTheChosenTime() {
        val evening = LocalDateTime.of(2026, 11, 17, 22, 0)
        // 03:00 is 5 hours away, tonight.
        assertEquals(5 * 3_600_000L, ReminderLogic.delayUntil(3 * 60, evening))
        val earlyMorning = LocalDateTime.of(2026, 11, 17, 2, 30)
        assertEquals(30 * 60_000L, ReminderLogic.delayUntil(3 * 60, earlyMorning))
    }
}
