package com.speedcards.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.speedcards.app.backup.BackupNotifications
import com.speedcards.app.backup.ReminderWorker
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.widget.StudyReminderWorker
import kotlinx.coroutines.flow.first
import com.speedcards.data.backup.BackupManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SpeedCardsApp : Application(), Configuration.Provider {
    @Inject lateinit var startup: AppStartup
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prefs: UserPrefs

    /** True when this launch finished a restore (the screen shows a confirmation). */
    var restoredOnLaunch = false
        private set

    /** Returns true once after a restore, so the confirmation shows a single time. */
    fun consumeRestored(): Boolean = restoredOnLaunch.also { restoredOnLaunch = false }

    override fun onCreate() {
        // A staged restore must be swapped in before anything opens the database.
        restoredOnLaunch = BackupManager.applyPendingRestore(this)
        super.onCreate()
        BackupNotifications.createChannels(this)
        StudyReminderWorker.createChannel(this)
        startup.begin()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val state = prefs.backupStateNow()
            ReminderWorker.schedule(this@SpeedCardsApp, state.reminderEnabled, state.reminderMinuteOfDay)
            val study = prefs.studyReminder.first()
            StudyReminderWorker.schedule(this@SpeedCardsApp, study.enabled, study.minuteOfDay)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
