package com.speedcards.app.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.audio.AudioPlayer
import com.speedcards.app.audio.AudioRecorder
import com.speedcards.app.prefs.AudioSettings
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.db.RecordingRow
import com.speedcards.app.ui.links.LinkRowData
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.LinkedNote
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.repo.DueCounts
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.PlayableAudio
import com.speedcards.data.repo.QueueResult
import com.speedcards.data.repo.StudyCard
import com.speedcards.data.repo.StudyRepository
import com.speedcards.data.repo.UndoEntry
import com.speedcards.scheduler.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class PeekNote(val noteId: Long, val fields: List<Pair<String, String>>)

/** Pronunciation practice: record yourself, then hear the reference followed by your take. */
data class ShadowState(
    val recording: Boolean = false,
    /** The take just recorded, not yet saved. */
    val take: File? = null,
    val saved: List<RecordingRow> = emptyList(),
    val showHistory: Boolean = false,
)

data class StudyState(
    val deckName: String = "",
    val loading: Boolean = true,
    val current: StudyCard? = null,
    val revealed: Boolean = false,
    val counts: DueCounts = DueCounts.ZERO,
    val finished: Boolean = false,
    val nextDueAt: Long? = null,
    val reviewedThisSession: Int = 0,
    val canUndo: Boolean = false,
    val message: String? = null,
    val audio: AudioSettings = AudioSettings(),
    val shadow: ShadowState = ShadowState(),
    val showAudioSettings: Boolean = false,
    val related: List<LinkedNote> = emptyList(),
    /** A linked note opened from the Related list, shown read-only. */
    val peek: PeekNote? = null,
) {
    val relatedRows get() = related.map { LinkRowData(it.noteId.toString(), it.label, it.front, it.back, it.type.color) }

    /** Whether there is anything to play for the side on screen. */
    val hasAudio: Boolean
        get() = current?.let { !it.questionAudio.isEmpty || (revealed && !it.answerAudio.isEmpty) } ?: false
}

@HiltViewModel
class StudyViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val study: StudyRepository,
    private val decks: DeckRepository,
    private val folders: com.speedcards.data.repo.FolderRepository,
    private val prefs: UserPrefs,
    private val media: MediaRepository,
    private val recorder: AudioRecorder,
    private val links: LinkRepository,
    private val notes: NoteRepository,
    private val widgets: com.speedcards.app.widget.WidgetUpdater,
    val player: AudioPlayer,
) : ViewModel() {
    private val deckId: Long = checkNotNull(savedState["deckId"])
    /** > 0 when studying ahead (reviews due within this many days). */
    private val aheadDays: Int = savedState.get<Int>("ahead") ?: 0
    private val _state = MutableStateFlow(StudyState())
    val state: StateFlow<StudyState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<UndoEntry>()
    private var shownAt = 0L

    init {
        viewModelScope.launch {
            startup.ready()
            val name = when {
                deckId == StudyRepository.ALL_DECKS -> "All decks"
                deckId == StudyRepository.UNFILED -> "Unfiled decks"
                deckId < 0 -> StudyRepository.folderOf(deckId)?.let { folders.get(it)?.name }.orEmpty()
                else -> decks.get(deckId)?.name.orEmpty()
            }
            _state.update { it.copy(deckName = if (aheadDays > 0) "$name · ahead $aheadDays d" else name, audio = prefs.audioSettings.first()) }
            loadNext()
        }
        viewModelScope.launch { prefs.audioSettings.collect { a -> _state.update { it.copy(audio = a) } } }
        viewModelScope.launch {
            player.problems.collect { p -> if (p != null) { _state.update { it.copy(message = p) }; player.problems.value = null } }
        }
    }

    private suspend fun loadNext() {
        resetShadow()
        // Don't follow a card with itself, a sibling, or a linked note when something else is due.
        val previous = _state.value.current?.card?.noteId
        val avoid = previous?.let { links.neighbours(it) + it }.orEmpty()
        when (val r = study.next(deckId, avoid, aheadDays)) {
            is QueueResult.Next -> {
                shownAt = System.currentTimeMillis()
                val related = links.links(r.studyCard.card.noteId)
                _state.update {
                    it.copy(
                        loading = false, current = r.studyCard, revealed = false, counts = r.counts,
                        finished = false, nextDueAt = null, related = related, peek = null,
                    )
                }
                autoplay(r.studyCard.questionAudio)
            }
            is QueueResult.Finished -> {
                player.stop()
                _state.update {
                    it.copy(loading = false, current = null, revealed = false, counts = DueCounts.ZERO, finished = true, nextDueAt = r.nextDueAt)
                }
            }
        }
    }

    private fun autoplay(audio: PlayableAudio) {
        val settings = _state.value.audio
        if (settings.autoplay && !settings.muted) player.play(player.stepsFor(audio, settings.ttsFallback), settings.speed) else player.stop()
    }

    /** Plays the side on screen again (the question if the answer side has no audio of its own). */
    fun replay() {
        val s = _state.value
        val card = s.current ?: return
        if (s.audio.muted) return _state.update { it.copy(message = "Card audio is muted. Unmute it on the home screen.") }
        val audio = if (s.revealed && !card.answerAudio.isEmpty) card.answerAudio else card.questionAudio
        player.play(player.stepsFor(audio, s.audio.ttsFallback), s.audio.speed)
    }

    /** Re-renders the current card, e.g. after it was edited. */
    fun reloadCurrent() = viewModelScope.launch {
        if (_state.value.loading || _state.value.finished) return@launch
        val card = _state.value.current?.card ?: return@launch
        runCatching { study.load(card) }
            .onSuccess { sc -> _state.update { it.copy(current = sc, related = links.links(sc.card.noteId)) } }
            .onFailure { loadNext() }
    }

    fun reveal() {
        if (_state.value.revealed) return
        _state.update { it.copy(revealed = true) }
        _state.value.current?.let { autoplay(it.answerAudio) }
    }

    fun answer(rating: Rating) = viewModelScope.launch {
        val current = _state.value.current ?: return@launch
        if (!_state.value.revealed) return@launch
        // Cap think time so a card left on screen doesn't skew stats.
        val duration = (System.currentTimeMillis() - shownAt).coerceAtMost(60_000)
        val outcome = study.answer(current.card, rating, duration)
        undoStack.addLast(outcome.undo)
        if (undoStack.size > 20) undoStack.removeFirst()
        _state.update {
            it.copy(
                reviewedThisSession = it.reviewedThisSession + 1,
                canUndo = true,
                message = if (outcome.becameLeech) "This card is a leech — you keep forgetting it. Consider rewriting or splitting it." else null,
            )
        }
        loadNext()
    }

    fun undo() = viewModelScope.launch {
        val entry = undoStack.removeLastOrNull() ?: return@launch
        study.undo(entry)
        val restored = study.load(entry.cardBefore)
        resetShadow()
        shownAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                current = restored, revealed = false, finished = false,
                counts = study.countsFor(deckId),
                reviewedThisSession = (it.reviewedThisSession - 1).coerceAtLeast(0),
                canUndo = undoStack.isNotEmpty(),
            )
        }
        autoplay(restored.questionAudio)
    }

    fun bury() = viewModelScope.launch {
        val card = _state.value.current?.card ?: return@launch
        study.bury(card)
        _state.update { it.copy(message = "Card hidden until tomorrow.") }
        loadNext()
    }

    fun suspend() = viewModelScope.launch {
        val card = _state.value.current?.card ?: return@launch
        study.setSuspended(card, true)
        _state.update { it.copy(message = "Card suspended. Unsuspend it from the card browser.") }
        loadNext()
    }

    // --- Related notes -----------------------------------------------------------------------

    fun peek(noteId: Long) = viewModelScope.launch {
        val n = notes.get(noteId) ?: return@launch
        val fields = n.type.fields.mapIndexed { i, f -> f.name to LinkRepository.plain(n.note.fields.getOrElse(i) { "" }) }
            .filter { it.second.isNotBlank() }
        _state.update { it.copy(peek = PeekNote(noteId, fields)) }
    }

    fun closePeek() = _state.update { it.copy(peek = null) }

    // --- Audio settings -----------------------------------------------------------------------

    fun showAudioSettings(show: Boolean) = _state.update { it.copy(showAudioSettings = show) }

    fun updateAudio(settings: AudioSettings) = viewModelScope.launch {
        if (settings.muted) player.stop()
        prefs.setAudioSettings(settings)
    }

    /** Steps through 0.5×, 0.75×, 1×, 1.25×, 1.5×. */
    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)
        val current = _state.value.audio.speed
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        updateAudio(_state.value.audio.copy(speed = next))
    }

    // --- Shadowing ----------------------------------------------------------------------------

    /** The target-language audio to imitate: recordings if any, else TTS in the deck's language. */
    private fun shadowReference(card: StudyCard): PlayableAudio {
        val sounds = card.questionAudio.sounds.ifEmpty { card.answerAudio.sounds }
        val target = card.deck.languageFront
        val speech = (card.questionAudio.speech + card.answerAudio.speech).filter { it.languageTag == target }.distinct()
        return PlayableAudio(sounds, speech.take(1))
    }

    fun startTake() {
        player.stop()
        _state.value.shadow.take?.delete()
        val started = runCatching { recorder.start() }.isSuccess
        _state.update {
            if (started) it.copy(shadow = it.shadow.copy(recording = true, take = null))
            else it.copy(message = "Couldn't start the microphone.")
        }
    }

    fun stopTake() {
        val file = recorder.stop()
        _state.update { it.copy(shadow = it.shadow.copy(recording = false, take = file)) }
        if (file == null) _state.update { it.copy(message = "Recording was too short.") } else playComparison()
    }

    /** Reference audio, then your take. */
    fun playComparison() {
        val s = _state.value
        val card = s.current ?: return
        val take = s.shadow.take ?: return
        val reference = player.stepsFor(shadowReference(card), tts = true)
        player.play(reference + AudioPlayer.Step.Files(listOf(take)), s.audio.speed)
    }

    fun saveTake() = viewModelScope.launch {
        val s = _state.value
        val card = s.current ?: return@launch
        val take = s.shadow.take ?: return@launch
        player.stop() // the file is moved into media storage
        media.saveRecording(card.card.id, take)
        _state.update { it.copy(shadow = it.shadow.copy(take = null, saved = media.recordings(card.card.id)), message = "Take saved") }
    }

    fun discardTake() {
        _state.value.shadow.take?.delete()
        _state.update { it.copy(shadow = it.shadow.copy(take = null)) }
    }

    fun showHistory(show: Boolean) = viewModelScope.launch {
        val card = _state.value.current ?: return@launch
        val saved = if (show) media.recordings(card.card.id) else _state.value.shadow.saved
        _state.update { it.copy(shadow = it.shadow.copy(showHistory = show, saved = saved)) }
    }

    fun playSaved(row: RecordingRow) = player.playFile(media.file(row.fileName), _state.value.audio.speed)

    fun deleteSaved(row: RecordingRow) = viewModelScope.launch {
        media.deleteRecording(row)
        val card = _state.value.current ?: return@launch
        _state.update { it.copy(shadow = it.shadow.copy(saved = media.recordings(card.card.id))) }
    }

    private fun resetShadow() {
        recorder.stop()?.delete()
        _state.value.shadow.take?.delete()
        _state.update { it.copy(shadow = ShadowState()) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun pauseAudio() {
        player.stop()
        if (_state.value.shadow.recording) stopTake()
    }

    override fun onCleared() {
        player.stop()
        resetShadow()
        widgets.update() // due count on the home-screen widget
    }
}
