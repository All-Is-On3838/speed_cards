package com.speedcards.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.data.db.Deck
import com.speedcards.data.db.NoteRow
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.template.Cloze
import com.speedcards.data.template.Sounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedState: SavedStateHandle,
    startup: AppStartup,
    notes: NoteRepository,
    deckRepo: DeckRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    /** -1 = all decks. */
    val deckId = MutableStateFlow(savedState.get<Long>("deckId") ?: -1L)
    val decks = MutableStateFlow<List<Deck>>(emptyList())

    val results: StateFlow<List<NoteRow>> = combine(query, deckId) { q, d -> q to d }
        .flatMapLatest { (q, d) -> notes.search(q, d.takeIf { it > 0 }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { startup.ready(); deckRepo.observeDecks().collect { decks.value = it } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, vm: BrowserViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val deckId by vm.deckId.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val allDecks = Deck(id = -1, name = "All decks", presetId = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search card text") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            DeckDropdown(
                decks = listOf(allDecks) + decks,
                selectedId = deckId,
                onSelect = { vm.deckId.value = it.id },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Text(
                "${results.size}${if (results.size == 500) "+" else ""} notes",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(Sounds.strip(Cloze.strip(row.fields.firstOrNull().orEmpty())), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val second = Sounds.strip(row.fields.getOrNull(1).orEmpty())
                            Text(second.ifBlank { "— needs a meaning —" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = { Text(row.deckName, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { onOpenNote(row.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
