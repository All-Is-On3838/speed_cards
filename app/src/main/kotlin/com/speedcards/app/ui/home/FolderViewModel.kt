package com.speedcards.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.prefs.BackupState
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Folder
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.DueCounts
import com.speedcards.data.repo.FolderDeletionException
import com.speedcards.data.repo.FolderRepository
import com.speedcards.data.repo.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A folder on the home screen. [folder] is null for the "Unfiled" group of decks not in any folder. */
data class FolderRow(val folder: Folder?, val deckCount: Int, val counts: DueCounts)

data class FolderState(
    val loading: Boolean = true,
    val folders: List<FolderRow> = emptyList(),
    val unfiled: FolderRow? = null,
    val inboxCount: Int = 0,
    val inboxDeckId: Long? = null,
    val total: DueCounts = DueCounts.ZERO,
    val streakDays: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class FolderViewModel @Inject constructor(
    private val startup: AppStartup,
    private val deckRepo: DeckRepository,
    private val folderRepo: FolderRepository,
    private val study: StudyRepository,
    private val stats: com.speedcards.data.repo.StatsRepository,
    private val widgets: com.speedcards.app.widget.WidgetUpdater,
    private val prefs: UserPrefs,
) : ViewModel() {
    val backupState: StateFlow<BackupState?> = prefs.backupState
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val muted: StateFlow<Boolean> = prefs.audioSettings.map { it.muted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(FolderState())
    val state: StateFlow<FolderState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            startup.ready()
            combine(deckRepo.observeDecks(), folderRepo.observeAll(), study.observeCounts()) { decks, folders, _ ->
                decks to folders
            }.collect { (decks, folders) -> rebuild(decks, folders) }
        }
    }

    fun toggleMuted() = viewModelScope.launch {
        val nowMuted = !muted.value
        prefs.setMuted(nowMuted)
        _state.update { it.copy(message = if (nowMuted) "Card audio muted" else "Card audio on") }
    }

    /** Due counts depend on the clock, so refresh whenever the screen comes back. */
    fun refresh() = viewModelScope.launch {
        startup.ready()
        rebuild(deckRepo.all(), folderRepo.all())
    }

    private suspend fun rebuild(decks: List<Deck>, folders: List<Folder>) {
        val roots = decks.filter { it.parentId == null && it.kind == DeckKind.NORMAL }
        val rows = folders.map { f ->
            FolderRow(f, roots.count { it.folderId == f.id }, study.countsFor(StudyRepository.folderScope(f.id)))
        }
        val unfiledCount = roots.count { it.folderId == null }
        val unfiled = if (unfiledCount > 0) FolderRow(null, unfiledCount, study.countsFor(StudyRepository.UNFILED)) else null
        val inbox = decks.firstOrNull { it.kind == DeckKind.INBOX }
        _state.update {
            it.copy(
                loading = false,
                folders = rows,
                unfiled = unfiled,
                inboxCount = inbox?.let { d -> deckRepo.noteCount(d.id) } ?: 0,
                inboxDeckId = inbox?.id,
                total = study.countsFor(StudyRepository.ALL_DECKS),
                streakDays = stats.streak(),
            )
        }
        widgets.update()
    }

    fun createFolder(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) folderRepo.create(name)
    }

    fun renameFolder(folder: Folder, name: String) = viewModelScope.launch { folderRepo.rename(folder, name) }

    fun deleteFolder(folder: Folder) = viewModelScope.launch {
        try {
            folderRepo.delete(folder)
            _state.update { it.copy(message = "Deleted \"${folder.name}\".") }
        } catch (e: FolderDeletionException) {
            _state.update { it.copy(message = e.message) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
