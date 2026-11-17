package com.speedcards.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.speedcards.app.prefs.StudyReminderSettings
import com.speedcards.app.widget.StudyReminderWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.ui.common.ToneColors
import com.speedcards.data.template.RenderOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPrefs,
) : ViewModel() {
    val display = prefs.displaySettings.stateIn(viewModelScope, SharingStarted.Eagerly, RenderOptions())
    val studyReminder = prefs.studyReminder.stateIn(viewModelScope, SharingStarted.Eagerly, StudyReminderSettings())
    fun update(options: RenderOptions) = viewModelScope.launch { prefs.setDisplaySettings(options) }
    fun updateReminder(s: StudyReminderSettings) = viewModelScope.launch {
        prefs.setStudyReminder(s)
        StudyReminderWorker.schedule(context, s.enabled, s.minuteOfDay)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val d by vm.display.collectAsStateWithLifecycle()
    val reminder by vm.studyReminder.collectAsStateWithLifecycle()
    var pickTime by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Chinese cards", style = MaterialTheme.typography.titleMedium)
            Text("Main script (the other is shown smaller underneath)")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Simplified 简体", true to "Traditional 繁體").forEachIndexed { i, (trad, label) ->
                    SegmentedButton(
                        selected = d.traditionalFirst == trad,
                        onClick = { vm.update(d.copy(traditionalFirst = trad)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colour pinyin by tone")
                    Text(
                        buildAnnotatedString {
                            listOf("1st " to 1, "2nd " to 2, "3rd " to 3, "4th " to 4, "neutral" to 0).forEach { (t, tone) ->
                                withStyle(SpanStyle(color = ToneColors[tone])) { append(t) }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(d.toneColors, { vm.update(d.copy(toneColors = it)) })
            }

            HorizontalDivider()
            Text("Study reminder", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remind me when cards are due", Modifier.weight(1f))
                Switch(reminder.enabled, { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.updateReminder(reminder.copy(enabled = on))
                })
            }
            if (reminder.enabled) {
                TextButton(onClick = { pickTime = true }) {
                    Text("At %02d:%02d".format(reminder.minuteOfDay / 60, reminder.minuteOfDay % 60))
                }
            }
            Text(
                "Tip: add the Speed Cards widget to your home screen, the \"Add card\" tile to Quick Settings, " +
                    "or long-press the app icon for Quick add.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pickTime) {
        val t = rememberTimePickerState(reminder.minuteOfDay / 60, reminder.minuteOfDay % 60)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Study reminder time") },
            text = { TimePicker(t) },
            confirmButton = { TextButton(onClick = { vm.updateReminder(reminder.copy(minuteOfDay = t.hour * 60 + t.minute)); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}
