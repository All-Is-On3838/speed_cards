package com.speedcards.app.ui.links

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.ui.common.Dropdown
import com.speedcards.data.db.LinkType
import com.speedcards.data.db.NoteRow
import com.speedcards.data.repo.LinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What a link row shows; shared by saved links and links waiting for a new note to be saved. */
data class LinkRowData(val key: String, val label: String, val front: String, val back: String, val color: Long)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LinkPickerViewModel @Inject constructor(private val links: LinkRepository) : ViewModel() {
    val query = MutableStateFlow("")
    var excludeNoteId: Long? = null
    /** Last link type chosen; kept between picker openings on the same screen. */
    val selectedTypeId = MutableStateFlow<Long?>(null)

    val results: StateFlow<List<NoteRow>> = query.debounce(150)
        .mapLatest { q -> if (q.isBlank()) emptyList() else links.search(q, excludeNoteId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val types: StateFlow<List<LinkType>> = links.observeTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Search for a card and choose how it relates. */
@Composable
fun LinkPickerDialog(
    excludeNoteId: Long?,
    initialQuery: String,
    onDismiss: () -> Unit,
    onPick: (NoteRow, LinkType) -> Unit,
    vm: LinkPickerViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selectedTypeId by vm.selectedTypeId.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.excludeNoteId = excludeNoteId
        vm.query.value = initialQuery
        focus.requestFocus()
    }
    val selectedType = types.firstOrNull { it.id == selectedTypeId } ?: types.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to another card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("This card is…", types, selectedType, { linkTypeLabel(it) }, { vm.selectedTypeId.value = it.id })
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search cards") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(results, key = { it.id }) { row ->
                        ListItem(
                            headlineContent = { Text(LinkRepository.plain(row.fields.firstOrNull().orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    LinkRepository.plain(row.fields.getOrNull(1).orEmpty()).ifBlank { row.deckName },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable(enabled = selectedType != null) { onPick(row, selectedType!!) },
                        )
                    }
                }
                if (query.isNotBlank() && results.isEmpty()) Text("No matching cards.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun linkTypeLabel(t: LinkType) = if (t.symmetric || t.inverseName == null) t.name else "${t.name}  (↔ ${t.inverseName})"

@Composable
fun LinkColorDot(color: Long) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(color)))
}

/** Editor section: current links with remove buttons, plus "Add link". */
@Composable
fun LinksSection(rows: List<LinkRowData>, onAdd: () -> Unit, onRemove: (String) -> Unit, onOpen: ((String) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Links", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Default.AddLink, null, Modifier.size(18.dp))
                Text("  Add link")
            }
        }
        if (rows.isEmpty()) {
            Text("Type [[ in any field, or tap Add link, to connect this card to related ones.", style = MaterialTheme.typography.bodySmall)
        }
        rows.forEach { row ->
            ListItem(
                leadingContent = { LinkColorDot(row.color) },
                overlineContent = { Text(row.label) },
                headlineContent = { Text(row.front, fontWeight = FontWeight.Medium) },
                supportingContent = if (row.back.isNotBlank()) { { Text(row.back, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
                trailingContent = { IconButton(onClick = { onRemove(row.key) }) { Icon(Icons.Default.Close, "Remove link") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.padding(vertical = 2.dp).clip(MaterialTheme.shapes.small)
                    .let { m -> if (onOpen != null) m.clickable { onOpen(row.key) } else m },
            )
        }
    }
}

/** Compact "Related" list shown under a revealed card. */
@Composable
fun RelatedList(rows: List<LinkRowData>, onPeek: (String) -> Unit) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Related", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onPeek(row.key) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinkColorDot(row.color)
                Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(row.front, fontWeight = FontWeight.Medium)
                if (row.back.isNotBlank()) Text("— ${row.back}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}
