package com.speedcards.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcards.app.audio.RecordDialog
import com.speedcards.app.ui.common.CardText
import com.speedcards.app.ui.links.LinkPickerDialog
import com.speedcards.app.ui.links.LinksSection
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.app.ui.common.Dropdown
import com.speedcards.data.db.NoteKind
import com.speedcards.data.template.Sounds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNoteScreen(
    onDone: () -> Unit,
    onOpenNote: (Long) -> Unit = {},
    onGraph: (Long) -> Unit = {},
    vm: EditNoteViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAnswer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var recordingField by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.message, state.addedCount) {
        state.message?.let {
            snackbar.showSnackbar(if (state.isEdit) it else "$it (${state.addedCount} this session)")
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit card" else "Add card") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    state.noteId?.let { id -> IconButton(onClick = { onGraph(id) }) { Icon(Icons.Default.Hub, "Link graph") } }
                    if (state.isEdit) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete note") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val type = state.type ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isEdit) {
                    Dropdown("Type", state.noteTypes, type, { it.name }, vm::selectType, Modifier.weight(1f))
                }
                DeckDropdown(state.decks, state.deckId, vm::selectDeck, Modifier.weight(1f))
            }
            if (type.kind == NoteKind.CLOZE) {
                Text(
                    "Wrap hidden words like {{c1::hablo}}. Use c2, c3… for extra cards from the same sentence.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            type.fields.forEachIndexed { i, field ->
                val value = state.fields.getOrElse(i) { "" }
                OutlinedTextField(
                    value = Sounds.strip(value),
                    onValueChange = { vm.setFieldText(i, it) },
                    label = { Text(field.name) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { recordingField = i }) { Icon(Icons.Default.Mic, "Record audio for ${field.name}") }
                            if (!state.isEdit) {
                                IconButton(onClick = { vm.toggleSticky(i) }) {
                                    Icon(
                                        if (i in state.sticky) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = if (i in state.sticky) "Unpin field" else "Keep this field after adding",
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Sounds.extract(value).takeIf { it.isNotEmpty() }?.let { sounds ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sounds.forEachIndexed { n, name ->
                            InputChip(
                                selected = false,
                                onClick = { vm.playSound(name) },
                                label = { Text("Audio ${n + 1}") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close, "Remove audio", Modifier.size(18.dp).clickable { vm.removeSound(i, name) },
                                    )
                                },
                            )
                        }
                    }
                }
                if (i == 0 && state.duplicates.isNotEmpty()) {
                    val d = state.duplicates.first()
                    Text(
                        "Already exists in ${d.deckName}" + if (state.duplicates.size > 1) " (+${state.duplicates.size - 1} more)" else "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = state.tags, onValueChange = vm::setTags, label = { Text("Tags (space separated)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read front aloud", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.readFront) "The question side is spoken when it's shown."
                        else "The question side stays silent; you'll hear it after flipping. Listening cards still play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(state.readFront, vm::setReadFront, Modifier.padding(start = 8.dp))
            }
            if (state.isEdit && state.cards.isNotEmpty()) CardsSection(state, vm)
            LinksSection(
                rows = state.linkRows,
                onAdd = vm::openLinkPicker,
                onRemove = vm::removeLink,
                onOpen = { key -> vm.linkedNoteId(key)?.let(onOpenNote) },
            )

            state.preview?.let { preview ->
                Text(
                    "Preview · ${state.cardCount} card${if (state.cardCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Card(
                    onClick = { showAnswer = !showAnswer },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CardText(if (showAnswer) preview.answer else preview.question, Modifier.padding(20.dp))
                    Text(
                        if (showAnswer) "Back · tap for front" else "Front · tap for back",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isEdit) {
                    Button(onClick = { vm.save(false) }, enabled = state.canSave, modifier = Modifier.weight(1f)) { Text("Save") }
                } else {
                    OutlinedButton(onClick = { vm.save(false) }, enabled = state.canSave, modifier = Modifier.weight(1f)) { Text("Save & close") }
                    Button(onClick = { vm.save(true) }, enabled = state.canSave, modifier = Modifier.weight(1f)) { Text("Save & next") }
                }
            }
        }
    }

    state.linkPickerQuery?.let { q ->
        LinkPickerDialog(
            excludeNoteId = state.noteId,
            initialQuery = q,
            onDismiss = vm::closeLinkPicker,
            onPick = { row, type -> vm.addLink(row, type) },
        )
    }

    recordingField?.let { i ->
        RecordDialog(
            title = "Record ${state.type?.fields?.getOrNull(i)?.name.orEmpty()}",
            onDismiss = { recordingField = null },
            onSaved = { file -> vm.attachRecording(i, file) },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("All of its cards and their review history will be deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Each card made from this note: its status, and suspend / reset / clear-leech controls. */
@Composable
private fun CardsSection(state: EditorState, vm: EditNoteViewModel) {
    var confirmReset by remember { mutableStateOf<com.speedcards.data.db.Card?>(null) }
    val type = state.type ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Cards", style = MaterialTheme.typography.titleSmall)
        state.cards.forEach { card ->
            val name = if (type.kind == NoteKind.CLOZE) "Cloze ${card.ord + 1}" else type.templates.getOrNull(card.ord)?.name ?: "Card ${card.ord + 1}"
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                cardStatus(card) + if (card.leech) " · leech" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (card.leech) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Active", style = MaterialTheme.typography.labelSmall)
                        Switch(!card.suspended, { active -> vm.setSuspended(card, !active) }, Modifier.padding(start = 4.dp))
                    }
                    Row {
                        if (card.reps > 0) TextButton(onClick = { confirmReset = card }) { Text("Reset to new") }
                        if (card.leech) TextButton(onClick = { vm.clearLeech(card) }) { Text("Clear leech") }
                    }
                }
            }
        }
    }
    confirmReset?.let { card ->
        AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text("Reset this card?") },
            text = { Text("It goes back to new and will be learned again. Its review history is kept for statistics.") },
            confirmButton = { TextButton(onClick = { vm.resetCard(card); confirmReset = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text("Cancel") } },
        )
    }
}

private fun cardStatus(card: com.speedcards.data.db.Card): String = when {
    card.suspended -> "Suspended"
    card.state == com.speedcards.scheduler.CardState.NEW -> "New"
    card.state == com.speedcards.scheduler.CardState.REVIEW ->
        "Every ${card.intervalDays}d · due " + android.text.format.DateUtils.getRelativeTimeSpanString(card.due).toString().lowercase()
    else -> "Learning"
}
