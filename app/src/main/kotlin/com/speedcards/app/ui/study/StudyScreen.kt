package com.speedcards.app.ui.study

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcards.app.ui.common.CardText
import com.speedcards.app.ui.links.RelatedList
import com.speedcards.app.ui.theme.RatingColors
import com.speedcards.data.repo.DueCounts
import com.speedcards.scheduler.Rating
import com.speedcards.scheduler.formatDelay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    onBack: () -> Unit,
    onEditNote: (Long) -> Unit,
    onGraph: (Long) -> Unit = {},
    onPracticeLinked: (Long) -> Unit = {},
    vm: StudyViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }

    // Refresh on every return (e.g. from the editor) so edits and new audio show up.
    LifecycleResumeEffect(Unit) {
        vm.reloadCurrent()
        onPauseOrDispose { vm.pauseAudio() }
    }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startTake()
    }
    val startTake = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startTake()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    StudyDialogs(state, vm)
    state.peek?.let { peek ->
        AlertDialog(
            onDismissRequest = vm::closePeek,
            title = { Text(peek.fields.firstOrNull()?.second.orEmpty()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    peek.fields.drop(1).forEach { (name, value) ->
                        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(value)
                    }
                    Text("Just a peek — this doesn't affect its schedule.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::closePeek) { Text("Close") } },
            dismissButton = { TextButton(onClick = { vm.closePeek(); onGraph(peek.noteId) }) { Text("Graph") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.deckName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo last answer") }
                    state.current?.let { current ->
                        IconButton(onClick = { onEditNote(current.card.noteId) }) { Icon(Icons.Default.Edit, "Edit card") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Bury until tomorrow") }, onClick = { menu = false; vm.bury() })
                                DropdownMenuItem(text = { Text("Suspend card") }, onClick = { menu = false; vm.suspend() })
                                DropdownMenuItem(text = { Text("Link graph") }, onClick = { menu = false; onGraph(current.card.noteId) })
                                DropdownMenuItem(
                                    text = { Text("Practice linked cards") },
                                    onClick = { menu = false; onPracticeLinked(current.card.noteId) },
                                )
                                DropdownMenuItem(text = { Text("My recordings") }, onClick = { menu = false; vm.showHistory(true) })
                                DropdownMenuItem(text = { Text("Audio settings") }, onClick = { menu = false; vm.showAudioSettings(true) })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.finished -> Finished(state, onBack, Modifier.align(Alignment.Center))
                else -> state.current?.let { current ->
                    Column(Modifier.fillMaxSize()) {
                        Counts(state.counts, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                        // Tapping anywhere on the card reveals the answer.
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = !state.revealed,
                                    onClick = vm::reveal,
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column {
                                CardText(if (state.revealed) current.answer else current.question)
                                if (state.revealed) RelatedList(state.relatedRows) { key -> vm.peek(key.toLong()) }
                            }
                        }
                        if (current.card.leech) {
                            Text(
                                "Leech",
                                color = RatingColors.again,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                        AudioBar(state, vm, startTake)
                        if (state.revealed) {
                            AnswerButtons(current.buttonDelays, vm::answer)
                        } else {
                            Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) {
                                Text("Show answer")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudyDialogs(state: StudyState, vm: StudyViewModel) {
    if (state.showAudioSettings) {
        val a = state.audio
        AlertDialog(
            onDismissRequest = { vm.showAudioSettings(false) },
            title = { Text("Audio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitch("Mute all card audio", a.muted) { vm.updateAudio(a.copy(muted = it)) }
                    SettingSwitch("Play audio automatically", a.autoplay) { vm.updateAudio(a.copy(autoplay = it)) }
                    SettingSwitch("Read text aloud when there's no recording", a.ttsFallback) { vm.updateAudio(a.copy(ttsFallback = it)) }
                    Text("Speed: ${formatSpeed(a.speed)}×")
                    Slider(
                        value = a.speed,
                        onValueChange = { vm.updateAudio(a.copy(speed = (Math.round(it * 4) / 4f))) },
                        valueRange = 0.5f..1.5f,
                        steps = 3,
                    )
                    Text(
                        "Text-to-speech uses each deck's languages (Deck options). Voices are installed in Android Settings › Text-to-speech.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.showAudioSettings(false) }) { Text("Done") } },
        )
    }
    if (state.shadow.showHistory) {
        ModalBottomSheet(onDismissRequest = { vm.showHistory(false) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Your recordings for this card", style = MaterialTheme.typography.titleMedium)
                if (state.shadow.saved.isEmpty()) {
                    Text(
                        "None yet. Tap the mic while studying, compare with A/B, then tap Keep.",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                state.shadow.saved.forEach { row ->
                    ListItem(
                        headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.timestamp))) },
                        leadingContent = { IconButton(onClick = { vm.playSaved(row) }) { Icon(Icons.Default.PlayArrow, "Play") } },
                        trailingContent = { IconButton(onClick = { vm.deleteSaved(row) }) { Icon(Icons.Default.Delete, "Delete") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AudioBar(state: StudyState, vm: StudyViewModel, startTake: () -> Unit) {
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val shadow = state.shadow
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { if (playing) vm.player.stop() else vm.replay() }, enabled = state.hasAudio || playing) {
            when {
                playing -> Icon(Icons.Default.Stop, "Stop audio")
                state.audio.muted -> Icon(Icons.AutoMirrored.Filled.VolumeOff, "Card audio muted")
                else -> Icon(Icons.AutoMirrored.Filled.VolumeUp, "Play audio")
            }
        }
        TextButton(onClick = vm::cycleSpeed) { Text("${formatSpeed(state.audio.speed)}×") }
        if (shadow.recording) {
            FilledIconButton(
                onClick = vm::stopTake,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.Stop, "Stop recording") }
        } else {
            IconButton(onClick = startTake) { Icon(Icons.Default.Mic, "Record yourself") }
        }
        if (shadow.take != null) {
            TextButton(onClick = vm::playComparison) { Text("A/B") }
            TextButton(onClick = vm::saveTake) { Text("Keep") }
            IconButton(onClick = vm::discardTake) { Icon(Icons.Default.Delete, "Discard take") }
        }
    }
}

private fun formatSpeed(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

@Composable
private fun Counts(c: DueCounts, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("New ${c.new}", color = RatingColors.easy)
        Text("Learning ${c.learning}", color = RatingColors.again)
        Text("Review ${c.review}", color = RatingColors.medium)
    }
}

private fun ratingLabel(r: Rating) = when (r) {
    Rating.AGAIN -> "Again"
    Rating.HARD -> "Hard"
    Rating.MEDIUM -> "Medium"
    Rating.EASY -> "Easy"
}

fun ratingColor(r: Rating): Color = when (r) {
    Rating.AGAIN -> RatingColors.again
    Rating.HARD -> RatingColors.hard
    Rating.MEDIUM -> RatingColors.medium
    Rating.EASY -> RatingColors.easy
}

@Composable
private fun AnswerButtons(delays: Map<Rating, Long>, onAnswer: (Rating) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Rating.entries.filter { it in delays }.forEach { rating ->
            FilledTonalButton(
                onClick = { onAnswer(rating) },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = ratingColor(rating).copy(alpha = 0.18f)),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatDelay(delays.getValue(rating)), style = MaterialTheme.typography.labelSmall, color = ratingColor(rating))
                    Text(ratingLabel(rating), color = ratingColor(rating), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun Finished(state: StudyState, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Done for now!", style = MaterialTheme.typography.headlineMedium)
        Text("You answered ${state.reviewedThisSession} cards this session.", textAlign = TextAlign.Center)
        state.nextDueAt?.let {
            Text(
                "More learning cards are due at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}.",
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onBack) { Text("Back to decks") }
    }
}
