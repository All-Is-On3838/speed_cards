package com.speedcards.app.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.speedcards.app.MainActivity
import com.speedcards.app.R

object BackupNotifications {
    const val CHANNEL_BACKUP = "backup"
    const val CHANNEL_REMINDER = "backup_reminder"
    const val ID_PROGRESS = 1001
    const val ID_RESULT = 1002
    const val ID_REMINDER = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BACKUP, "Backups", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Backup reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A daily nudge to back up when you've studied or edited cards."
            }
        )
    }

    fun progress(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backing up Speed Cards")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    fun showResult(context: Context, title: String, text: String, retry: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBackupScreen(context))
            .setAutoCancel(true)
        if (retry) builder.addAction(0, "Retry", BackupActionReceiver.pending(context, BackupActionReceiver.ACTION_BACKUP_NOW))
        notify(context, ID_RESULT, builder)
    }

    fun showReminder(context: Context, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Back up Speed Cards?")
            .setContentText(text)
            .setContentIntent(openBackupScreen(context))
            .setAutoCancel(true)
            .addAction(0, "Back up now", BackupActionReceiver.pending(context, BackupActionReceiver.ACTION_BACKUP_NOW))
            .addAction(0, "Snooze 1h", BackupActionReceiver.pending(context, BackupActionReceiver.ACTION_SNOOZE))
            .addAction(0, "Skip today", BackupActionReceiver.pending(context, BackupActionReceiver.ACTION_SKIP_TODAY))
        notify(context, ID_REMINDER, builder)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun openBackupScreen(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BACKUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
