package com.speedcards.app.quickadd

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.speedcards.app.audio.RecordDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.app.ui.theme.SpeedCardsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bottom sheet shown over other apps when you pick "Speed Cards" from the text-selection menu
 * (ACTION_PROCESS_TEXT) or the share sheet (ACTION_SEND). Closing it returns to that app.
 */
@AndroidEntryPoint
class QuickAddActivity : ComponentActivity() {
    private val vm: QuickAddViewModel by viewModels()
    private var clipboardChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.start(captureFrom(intent))
        setContent { SpeedCardsTheme { QuickAddSheet(vm, onClose = ::finish) } }
    }

    /** The clipboard can only be read once our window has focus (Android 10+). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardChecked) return
        clipboardChecked = true
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        vm.offerClipboard(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString())
    }

    private fun captureFrom(intent: Intent?): Captured = when (intent?.action) {
        Intent.ACTION_PROCESS_TEXT -> CaptureParser.parse(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
        Intent.ACTION_SEND -> CaptureParser.parse(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        else -> Captured()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickAddSheet(vm: QuickAddViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() } }
    val focus = LocalFocusManager.current
    val hideKeyboard = KeyboardActions(onDone = { focus.clearFocus() })

    LaunchedEffect(state.savedMessage) {
        if (state.savedMessage != null) { delay(700); close() }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        // Title and buttons stay pinned; only the fields scroll, so the keyboard never hides Save.
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add to Speed Cards", style = MaterialTheme.typography.titleLarge)

            state.savedMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            if (!state.ready) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.save(toInbox = true) }, enabled = state.word.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text("Save to Inbox")
                }
                Button(onClick = { vm.save(toInbox = false) }, enabled = state.canSave && state.meaning.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
            }
            QuickAddFields(state, vm, hideKeyboard, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddFields(state: QuickAddState, vm: QuickAddViewModel, hideKeyboard: KeyboardActions, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.clipboardOffer?.let { clip ->
                AssistChip(
                    onClick = vm::acceptClipboard,
                    label = { Text("Paste: $clip", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                )
            }

            var recording by remember { mutableStateOf(false) }
            OutlinedTextField(
                state.word, vm::setWord, label = { Text("Word") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = hideKeyboard,
                trailingIcon = { IconButton(onClick = { recording = true }) { Icon(Icons.Default.Mic, "Record pronunciation") } },
                modifier = Modifier.fillMaxWidth(),
            )
            state.sound?.let {
                InputChip(
                    selected = false, onClick = vm::removeRecording,
                    label = { Text("Pronunciation recorded") },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove recording") },
                )
            }
            if (recording) {
                RecordDialog(title = "Record \"${state.word.ifBlank { "word" }}\"", onDismiss = { recording = false }, onSaved = { vm.attachRecording(it) })
            }
            if (state.example.isNotBlank() && state.chips.isNotEmpty()) {
                Text("Tap the word you want to learn:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.chips.forEach { w -> SuggestionChip(onClick = { vm.setWord(w) }, label = { Text(w) }) }
                }
            }
            state.duplicate?.let { dup ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Already in ${state.duplicateDeckName}: ${dup.fields.getOrNull(1).orEmpty().ifBlank { "(no meaning yet)" }}")
                        if (vm.canAddExampleToExisting()) {
                            TextButton(onClick = vm::addExampleToExisting) { Text("Add this sentence to that card instead") }
                        }
                    }
                }
            }
            OutlinedTextField(
                state.meaning, vm::setMeaning, label = { Text("Meaning (optional for Inbox)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = hideKeyboard,
                trailingIcon = {
                    IconButton(onClick = vm::suggestMeaning, enabled = !state.translating && (state.word.isNotBlank() || state.example.isNotBlank())) {
                        Icon(Icons.Default.Translate, "Suggest meaning")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            state.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(state.example, vm::setExample, label = { Text("Example sentence") }, modifier = Modifier.fillMaxWidth())
            if (state.source.isNotBlank()) {
                Text("Source: ${state.source}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DeckDropdown(state.decks, state.deckId, vm::selectDeck, includeInbox = false)
        }
}
