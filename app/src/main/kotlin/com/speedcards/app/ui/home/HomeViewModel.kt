package com.speedcards.app.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.prefs.BackupState
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Folder
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.repo.DeckDeletionException
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.DueCounts
import com.speedcards.data.repo.FolderRepository
import com.speedcards.data.repo.PresetRepository
import com.speedcards.data.repo.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeckRow(val deck: Deck, val depth: Int, val counts: DueCounts, val hasChildren: Boolean)

data class HomeState(
    val loading: Boolean = true,
    /** The open folder; null when showing unfiled decks. */
    val folder: Folder? = null,
    val folders: List<Folder> = emptyList(),
    val rows: List<DeckRow> = emptyList(),
    val decks: List<Deck> = emptyList(),
    val presets: List<SchedulingPreset> = emptyList(),
    val message: String? = null,
    val streakDays: Int = 0,
    val collapsed: Set<Long> = emptySet(),
) {
    /** [rows] without the descendants of collapsed decks. */
    val visibleRows: List<DeckRow>
        get() {
            var hideBelow = Int.MAX_VALUE
            return rows.filter { row ->
                if (row.depth > hideBelow) return@filter false
                hideBelow = if (row.deck.id in collapsed) row.depth else Int.MAX_VALUE
                true
            }
        }
}

/** The decks in one folder. Opened from [FolderScreen]. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val folderRepo: FolderRepository,
    private val deckRepo: DeckRepository,
    private val presetRepo: PresetRepository,
    private val study: StudyRepository,
    private val stats: com.speedcards.data.repo.StatsRepository,
    private val widgets: com.speedcards.app.widget.WidgetUpdater,
    private val prefs: UserPrefs,
) : ViewModel() {
    val backupState: StateFlow<BackupState?> = prefs.backupState
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** All card audio is off, e.g. while studying somewhere quiet. */
    val muted: StateFlow<Boolean> = prefs.audioSettings.map { it.muted }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** The folder being shown; null for unfiled decks. */
    val folderId: Long? = savedState.get<Long>("folderId")?.takeIf { it != UNFILED_ARG }

    /** What "Study all due cards" studies on this screen. */
    val studyScope: Long get() = StudyRepository.folderScope(folderId)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            startup.ready()
            combine(deckRepo.observeDecks(), study.observeCounts(), presetRepo.observeAll(), folderRepo.observeAll()) { decks, _, presets, folders ->
                Triple(decks, presets, folders)
            }.collect { (decks, presets, folders) -> rebuild(decks, presets, folders) }
        }
        viewModelScope.launch { prefs.collapsedDecks.collect { c -> _state.update { it.copy(collapsed = c) } } }
    }

    fun toggleMuted() = viewModelScope.launch {
        val nowMuted = !muted.value
        prefs.setMuted(nowMuted)
        _state.update { it.copy(message = if (nowMuted) "Card audio muted" else "Card audio on") }
    }

    fun toggleCollapsed(deckId: Long) = viewModelScope.launch {
        prefs.setDeckCollapsed(deckId, deckId !in _state.value.collapsed)
    }

    /** Due counts depend on the clock, so refresh whenever the screen comes back. */
    fun refresh() = viewModelScope.launch {
        startup.ready()
        rebuild(deckRepo.all(), presetRepo.all(), folderRepo.all())
    }

    private suspend fun rebuild(decks: List<Deck>, presets: List<SchedulingPreset>, folders: List<Folder>) {
        val children = decks.groupBy { it.parentId }
        val rows = mutableListOf<DeckRow>()
        suspend fun walk(deck: Deck, depth: Int) {
            rows += DeckRow(deck, depth, study.countsFor(deck.id), children[deck.id].orEmpty().isNotEmpty())
            children[deck.id].orEmpty().forEach { walk(it, depth + 1) }
        }
        decks.filter { it.parentId == null && it.kind == DeckKind.NORMAL && it.folderId == folderId }.forEach { walk(it, 0) }
        _state.update {
            it.copy(
                loading = false, folder = folders.firstOrNull { f -> f.id == folderId }, folders = folders,
                rows = rows, decks = decks, presets = presets, streakDays = stats.streak(),
            )
        }
        widgets.update()
    }

    fun saveDeck(existing: Deck?, name: String, parentId: Long?, folderId: Long?, presetId: Long, front: String?, back: String?) =
        viewModelScope.launch {
            if (name.isBlank()) return@launch
            if (existing == null) deckRepo.create(name, parentId, presetId, front, back, folderId)
            else {
                // Prevent making a deck its own ancestor.
                val invalidParent = parentId != null && parentId in DeckRepository.subtree(deckRepo.all(), existing.id)
                deckRepo.update(
                    existing.copy(
                        name = name,
                        parentId = if (invalidParent) existing.parentId else parentId,
                        folderId = if (invalidParent) existing.folderId else folderId.takeIf { parentId == null },
                        presetId = presetId,
                        languageFront = front,
                        languageBack = back,
                    )
                )
                if (invalidParent) _state.update { it.copy(message = "A deck can't be moved inside itself.") }
            }
        }

    fun deleteDeck(deck: Deck) = viewModelScope.launch {
        try {
            deckRepo.delete(deck.id)
            _state.update { it.copy(message = "Deleted \"${deck.name}\".") }
        } catch (e: DeckDeletionException) {
            _state.update { it.copy(message = e.message) }
        }
    }

    fun renameFolder(name: String) = viewModelScope.launch {
        _state.value.folder?.let { folderRepo.rename(it, name) }
    }

    suspend fun tagsFor(deckId: Long): List<String> = study.tagsIn(deckId)

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        /** Route value for "decks that aren't in a folder" (folder ids start at 1). */
        const val UNFILED_ARG = -1L
    }
}
