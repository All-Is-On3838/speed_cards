package com.speedcards.app.ui.links

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.audio.AudioPlayer
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.ui.common.CardText
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.StudyCard
import com.speedcards.data.repo.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PracticeState(
    val loading: Boolean = true,
    val depth: Int = 1,
    val noteCount: Int = 0,
    val queue: List<StudyCard> = emptyList(),
    val revealed: Boolean = false,
    val done: Int = 0,
) {
    val current get() = queue.firstOrNull()
}

/**
 * Practice without changing schedules. Sources:
 * - a card plus everything linked to it (noteId), with a depth choice
 * - a deck, optionally one tag ("cram")
 * - cards you answered Again recently (forgotten days)
 */
@HiltViewModel
class ClusterPracticeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val links: LinkRepository,
    private val study: StudyRepository,
    private val prefs: UserPrefs,
    val player: AudioPlayer,
) : ViewModel() {
    private val noteId: Long = savedState.get<Long>("noteId") ?: -1L
    private val deckId: Long = savedState.get<Long>("deckId") ?: -1L
    private val tag: String? = savedState.get<String>("tag")?.takeIf { it.isNotEmpty() }
    private val forgottenDays: Int = savedState.get<Int>("forgotten") ?: 0
    /** Depth chips only make sense for linked clusters. */
    val isCluster = noteId > 0
    val title = when {
        isCluster -> "Practice linked cards"
        forgottenDays > 0 -> "Practice forgotten cards"
        tag != null -> "Cram #$tag"
        else -> "Cram deck"
    }
    private val _state = MutableStateFlow(PracticeState())
    val state = _state.asStateFlow()

    init { setDepth(1) }

    fun setDepth(depth: Int) = viewModelScope.launch {
        startup.ready()
        _state.update { it.copy(loading = true, depth = depth) }
        val cards = when {
            isCluster -> study.cardsForNotes(links.cluster(noteId, depth).toList())
            forgottenDays > 0 -> study.forgottenCards(deckId, forgottenDays)
            else -> study.cardsForNotes(study.cramNoteIds(deckId, tag))
        }.shuffled()
        _state.value = PracticeState(loading = false, depth = depth, noteCount = cards.map { it.card.noteId }.distinct().size, queue = cards)
        playCurrent(question = true)
    }

    fun reveal() {
        _state.update { it.copy(revealed = true) }
        playCurrent(question = false)
    }

    /** Again: the card goes to the back of the queue. */
    fun again() {
        _state.update { s -> s.copy(queue = s.queue.drop(1) + s.queue.take(1), revealed = false) }
        playCurrent(question = true)
    }

    fun gotIt() {
        _state.update { s -> s.copy(queue = s.queue.drop(1), revealed = false, done = s.done + 1) }
        playCurrent(question = true)
    }

    private fun playCurrent(question: Boolean) = viewModelScope.launch {
        val card = _state.value.current ?: return@launch player.stop()
        val audio = prefs.audioSettings.first()
        if (!audio.autoplay || audio.muted) return@launch
        player.play(player.stepsFor(if (question) card.questionAudio else card.answerAudio, audio.ttsFallback), audio.speed)
    }

    override fun onCleared() = player.stop()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterPracticeScreen(onBack: () -> Unit, vm: ClusterPracticeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { onPauseOrDispose { vm.player.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.isCluster) {
                Text("Include cards linked up to", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { d ->
                        FilterChip(selected = state.depth == d, onClick = { vm.setDepth(d) }, label = { Text("$d step${if (d > 1) "s" else ""}") })
                    }
                }
            }
            Text(
                "${plural(state.noteCount, "note")} · ${plural(state.queue.size, "card")} left · practice only, schedules aren't changed",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val current = state.current
            when {
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                current == null -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (state.done == 0) "Nothing to practise here." else "All done!", style = MaterialTheme.typography.headlineMedium)
                    Text("${state.done} cards practised.", textAlign = TextAlign.Center)
                    Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
                }
                else -> {
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !state.revealed, onClick = vm::reveal)
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CardText(if (state.revealed) current.answer else current.question) }
                    if (state.revealed) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::again, modifier = Modifier.weight(1f).height(56.dp)) { Text("Again") }
                            Button(onClick = vm::gotIt, modifier = Modifier.weight(1f).height(56.dp)) { Text("Got it") }
                        }
                    } else {
                        Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) { Text("Show answer") }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
