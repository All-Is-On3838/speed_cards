package com.speedcards.app.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.ui.common.DeckDropdown
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Stats
import com.speedcards.data.repo.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val startup: AppStartup,
    private val repo: StatsRepository,
    private val decks: DeckRepository,
) : ViewModel() {
    val stats = MutableStateFlow<Stats?>(null)
    val deckChoices = MutableStateFlow<List<Deck>>(emptyList())
    /** -1 = all decks. */
    val deckId = MutableStateFlow(-1L)

    fun refresh() = viewModelScope.launch {
        startup.ready()
        deckChoices.value = decks.all().filter { it.parentId == null && it.kind == DeckKind.NORMAL }
        stats.value = repo.stats(deckId.value.takeIf { it > 0 })
    }

    fun selectDeck(id: Long) { deckId.value = id; stats.value = null; refresh() }
}

private fun minutes(ms: Long) = (ms / 60_000.0).let { if (it < 1 && ms > 0) "<1 min" else "${it.roundToInt()} min" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val decks by vm.deckChoices.collectAsStateWithLifecycle()
    val deckId by vm.deckId.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeckDropdown(
                decks = listOf(Deck(id = -1, name = "All decks", presetId = 0)) + decks,
                selectedId = deckId,
                onSelect = { vm.selectDeck(it.id) },
                includeInbox = false,
            )
            val s = stats
            if (s == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            // Headline numbers.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${s.streakDays}", "day streak", Modifier.weight(1f))
                StatTile("${s.reviewsToday}", "reviews today", Modifier.weight(1f))
                StatTile(minutes(s.timeTodayMs), "studied today", Modifier.weight(1f))
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${s.wordsLearned}", "words learned", Modifier.weight(1f))
                StatTile(s.overallRetention?.let { "${(it * 100).roundToInt()}%" } ?: "—", "retention (30d)", Modifier.weight(1f))
                StatTile(minutes(s.time7Ms), "last 7 days", Modifier.weight(1f))
            }

            Section("Reviews, last 6 months") { Heatmap(s.heatmap, s.today) }

            Section("Due in the next 30 days") {
                val fmt = DateTimeFormatter.ofPattern("d MMM")
                BarChart(
                    values = s.forecast,
                    labels = s.forecast.indices.map { if (it == 0) "Today" else "+$it" },
                    describe = { i ->
                        val day = if (i == 0) "Today (incl. overdue)" else s.today.plusDays(i.toLong()).format(fmt)
                        "$day: ${s.forecast[i]} cards"
                    },
                    labelEvery = 7,
                )
            }

            Section("Cards") {
                val total = (s.newCards + s.learningCards + s.youngCards + s.matureCards).coerceAtLeast(1)
                listOf(
                    "New" to s.newCards, "Learning" to s.learningCards,
                    "Young (under 21 days)" to s.youngCards, "Mature (21+ days)" to s.matureCards,
                ).forEach { (label, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { n.toFloat() / total },
                            color = chartBlue(),
                            modifier = Modifier.weight(0.4f),
                        )
                        Text("$n", Modifier.weight(0.15f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (s.suspendedCards > 0) Text("${s.suspendedCards} suspended", style = MaterialTheme.typography.bodySmall)
            }

            Section("Review intervals") {
                BarChart(s.intervals.map { it.count }, s.intervals.map { it.label }, describe = { i -> "${s.intervals[i].label}: ${s.intervals[i].count} cards" }, labelEvery = 2, height = 110)
            }
            Section("Ease") {
                BarChart(s.eases.map { it.count }, s.eases.map { it.label }, describe = { i -> "${s.eases[i].label}: ${s.eases[i].count} cards" }, height = 100)
                Text("Lower ease means a card has been hard for you; it comes back sooner.", style = MaterialTheme.typography.bodySmall)
            }

            if (s.retention30.isNotEmpty()) Section("Retention by deck, last 30 days") {
                s.retention30.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(r.deckName, Modifier.weight(1f))
                        Text("${r.rate?.let { (it * 100).roundToInt() } ?: "—"}% of ${r.reviews}")
                    }
                }
            }

            Section("Leeches (${s.leeches.size})") {
                if (s.leeches.isEmpty()) Text("No leeches. Cards you forget 8 times show up here.", style = MaterialTheme.typography.bodySmall)
                s.leeches.take(30).forEach { l ->
                    ListItem(
                        headlineContent = { Text(l.front) },
                        supportingContent = { Text("${l.deckName} · forgotten ${l.lapses}×" + if (l.suspended) " · suspended" else "") },
                        modifier = Modifier.clickable { onOpenNote(l.noteId) },
                    )
                }
                if (s.leeches.isNotEmpty()) Text("Tap one to rewrite it, split it, or reset it.", style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        content()
    }
}
