package com.speedcards.app.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.speedcards.app.BuildConfig
import com.speedcards.app.prefs.BackupState
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.backup.BackupManager
import com.speedcards.data.backup.Retention
import com.speedcards.data.repo.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Outcome of one backup run: did the snapshot land in the folder? */
data class BackupOutcome(val success: Boolean, val message: String)

/**
 * Snapshot to the phone, then upload pending snapshots and new media to the chosen folder.
 * Started by you (Back up now / the reminder's button); there are no automatic retries.
 * The daily automatic backup is [AutoBackupWorker].
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupManager,
    private val media: MediaRepository,
    private val nas: NasFolder,
    private val prefs: UserPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing…")

    private fun foreground(text: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(BackupNotifications.ID_PROGRESS, BackupNotifications.progress(applicationContext, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else ForegroundInfo(BackupNotifications.ID_PROGRESS, BackupNotifications.progress(applicationContext, text))

    override suspend fun doWork(): Result {
        val outcome = runBackup(
            applicationContext, backups, media, nas, prefs,
            onProgress = { runCatching { setForeground(foreground(it)) } },
        )
        BackupNotifications.cancel(applicationContext, BackupNotifications.ID_PROGRESS)
        BackupNotifications.cancel(applicationContext, BackupNotifications.ID_REMINDER)
        BackupNotifications.showResult(
            applicationContext,
            title = if (outcome.success) "Backup complete" else "Backup failed",
            text = outcome.message,
            retry = !outcome.success,
        )
        return Result.success(workDataOf(KEY_MESSAGE to outcome.message, KEY_SUCCESS to outcome.success))
    }

    companion object {
        const val UNIQUE_NAME = "manual-backup"
        const val KEY_MESSAGE = "message"
        const val KEY_SUCCESS = "success"

        /**
         * Writes a snapshot into the chosen folder and checks it arrived intact (size matches),
         * then copies any new media and prunes old backups. Success means the snapshot is in the folder.
         */
        /** One backup at a time, so a manual and an automatic run never write the same file together. */
        private val running = Mutex()

        suspend fun runBackup(
            context: Context,
            backups: BackupManager,
            media: MediaRepository,
            nas: NasFolder,
            prefs: UserPrefs,
            onProgress: suspend (String) -> Unit = {},
        ): BackupOutcome = running.withLock { runBackupLocked(context, backups, media, nas, prefs, onProgress) }

        private suspend fun runBackupLocked(
            context: Context,
            backups: BackupManager,
            media: MediaRepository,
            nas: NasFolder,
            prefs: UserPrefs,
            onProgress: suspend (String) -> Unit,
        ): BackupOutcome {
            val folder = prefs.backupStateNow().folderUri?.let(Uri::parse)
                ?: return fail(prefs, "Choose a backup folder first (Backup & restore › Choose folder).")
            val now = System.currentTimeMillis()
            val name = BackupManager.fileName(now)
            val snapshot = File(context.cacheDir, name)
            return try {
                onProgress("Saving snapshot…")
                backups.snapshot(snapshot, includeMedia = false, appVersion = BuildConfig.VERSION_NAME)
                onProgress("Writing to ${nas.displayName(folder)}…")
                nas.upload(folder, snapshot, name) // throws unless the written size matches
                // Media the folder doesn't have yet, so restores have every recording.
                val remote = nas.mediaNames(folder)
                val missing = media.mediaDir.listFiles().orEmpty().filter { it.isFile && it.name !in remote }
                missing.forEachIndexed { i, f ->
                    if (i % 5 == 0) onProgress("Copying media ${i + 1}/${missing.size}…")
                    nas.upload(folder, f, f.name, subfolder = "media")
                }
                val remoteBackups = nas.listBackups(folder)
                val keep = Retention.keep(remoteBackups.associate { it.name to it.timestamp })
                remoteBackups.filter { it.name !in keep }.forEach { nas.delete(it.uri) }
                prefs.recordBackup(now)
                BackupOutcome(true, "Saved $name to ${nas.displayName(folder)}.")
            } catch (e: Exception) {
                fail(prefs, "The backup couldn't be written to the folder: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                snapshot.delete()
            }
        }

        private suspend fun fail(prefs: UserPrefs, message: String): BackupOutcome {
            prefs.recordBackupFailure(message)
            return BackupOutcome(false, message)
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // KEEP: tapping twice never starts two backups.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Decides whether the scheduled automatic backup has anything to do. */
object AutoBackupLogic {
    /** Attempts per day before giving up and notifying (the NAS may be asleep or off Wi-Fi). */
    const val MAX_ATTEMPTS = 4

    fun shouldRun(state: BackupState, lastChangeAt: Long): Boolean =
        state.autoEnabled && state.folderUri != null && lastChangeAt > state.lastBackupAt
}

/**
 * The daily automatic backup, at the time chosen in Backup & restore. Runs the same backup as
 * Back up now, but only when something changed since the last backup. Quiet when it works; if the
 * folder can't be reached it retries (30 min, then longer) and notifies only once it gives up.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupManager,
    private val media: MediaRepository,
    private val nas: NasFolder,
    private val prefs: UserPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing…")

    private fun foreground(text: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(BackupNotifications.ID_PROGRESS, BackupNotifications.progress(applicationContext, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else ForegroundInfo(BackupNotifications.ID_PROGRESS, BackupNotifications.progress(applicationContext, text))

    override suspend fun doWork(): Result {
        if (!AutoBackupLogic.shouldRun(prefs.backupStateNow(), backups.lastChangeAt())) return Result.success()
        val outcome = BackupWorker.runBackup(
            applicationContext, backups, media, nas, prefs,
            // Android may not allow a foreground notification from the background; the backup runs either way.
            onProgress = { runCatching { setForeground(foreground(it)) } },
        )
        BackupNotifications.cancel(applicationContext, BackupNotifications.ID_PROGRESS)
        if (outcome.success) {
            // Today's reminder isn't needed any more.
            BackupNotifications.cancel(applicationContext, BackupNotifications.ID_REMINDER)
            return Result.success()
        }
        if (runAttemptCount + 1 < AutoBackupLogic.MAX_ATTEMPTS) return Result.retry()
        BackupNotifications.showResult(applicationContext, title = "Automatic backup failed", text = outcome.message, retry = true)
        return Result.success()
    }

    companion object {
        const val UNIQUE_DAILY = "auto-backup"

        /**
         * Schedules the daily backup at [minuteOfDay], or cancels it. [timeChanged] re-plans it from now;
         * otherwise (e.g. at app start) an already scheduled run is left alone, so a backup in progress
         * is never cancelled just because the app was opened.
         */
        fun schedule(context: Context, enabled: Boolean, minuteOfDay: Int, timeChanged: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(UNIQUE_DAILY); return }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(ReminderLogic.delayUntil(minuteOfDay, LocalDateTime.now()), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            val policy = if (timeChanged) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
            wm.enqueueUniquePeriodicWork(UNIQUE_DAILY, policy, request)
        }
    }
}

/** Decides whether today's reminder should be shown. */
object ReminderLogic {
    fun shouldRemind(state: BackupState, lastChangeAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!state.reminderEnabled) return false
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        if (state.skippedEpochDay == today.toEpochDay()) return false
        val lastBackupDay = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(state.lastBackupAt), zone)
        if (state.lastBackupAt > 0 && lastBackupDay == today) return false
        if (lastChangeAt <= state.lastBackupAt) return false // nothing new since the last backup
        return true
    }

    /** Delay until the next [minuteOfDay] (today if still ahead, else tomorrow). */
    fun delayUntil(minuteOfDay: Int, now: LocalDateTime): Long {
        var next = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        return ChronoUnit.MILLIS.between(now, next)
    }

    fun message(state: BackupState, reviewsSince: Int, now: Long): String {
        val last = if (state.lastBackupAt == 0L) "never" else {
            val days = ChronoUnit.DAYS.between(
                LocalDate.ofInstant(java.time.Instant.ofEpochMilli(state.lastBackupAt), ZoneId.systemDefault()),
                LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault()),
            )
            when (days) { 0L -> "today"; 1L -> "yesterday"; else -> "$days days ago" }
        }
        return "Last backup: $last · $reviewsSince review${if (reviewsSince == 1) "" else "s"} since"
    }
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupManager,
    private val prefs: UserPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = prefs.backupStateNow()
        val now = System.currentTimeMillis()
        if (ReminderLogic.shouldRemind(state, backups.lastChangeAt(), now)) {
            BackupNotifications.showReminder(applicationContext, ReminderLogic.message(state, backups.reviewsSince(state.lastBackupAt), now))
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_DAILY = "backup-reminder"
        const val UNIQUE_SNOOZE = "backup-reminder-snooze"

        /** (Re)schedules the daily reminder at [minuteOfDay], or cancels it. */
        fun schedule(context: Context, enabled: Boolean, minuteOfDay: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(UNIQUE_DAILY); return }
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(ReminderLogic.delayUntil(minuteOfDay, LocalDateTime.now()), TimeUnit.MILLISECONDS)
                .build()
            // Re-enqueue (not UPDATE): UPDATE keeps the original enqueue time, so a changed time could fire at once.
            wm.enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }

        fun snooze(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_SNOOZE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun prefs(): UserPrefs
}

/** Buttons on the reminder and result notifications. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BackupNotifications.cancel(context, BackupNotifications.ID_REMINDER)
        when (intent.action) {
            ACTION_BACKUP_NOW -> BackupWorker.enqueue(context)
            ACTION_SNOOZE -> ReminderWorker.snooze(context)
            ACTION_SKIP_TODAY -> {
                val prefs = EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java).prefs()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { prefs.skipReminderOn(LocalDate.now().toEpochDay()) } finally { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_BACKUP_NOW = "com.speedcards.app.BACKUP_NOW"
        const val ACTION_SNOOZE = "com.speedcards.app.BACKUP_SNOOZE"
        const val ACTION_SKIP_TODAY = "com.speedcards.app.BACKUP_SKIP_TODAY"

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, BackupActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
