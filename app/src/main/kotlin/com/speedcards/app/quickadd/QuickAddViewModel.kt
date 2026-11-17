package com.speedcards.app.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Note
import com.speedcards.data.db.NoteType
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickAddState(
    val ready: Boolean = false,
    val decks: List<Deck> = emptyList(),
    val deckId: Long? = null,
    val word: String = "",
    val meaning: String = "",
    val example: String = "",
    val source: String = "",
    val clipboardOffer: String? = null,
    val duplicate: Note? = null,
    val duplicateDeckName: String = "",
    /** Set when saved; the sheet shows it briefly then closes. */
    val savedMessage: String? = null,
    /** Recorded pronunciation for the word (media file name). */
    val sound: String? = null,
    val translating: Boolean = false,
    val hint: String? = null,
) {
    val canSave get() = word.isNotBlank() && deckId != null
    val chips get() = CaptureParser.wordChips(example)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val startup: AppStartup,
    private val notes: NoteRepository,
    private val deckRepo: DeckRepository,
    private val prefs: UserPrefs,
    private val media: com.speedcards.data.repo.MediaRepository,
    private val language: OnDeviceLanguage,
) : ViewModel() {
    private val _state = MutableStateFlow(QuickAddState())
    val state: StateFlow<QuickAddState> = _state.asStateFlow()
    private var vocab: NoteType? = null
    private var initialized = false

    fun start(captured: Captured) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            startup.ready()
            vocab = notes.noteTypeByName(Defaults.NOTE_TYPE_VOCAB)
            val decks = deckRepo.all()
            val normal = decks.filter { it.kind == DeckKind.NORMAL }
            val deckId = (prefs.quickAddDeckId() ?: prefs.lastDeckId())?.takeIf { id -> normal.any { it.id == id } }
                ?: normal.firstOrNull()?.id
            _state.update {
                it.copy(
                    ready = true, decks = decks, deckId = deckId,
                    word = captured.word, example = captured.example, source = captured.source,
                )
            }
            detectDeck(captured.word.ifBlank { captured.example })
        }
        viewModelScope.launch {
            _state.map { it.word }.distinctUntilChanged().debounce(250).collect { word ->
                val dup = notes.findDuplicates(word).firstOrNull()
                val deckName = dup?.let { d -> _state.value.decks.firstOrNull { it.id == d.deckId }?.name }.orEmpty()
                _state.update { it.copy(duplicate = dup, duplicateDeckName = deckName) }
            }
        }
    }

    /** Picks a deck whose learning language matches the text (keeps the remembered deck if it already matches). */
    private suspend fun detectDeck(text: String) {
        if (text.isBlank()) return
        val code = language.detect(text) ?: return
        val decks = _state.value.decks.filter { it.kind == DeckKind.NORMAL }
        val current = decks.firstOrNull { it.id == _state.value.deckId }
        if (current?.languageFront?.startsWith(code) == true) return
        val match = decks.filter { it.languageFront?.startsWith(code) == true }.minByOrNull { if (it.parentId == null) 0 else 1 } ?: return
        _state.update { it.copy(deckId = match.id, hint = "Looks like ${java.util.Locale.forLanguageTag(code).displayLanguage}: deck set to ${match.name}") }
    }

    /** Fills Meaning with an on-device translation of the word (or the example when there's no word yet). */
    fun suggestMeaning() = viewModelScope.launch {
        val s = _state.value
        val deck = s.decks.firstOrNull { it.id == s.deckId }
        val text = s.word.ifBlank { s.example }.trim()
        if (text.isEmpty()) return@launch
        val from = deck?.languageFront ?: language.detect(text)
        if (from == null) { _state.update { it.copy(hint = "Set the deck's language in Deck options to translate.") }; return@launch }
        _state.update { it.copy(translating = true, hint = "Translating… (the first time downloads a language model)") }
        val result = runCatching { language.translate(text, from, deck?.languageBack ?: "en") }
        _state.update {
            result.fold(
                onSuccess = { t -> it.copy(translating = false, meaning = t, hint = "Suggested on the phone — check it") },
                onFailure = { e -> it.copy(translating = false, hint = "Couldn't translate: ${e.message}") },
            )
        }
    }

    fun attachRecording(file: java.io.File) = viewModelScope.launch {
        val name = media.addFile(file, "m4a", "audio/mp4")
        _state.update { it.copy(sound = name) }
    }

    fun removeRecording() = _state.update { it.copy(sound = null) }

    fun offerClipboard(text: String?) {
        val t = text?.trim()?.takeIf { it.isNotEmpty() && it.length < 500 } ?: return
        if (_state.value.word.isBlank() && _state.value.example.isBlank()) _state.update { it.copy(clipboardOffer = t) }
    }

    fun acceptClipboard() {
        val c = CaptureParser.parse(_state.value.clipboardOffer)
        _state.update { it.copy(word = c.word, example = c.example, source = it.source.ifBlank { c.source }, clipboardOffer = null) }
        viewModelScope.launch { detectDeck(c.word.ifBlank { c.example }) }
    }

    fun setWord(v: String) = _state.update { it.copy(word = v) }
    fun setMeaning(v: String) = _state.update { it.copy(meaning = v) }
    fun setExample(v: String) = _state.update { it.copy(example = v) }
    fun selectDeck(d: Deck) = _state.update { it.copy(deckId = d.id) }

    /** Saves a Vocab note into the chosen deck, or into the Inbox to finish later. */
    fun save(toInbox: Boolean) = viewModelScope.launch {
        val s = _state.value
        val type = vocab ?: return@launch
        if (s.word.isBlank()) return@launch
        val deck = if (toInbox) deckRepo.inbox() else s.decks.firstOrNull { it.id == s.deckId } ?: return@launch
        val word = com.speedcards.data.template.Sounds.join(s.word, listOfNotNull(s.sound))
        notes.addNote(type, deck.id, listOf(word, s.meaning, s.example, "", "", s.source))
        if (!toInbox) prefs.rememberQuickAddDeck(deck.id)
        _state.update { it.copy(savedMessage = if (toInbox) "Saved to Inbox" else "Added to ${deck.name}") }
    }

    /** Adds the captured sentence as an example on the note that already has this word. */
    fun addExampleToExisting() = viewModelScope.launch {
        val s = _state.value
        val dup = s.duplicate ?: return@launch
        val existing = notes.get(dup.id) ?: return@launch
        val exampleIndex = existing.type.fields.indexOfFirst { it.name == "Example" }
        if (exampleIndex < 0 || s.example.isBlank()) return@launch
        val fields = existing.note.fields.toMutableList()
        val current = fields[exampleIndex]
        fields[exampleIndex] = if (current.isBlank()) s.example else "$current\n${s.example}"
        notes.updateNote(dup.id, dup.deckId, fields)
        _state.update { it.copy(savedMessage = "Example added to existing card") }
    }

    fun canAddExampleToExisting(): Boolean = _state.value.duplicate != null && _state.value.example.isNotBlank()
}
