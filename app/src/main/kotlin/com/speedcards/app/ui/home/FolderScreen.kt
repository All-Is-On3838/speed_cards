package com.speedcards.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcards.app.SpeedCardsApp
import com.speedcards.app.ui.backup.backupAgeColor
import com.speedcards.app.ui.backup.relative
import com.speedcards.data.db.Folder
import com.speedcards.data.repo.StudyRepository

/** What the folder name dialog is doing: creating a folder ([existing] null) or renaming one. */
private data class FolderDialogTarget(val existing: Folder?)

/** The home screen: a list of folders. Opening one shows its decks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    onOpenFolder: (Long?) -> Unit,
    onStudy: (Long) -> Unit,
    onAdd: () -> Unit,
    onBrowse: (Long?) -> Unit,
    onPresets: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onMedia: () -> Unit,
    onLinkTypes: () -> Unit,
    vm: FolderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<FolderDialogTarget?>(null) }
    var confirmDelete by remember { mutableStateOf<Folder?>(null) }

    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    val backup by vm.backupState.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as SpeedCardsApp
    LaunchedEffect(Unit) { if (app.consumeRestored()) snackbar.showSnackbar("Restore complete.") }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Cards") },
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
                            DropdownMenuItem(text = { Text("New folder") }, onClick = { menuOpen = false; nameDialog = FolderDialogTarget(null) })
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
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add card") })
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
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.total.total == 0) "All caught up for today." else "${state.total.total} cards to study today",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.streakDays > 0) {
                        Text(
                            "${state.streakDays}-day streak",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (state.total.total > 0) {
                        Button(
                            onClick = { onStudy(StudyRepository.ALL_DECKS) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Study all due cards") }
                    }
                }
            }
            if (state.inboxCount > 0) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onBrowse(state.inboxDeckId) }) {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Inbox, null) },
                            headlineContent = { Text("Inbox") },
                            supportingContent = { Text("${state.inboxCount} captured word${if (state.inboxCount == 1) "" else "s"} to finish") },
                        )
                    }
                }
            }
            items(state.folders, key = { it.folder!!.id }) { row ->
                FolderCard(
                    row = row,
                    onOpen = { onOpenFolder(row.folder!!.id) },
                    onRename = { nameDialog = FolderDialogTarget(row.folder) },
                    onDelete = { confirmDelete = row.folder },
                )
            }
            state.unfiled?.let { row ->
                item(key = "unfiled") { FolderCard(row = row, onOpen = { onOpenFolder(null) }, onRename = null, onDelete = null) }
            }
            if (state.folders.isEmpty() && state.unfiled == null) {
                item {
                    Text(
                        "No folders yet. Use ⋮ › New folder to make one.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            backup?.let { b ->
                item {
                    val color = backupAgeColor(b.lastBackupAt)
                    Text(
                        "Last backup: ${relative(b.lastBackupAt)}",
                        color = color ?: MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onBackup).padding(16.dp),
                    )
                }
            }
            item { Spacer(Modifier.padding(48.dp)) }
        }
    }

    nameDialog?.let { target ->
        FolderNameDialog(
            existing = target.existing,
            onDismiss = { nameDialog = null },
            onSave = { name ->
                if (target.existing == null) vm.createFolder(name) else vm.renameFolder(target.existing, name)
                nameDialog = null
            },
        )
    }
    confirmDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${folder.name}\"?") },
            text = { Text("Only the empty folder is deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteFolder(folder); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderCard(row: FolderRow, onOpen: () -> Unit, onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val decks = "${row.deckCount} deck${if (row.deckCount == 1) "" else "s"}"
    val due = row.counts.total
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onOpen)) {
        ListItem(
            leadingContent = { Icon(if (row.folder == null) Icons.Default.FolderOpen else Icons.Default.Folder, null) },
            headlineContent = { Text(row.folder?.name ?: "Unfiled decks", style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(if (due > 0) "$decks · $due to study" else decks) },
            trailingContent = if (onRename == null && onDelete == null) null else {
                {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Folder options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            onRename?.let { DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; it() }) }
                            if (onDelete != null && row.deckCount == 0) {
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun FolderNameDialog(existing: Folder?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New folder" else "Rename folder") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
