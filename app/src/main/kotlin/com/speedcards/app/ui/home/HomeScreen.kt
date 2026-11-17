package com.speedcards.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.app.ui.common.Dropdown
import com.speedcards.app.ui.theme.RatingColors
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Folder
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.repo.DueCounts

/** Sentinel for "create a new top-level deck" vs. editing an existing one. */
private data class DeckDialogTarget(val existing: Deck?, val parentId: Long?)

/** The decks in one folder (or the unfiled decks), opened from [FolderScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onStudy: (Long) -> Unit,
    onAdd: (Long?) -> Unit,
    onBrowse: (Long?) -> Unit,
    onPresets: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onCram: (Long, String?) -> Unit,
    onForgotten: (Long, Int) -> Unit,
    onStudyAhead: (Long, Int) -> Unit,
    onMedia: () -> Unit,
    onLinkTypes: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DeckDialogTarget?>(null) }
    var confirmDelete by remember { mutableStateOf<Deck?>(null) }
    var customStudy by remember { mutableStateOf<Deck?>(null) }
    var renaming by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    val muted by vm.muted.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.folder?.name ?: if (vm.folderId == null) "Unfiled decks" else "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Folders") } },
                actions = {
                    IconButton(onClick = vm::toggleMuted) {
                        if (muted) Icon(Icons.AutoMirrored.Filled.VolumeOff, "Card audio muted. Tap to unmute")
                        else Icon(Icons.AutoMirrored.Filled.VolumeUp, "Mute all card audio")
                    }
                    IconButton(onClick = onStats) { Icon(Icons.Default.BarChart, "Statistics") }
                    IconButton(onClick = { onBrowse(null) }) { Icon(Icons.AutoMirrored.Filled.List, "Browse cards") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("New deck") }, onClick = { menuOpen = false; dialog = DeckDialogTarget(null, null) })
                            if (state.folder != null) {
                                DropdownMenuItem(text = { Text("Rename folder") }, onClick = { menuOpen = false; renaming = true })
                            }
                            DropdownMenuItem(text = { Text("Import") }, onClick = { menuOpen = false; onImport() })
                            DropdownMenuItem(text = { Text("Backup & restore") }, onClick = { menuOpen = false; onBackup() })
                            DropdownMenuItem(text = { Text("Scheduling presets") }, onClick = { menuOpen = false; onPresets() })
                            DropdownMenuItem(text = { Text("Link types") }, onClick = { menuOpen = false; onLinkTypes() })
                            DropdownMenuItem(text = { Text("Audio & media") }, onClick = { menuOpen = false; onMedia() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add card") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text("Setting up your decks…", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val total = state.rows.filter { it.depth == 0 }.fold(DueCounts.ZERO) { a, r -> a + r.counts }
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (total.total == 0) "All caught up in this folder." else "${total.total} cards to study today",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.streakDays > 0) {
                        Text(
                            "${state.streakDays}-day streak",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (total.total > 0) {
                        Button(
                            onClick = { onStudy(vm.studyScope) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Study all due cards") }
                    }
                }
            }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "No decks in this folder yet. Use ⋮ › New deck to add one, or move a deck here from its Deck options.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.visibleRows, key = { it.deck.id }) { row ->
                DeckRowItem(
                    row = row,
                    collapsed = row.deck.id in state.collapsed,
                    onToggle = { vm.toggleCollapsed(row.deck.id) },
                    onClick = { onStudy(row.deck.id) },
                    onAdd = { onAdd(row.deck.id) },
                    onBrowse = { onBrowse(row.deck.id) },
                    onEdit = { dialog = DeckDialogTarget(row.deck, row.deck.parentId) },
                    onAddSubDeck = { dialog = DeckDialogTarget(null, row.deck.id) },
                    onCustomStudy = { customStudy = row.deck },
                    onDelete = { confirmDelete = row.deck },
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.padding(48.dp)) }
        }
    }

    dialog?.let { target ->
        DeckDialog(
            existing = target.existing,
            initialParentId = target.parentId,
            initialFolderId = target.existing?.folderId ?: vm.folderId,
            decks = state.decks,
            folders = state.folders,
            presets = state.presets,
            onDismiss = { dialog = null },
            onSave = { name, parentId, folderId, presetId, front, back ->
                vm.saveDeck(target.existing, name, parentId, folderId, presetId, front, back)
                dialog = null
            },
        )
    }
    customStudy?.let { deck ->
        CustomStudyDialog(
            deck = deck,
            loadTags = { vm.tagsFor(deck.id) },
            onDismiss = { customStudy = null },
            onCram = { tag -> customStudy = null; onCram(deck.id, tag) },
            onForgotten = { days -> customStudy = null; onForgotten(deck.id, days) },
            onAhead = { days -> customStudy = null; onStudyAhead(deck.id, days) },
        )
    }
    if (renaming) {
        state.folder?.let { folder ->
            FolderNameDialog(existing = folder, onDismiss = { renaming = false }, onSave = { vm.renameFolder(it); renaming = false })
        }
    }
    confirmDelete?.let { deck ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${deck.name}\"?") },
            text = { Text("This deletes the deck, its sub-decks and all of their cards and review history.") },
            confirmButton = { TextButton(onClick = { vm.deleteDeck(deck); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeckRowItem(
    row: DeckRow,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onAddSubDeck: () -> Unit,
    onCustomStudy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (4 + row.depth * 20).dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.ExpandMore,
                    if (collapsed) "Show sub-decks of ${row.deck.name}" else "Hide sub-decks of ${row.deck.name}",
                    modifier = Modifier.rotate(if (collapsed) -90f else 0f),
                )
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Text(
            row.deck.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        CountsText(row.counts)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Deck options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add cards") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("Custom study…") }, onClick = { menu = false; onCustomStudy() })
                DropdownMenuItem(text = { Text("Browse") }, onClick = { menu = false; onBrowse() })
                DropdownMenuItem(text = { Text("Deck options") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Add sub-deck") }, onClick = { menu = false; onAddSubDeck() })
                if (!row.deck.builtIn) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun CountsText(c: DueCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${c.new}", color = RatingColors.easy.takeIf { c.new > 0 } ?: MaterialTheme.colorScheme.outline)
        Text("${c.learning}", color = RatingColors.again.takeIf { c.learning > 0 } ?: MaterialTheme.colorScheme.outline)
        Text("${c.review}", color = RatingColors.medium.takeIf { c.review > 0 } ?: MaterialTheme.colorScheme.outline)
    }
}

private val languageOptions = listOf(
    null to "None",
    "es-MX" to "Spanish (Mexico)",
    "es-ES" to "Spanish (Spain)",
    "zh-CN" to "Chinese (Mandarin)",
    "fr-FR" to "French",
    "de-DE" to "German",
    "it-IT" to "Italian",
    "pt-BR" to "Portuguese (Brazil)",
    "ja-JP" to "Japanese",
    "ko-KR" to "Korean",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
)

@Composable
private fun DeckDialog(
    existing: Deck?,
    initialParentId: Long?,
    initialFolderId: Long?,
    decks: List<Deck>,
    folders: List<Folder>,
    presets: List<SchedulingPreset>,
    onDismiss: () -> Unit,
    onSave: (name: String, parentId: Long?, folderId: Long?, presetId: Long, front: String?, back: String?) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var parentId by remember { mutableStateOf(initialParentId) }
    var folderId by remember { mutableStateOf(initialFolderId) }
    // Starter decks always stay in a folder, otherwise they'd be re-filed at the next launch.
    val noFolder = Folder(id = -1, name = "(none — unfiled)")
    val folderChoices = (if (existing?.builtIn == true) emptyList() else listOf(noFolder)) + folders
    val parentPresetId = decks.firstOrNull { it.id == initialParentId }?.presetId
    var presetId by remember { mutableStateOf(existing?.presetId ?: parentPresetId ?: presets.firstOrNull()?.id ?: 0L) }
    var front by remember { mutableStateOf(existing?.languageFront ?: decks.firstOrNull { it.id == initialParentId }?.languageFront) }
    var back by remember { mutableStateOf<String?>(existing?.languageBack ?: decks.firstOrNull { it.id == initialParentId }?.languageBack ?: "en-US") }
    val noParent = Deck(id = -1, name = "(none — top level)", presetId = 0)
    val parentChoices = listOf(noParent) + decks.filter { it.kind == DeckKind.NORMAL && it.id != existing?.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New deck" else "Deck options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    enabled = existing?.builtIn != true, modifier = Modifier.fillMaxWidth(),
                )
                if (existing?.kind != DeckKind.INBOX) {
                    DeckDropdown(
                        decks = parentChoices, selectedId = parentId ?: -1,
                        onSelect = { parentId = it.id.takeIf { id -> id != -1L } },
                        label = "Parent deck", includeInbox = false,
                    )
                }
                // Only top-level decks are filed; a sub-deck lives in its top-level deck's folder.
                if (existing?.kind != DeckKind.INBOX && parentId == null) {
                    Dropdown(
                        "Folder", folderChoices, folderChoices.firstOrNull { it.id == (folderId ?: -1L) }, { it.name },
                        { folderId = it.id.takeIf { id -> id != -1L } },
                    )
                }
                Dropdown("Scheduling preset", presets, presets.firstOrNull { it.id == presetId }, { it.name }, { presetId = it.id })
                Dropdown("Language you're learning", languageOptions, languageOptions.firstOrNull { it.first == front }, { it.second }, { front = it.first })
                Dropdown("Your language", languageOptions, languageOptions.firstOrNull { it.first == back }, { it.second }, { back = it.first })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, parentId, folderId, presetId, front, back) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Extra study that's outside the normal daily queue. */
@Composable
private fun CustomStudyDialog(
    deck: Deck,
    loadTags: suspend () -> List<String>,
    onDismiss: () -> Unit,
    onCram: (String?) -> Unit,
    onForgotten: (Int) -> Unit,
    onAhead: (Int) -> Unit,
) {
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(deck.id) { tags = loadTags() }
    var tag by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom study: ${deck.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Practice — doesn't change schedules", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { onCram(null) }, modifier = Modifier.fillMaxWidth()) { Text("Cram the whole deck") }
                if (tags.isNotEmpty()) {
                    Dropdown("Tag", tags, tag, { "#$it" }, { tag = it })
                    OutlinedButton(onClick = { onCram(tag) }, enabled = tag != null, modifier = Modifier.fillMaxWidth()) {
                        Text(tag?.let { "Cram #$it" } ?: "Cram a tag")
                    }
                }
                Text("Cards you forgot (answered Again)", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                        OutlinedButton(onClick = { onForgotten(d) }, contentPadding = SmallButtonPadding, modifier = Modifier.weight(1f)) {
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    }
                }
                HorizontalDivider()
                Text("Study ahead — counts as real reviews", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Review cards due soon. Answering early earns smaller interval gains, so use it before a trip or a break.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7).forEach { d ->
                        OutlinedButton(onClick = { onAhead(d) }, contentPadding = SmallButtonPadding, modifier = Modifier.weight(1f)) {
                            Text("$d day${if (d > 1) "s" else ""}", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val SmallButtonPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
