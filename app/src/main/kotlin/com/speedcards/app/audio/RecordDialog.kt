package com.speedcards.app.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class RecordPhase { IDLE, RECORDING, RECORDED }

data class RecordState(
    val phase: RecordPhase = RecordPhase.IDLE,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val file: File? = null,
    val durationMs: Long = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val error: String? = null,
) {
    val isTrimmed get() = trimStartMs > 0 || (trimEndMs in 1 until durationMs)
}

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recorder: AudioRecorder,
    val player: AudioPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = _state.asStateFlow()
    private var ticker: Job? = null

    fun start() {
        player.stop()
        discardFile()
        val started = runCatching { recorder.start() }
        if (started.isFailure) {
            _state.update { it.copy(error = "Couldn't start the microphone.") }
            return
        }
        val begin = System.currentTimeMillis()
        _state.value = RecordState(phase = RecordPhase.RECORDING)
        ticker = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(elapsedMs = System.currentTimeMillis() - begin, level = recorder.level()) }
                delay(100)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        val file = recorder.stop()
        if (file == null) {
            _state.value = RecordState(error = "Recording was too short. Try again.")
            return
        }
        val duration = AudioFiles.durationMs(file)
        _state.value = RecordState(phase = RecordPhase.RECORDED, file = file, durationMs = duration, trimEndMs = duration)
    }

    fun setTrim(startMs: Long, endMs: Long) = _state.update { it.copy(trimStartMs = startMs, trimEndMs = endMs) }

    fun playSelection() {
        val s = _state.value
        val file = s.file ?: return
        player.playFile(file, clipStartMs = s.trimStartMs, clipEndMs = s.trimEndMs.takeIf { it < s.durationMs })
    }

    /** Returns the finished (and trimmed) file; the caller takes ownership of it. */
    suspend fun finish(): File? {
        player.stop()
        val s = _state.value
        val file = s.file ?: return null
        val result = if (s.isTrimmed) withContext(Dispatchers.IO) {
            runCatching { AudioFiles.trim(file, s.trimStartMs, s.trimEndMs) }.getOrNull()?.also { file.delete() } ?: file
        } else file
        _state.value = RecordState()
        return result
    }

    fun reset() {
        ticker?.cancel()
        recorder.stop()?.delete()
        player.stop()
        discardFile()
        _state.value = RecordState()
    }

    private fun discardFile() { _state.value.file?.delete() }

    override fun onCleared() = reset()
}

/**
 * Record → review/trim → save. [onSaved] receives a temporary .m4a file to store with MediaRepository.
 */
@Composable
fun RecordDialog(title: String, onDismiss: () -> Unit, onSaved: suspend (File) -> Unit, vm: RecordingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    val startRecording = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { vm.reset() } }

    AlertDialog(
        onDismissRequest = { vm.reset(); onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (state.phase) {
                    RecordPhase.IDLE -> {
                        Text("Tap the microphone and speak.")
                        BigButton(Icons.Default.Mic, "Start recording", onClick = startRecording)
                    }
                    RecordPhase.RECORDING -> {
                        Text(formatMs(state.elapsedMs), style = MaterialTheme.typography.headlineMedium)
                        LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        BigButton(Icons.Default.Stop, "Stop recording", onClick = vm::stop, danger = true)
                    }
                    RecordPhase.RECORDED -> {
                        Text("${formatMs(state.trimEndMs - state.trimStartMs)} selected of ${formatMs(state.durationMs)}")
                        if (state.durationMs > 0) {
                            RangeSlider(
                                value = state.trimStartMs.toFloat()..state.trimEndMs.toFloat(),
                                onValueChange = { r -> vm.setTrim(r.start.toLong(), r.endInclusive.toLong()) },
                                valueRange = 0f..state.durationMs.toFloat(),
                            )
                            Text("Drag the ends to trim silence.", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BigButton(
                                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                if (playing) "Stop" else "Play",
                                onClick = { if (playing) vm.player.stop() else vm.playSelection() },
                            )
                            OutlinedButton(onClick = startRecording) { Text("Re-record") }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.phase == RecordPhase.RECORDED,
                onClick = { scope.launch { vm.finish()?.let { onSaved(it) }; onDismiss() } },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { vm.reset(); onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = if (danger) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
        else IconButtonDefaults.filledIconButtonColors(),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp)) }
}

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}
