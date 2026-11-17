package com.speedcards.app.ui.links

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.data.db.LinkType
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.LinkTypeInUseException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinkTypesViewModel @Inject constructor(private val links: LinkRepository) : ViewModel() {
    val types = links.observeTypes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun save(type: LinkType) = viewModelScope.launch {
        if (type.name.isBlank()) return@launch
        runCatching { links.saveType(type.copy(name = type.name.trim(), inverseName = type.inverseName?.trim()?.ifBlank { null })) }
            .onFailure { message.value = "A link type with that name already exists." }
    }

    fun delete(type: LinkType) = viewModelScope.launch {
        try { links.deleteType(type) } catch (e: LinkTypeInUseException) { message.value = "Can't delete: ${e.message}" }
    }
}

private val palette = listOf(
    0xFF43A047, 0xFFE53935, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFFFB8C00, 0xFF6D4C41, 0xFF00897B, 0xFF546E7A, 0xFFFDD835,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinkTypesScreen(onBack: () -> Unit, vm: LinkTypesViewModel = hiltViewModel()) {
    val types by vm.types.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<LinkType?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link types") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = LinkType(name = "", color = palette.first()) }) { Icon(Icons.Default.Add, "New link type") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(types, key = { it.id }) { t ->
                ListItem(
                    leadingContent = { LinkColorDot(t.color) },
                    headlineContent = { Text(t.name) },
                    supportingContent = {
                        Text(
                            when {
                                t.symmetric -> "Works both ways"
                                t.inverseName != null -> "Other side reads: ${t.inverseName}"
                                else -> "One way"
                            } + if (t.builtIn) " · built in" else "",
                        )
                    },
                    trailingContent = {
                        if (!t.builtIn) IconButton(onClick = { vm.delete(t) }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                    modifier = Modifier.clickable(enabled = !t.builtIn) { editing = t },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial.name) }
        var inverse by remember(initial) { mutableStateOf(initial.inverseName.orEmpty()) }
        var symmetric by remember(initial) { mutableStateOf(initial.symmetric) }
        var color by remember(initial) { mutableStateOf(initial.color) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (initial.id == 0L) "New link type" else "Edit link type") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. diminutive of") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Works both ways (like synonym)", Modifier.weight(1f))
                        Switch(symmetric, { symmetric = it })
                    }
                    if (!symmetric) {
                        OutlinedTextField(
                            inverse, { inverse = it }, label = { Text("Seen from the other card, e.g. has diminutive") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        palette.forEach { c ->
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(Color(c))
                                    .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { color = c },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.save(initial.copy(name = name, inverseName = if (symmetric) null else inverse, symmetric = symmetric, color = color))
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
