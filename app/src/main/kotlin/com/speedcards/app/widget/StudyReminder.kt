package com.speedcards.app.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.speedcards.app.MainActivity
import com.speedcards.app.R
import com.speedcards.app.backup.ReminderLogic
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.quickadd.QuickAddActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Daily nudge when cards are due. Silent when nothing is due. */
@HiltWorker
class StudyReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val widgets: WidgetUpdater,
    private val prefs: UserPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!prefs.studyReminder.first().enabled) return Result.success()
        val s = widgets.summary()
        widgets.update()
        if (s.due == 0) return Result.success()
        val text = buildString {
            append("${s.due} card${if (s.due == 1) "" else "s"} due")
            if (s.streakDays > 0) append(if (s.studiedToday) " · ${s.streakDays}-day streak" else " · keep your ${s.streakDays}-day streak going")
        }
        show(applicationContext, text)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "study_reminder"
        private const val UNIQUE = "study-reminder"
        private const val NOTIFICATION_ID = 2001

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Study reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A daily reminder when you have cards to study."
                }
            )
        }

        fun schedule(context: Context, enabled: Boolean, minuteOfDay: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(UNIQUE); return }
            val request = PeriodicWorkRequestBuilder<StudyReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(ReminderLogic.delayUntil(minuteOfDay, LocalDateTime.now()), TimeUnit.MILLISECONDS)
                .build()
            // Re-enqueue (not UPDATE): UPDATE keeps the original enqueue time, so a changed time could fire at once.
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }

        private fun show(context: Context, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time to study")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }
}

/** Quick Settings tile: opens the quick-add sheet from anywhere (paste what you copied with one tap). */
class QuickAddTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            icon = Icon.createWithResource(this@QuickAddTileService, R.drawable.ic_notification)
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
