package com.speedcards.app.ui.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.speedcards.app.BuildConfig
import com.speedcards.app.backup.AutoBackupWorker
import com.speedcards.app.backup.BackupWorker
import com.speedcards.app.backup.NasFolder
import com.speedcards.app.backup.ReminderWorker
import com.speedcards.app.backup.RemoteBackup
import com.speedcards.app.prefs.BackupState
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.backup.BackupManager
import com.speedcards.data.backup.StagedRestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

sealed interface RestoreSource {
    data class Local(val uri: Uri, val name: String) : RestoreSource
    data class Nas(val backup: RemoteBackup) : RestoreSource
    val label get() = when (this) { is Local -> name; is Nas -> backup.name }
}

data class BackupUi(
    val nasBackups: List<RemoteBackup>? = null,
    val nasError: String? = null,
    val message: String? = null,
    val confirmRestore: RestoreSource? = null,
    val restoring: Boolean = false,
    val staged: StagedRestore? = null,
    val exporting: Boolean = false,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPrefs,
    private val nas: NasFolder,
    private val backups: BackupManager,
) : ViewModel() {
    val backupState: StateFlow<BackupState> = prefs.backupState.stateIn(viewModelScope, SharingStarted.Eagerly, BackupState())
    val running: StateFlow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val ui = MutableStateFlow(BackupUi())

    init { refreshNas() }

    fun folderName(state: BackupState) = state.folderUri?.let { nas.displayName(Uri.parse(it)) }

    fun chooseFolder(uri: Uri) = viewModelScope.launch {
        runCatching { nas.persistPermission(uri) }.onFailure { ui.update { u -> u.copy(message = "Couldn't keep access to that folder.") }; return@launch }
        backupState.value.folderUri?.let { old -> if (old != uri.toString()) nas.releasePermission(Uri.parse(old)) }
        prefs.setBackupFolder(uri.toString())
        refreshNas()
    }

    fun refreshNas() = viewModelScope.launch {
        val folder = prefs.backupStateNow().folderUri?.let(Uri::parse) ?: return@launch ui.update { it.copy(nasBackups = null, nasError = null) }
        ui.update { it.copy(nasBackups = null, nasError = null) }
        val result = withTimeoutOrNull(15_000) { runCatching { nas.listBackups(folder) } }
        ui.update {
            when {
                result == null -> it.copy(nasError = "The NAS didn't answer. Is it on and are you on home Wi-Fi?")
                result.isFailure -> it.copy(nasError = result.exceptionOrNull()?.message ?: "Couldn't list backups.")
                else -> it.copy(nasBackups = result.getOrNull())
            }
        }
    }

    fun backupNow() = BackupWorker.enqueue(context)

    fun setReminder(enabled: Boolean, minuteOfDay: Int) = viewModelScope.launch {
        prefs.setReminder(enabled, minuteOfDay)
        ReminderWorker.schedule(context, enabled, minuteOfDay)
    }

    fun setAutoBackup(enabled: Boolean, minuteOfDay: Int) = viewModelScope.launch {
        prefs.setAutoBackup(enabled, minuteOfDay)
        AutoBackupWorker.schedule(context, enabled, minuteOfDay, timeChanged = true)
    }

    /** Full backup with all media, saved wherever you choose (e.g. Drive or Downloads) for keeping offline. */
    fun exportFull(dest: Uri) = viewModelScope.launch {
        ui.update { it.copy(exporting = true) }
        val result = runCatching {
            val tmp = File(context.cacheDir, "export.${BackupManager.EXTENSION}")
            backups.snapshot(tmp, includeMedia = true, appVersion = BuildConfig.VERSION_NAME)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(dest, "w")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
            }
        }
        ui.update { it.copy(exporting = false, message = if (result.isSuccess) "Full backup exported." else "Export failed: ${result.exceptionOrNull()?.message}") }
    }

    fun askRestore(source: RestoreSource) = ui.update { it.copy(confirmRestore = source) }
    fun cancelRestore() { backups.cancelStagedRestore(); ui.update { it.copy(confirmRestore = null, staged = null) } }

    /** Unpacks the backup; it's swapped in when the app restarts. */
    fun restore(source: RestoreSource) = viewModelScope.launch {
        ui.update { it.copy(confirmRestore = null, restoring = true) }
        val folder = prefs.backupStateNow().folderUri?.let(Uri::parse)
        val result = runCatching {
            val zip = File(context.cacheDir, "restore.${BackupManager.EXTENSION}")
            when (source) {
                is RestoreSource.Local -> withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(source.uri)!!.use { input -> zip.outputStream().use { input.copyTo(it) } }
                }
                is RestoreSource.Nas -> nas.download(source.backup.uri, zip)
            }
            backups.stageRestore(zip) { entry, target ->
                folder != null && runCatching { nas.downloadMedia(folder, entry.name, target) }.getOrDefault(false)
            }.also { zip.delete() }
        }
        ui.update {
            result.fold(
                onSuccess = { staged -> it.copy(restoring = false, staged = staged) },
                onFailure = { e -> it.copy(restoring = false, message = "Restore failed: ${e.message}") },
            )
        }
    }

    fun messageShown() = ui.update { it.copy(message = null) }
}

/** Restarts the app so a staged restore is applied before the database opens. */
fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

fun backupAgeColor(lastBackupAt: Long, now: Long = System.currentTimeMillis()): Color? {
    if (lastBackupAt == 0L) return Color(0xFFD32F2F)
    val days = (now - lastBackupAt) / DateUtils.DAY_IN_MILLIS
    return when {
        days >= 7 -> Color(0xFFD32F2F)
        days >= 3 -> Color(0xFFF57C00)
        else -> null
    }
}

fun relative(at: Long): String =
    if (at == 0L) "never" else DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Which time the time picker is setting. */
private enum class TimeTarget { AUTO, REMINDER }

private fun hhmm(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.backupState.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickTime by remember { mutableStateOf<TimeTarget?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::chooseFolder) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportFull) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.askRestore(RestoreSource.Local(it, it.lastPathSegment?.substringAfterLast('/') ?: "backup")) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status
            val ageColor = backupAgeColor(state.lastBackupAt)
            Card(
                colors = if (ageColor != null) CardDefaults.cardColors(containerColor = ageColor.copy(alpha = 0.15f)) else CardDefaults.cardColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Last backup: ${relative(state.lastBackupAt)}", style = MaterialTheme.typography.titleMedium, color = ageColor ?: Color.Unspecified)
                    state.lastError?.let { Text("Last attempt failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            Button(onClick = { askNotifications(); vm.backupNow() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                if (running) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Backing up…") } else Text("Back up now")
            }

            // Folder
            HorizontalDivider()
            Text("NAS folder", style = MaterialTheme.typography.titleMedium)
            Text(
                vm.folderName(state) ?: "Not chosen. Pick a folder on your NAS (via an SMB documents provider app or your NAS's app) or anywhere on the phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (state.folderUri == null) "Choose folder" else "Change folder") }

            // Automatic backup
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.folderUri == null) "Choose a folder first."
                        else "Backs up once a day when something changed. You're only notified if it fails.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.autoEnabled,
                    onCheckedChange = { on -> if (on) askNotifications(); vm.setAutoBackup(on, state.autoMinuteOfDay) },
                    enabled = state.folderUri != null || state.autoEnabled,
                )
            }
            if (state.autoEnabled) {
                TextButton(onClick = { pickTime = TimeTarget.AUTO }) { Text("Back up at ${hhmm(state.autoMinuteOfDay)}") }
                Text(
                    "Android may run it a little later to save battery, and waits if the battery is low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // Reminder
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily reminder", style = MaterialTheme.typography.titleMedium)
                    Text("Skipped on days you've already backed up or changed nothing.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.reminderEnabled, { on -> if (on) askNotifications(); vm.setReminder(on, state.reminderMinuteOfDay) })
            }
            if (state.reminderEnabled) {
                TextButton(onClick = { pickTime = TimeTarget.REMINDER }) { Text("Remind me at ${hhmm(state.reminderMinuteOfDay)}") }
            }

            // Export / restore
            HorizontalDivider()
            Text("Full backup", style = MaterialTheme.typography.titleMedium)
            Text("One file with everything, including audio — for keeping offline.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { exportPicker.launch(BackupManager.fileName().replace(".scbackup", "-full.scbackup")) }, enabled = !ui.exporting) {
                Text(if (ui.exporting) "Exporting…" else "Export full backup")
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Restore", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.folderUri != null) TextButton(onClick = vm::refreshNas) { Text("Refresh") }
            }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("*/*")) }) { Text("Restore from a file…") }
            if (state.folderUri != null) {
                ui.nasError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val list = ui.nasBackups
                if (list == null && ui.nasError == null) CircularProgressIndicator()
                list?.take(15)?.forEach { b ->
                    ListItem(
                        headlineContent = { Text(DateUtils.formatDateTime(context, b.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)) },
                        supportingContent = { Text("${b.name} · ${Formatter.formatShortFileSize(context, b.size)}") },
                        trailingContent = { IconButton(onClick = { vm.askRestore(RestoreSource.Nas(b)) }) { Icon(Icons.Default.Restore, "Restore") } },
                    )
                }
                if (list?.isEmpty() == true) Text("No backups in the folder yet.", style = MaterialTheme.typography.bodySmall)
            }
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    pickTime?.let { target ->
        val initial = if (target == TimeTarget.AUTO) state.autoMinuteOfDay else state.reminderMinuteOfDay
        val t = rememberTimePickerState(initial / 60, initial % 60)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            title = { Text(if (target == TimeTarget.AUTO) "Automatic backup time" else "Reminder time") },
            text = { TimePicker(t) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = t.hour * 60 + t.minute
                    if (target == TimeTarget.AUTO) vm.setAutoBackup(true, minute) else vm.setReminder(true, minute)
                    pickTime = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text("Cancel") } },
        )
    }
    ui.confirmRestore?.let { source ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.label, style = MaterialTheme.typography.bodySmall)
                    Text("This replaces your current data. Back up first?")
                }
            },
            confirmButton = { TextButton(onClick = { vm.restore(source) }) { Text("Restore") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askNotifications(); vm.backupNow() }) { Text("Back up now") }
                    TextButton(onClick = vm::cancelRestore) { Text("Cancel") }
                }
            },
        )
    }
    if (ui.restoring) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Preparing restore…") }, text = { CircularProgressIndicator() })
    }
    ui.staged?.let { staged ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Ready to restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Backup from ${DateUtils.formatDateTime(context, staged.manifest.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}: " +
                        "${staged.manifest.noteCount} notes, ${staged.manifest.cardCount} cards.")
                    if (staged.missingMedia.isNotEmpty()) {
                        Text("${staged.missingMedia.size} audio/image files couldn't be found and will be missing.", color = MaterialTheme.colorScheme.error)
                    }
                    Text("Speed Cards will restart to finish.")
                }
            },
            confirmButton = { TextButton(onClick = { restartApp(context) }) { Text("Restart now") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
}
