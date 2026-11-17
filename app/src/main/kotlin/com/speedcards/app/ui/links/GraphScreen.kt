package com.speedcards.app.ui.links

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.data.repo.ForceLayout
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.LinkedNote
import com.speedcards.data.repo.NoteGraph
import com.speedcards.data.repo.Point
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.hypot

data class GraphState(
    val graph: NoteGraph? = null,
    val positions: Map<Long, Point> = emptyMap(),
    val selected: Long? = null,
    val selectedLinks: List<LinkedNote> = emptyList(),
)

@HiltViewModel
class GraphViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val links: LinkRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GraphState())
    val state = _state.asStateFlow()

    init { load(checkNotNull(savedState["noteId"])) }

    fun load(centerId: Long) = viewModelScope.launch {
        startup.ready()
        val graph = links.graph(centerId)
        val positions = withContext(Dispatchers.Default) { ForceLayout.layout(graph) }
        _state.value = GraphState(graph, positions, centerId, links.links(centerId))
    }

    fun select(noteId: Long?) = viewModelScope.launch {
        _state.update { it.copy(selected = noteId, selectedLinks = noteId?.let { id -> links.links(id) }.orEmpty()) }
    }
}

private const val NODE_RADIUS = 26f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, vm: GraphViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val graph = state.graph
    LaunchedEffect(graph?.centerId) { zoom = 1f; pan = Offset.Zero }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link graph") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (graph == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Legend of link types in this neighbourhood.
            val types = graph.edges.map { it.type }.distinctBy { it.id }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                types.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinkColorDot(t.color)
                        Text(t.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (graph.truncated) Text("(showing nearest ${graph.nodes.size})", style = MaterialTheme.typography.labelMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (graph.edges.isEmpty()) {
                    Text(
                        "This card has no links yet. Open it in the editor and tap Add link.",
                        modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                    )
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(0.3f, 3f)
                                pan += panChange
                            }
                        }
                        .pointerInput(state.positions) {
                            detectTapGestures { tap ->
                                val origin = Offset(size.width / 2f, size.height / 2f) + pan
                                val hit = state.positions.minByOrNull { (_, p) ->
                                    hypot(origin.x + p.x * zoom - tap.x, origin.y + p.y * zoom - tap.y)
                                }?.takeIf { (_, p) ->
                                    hypot(origin.x + p.x * zoom - tap.x, origin.y + p.y * zoom - tap.y) < (NODE_RADIUS + 14) * zoom
                                }
                                vm.select(hit?.key)
                            }
                        },
                ) {
                    translate(size.width / 2 + pan.x, size.height / 2 + pan.y) {
                        withTransform({ scale(zoom, zoom, pivot = Offset.Zero) }) {
                            graph.edges.forEach { e ->
                                val a = state.positions[e.from] ?: return@forEach
                                val b = state.positions[e.to] ?: return@forEach
                                drawLine(Color(e.type.color), Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 4f)
                            }
                            graph.nodes.forEach { node ->
                                val p = state.positions[node.noteId] ?: return@forEach
                                val c = Offset(p.x, p.y)
                                val isCenter = node.noteId == graph.centerId
                                val isSelected = node.noteId == state.selected
                                drawCircle(
                                    if (isCenter) colors.primary else colors.secondaryContainer,
                                    radius = if (isCenter) NODE_RADIUS * 1.3f else NODE_RADIUS,
                                    center = c,
                                )
                                if (isSelected) drawCircle(colors.tertiary, NODE_RADIUS * 1.5f, c, style = Stroke(5f))
                                val text = measurer.measure(
                                    node.label.take(24),
                                    TextStyle(
                                        fontSize = 13.sp,
                                        color = colors.onSurface,
                                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y + NODE_RADIUS * 1.4f))
                            }
                        }
                    }
                }
            }
            // Details for the selected card.
            state.selected?.let { id ->
                val node = graph.nodes.firstOrNull { it.noteId == id }
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node?.label.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        state.selectedLinks.take(4).forEach { l ->
                            Text("${l.label} → ${l.front}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.selectedLinks.size > 4) Text("+${state.selectedLinks.size - 4} more", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (id != graph.centerId) OutlinedButton(onClick = { vm.load(id) }) { Text("Centre here") }
                            Button(onClick = { onOpenNote(id) }) { Text("Edit card") }
                        }
                    }
                }
            }
        }
    }
}
