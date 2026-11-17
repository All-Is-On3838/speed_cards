package com.speedcards.app.ui.importcsv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.speedcards.app.AppStartup
import com.speedcards.app.backup.NasFolder
import com.speedcards.app.backup.RemoteFile
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.ui.importfile.ImportKind
import com.speedcards.app.ui.importfile.IncomingFiles
import com.speedcards.data.io.ZipCsvReader
import java.io.File
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.app.ui.common.Dropdown
import com.speedcards.data.csv.CsvImportOptions
import com.speedcards.data.csv.CsvImporter
import com.speedcards.data.csv.CsvParser
import com.speedcards.data.csv.ImportReport
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.NoteType
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CsvImportState(
    val fileName: String? = null,
    val rows: List<List<String>> = emptyList(),
    val delimiter: Char = ',',
    val hasHeader: Boolean = true,
    val noteTypes: List<NoteType> = emptyList(),
    val decks: List<Deck> = emptyList(),
    val type: NoteType? = null,
    val deckId: Long? = null,
    val fieldColumns: List<Int?> = emptyList(),
    val tagsColumn: Int? = null,
    val idColumn: Int? = null,
    val extraTags: String = "",
    val allowDuplicates: Boolean = false,
    val importing: Boolean = false,
    val report: ImportReport? = null,
    val undoneCount: Int? = null,
    val error: String? = null,
) {
    val columnCount get() = rows.maxOfOrNull { it.size } ?: 0
    val header get() = if (hasHeader) rows.firstOrNull() else null
    val dataRowCount get() = (rows.size - if (hasHeader) 1 else 0).coerceAtLeast(0)
    val canImport get() = type != null && deckId != null && fieldColumns.firstOrNull() != null && dataRowCount > 0 && !importing

    fun columnLabel(i: Int?): String {
        if (i == null) return "(none)"
        val name = header?.getOrNull(i)?.takeIf { it.isNotBlank() }
        val sample = rows.getOrNull(if (hasHeader) 1 else 0)?.getOrNull(i).orEmpty()
        return "Column ${i + 1}" + (name?.let { ": $it" } ?: "") + if (sample.isNotBlank()) " (e.g. ${sample.take(20)})" else ""
    }
}

@HiltViewModel
class CsvImportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val importer: CsvImporter,
    private val notes: NoteRepository,
    private val decks: DeckRepository,
    private val files: IncomingFiles,
    private val zipReader: ZipCsvReader,
    private val nas: NasFolder,
    private val prefs: UserPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(CsvImportState())
    val state: StateFlow<CsvImportState> = _state.asStateFlow()

    /** Zip the CSV came from; its media is stored when importing. */
    private var mediaZip: File? = null
    /** Set when the file came from the NAS import folder; it's moved to import/done after importing. */
    private var nasFile: RemoteFile? = null

    init {
        viewModelScope.launch {
            startup.ready()
            val types = notes.noteTypes()
            val allDecks = decks.all()
            _state.update {
                it.copy(
                    noteTypes = types, decks = allDecks,
                    type = types.firstOrNull { t -> t.name == "Basic" } ?: types.first(),
                    deckId = allDecks.first { d -> d.kind == DeckKind.NORMAL }.id,
                )
            }
            savedState.get<String>("uri")?.takeIf { it.isNotEmpty() }?.let { arg ->
                val uri = Uri.parse(arg)
                if (savedState.get<Boolean>("nas") == true) nasFile = RemoteFile(files.displayName(uri), 0, uri)
                load(uri)
            }
        }
    }

    fun load(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val name = files.displayName(uri)
            val copy = files.copyToCache(uri, name)
            val (csvName, text) = if (files.kindOf(name, copy) == ImportKind.CSV_ZIP) {
                mediaZip = copy
                val content = zipReader.read(copy)
                "$name › ${content.csvName} (+${content.mediaNames.size} media)" to content.csvText
            } else {
                mediaZip = null
                name to copy.readText(Charsets.UTF_8)
            }
            val delimiter = if (csvName.endsWith(".tsv", true) || csvName.endsWith(".txt", true) && '\t' in text) '\t'
            else CsvParser.detectDelimiter(text)
            Triple(csvName, delimiter, CsvParser.parse(text, delimiter))
        }
        result.onSuccess { (name, delimiter, rows) ->
            _state.update { it.copy(fileName = name, delimiter = delimiter, rows = rows, report = null, undoneCount = null, error = null) }
            remap()
        }.onFailure { e -> _state.update { it.copy(error = "Couldn't read the file: ${e.message}") } }
    }

    private fun remap() = _state.update { s ->
        val type = s.type ?: return@update s
        val header = s.header?.map { it.trim().lowercase() }
        s.copy(
            fieldColumns = importer.guessMapping(type, s.header, s.columnCount),
            tagsColumn = header?.indexOfFirst { it == "tags" || it == "tag" }?.takeIf { it >= 0 },
            idColumn = header?.indexOfFirst { it == "id" || it == "guid" }?.takeIf { it >= 0 },
        )
    }

    fun setHasHeader(v: Boolean) { _state.update { it.copy(hasHeader = v) }; remap() }
    fun setType(t: NoteType) { _state.update { it.copy(type = t) }; remap() }
    fun setDeck(d: Deck) = _state.update { it.copy(deckId = d.id) }
    fun setFieldColumn(field: Int, col: Int?) = _state.update {
        it.copy(fieldColumns = it.fieldColumns.toMutableList().also { l -> l[field] = col })
    }
    fun setTagsColumn(col: Int?) = _state.update { it.copy(tagsColumn = col) }
    fun setIdColumn(col: Int?) = _state.update { it.copy(idColumn = col) }
    fun setExtraTags(v: String) = _state.update { it.copy(extraTags = v) }
    fun setAllowDuplicates(v: Boolean) = _state.update { it.copy(allowDuplicates = v) }

    fun import() = viewModelScope.launch {
        val s = _state.value
        val type = s.type ?: return@launch
        val deckId = s.deckId ?: return@launch
        _state.update { it.copy(importing = true) }
        val media = mediaZip?.let { zipReader.storeMedia(it) }
        val report = importer.import(
            s.rows,
            CsvImportOptions(
                noteType = type, deckId = deckId, hasHeader = s.hasHeader, fieldColumns = s.fieldColumns,
                tagsColumn = s.tagsColumn, idColumn = s.idColumn,
                extraTags = s.extraTags.split(Regex("\\s+")).filter { it.isNotBlank() },
                allowDuplicates = s.allowDuplicates,
            ),
            s.fileName ?: "import.csv",
            media,
        )
        _state.update { it.copy(importing = false, report = report) }
        nasFile?.let { f -> prefs.backupStateNow().folderUri?.let { runCatching { nas.markImported(Uri.parse(it), f) } } }
    }

    fun undo() = viewModelScope.launch {
        val report = _state.value.report ?: return@launch
        val n = importer.undo(report.batchId)
        _state.update { it.copy(undoneCount = n) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportScreen(onBack: () -> Unit, vm: CsvImportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.load(it) } }
    val pick = {
        picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "text/tab-separated-values", "application/csv", "application/zip"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import CSV / TSV") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.report?.let { report ->
                ReportCard(report, state.undoneCount, onUndo = vm::undo, onDone = onBack)
                return@Column
            }
            if (state.rows.isEmpty()) {
                Text(
                    "Make your cards in Excel, Google Sheets or Numbers, then export as CSV or TSV. " +
                        "Put column names in the first row (e.g. Front, Back, tags). An optional \"id\" column lets you re-import edits without duplicates. " +
                        "To include audio, zip the CSV with the sound files and put file names (hola.mp3) or [sound:hola.mp3] in a column.",
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Choose file") }
                return@Column
            }

            Text("${state.fileName} · ${state.dataRowCount} rows · ${state.columnCount} columns · separator ${delimiterName(state.delimiter)}")
            OutlinedButton(onClick = pick) { Text("Choose a different file") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("First row has column names", Modifier.weight(1f))
                Switch(state.hasHeader, vm::setHasHeader)
            }
            Dropdown("Note type", state.noteTypes, state.type, { it.name }, vm::setType)
            DeckDropdown(state.decks, state.deckId, vm::setDeck, label = "Into deck")

            Text("Match columns to fields", style = MaterialTheme.typography.titleMedium)
            val columns: List<Int?> = listOf<Int?>(null) + (0 until state.columnCount).toList()
            state.type?.fields?.forEachIndexed { i, field ->
                Dropdown(field.name, columns, state.fieldColumns.getOrNull(i), state::columnLabel, { vm.setFieldColumn(i, it) })
            }
            Dropdown("Tags column", columns, state.tagsColumn, state::columnLabel, vm::setTagsColumn)
            Dropdown("Id column (for safe re-import)", columns, state.idColumn, state::columnLabel, vm::setIdColumn)
            OutlinedTextField(
                state.extraTags, vm::setExtraTags, label = { Text("Add these tags to every card") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Import rows that duplicate existing cards", Modifier.weight(1f))
                Switch(state.allowDuplicates, vm::setAllowDuplicates)
            }

            Text("Preview", style = MaterialTheme.typography.titleMedium)
            val type = state.type
            if (type != null) {
                state.rows.drop(if (state.hasHeader) 1 else 0).take(3).forEach { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            type.fields.forEachIndexed { i, f ->
                                val v = state.fieldColumns.getOrNull(i)?.let { row.getOrNull(it) }.orEmpty()
                                if (v.isNotBlank()) Text("${f.name}: $v", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Button(onClick = vm::import, enabled = state.canImport, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.importing) "Importing…" else "Import ${state.dataRowCount} rows")
            }
        }
    }
}

private fun delimiterName(c: Char) = when (c) {
    '\t' -> "tab"
    ',' -> "comma"
    ';' -> "semicolon"
    '|' -> "pipe"
    else -> "'$c'"
}

@Composable
private fun ReportCard(report: ImportReport, undoneCount: Int?, onUndo: () -> Unit, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Import finished", style = MaterialTheme.typography.titleLarge)
            Text("Created: ${report.created}")
            Text("Updated: ${report.updated}")
            Text("Skipped as duplicates: ${report.duplicates.size}" + if (report.duplicates.isNotEmpty()) " (rows ${report.duplicates.take(15).joinToString()}${if (report.duplicates.size > 15) "…" else ""})" else "")
            Text("Failed: ${report.failures.size}")
            report.failures.take(20).forEach { Text("  Row ${it.rowNumber}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
            if (undoneCount != null) {
                Text("Undone — removed $undoneCount created notes. Updated notes were left as they are.", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (undoneCount == null && report.created > 0) OutlinedButton(onClick = onUndo) { Text("Undo import") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
