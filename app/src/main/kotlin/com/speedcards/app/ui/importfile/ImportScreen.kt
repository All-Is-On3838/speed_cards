package com.speedcards.app.ui.importfile

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.backup.NasFolder
import com.speedcards.app.backup.RemoteFile
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.csv.CsvImporter
import com.speedcards.data.io.ApkgImporter
import com.speedcards.data.io.ApkgSummary
import com.speedcards.data.io.DocumentImportOptions
import com.speedcards.data.io.DocumentImportReport
import com.speedcards.data.io.DocumentImporter
import com.speedcards.data.io.ImportDocument
import com.speedcards.data.io.SctxtParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

data class ImportUiState(
    val fileName: String? = null,
    val kind: ImportKind? = null,
    val loading: Boolean = false,
    val apkg: ApkgSummary? = null,
    val sctxt: ImportDocument? = null,
    val keepScheduling: Boolean = true,
    val importing: Boolean = false,
    val report: DocumentImportReport? = null,
    val undone: Int? = null,
    val error: String? = null,
    /** CSV files continue in the column-matching wizard. */
    val openCsvWizard: Pair<Uri, Boolean>? = null,
    val nasFolderChosen: Boolean = false,
    val nasFiles: List<RemoteFile>? = null,
    val nasError: String? = null,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val files: IncomingFiles,
    private val apkgImporter: ApkgImporter,
    private val documents: DocumentImporter,
    private val csv: CsvImporter,
    private val nas: NasFolder,
    private val prefs: UserPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()
    private var file: File? = null
    private var source: Uri? = null
    private var fromNas: RemoteFile? = null

    init {
        viewModelScope.launch {
            startup.ready()
            savedState.get<String>("uri")?.takeIf { it.isNotEmpty() }?.let { open(Uri.parse(it)) }
            refreshNas()
        }
    }

    fun refreshNas() = viewModelScope.launch {
        val folder = prefs.backupStateNow().folderUri?.let(Uri::parse)
        if (folder == null) { _state.update { it.copy(nasFolderChosen = false, nasFiles = null) }; return@launch }
        _state.update { it.copy(nasFolderChosen = true, nasError = null) }
        // Network shares can be slow or offline; don't hang the screen.
        val listed = withTimeoutOrNull(15_000) { runCatching { nas.importFiles(folder) } }
        _state.update {
            when {
                listed == null -> it.copy(nasFiles = null, nasError = "The NAS didn't answer.")
                listed.isFailure -> it.copy(nasFiles = null, nasError = listed.exceptionOrNull()?.message)
                else -> it.copy(nasFiles = listed.getOrNull())
            }
        }
    }

    fun openNas(remote: RemoteFile) { fromNas = remote; open(remote.uri) }

    fun open(uri: Uri) = viewModelScope.launch {
        val nasSource = fromNas?.takeIf { it.uri == uri }
        fromNas = nasSource
        source = uri
        val name = files.displayName(uri)
        _state.update { ImportUiState(fileName = name, loading = true, nasFolderChosen = it.nasFolderChosen, nasFiles = it.nasFiles) }
        try {
            val copy = files.copyToCache(uri, name)
            file = copy
            when (val kind = files.kindOf(name, copy)) {
                ImportKind.APKG -> _state.update { it.copy(kind = kind, loading = false, apkg = apkgImporter.summarize(copy)) }
                ImportKind.SCTXT -> _state.update {
                    it.copy(kind = kind, loading = false, sctxt = SctxtParser.parse(copy.readText(), defaultDeck = "Imported"))
                }
                ImportKind.CSV, ImportKind.CSV_ZIP -> _state.update { it.copy(kind = kind, loading = false, openCsvWizard = uri to (nasSource != null)) }
                else -> _state.update { it.copy(kind = kind, loading = false) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't read the file.") }
        }
    }

    fun csvWizardOpened() = _state.update { it.copy(openCsvWizard = null) }
    fun setKeepScheduling(v: Boolean) = _state.update { it.copy(keepScheduling = v) }

    fun import() = viewModelScope.launch {
        val f = file ?: return@launch
        val s = _state.value
        _state.update { it.copy(importing = true, error = null) }
        try {
            val report = when (s.kind) {
                ImportKind.APKG -> apkgImporter.import(f, s.keepScheduling)
                ImportKind.SCTXT -> documents.import(s.sctxt!!, DocumentImportOptions(sourceName = s.fileName ?: f.name))
                else -> return@launch
            }
            _state.update { it.copy(importing = false, report = report) }
            fromNas?.let { remote -> prefs.backupStateNow().folderUri?.let { runCatching { nas.markImported(Uri.parse(it), remote) } } }
            refreshNas()
        } catch (e: Exception) {
            _state.update { it.copy(importing = false, error = e.message ?: "Import failed.") }
        }
    }

    fun undo() = viewModelScope.launch {
        val report = _state.value.report ?: return@launch
        _state.update { it.copy(undone = csv.undo(report.base.batchId)) }
    }

    fun reset() { file = null; source = null; fromNas = null; _state.update { ImportUiState(nasFolderChosen = it.nasFolderChosen, nasFiles = it.nasFiles) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onCsvWizard: (Uri, Boolean) -> Unit,
    onOpenBackup: () -> Unit,
    vm: ImportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.reset(); vm.open(it) } }
    LaunchedEffect(state.openCsvWizard) {
        state.openCsvWizard?.let { (uri, nas) -> vm.csvWizardOpened(); onCsvWizard(uri, nas) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.report?.let { r ->
                ReportCard(r, state.undone, onUndo = vm::undo, onDone = onBack, onAnother = vm::reset)
                return@Column
            }
            if (state.fileName == null) {
                Text("Import cards made on your computer:")
                Text(
                    "• Anki decks (.apkg)\n• Spreadsheets (.csv, .tsv), or a .zip of one with its audio files\n• Speed Cards text (.sctxt), which can include links",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose file") }
                NasSection(state, vm, context)
                return@Column
            }

            Text(state.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                state.kind == ImportKind.APKG -> state.apkg?.let { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Anki deck: ${a.noteCount} notes · ${a.cardCount} cards · ${a.mediaCount} media files")
                            Text("Decks: ${a.deckNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            Text("Note types: ${a.noteTypeNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep review progress")
                            Text("Intervals and due dates carry over from Anki.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.keepScheduling, vm::setKeepScheduling)
                    }
                }
                state.kind == ImportKind.SCTXT -> state.sctxt?.let { d ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${d.notes.size} notes · ${d.notes.sumOf { it.links.size }} links")
                            Text("Decks: ${d.notes.map { it.deck }.distinct().joinToString()}", style = MaterialTheme.typography.bodySmall)
                            Text("Note types: ${d.notes.map { it.type }.distinct().joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.kind == ImportKind.BACKUP -> {
                    Text("This is a Speed Cards backup. Restore it from Backup & restore.")
                    Button(onClick = onOpenBackup) { Text("Open Backup & restore") }
                }
                state.kind == ImportKind.ANKI_COLLECTION ->
                    Text("This is a whole Anki collection (.colpkg). In Anki, export a deck as .apkg with \"Support older Anki versions\" ticked instead.")
                state.kind == ImportKind.UNKNOWN ->
                    Text("Speed Cards doesn't recognise this file. Use .apkg, .csv, .tsv, .zip or .sctxt.")
            }
            if (state.kind == ImportKind.APKG || state.kind == ImportKind.SCTXT) {
                Button(onClick = vm::import, enabled = !state.importing && state.error == null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.importing) "Importing…" else "Import")
                }
            }
            OutlinedButton(onClick = vm::reset) { Text("Choose a different file") }
        }
    }
}

@Composable
private fun NasSection(state: ImportUiState, vm: ImportViewModel, context: android.content.Context) {
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("NAS import folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (state.nasFolderChosen) IconButton(onClick = { vm.refreshNas() }) { Icon(Icons.Default.Refresh, "Refresh") }
    }
    if (!state.nasFolderChosen) {
        Text("Choose a backup folder in Backup & restore. Files you put in its import/ sub-folder show up here.", style = MaterialTheme.typography.bodySmall)
        return
    }
    state.nasError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val list = state.nasFiles
    when {
        list == null && state.nasError == null -> CircularProgressIndicator()
        list.isNullOrEmpty() -> Text("No files waiting. Save files into the import/ folder on your NAS.", style = MaterialTheme.typography.bodySmall)
        else -> list.forEach { f ->
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                headlineContent = { Text(f.name) },
                supportingContent = { Text(Formatter.formatShortFileSize(context, f.size)) },
                modifier = Modifier.clickable { vm.openNas(f) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(r: DocumentImportReport, undone: Int?, onUndo: () -> Unit, onDone: () -> Unit, onAnother: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Import finished", style = MaterialTheme.typography.titleLarge)
            Text("Created: ${r.base.created}")
            Text("Updated: ${r.base.updated}")
            if (r.linksCreated > 0) Text("Links: ${r.linksCreated}")
            if (r.decksCreated > 0) Text("New decks: ${r.decksCreated}")
            Text("Skipped as duplicates: ${r.base.duplicates.size}")
            if (r.base.failures.isNotEmpty()) {
                Text("Problems: ${r.base.failures.size}")
                r.base.failures.take(20).forEach { Text("  Line ${it.rowNumber}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
            }
            undone?.let { Text("Undone — removed $it created notes. Updated notes were left as they are.", color = MaterialTheme.colorScheme.primary) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (undone == null && r.base.created > 0) OutlinedButton(onClick = onUndo) { Text("Undo import") }
                OutlinedButton(onClick = onAnother) { Text("Import another") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
