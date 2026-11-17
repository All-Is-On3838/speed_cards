package com.speedcards.app.ui.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.ui.common.NumberField
import com.speedcards.app.ui.study.ratingColor
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.repo.PresetRepository
import com.speedcards.scheduler.SimulationStep
import com.speedcards.scheduler.formatDelay
import com.speedcards.scheduler.parseRatings
import com.speedcards.scheduler.simulate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------
// List

@HiltViewModel
class PresetListViewModel @Inject constructor(private val repo: PresetRepository) : ViewModel() {
    val presets = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun create(onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.save(SchedulingPreset(name = "New preset")))
    }

    fun duplicate(p: SchedulingPreset, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.save(p.copy(id = 0, name = "${p.name} copy")))
    }

    fun delete(p: SchedulingPreset) = viewModelScope.launch {
        if (!repo.delete(p)) message.value = "\"${p.name}\" is used by a deck (or is the last preset)."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetListScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: PresetListViewModel = hiltViewModel()) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduling presets") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { vm.create(onEdit) }) { Icon(Icons.Default.Add, "New preset") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(presets, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text("Steps ${p.learningSteps} · Hard ×${p.hardMultiplier} · Easy bonus ×${p.easyBonus} · ${p.newPerDay} new/day")
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { vm.duplicate(p, onEdit) }) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                            IconButton(onClick = { vm.delete(p) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    },
                    modifier = Modifier.clickable { onEdit(p.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Editor + simulator

/** Form values are kept as text so partially typed numbers don't jump around. */
data class PresetForm(
    val name: String = "",
    val learningSteps: String = "",
    val relearningSteps: String = "",
    val graduating: String = "",
    val easyInterval: String = "",
    val startingEase: String = "",
    val hardMultiplier: String = "",
    val easyBonus: String = "",
    val intervalModifier: String = "",
    val againDelta: String = "",
    val hardDelta: String = "",
    val easyDelta: String = "",
    val minimumEase: String = "",
    val maximumInterval: String = "",
    val lapseMultiplier: String = "",
    val newPerDay: String = "",
    val reviewsPerDay: String = "",
    val leechThreshold: String = "",
    val fuzz: Boolean = true,
    val showAgain: Boolean = true,
) {
    /** Ease values are shown as percentages (250 = 2.5x). */
    fun toPreset(base: SchedulingPreset): SchedulingPreset? = runCatching {
        base.copy(
            name = name.trim().ifEmpty { base.name },
            learningSteps = SchedulingPreset.formatSteps(SchedulingPreset.parseSteps(learningSteps)),
            relearningSteps = SchedulingPreset.formatSteps(SchedulingPreset.parseSteps(relearningSteps)),
            graduatingIntervalDays = graduating.toInt().coerceAtLeast(1),
            easyIntervalDays = easyInterval.toInt().coerceAtLeast(1),
            startingEase = startingEase.toDouble() / 100,
            hardMultiplier = hardMultiplier.toDouble(),
            easyBonus = easyBonus.toDouble(),
            intervalModifier = intervalModifier.toDouble(),
            againEaseDelta = againDelta.toDouble() / 100,
            hardEaseDelta = hardDelta.toDouble() / 100,
            easyEaseDelta = easyDelta.toDouble() / 100,
            minimumEase = minimumEase.toDouble() / 100,
            maximumIntervalDays = maximumInterval.toInt().coerceAtLeast(1),
            lapseIntervalMultiplier = lapseMultiplier.toDouble(),
            newPerDay = newPerDay.toInt().coerceAtLeast(0),
            reviewsPerDay = reviewsPerDay.toInt().coerceAtLeast(0),
            leechThreshold = leechThreshold.toInt().coerceAtLeast(0),
            fuzzEnabled = fuzz,
            showAgain = showAgain,
        )
    }.getOrNull()

    companion object {
        private fun pct(v: Double) = Math.round(v * 100).toString()
        fun from(p: SchedulingPreset) = PresetForm(
            name = p.name,
            learningSteps = p.learningSteps,
            relearningSteps = p.relearningSteps,
            graduating = p.graduatingIntervalDays.toString(),
            easyInterval = p.easyIntervalDays.toString(),
            startingEase = pct(p.startingEase),
            hardMultiplier = p.hardMultiplier.toString(),
            easyBonus = p.easyBonus.toString(),
            intervalModifier = p.intervalModifier.toString(),
            againDelta = pct(p.againEaseDelta),
            hardDelta = pct(p.hardEaseDelta),
            easyDelta = pct(p.easyEaseDelta),
            minimumEase = pct(p.minimumEase),
            maximumInterval = p.maximumIntervalDays.toString(),
            lapseMultiplier = p.lapseIntervalMultiplier.toString(),
            newPerDay = p.newPerDay.toString(),
            reviewsPerDay = p.reviewsPerDay.toString(),
            leechThreshold = p.leechThreshold.toString(),
            fuzz = p.fuzzEnabled,
            showAgain = p.showAgain,
        )
    }
}

data class PresetEditState(
    val base: SchedulingPreset? = null,
    val form: PresetForm = PresetForm(),
    val pattern: String = "M M M H M E A M",
    val saved: Boolean = false,
) {
    val valid get() = base != null && form.toPreset(base) != null
    val simulation: List<SimulationStep>
        get() {
            val preset = base?.let { form.toPreset(it) } ?: return emptyList()
            return simulate(preset.toConfig(), parseRatings(pattern).let { r -> if (preset.showAgain) r else r.filter { it.ordinal > 0 } })
        }
}

@HiltViewModel
class PresetEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    startup: AppStartup,
    private val repo: PresetRepository,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState["presetId"])
    private val _state = MutableStateFlow(PresetEditState())
    val state: StateFlow<PresetEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            startup.ready()
            repo.get(id)?.let { p -> _state.update { it.copy(base = p, form = PresetForm.from(p)) } }
        }
    }

    fun edit(transform: PresetForm.() -> PresetForm) = _state.update { it.copy(form = it.form.transform()) }
    fun setPattern(p: String) = _state.update { it.copy(pattern = p) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val preset = s.base?.let { s.form.toPreset(it) } ?: return@launch
        repo.save(preset)
        _state.update { it.copy(saved = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(onBack: () -> Unit, vm: PresetEditViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val f = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit preset") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (state.base == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(f.name, { v -> vm.edit { copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Section("Learning")
            OutlinedTextField(
                f.learningSteps, { v -> vm.edit { copy(learningSteps = v) } },
                label = { Text("Learning steps (e.g. 10m 1h 1d)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                f.relearningSteps, { v -> vm.edit { copy(relearningSteps = v) } },
                label = { Text("Relearning steps after forgetting") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Pair2(
                { NumberField("Graduating interval (days)", f.graduating, { v -> vm.edit { copy(graduating = v) } }, it) },
                { NumberField("Easy interval (days)", f.easyInterval, { v -> vm.edit { copy(easyInterval = v) } }, it) },
            )

            Section("Answer buttons")
            Pair2(
                { NumberField("Hard multiplier", f.hardMultiplier, { v -> vm.edit { copy(hardMultiplier = v) } }, it, decimal = true) },
                { NumberField("Easy bonus", f.easyBonus, { v -> vm.edit { copy(easyBonus = v) } }, it, decimal = true) },
            )
            Text("Medium multiplies the interval by the card's ease.", style = MaterialTheme.typography.bodySmall)
            Pair2(
                { NumberField("Starting ease %", f.startingEase, { v -> vm.edit { copy(startingEase = v) } }, it, decimal = true) },
                { NumberField("Minimum ease %", f.minimumEase, { v -> vm.edit { copy(minimumEase = v) } }, it, decimal = true) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Again ease Δ%", f.againDelta, { v -> vm.edit { copy(againDelta = v) } }, Modifier.weight(1f), decimal = true)
                NumberField("Hard ease Δ%", f.hardDelta, { v -> vm.edit { copy(hardDelta = v) } }, Modifier.weight(1f), decimal = true)
                NumberField("Easy ease Δ%", f.easyDelta, { v -> vm.edit { copy(easyDelta = v) } }, Modifier.weight(1f), decimal = true)
            }
            Toggle("Show Again (forgot) button", f.showAgain) { v -> vm.edit { copy(showAgain = v) } }

            Section("Intervals")
            Pair2(
                { NumberField("Interval modifier", f.intervalModifier, { v -> vm.edit { copy(intervalModifier = v) } }, it, decimal = true) },
                { NumberField("Maximum interval (days)", f.maximumInterval, { v -> vm.edit { copy(maximumInterval = v) } }, it) },
            )
            Pair2(
                { NumberField("Kept after lapse (0–1)", f.lapseMultiplier, { v -> vm.edit { copy(lapseMultiplier = v) } }, it, decimal = true) },
                { NumberField("Leech after N lapses", f.leechThreshold, { v -> vm.edit { copy(leechThreshold = v) } }, it) },
            )
            Toggle("Fuzz (spread out due dates)", f.fuzz) { v -> vm.edit { copy(fuzz = v) } }

            Section("Daily limits")
            Pair2(
                { NumberField("New cards / day", f.newPerDay, { v -> vm.edit { copy(newPerDay = v) } }, it) },
                { NumberField("Reviews / day", f.reviewsPerDay, { v -> vm.edit { copy(reviewsPerDay = v) } }, it) },
            )

            Section("Simulator")
            OutlinedTextField(
                state.pattern, vm::setPattern,
                label = { Text("Answers: A=Again H=Hard M=Medium E=Easy") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!state.valid) Text("Fix the highlighted fields to see a preview.", color = MaterialTheme.colorScheme.error)
                    state.simulation.forEachIndexed { i, step ->
                        Row {
                            Text("${i + 1}.", modifier = Modifier.weight(0.12f))
                            Text(step.rating.name.lowercase().replaceFirstChar { it.uppercase() }, color = ratingColor(step.rating), modifier = Modifier.weight(0.3f))
                            Text("next in ${formatDelay(step.delayMs)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.33f))
                            Text("day ${"%.1f".format(step.dueFromStartMs / 86_400_000.0)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.25f))
                        }
                    }
                }
            }
            Button(onClick = vm::save, enabled = state.valid, modifier = Modifier.fillMaxWidth()) { Text("Save preset") }
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Pair2(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
