package com.speedcards.app.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.speedcards.data.template.RenderOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** Small remembered choices: last deck/note type used and which fields are sticky. */
@Singleton
class UserPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val lastDeck = longPreferencesKey("last_deck_id")
        val lastNoteType = longPreferencesKey("last_note_type_id")
        val quickAddDeck = longPreferencesKey("quick_add_deck_id")
        fun sticky(noteTypeId: Long) = stringPreferencesKey("sticky_$noteTypeId")
        val autoplay = booleanPreferencesKey("audio_autoplay")
        val tts = booleanPreferencesKey("audio_tts_fallback")
        val speed = floatPreferencesKey("audio_speed")
        val muted = booleanPreferencesKey("audio_muted")
        val nasFolder = stringPreferencesKey("backup_folder_uri")
        val lastBackup = longPreferencesKey("backup_last_at")
        val lastError = stringPreferencesKey("backup_last_error")
        val reminderOn = booleanPreferencesKey("backup_reminder_on")
        val reminderMinute = intPreferencesKey("backup_reminder_minute")
        val skipDay = longPreferencesKey("backup_reminder_skip_day")
        val autoBackupOn = booleanPreferencesKey("backup_auto_on")
        val autoBackupMinute = intPreferencesKey("backup_auto_minute")
        val traditionalFirst = booleanPreferencesKey("display_traditional_first")
        val toneColors = booleanPreferencesKey("display_tone_colors")
        val studyReminderOn = booleanPreferencesKey("study_reminder_on")
        val studyReminderMinute = intPreferencesKey("study_reminder_minute")
        val collapsedDecks = stringSetPreferencesKey("home_collapsed_decks")
    }

    /** Decks whose sub-decks are hidden on the home screen. */
    val collapsedDecks: Flow<Set<Long>> = context.dataStore.data.map { p ->
        p[Keys.collapsedDecks].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun setDeckCollapsed(deckId: Long, collapsed: Boolean) = context.dataStore.edit {
        val current = it[Keys.collapsedDecks].orEmpty()
        it[Keys.collapsedDecks] = if (collapsed) current + deckId.toString() else current - deckId.toString()
    }

    val studyReminder: Flow<StudyReminderSettings> = context.dataStore.data.map {
        StudyReminderSettings(it[Keys.studyReminderOn] ?: true, it[Keys.studyReminderMinute] ?: (19 * 60))
    }

    suspend fun setStudyReminder(s: StudyReminderSettings) = context.dataStore.edit {
        it[Keys.studyReminderOn] = s.enabled
        it[Keys.studyReminderMinute] = s.minuteOfDay
    }

    /** How Chinese cards are shown. */
    val displaySettings: Flow<RenderOptions> = context.dataStore.data.map {
        RenderOptions(traditionalFirst = it[Keys.traditionalFirst] ?: false, toneColors = it[Keys.toneColors] ?: true)
    }

    suspend fun setDisplaySettings(options: RenderOptions) = context.dataStore.edit {
        it[Keys.traditionalFirst] = options.traditionalFirst
        it[Keys.toneColors] = options.toneColors
    }

    val backupState: Flow<BackupState> = context.dataStore.data.map {
        BackupState(
            folderUri = it[Keys.nasFolder],
            lastBackupAt = it[Keys.lastBackup] ?: 0L,
            lastError = it[Keys.lastError],
            reminderEnabled = it[Keys.reminderOn] ?: true,
            reminderMinuteOfDay = it[Keys.reminderMinute] ?: (21 * 60),
            skippedEpochDay = it[Keys.skipDay] ?: -1L,
            autoEnabled = it[Keys.autoBackupOn] ?: false,
            autoMinuteOfDay = it[Keys.autoBackupMinute] ?: (3 * 60),
        )
    }

    suspend fun backupStateNow(): BackupState = backupState.first()

    suspend fun setBackupFolder(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.nasFolder) else it[Keys.nasFolder] = uri
    }

    /** A snapshot was written to the backup folder and verified. */
    suspend fun recordBackup(at: Long) = context.dataStore.edit {
        it[Keys.lastBackup] = at
        it.remove(Keys.lastError)
    }

    suspend fun recordBackupFailure(message: String) = context.dataStore.edit { it[Keys.lastError] = message }

    suspend fun setReminder(enabled: Boolean, minuteOfDay: Int) = context.dataStore.edit {
        it[Keys.reminderOn] = enabled
        it[Keys.reminderMinute] = minuteOfDay
    }

    suspend fun setAutoBackup(enabled: Boolean, minuteOfDay: Int) = context.dataStore.edit {
        it[Keys.autoBackupOn] = enabled
        it[Keys.autoBackupMinute] = minuteOfDay
    }

    suspend fun skipReminderOn(epochDay: Long) = context.dataStore.edit { it[Keys.skipDay] = epochDay }

    val audioSettings: Flow<AudioSettings> = context.dataStore.data.map {
        AudioSettings(
            autoplay = it[Keys.autoplay] ?: true,
            ttsFallback = it[Keys.tts] ?: true,
            speed = it[Keys.speed] ?: 1f,
            muted = it[Keys.muted] ?: false,
        )
    }

    suspend fun setAudioSettings(settings: AudioSettings) = context.dataStore.edit {
        it[Keys.autoplay] = settings.autoplay
        it[Keys.tts] = settings.ttsFallback
        it[Keys.speed] = settings.speed
        it[Keys.muted] = settings.muted
    }

    suspend fun setMuted(muted: Boolean) = context.dataStore.edit { it[Keys.muted] = muted }

    suspend fun lastDeckId(): Long? = context.dataStore.data.first()[Keys.lastDeck]
    suspend fun lastNoteTypeId(): Long? = context.dataStore.data.first()[Keys.lastNoteType]
    suspend fun quickAddDeckId(): Long? = context.dataStore.data.first()[Keys.quickAddDeck]

    suspend fun rememberAdd(deckId: Long, noteTypeId: Long) = context.dataStore.edit {
        it[Keys.lastDeck] = deckId
        it[Keys.lastNoteType] = noteTypeId
    }

    suspend fun rememberQuickAddDeck(deckId: Long) = context.dataStore.edit { it[Keys.quickAddDeck] = deckId }

    /** Sticky field indexes for a note type. */
    suspend fun stickyFields(noteTypeId: Long): Set<Int> =
        context.dataStore.data.first()[Keys.sticky(noteTypeId)]
            ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    suspend fun setStickyFields(noteTypeId: Long, indexes: Set<Int>) =
        context.dataStore.edit { it[Keys.sticky(noteTypeId)] = indexes.sorted().joinToString(",") }
}

data class AudioSettings(
    /** Play a side's audio automatically when it is shown. */
    val autoplay: Boolean = true,
    /** Read the text aloud when a side has no recording. */
    val ttsFallback: Boolean = true,
    /** Playback and speech speed, 0.5–1.5. */
    val speed: Float = 1f,
    /** No card audio at all (e.g. studying somewhere quiet). Toggled from the home screen. */
    val muted: Boolean = false,
)

data class BackupState(
    /** Folder chosen with the system folder picker (can be a NAS share), as a persisted tree URI. */
    val folderUri: String? = null,
    /** When a snapshot was last written to the backup folder. */
    val lastBackupAt: Long = 0L,
    /** Why the most recent backup failed, if it did. */
    val lastError: String? = null,
    val reminderEnabled: Boolean = true,
    val reminderMinuteOfDay: Int = 21 * 60,
    val skippedEpochDay: Long = -1L,
    /** Back up by itself once a day at [autoMinuteOfDay] (minutes after midnight). */
    val autoEnabled: Boolean = false,
    val autoMinuteOfDay: Int = 3 * 60,
)

data class StudyReminderSettings(val enabled: Boolean = true, val minuteOfDay: Int = 19 * 60)
