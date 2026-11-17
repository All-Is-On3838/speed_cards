package com.speedcards.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.speedcards.data.db.SpeedCardsDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpeedCardsDatabase::class.java)

    @Test fun v1To2MutesChineseStarterFrontsOnly() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO scheduling_preset (id, name, learningSteps, relearningSteps, graduatingIntervalDays, easyIntervalDays, startingEase, hardMultiplier, easyBonus, intervalModifier, againEaseDelta, hardEaseDelta, easyEaseDelta, minimumEase, maximumIntervalDays, lapseIntervalMultiplier, newPerDay, reviewsPerDay, fuzzEnabled, showAgain, leechThreshold) VALUES (1, 'p', '10', '10', 1, 4, 2.5, 1.2, 1.3, 1.0, -0.2, -0.15, 0.15, 1.3, 36500, 0.0, 20, 200, 1, 1, 8)")
            db.execSQL("INSERT INTO deck (id, name, presetId, builtIn, kind, created) VALUES (1, 'd', 1, 1, 'NORMAL', 0)")
            db.execSQL("INSERT INTO note_type (id, name, kind, fields, templates, builtIn) VALUES (1, 't', 'STANDARD', '[]', '[]', 1)")
            fun note(id: Int, guid: String, sort: String) = db.execSQL(
                "INSERT INTO note (id, guid, noteTypeId, deckId, fields, sortField, created, modified) VALUES ($id, '$guid', 1, 1, '[]', '$sort', 0, 0)"
            )
            note(1, "seed-zh-travel-001", "厕所")
            note(2, "seed-es-travel-001", "baño")
            note(3, "user-1", "猫")
        }
        helper.runMigrationsAndValidate("migration-test", 2, true, SpeedCardsDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id, readFront FROM note ORDER BY id").use { c ->
                val values = buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) }
                assertEquals(listOf(1 to 0, 2 to 1, 3 to 1), values)
            }
        }
    }

    @Test fun v2To3AddsFoldersAndLeavesDecksUnfiled() {
        helper.createDatabase("migration-test-3", 2).use { db ->
            db.execSQL("INSERT INTO scheduling_preset (id, name, learningSteps, relearningSteps, graduatingIntervalDays, easyIntervalDays, startingEase, hardMultiplier, easyBonus, intervalModifier, againEaseDelta, hardEaseDelta, easyEaseDelta, minimumEase, maximumIntervalDays, lapseIntervalMultiplier, newPerDay, reviewsPerDay, fuzzEnabled, showAgain, leechThreshold) VALUES (1, 'p', '10', '10', 1, 4, 2.5, 1.2, 1.3, 1.0, -0.2, -0.15, 0.15, 1.3, 36500, 0.0, 20, 200, 1, 1, 8)")
            db.execSQL("INSERT INTO deck (id, name, presetId, builtIn, kind, created) VALUES (1, 'd', 1, 1, 'NORMAL', 0)")
            db.execSQL("INSERT INTO scheduling_preset (id, name, learningSteps, relearningSteps, graduatingIntervalDays, easyIntervalDays, startingEase, hardMultiplier, easyBonus, intervalModifier, againEaseDelta, hardEaseDelta, easyEaseDelta, minimumEase, maximumIntervalDays, lapseIntervalMultiplier, newPerDay, reviewsPerDay, fuzzEnabled, showAgain, leechThreshold) VALUES (2, 'Vocabulary', '10', '10', 1, 4, 2.5, 1.2, 1.3, 1.0, -0.2, -0.15, 0.15, 1.3, 36500, 0.0, 20, 200, 1, 1, 8)")
        }
        helper.runMigrationsAndValidate("migration-test-3", 3, true, SpeedCardsDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT folderId FROM deck WHERE id = 1").use { c -> c.moveToFirst(); assertEquals(true, c.isNull(0)) }
            db.query("SELECT COUNT(*) FROM folder").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            // The untouched Vocabulary preset drops to 5 new cards a day; other presets keep theirs.
            db.query("SELECT name, newPerDay FROM scheduling_preset ORDER BY id").use { c ->
                val values = buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
                assertEquals(listOf("p" to 20, "Vocabulary" to 5), values)
            }
        }
    }
}
